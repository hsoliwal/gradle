/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class M3AnalysisV1Test {
    private static final String HEADER =
            "M3-STATIC-ANALYSIS-V1\nstatus\tCOMPLETE\nrule\tseverity\tpath\tline\tcolumn\tmessage64\n";

    @Test
    void matchesMavenCanonicalSortedRowProtocolWithoutStartingAnalyzers() throws Exception {
        String report = HEADER
                + row("Checkstyle.EmptyBlock", "WARNING", "src/A.java", 1, 2, "empty block")
                + row("PMD.UnusedImports", "ERROR", "src/B.java", 7, 0, "unused import");
        var snapshot = M3AnalysisV1.parse(report.getBytes(StandardCharsets.UTF_8));
        assertEquals(M3AnalysisV1.Status.COMPLETE, snapshot.status());
        assertEquals(2, snapshot.size());
        assertEquals(1L, snapshot.errors());
        assertEquals("Checkstyle.EmptyBlock", snapshot.entry(0).ruleId());
        assertEquals("unused import", snapshot.entry(1).message());
        assertThrows(IndexOutOfBoundsException.class, () -> snapshot.entry(2));
    }

    @Test
    void preservesNonCompleteOriginalAnalyzerClaimsAndEmptyReports() throws Exception {
        for (var expected : new M3AnalysisV1.Status[] {
                M3AnalysisV1.Status.PARTIAL, M3AnalysisV1.Status.FAILED}) {
            String report = HEADER.replace("status\tCOMPLETE", "status\t" + expected);
            var snapshot = M3AnalysisV1.parse(report.getBytes(StandardCharsets.UTF_8));
            assertEquals(expected, snapshot.status());
            assertEquals(0, snapshot.size());
            assertEquals(0L, snapshot.errors());
        }
    }

    @Test
    void refusesUnsupportedVersionsInvalidPathsOrderingPositionsAndEncodedMessages() {
        String row = row("Rule", "ERROR", "src/A.java", 1, 1, "problem");
        String[] invalid = {
            "",
            HEADER.substring(0, HEADER.length() - 1),
            HEADER.replace("ANALYSIS-V1", "ANALYSIS-V2"),
            HEADER.replace("status\tCOMPLETE", "status\tMISSING"),
            HEADER.replace("\n", "\r\n"),
            HEADER + row.substring(0, row.length() - 1),
            HEADER + row + row,
            HEADER + row("Rule", "ERROR", "src/Z.java", 1, 1, "later") + row,
            HEADER + row("Rule", "ERROR", "../parent", 1, 1, "problem"),
            HEADER + row("Rule", "ERROR", "C:/absolute", 1, 1, "problem"),
            HEADER + row("Rule", "ERROR", "src\\A.java", 1, 1, "problem"),
            HEADER + row("Rule", "FATAL", "src/A.java", 1, 1, "problem"),
            HEADER + row.replace("\t1\t1\t", "\t01\t1\t"),
            HEADER + row.replace("\t1\t1\t", "\t0\t1\t"),
            HEADER + row.replace("cHJvYmxlbQ", "***"),
            HEADER + row.replace("cHJvYmxlbQ", "cHJvYmxlbQ==")
        };
        for (int i = 0; i < invalid.length; i++) {
            final int index = i;
            assertThrows(IOException.class, () -> M3AnalysisV1.parse(
                    invalid[index].getBytes(StandardCharsets.UTF_8)), "case " + index);
        }
    }

    @Test
    void refusesMalformedUtf8AndOversizedInputs() {
        byte[] prefix = HEADER.getBytes(StandardCharsets.UTF_8);
        byte[] malformed = Arrays.copyOf(prefix, prefix.length + 2);
        malformed[prefix.length] = (byte) 0xc3;
        malformed[prefix.length + 1] = (byte) 0x28;
        assertThrows(IOException.class, () -> M3AnalysisV1.parse(malformed));
        assertThrows(IOException.class, () -> M3AnalysisV1.parse(new byte[M3AnalysisV1.MAX_BYTES + 1]));
    }

    @Test
    void acceptsCanonicalUtf8PathAndProjectWideFinding() throws Exception {
        String report = HEADER
                + row("ProjectRule", "INFO", "", 0, 0, "overall")
                + row("CheckStyle", "WARNING", "src/café.java", 2, 0, "café");
        var snapshot = M3AnalysisV1.parse(report.getBytes(StandardCharsets.UTF_8));
        assertEquals("", snapshot.entry(0).relativePath());
        assertEquals("src/café.java", snapshot.entry(1).relativePath());
        assertEquals("café", snapshot.entry(1).message());
        assertTrue(snapshot.size() == 2);
    }

    @Test
    void linearOriginalReportDeltasDistinguishNewResolvedAndUnchangedFindings() throws Exception {
        String before = HEADER
                + row("A", "ERROR", "src/A.java", 1, 1, "same")
                + row("C", "WARNING", "src/C.java", 2, 1, "resolved");
        String after = HEADER
                + row("A", "ERROR", "src/A.java", 1, 1, "same")
                + row("B", "ERROR", "src/B.java", 2, 1, "introduced")
                + row("D", "INFO", "src/D.java", 3, 1, "introduced");
        var earlier = M3AnalysisV1.parse(before.getBytes(StandardCharsets.UTF_8));
        var later = M3AnalysisV1.parse(after.getBytes(StandardCharsets.UTF_8));
        var difference = M3AnalysisV1.compare("pmd:1:check", earlier, "pmd:1:check", later);
        assertTrue(difference.comparable());
        assertEquals(1, difference.unchanged());
        assertEquals(2, difference.introduced());
        assertEquals(1, difference.resolved());
        assertEquals(1, difference.introducedErrors());
        var reversed = M3AnalysisV1.compare("pmd:1:check", later, "pmd:1:check", earlier);
        assertEquals(1, reversed.unchanged());
        assertEquals(1, reversed.introduced());
        assertEquals(2, reversed.resolved());
        assertEquals(0, reversed.introducedErrors());
    }

    @Test
    void severityAndMessageChangesRemainTwoObservationalChanges() throws Exception {
        var earlier = M3AnalysisV1.parse((HEADER
                + row("A", "WARNING", "src/A.java", 1, 1, "before"))
                .getBytes(StandardCharsets.UTF_8));
        var later = M3AnalysisV1.parse((HEADER
                + row("A", "ERROR", "src/A.java", 1, 1, "after"))
                .getBytes(StandardCharsets.UTF_8));
        var comparison = M3AnalysisV1.compare("a:1:check", earlier, "a:1:check", later);
        assertTrue(comparison.comparable());
        assertEquals(0, comparison.unchanged());
        assertEquals(1, comparison.introduced());
        assertEquals(1, comparison.resolved());
        assertEquals(1, comparison.introducedErrors());
    }

    @Test
    void incompleteOrForeignAnalyzerDeltasDoNotAppearClean() throws Exception {
        var complete = M3AnalysisV1.parse(HEADER.getBytes(StandardCharsets.UTF_8));
        var partial = M3AnalysisV1.parse(
                HEADER.replace("status\tCOMPLETE", "status\tPARTIAL").getBytes(StandardCharsets.UTF_8));
        var result = M3AnalysisV1.compare("checkstyle:1:check", complete, "checkstyle:1:check", partial);
        assertTrue(!result.comparable());
        assertEquals(0, result.introduced());
        assertThrows(IllegalArgumentException.class,
                () -> M3AnalysisV1.compare("checkstyle:1:check", complete, "pmd:1:check", complete));
        assertThrows(IllegalArgumentException.class,
                () -> M3AnalysisV1.compare("", complete, "", complete));
        assertThrows(IllegalArgumentException.class,
                () -> new M3AnalysisV1.Comparison(false, 0, 1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new M3AnalysisV1.Comparison(true, 0, 1, 0, 2));
    }

    private static String row(String rule, String severity, String path, int line, int column, String message) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
        return rule + "\t" + severity + "\t" + path + "\t" + line + "\t" + column + "\t" + encoded + "\n";
    }
}
