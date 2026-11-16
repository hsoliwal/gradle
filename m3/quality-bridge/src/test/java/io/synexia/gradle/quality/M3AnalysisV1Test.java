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
        assertEquals(M3AnalysisV1.Severity.ERROR, snapshot.entry(1).severity());
        assertEquals(1L, snapshot.errors(), "the packed index retains its original severity summary");
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

    private static String row(String rule, String severity, String path, int line, int column, String message) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(message.getBytes(StandardCharsets.UTF_8));
        return rule + "\t" + severity + "\t" + path + "\t" + line + "\t" + column + "\t" + encoded + "\n";
    }
}
