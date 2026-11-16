/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Adapter argv contract without requiring the Maven converter or external engines. */
final class M3AnalyzerAdapterCommandsTest {
    @Test
    void onlyAnExplicitOriginalAdapterAndStatusProducesAnImmutableArgVector() {
        List<String> command = M3AnalyzerAdapterCommands.normalize(
                "java", new File("original-adapter.jar"), new File("."),
                new File("target/checkstyle-result.xml"), new File("target/analysis.tsv"),
                new File("target/analysis.sha256"), "CHECKSTYLE", "PARTIAL");
        assertEquals("java", command.get(0));
        assertEquals("-cp", command.get(1));
        assertEquals("io.synexia.maven.analysis.NormalizeCli", command.get(3));
        assertEquals("CHECKSTYLE", command.get(4));
        assertEquals("PARTIAL", command.get(5));
        assertEquals(10, command.size());
        assertThrows(UnsupportedOperationException.class, () -> command.add("--execute"));
        assertFalse(command.contains("sh"));
        assertFalse(command.contains("bash"));
        assertFalse(command.contains("mvn"));
    }

    @Test
    void spotbugsUsesTheSamePinnedOriginalXmlAdapterWithoutNewEngine() {
        var cmd = M3AnalyzerAdapterCommands.normalize(
                "java", new File("original-adapter.jar"), new File("."),
                new File("target/spotbugsXml.xml"), new File("target/analysis.tsv"),
                new File("target/analysis.sha256"), "SPOTBUGS", "PARTIAL");
        assertEquals("SPOTBUGS", cmd.get(4));
        assertEquals("PARTIAL", cmd.get(5));
        assertEquals("io.synexia.maven.analysis.NormalizeCli", cmd.get(3));
        assertEquals(10, cmd.size());
        assertThrows(UnsupportedOperationException.class, () -> cmd.add("--autofix"));
        assertFalse(cmd.contains("mvn"));
    }

    @Test
    void originalCpdXmlUsesExistingPinnedJavaConverterWithoutASecondParser() {
        var cmd = M3AnalyzerAdapterCommands.normalize(
                "java", new File("original-adapter.jar"), new File("."),
                new File("target/cpd.xml"), new File("target/cpd-analysis.tsv"),
                new File("target/cpd-analysis.sha256"), "CPD", "PARTIAL");
        assertEquals("CPD", cmd.get(4));
        assertEquals("PARTIAL", cmd.get(5));
        assertEquals("io.synexia.maven.analysis.NormalizeCli", cmd.get(3));
        assertEquals(10, cmd.size());
        assertFalse(cmd.contains("mvn"));
        assertFalse(cmd.contains("java -jar"));
        assertThrows(UnsupportedOperationException.class, () -> cmd.add("--autofix"));
    }

    @Test
    void codeqlSarifUsesOriginalPinnedJavaAdapterAndNoShellExecution() {
        var args = M3AnalyzerAdapterCommands.normalize(
                "java", new File("m3-analyzer-report-adapter.jar"), new File("."),
                new File("build/reports/m3/codeql/results.sarif"),
                new File("build/reports/m3/import/analysis.tsv"),
                new File("build/reports/m3/import/analysis.sha256"),
                "SARIF", "PARTIAL");
        assertEquals("java", args.get(0));
        assertEquals("-cp", args.get(1));
        assertEquals("io.synexia.maven.analysis.NormalizeCli", args.get(3));
        assertEquals("SARIF", args.get(4));
        assertEquals("PARTIAL", args.get(5));
        assertEquals(10, args.size());
        assertFalse(args.contains("mvn"));
        assertFalse(args.contains("sh"));
        assertFalse(args.contains("--autofix"));
        assertThrows(UnsupportedOperationException.class, () -> args.add("--unsafe"));
    }

    @Test
    void semgrepNativeJsonUsesExistingPinnedExternalJavaNormalizer() {
        var args = M3AnalyzerAdapterCommands.normalize(
                "java", new File("m3-analyzer-report-adapter.jar"), new File("."),
                new File("build/reports/m3/semgrep/results.json"),
                new File("build/reports/m3/import/analysis.tsv"),
                new File("build/reports/m3/import/analysis.sha256"),
                "SEMGREP", "PARTIAL");
        assertEquals("SEMGREP", args.get(4));
        assertEquals("PARTIAL", args.get(5));
        assertEquals("io.synexia.maven.analysis.NormalizeCli", args.get(3));
        assertEquals(10, args.size());
        assertFalse(args.contains("mvn"));
        assertFalse(args.contains("sh"));
        assertFalse(args.contains("semgrep"));
        assertThrows(UnsupportedOperationException.class, () -> args.add("--autofix"));
    }

    @Test
    void forbidsImplicitFormatsStatusAndUnqualifiedBinaries() {
        var jar = new File("adapter.jar");
        var root = new File(".");
        var xml = new File("original.xml");
        var output = new File("report.tsv");
        var digest = new File("report.sha256");
        assertThrows(IllegalArgumentException.class, () ->
                M3AnalyzerAdapterCommands.normalize("java", jar, root, xml, output, digest, "UNKNOWN", "PARTIAL"));
        assertThrows(IllegalArgumentException.class, () ->
                M3AnalyzerAdapterCommands.normalize("java", jar, root, xml, output, digest, "PMD", "MISSING"));
        assertThrows(IllegalArgumentException.class, () ->
                M3AnalyzerAdapterCommands.normalize("java", new File("adapter.exe"),
                        root, xml, output, digest, "PMD", "COMPLETE"));
        assertThrows(IllegalArgumentException.class, () ->
                M3AnalyzerAdapterCommands.normalize("java\n", jar, root, xml, output, digest, "PMD", "FAILED"));
        assertTrue(M3AnalyzerAdapterCommands.normalize(
                "java", jar, root, xml, output, digest, "PMD", "FAILED").contains("FAILED"));
    }
}
