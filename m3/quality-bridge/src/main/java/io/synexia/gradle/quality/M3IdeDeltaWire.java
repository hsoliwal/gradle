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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free canonical M3-IDE-DELTA-V1 exchange codec from Maven #78/#82.
 * This is change evidence only, never permission to skip a compiler or task.
 */
public final class M3IdeDeltaWire {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHANGES = 65_536;
    private static final String MAGIC = "M3-IDE-DELTA-V1";

    public enum BuildKind { INCREMENTAL, AUTO, FULL, CLEAN }
    public enum ChangeKind { ADDED, MODIFIED, DELETED }

    public record Change(ChangeKind kind, String path) {
        public Change {
            Objects.requireNonNull(kind, "change kind");
            checkPath(path);
        }
    }

    public record Packet(String provider, String project, BuildKind kind,
            boolean complete, List<Change> changes) {
        public Packet {
            checkProvider(provider);
            checkProject(project);
            Objects.requireNonNull(kind, "build kind");
            Objects.requireNonNull(changes, "changes");
            changes = List.copyOf(changes);
            if (complete && (kind == BuildKind.FULL || kind == BuildKind.CLEAN)) {
                throw new IllegalArgumentException("FULL/CLEAN cannot claim complete deltas");
            }
            if (changes.size() > MAX_CHANGES) throw new IllegalArgumentException("delta row budget");
            String previous = null;
            for (Change change : changes) {
                if (previous != null && previous.compareTo(change.path()) >= 0) {
                    throw new IllegalArgumentException("changes must be unique and path-sorted");
                }
                previous = change.path();
            }
        }
    }

    private M3IdeDeltaWire() {}

    /** Canonical Maven wire bytes; validates every record before publication. */
    public static byte[] encode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        String project64 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                packet.project().getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(MAGIC).append('\n')
                .append("provider\t").append(packet.provider()).append('\n')
                .append("project64\t").append(project64).append('\n')
                .append("kind\t").append(packet.kind()).append('\n')
                .append("complete\t").append(packet.complete()).append('\n');
        for (Change change : packet.changes()) {
            result.append("change\t").append(change.kind()).append('\t')
                    .append(change.path()).append('\n');
            if (result.length() > MAX_BYTES) throw new IllegalArgumentException("oversized delta");
        }
        byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("oversized delta");
        return bytes;
    }

    /** Rejects noncanonical, malformed, oversized and ambiguous Maven wire bytes. */
    public static Packet decode(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("invalid IDE delta byte budget");
        }
        String decoded = decodeUtf8(bytes);
        if (!decoded.endsWith("\n") || decoded.indexOf('\r') >= 0 || decoded.charAt(0) == '\ufeff') {
            throw new IOException("IDE delta requires canonical LF lines");
        }
        String[] lines = decoded.split("\n", -1);
        if (lines.length < 6 || !MAGIC.equals(lines[0])) throw new IOException("unknown IDE delta version");
        String provider = field(lines[1], "provider");
        String project64 = field(lines[2], "project64");
        String kind = field(lines[3], "kind");
        String complete = field(lines[4], "complete");
        if (!("true".equals(complete) || "false".equals(complete))) {
            throw new IOException("invalid completeness claim");
        }
        List<Change> changes = parseChanges(lines);
        try {
            Packet packet = new Packet(provider, parseProject(project64),
                    BuildKind.valueOf(kind), Boolean.parseBoolean(complete), changes);
            if (!java.util.Arrays.equals(bytes, encode(packet))) throw new IOException("noncanonical IDE delta");
            return packet;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("malformed IDE delta", invalid);
        }
    }

    private static List<Change> parseChanges(String[] lines) throws IOException {
        if (lines.length - 6 > MAX_CHANGES) throw new IOException("too many delta changes");
        ArrayList<Change> changes = new ArrayList<>();
        for (int n = 5; n < lines.length - 1; n++) {
            String[] parts = lines[n].split("\t", -1);
            if (parts.length != 3 || !"change".equals(parts[0])) throw new IOException("invalid change row");
            try {
                changes.add(new Change(ChangeKind.valueOf(parts[1]), parts[2]));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("malformed change row", invalid);
            }
        }
        return changes;
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("IDE delta is not UTF-8", invalid);
        }
    }

    private static String field(String row, String name) throws IOException {
        String prefix = name + "\t";
        if (!row.startsWith(prefix) || row.indexOf('\t', prefix.length()) >= 0) {
            throw new IOException("invalid " + name + " field");
        }
        return row.substring(prefix.length());
    }

    private static String parseProject(String text) throws IOException {
        try {
            if (text.isEmpty() || text.indexOf('=') >= 0 || !text.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException("bad Base64URL");
            }
            String project = decodeUtf8(Base64.getUrlDecoder().decode(text));
            checkProject(project);
            if (!Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(project.getBytes(StandardCharsets.UTF_8)).equals(text)) {
                throw new IllegalArgumentException("noncanonical Base64URL");
            }
            return project;
        } catch (IllegalArgumentException invalid) {
            throw new IOException("invalid project64", invalid);
        }
    }

    private static void checkProvider(String provider) {
        checkName(provider, "provider", 128);
    }

    private static void checkProject(String project) {
        checkName(project, "project identifier", 256);
    }

    private static void checkName(String value, String name, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid " + name);
        }
        checkUtf16(value);
    }

    private static void checkPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 2048
                || path.charAt(0) == '/' || path.indexOf('\\') >= 0
                || path.indexOf(':') >= 0 || path.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("invalid project-relative delta path");
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("traversal or empty delta path segment");
            }
        }
        checkUtf16(path);
    }

    private static void checkUtf16(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i == text.length() || !Character.isLowSurrogate(text.charAt(i))) {
                    throw new IllegalArgumentException("unpaired high surrogate");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("unpaired low surrogate");
            }
        }
    }
}
