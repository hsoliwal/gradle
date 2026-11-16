/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Objects;

/**
 * Standalone Java 17 reader for Maven's original M3-STATIC-ANALYSIS-V1 wire
 * format. It reads existing analyzer claims, not source code or task outputs,
 * and does not authenticate the original analyzer's execution or completeness.
 *
 * <p>No Maven runtime, analyzer engine or Gradle task service is loaded.
 */
public final class M3AnalysisV1 {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_ROWS = 65_536;
    private static final int MAX_LINE = 16_384;
    private static final String MAGIC = "M3-STATIC-ANALYSIS-V1";
    private static final String COLUMNS = "rule\tseverity\tpath\tline\tcolumn\tmessage64";

    private static final Comparator<Finding> ORDER = Comparator.comparing(Finding::relativePath)
            .thenComparingInt(Finding::line)
            .thenComparingInt(Finding::column)
            .thenComparing(Finding::ruleId)
            .thenComparing(Finding::severity)
            .thenComparing(Finding::message);

    private M3AnalysisV1() {}

    public enum Status {
        COMPLETE,
        PARTIAL,
        FAILED
    }

    /** Original analyzer finding, as claimed by the producing adapter. */
    public record Finding(String ruleId, String severity, String relativePath, int line, int column, String message) {}

    /** Array-backed indexed snapshot: records are materialized only for selected rows. */
    public static final class Snapshot {
        private final Status status;
        private final String[] rules, levels, paths, messages;
        private final int[] lines, columns;

        private Snapshot(Status status, Mutable rows) {
            this.status = status;
            rules = Arrays.copyOf(rows.rules, rows.size);
            levels = Arrays.copyOf(rows.levels, rows.size);
            paths = Arrays.copyOf(rows.paths, rows.size);
            messages = Arrays.copyOf(rows.messages, rows.size);
            lines = Arrays.copyOf(rows.lines, rows.size);
            columns = Arrays.copyOf(rows.columns, rows.size);
        }

        public Status status() {
            return status;
        }

        public int size() {
            return rules.length;
        }

        public Finding entry(int index) {
            Objects.checkIndex(index, rules.length);
            return new Finding(rules[index], levels[index], paths[index], lines[index], columns[index], messages[index]);
        }

        /** Counts are evidence from the report, not proof that analysis ran. */
        public long errors() {
            return Arrays.stream(levels).filter("ERROR"::equals).count();
        }
    }

    /**
     * Observational difference between existing normalized reports; never
     * a cache hit, complete source inventory or permission to skip a task.
     *
     * @param comparable true only when both source reports claim COMPLETE
     * @param unchanged identical original findings present in both reports
     * @param introduced findings present only in the later report
     * @param resolved findings present only in the earlier report
     * @param introducedErrors new ERROR-severity findings
     */
    public record Comparison(boolean comparable, int unchanged, int introduced, int resolved, int introducedErrors) {
        public Comparison {
            if (unchanged < 0 || introduced < 0 || resolved < 0
                    || introducedErrors < 0 || introducedErrors > introduced
                    || (!comparable && (unchanged != 0 || introduced != 0 || resolved != 0))) {
                throw new IllegalArgumentException("invalid analysis change counts");
            }
        }
    }

    /**
     * Compare two indexed reports in O(n) without re-scanning their sources.
     * The caller must provide the exact same pinned provider/goal identity
     * for each report; these supplied strings are not authenticated by v1.
     *
     * @param originalProducer explicit producer coordinate for the baseline
     * @param earlier baseline report
     * @param updatedProducer explicit producer coordinate for the later report
     * @param later updated report
     * @return changes or an explicit non-comparable result for incomplete runs
     */
    public static Comparison compare(
            String originalProducer, Snapshot earlier, String updatedProducer, Snapshot later) {
        Objects.requireNonNull(earlier, "earlier");
        Objects.requireNonNull(later, "later");
        producer(originalProducer);
        producer(updatedProducer);
        if (!originalProducer.equals(updatedProducer)) {
            throw new IllegalArgumentException("analysis reports have different producer identities");
        }
        if (earlier.status() != Status.COMPLETE || later.status() != Status.COMPLETE) {
            return new Comparison(false, 0, 0, 0, 0);
        }
        int i = 0;
        int j = 0;
        int unchanged = 0;
        int introduced = 0;
        int resolved = 0;
        int introducedErrors = 0;
        Finding previousLeft = null;
        Finding previousRight = null;
        while (i < earlier.size() || j < later.size()) {
            Finding left = i < earlier.size() ? earlier.entry(i) : null;
            Finding right = j < later.size() ? later.entry(j) : null;
            if ((left != null && previousLeft != null && ORDER.compare(previousLeft, left) >= 0)
                    || (right != null && previousRight != null && ORDER.compare(previousRight, right) >= 0)) {
                throw new IllegalArgumentException("analysis findings are not in canonical order");
            }
            if (left == null || (right != null && ORDER.compare(left, right) > 0)) {
                introduced++;
                if ("ERROR".equals(String.valueOf(right.severity()))) {
                    introducedErrors++;
                }
                previousRight = right;
                j++;
            } else if (right == null || ORDER.compare(left, right) < 0) {
                resolved++;
                previousLeft = left;
                i++;
            } else {
                unchanged++;
                previousLeft = left;
                previousRight = right;
                i++;
                j++;
            }
        }
        return new Comparison(true, unchanged, introduced, resolved, introducedErrors);
    }

    private static void producer(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("explicit analyzer provider identity required");
        }
    }

    /**
     * Parse an existing, bounded, canonical UTF-8/LF report. Missing status
     * cannot be serialized as successful analysis; no file access is performed.
     */
    public static Snapshot parse(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length > MAX_BYTES) {
            throw new IOException("analysis report byte budget exceeded");
        }
        String text = decode(bytes);
        Mutable findings = new Mutable();
        Status status = null;
        int lineNumber = 0;
        for (int start = 0; start < text.length(); ) {
            int end = text.indexOf('\n', start);
            String row = canonicalRow(text, start, end);
            if (lineNumber == 0) {
                if (!MAGIC.equals(row)) {
                    throw new IOException("unexpected analysis schema");
                }
            } else if (lineNumber == 1) {
                status = status(row);
            } else if (lineNumber == 2) {
                if (!COLUMNS.equals(row)) {
                    throw new IOException("unexpected analysis report columns");
                }
            } else {
                findings.add(finding(row));
            }
            start = end + 1;
            lineNumber++;
        }
        if (lineNumber < 3) {
            throw new IOException("missing analysis report header");
        }
        return new Snapshot(status, findings);
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("analysis report requires strict UTF-8", malformed);
        }
    }

    private static String canonicalRow(String source, int start, int end) throws IOException {
        if (end < 0 || end - start > MAX_LINE) {
            throw new IOException("unterminated or oversized analysis row");
        }
        String row = source.substring(start, end);
        if (row.indexOf('\r') >= 0 || row.indexOf('\0') >= 0) {
            throw new IOException("analysis report requires LF-only records");
        }
        return row;
    }

    private static Status status(String line) throws IOException {
        return switch (line) {
            case "status\tCOMPLETE" -> Status.COMPLETE;
            case "status\tPARTIAL" -> Status.PARTIAL;
            case "status\tFAILED" -> Status.FAILED;
            default -> throw new IOException("invalid analyzer status");
        };
    }

    private static Finding finding(String row) throws IOException {
        String[] fields = row.split("\t", -1);
        if (fields.length != 6) {
            throw new IOException("analysis record requires six fields");
        }
        try {
            String rule = bounded(fields[0], 256, "rule");
            String severity = fields[1];
            if (!("INFO".equals(severity) || "WARNING".equals(severity) || "ERROR".equals(severity))) {
                throw new IllegalArgumentException("severity");
            }
            String path = path(fields[2]);
            int line = number(fields[3]), column = number(fields[4]);
            if ((line == 0 && column != 0) || (path.isEmpty() && (line != 0 || column != 0))) {
                throw new IllegalArgumentException("location");
            }
            String message = bounded(message(fields[5]), 2048, "message");
            return new Finding(rule, severity, path, line, column, message);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid analyzer finding", invalid);
        }
    }

    private static int number(String text) throws IOException {
        if (text.isEmpty() || text.length() > 10 || (text.length() > 1 && text.charAt(0) == '0')) {
            throw new IOException("noncanonical analysis position");
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                throw new IOException("noncanonical analysis position");
            }
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            throw new IOException("analysis position overflow", invalid);
        }
    }

    private static String message(String input) throws IOException {
        if (input.isEmpty() || input.length() > 12_288) {
            throw new IOException("invalid analyzer message length");
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(input);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("invalid analyzer message base64", malformed);
        }
        if (bytes.length > 8192
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(input)) {
            throw new IOException("noncanonical analyzer message base64");
        }
        return decode(bytes);
    }

    private static String path(String value) {
        Objects.requireNonNull(value, "path");
        if (value.isEmpty()) {
            return value;
        }
        if (value.length() > 2048 || value.startsWith("/") || value.indexOf(':') >= 0
                || value.indexOf('\\') >= 0 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("noncanonical analysis path");
        }
        for (String part : value.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new IllegalArgumentException("invalid analysis path segment");
            }
        }
        unicode(value);
        return value;
    }

    private static String bounded(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field);
        }
        unicode(value);
        return value;
    }

    private static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("invalid high surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("invalid low surrogate");
            }
        }
    }

    /** Parallel lanes, with a single preceding record for strict ordering. */
    private static final class Mutable {
        String[] rules = new String[8], levels = new String[8], paths = new String[8], messages = new String[8];
        int[] lines = new int[8], columns = new int[8];
        int size;
        Finding previous;

        void add(Finding finding) throws IOException {
            if (size == MAX_ROWS) {
                throw new IOException("analysis record budget exceeded");
            }
            if (previous != null && ORDER.compare(previous, finding) >= 0) {
                throw new IOException("duplicate or unsorted analyzer findings");
            }
            previous = finding;
            if (size == rules.length) {
                int capacity = Math.min(MAX_ROWS, size * 2);
                rules = Arrays.copyOf(rules, capacity);
                levels = Arrays.copyOf(levels, capacity);
                paths = Arrays.copyOf(paths, capacity);
                messages = Arrays.copyOf(messages, capacity);
                lines = Arrays.copyOf(lines, capacity);
                columns = Arrays.copyOf(columns, capacity);
            }
            rules[size] = finding.ruleId();
            levels[size] = finding.severity();
            paths[size] = finding.relativePath();
            messages[size] = finding.message();
            lines[size] = finding.line();
            columns[size] = finding.column();
            size++;
        }
    }
}
