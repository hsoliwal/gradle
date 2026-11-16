/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** No original Checkstyle/PMD process is started by this TestKit fixture. */
final class M3NormalizeAnalyzerXmlTaskTest {
    @TempDir
    Path project;

    @Test
    void explicitInvocationRefusesAnUnpinnedAdapterBeforeExecutingAnyTool() throws Exception {
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/checkstyle-result.xml"),
                "<checkstyle><file name=\"src/A.java\">"
                        + "<error line=\"1\" severity=\"warning\" source=\"Rule\" message=\"text\"/>"
                        + "</file></checkstyle>");
        Files.write(project.resolve("adapter.jar"), new byte[] {1, 2, 3, 4});
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-adapter-test'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3NormalizeAnalyzerXml') {\n"
                + "  adapterJar.set(layout.projectDirectory.file('adapter.jar'))\n"
                + "  adapterSha256.set('0' * 64)\n"
                + "  originalXml.set(layout.projectDirectory.file('target/checkstyle-result.xml'))\n"
                + "  format.set('CHECKSTYLE')\n"
                + "  originalStatus.set('PARTIAL')\n"
                + "}\n");
        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3NormalizeAnalyzerXml", "--stacktrace")
                .buildAndFail();
        assertTrue(result.getOutput().contains("adapter binary does not match its SHA-256 pin"));
        assertTrue(!Files.exists(project.resolve("build/reports/m3/import/analysis.tsv")));
    }

    @Test
    void sarifReportAliasPreservesOriginalBinaryPinAndNeverStartsCodeql() throws Exception {
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/codeql.sarif"),
                "{\"version\":\"2.1.0\",\"runs\":[]}");
        Files.write(project.resolve("adapter.jar"), new byte[] {5, 6, 7, 8});
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-sarif-import'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3NormalizeAnalyzerXml') {\n"
                + "  adapterJar.set(layout.projectDirectory.file('adapter.jar'))\n"
                + "  adapterSha256.set('0' * 64)\n"
                + "  originalReport.set(layout.projectDirectory.file('target/codeql.sarif'))\n"
                + "  format.set('SARIF')\n"
                + "  originalStatus.set('PARTIAL')\n"
                + "}\n");
        var output = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3NormalizeAnalyzerXml", "--stacktrace")
                .buildAndFail().getOutput();
        assertTrue(output.contains("adapter binary does not match its SHA-256 pin"));
        assertTrue(!Files.exists(project.resolve("build/reports/m3/import/analysis.tsv")));
    }

    @Test
    void explicitSemgrepJsonImportDoesNotExecuteOriginalAnalyzerWhenAdapterPinFails() throws Exception {
        Files.createDirectories(project.resolve("target"));
        Files.writeString(project.resolve("target/original.json"),
                "{\"results\":[],\"errors\":[],\"paths\":{\"scanned\":[],\"skipped\":[]}}");
        Files.write(project.resolve("adapter.jar"), new byte[] {12, 34, 56});
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-semgrep-import'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3NormalizeAnalyzerXml') {\n"
                + "  adapterJar.set(layout.projectDirectory.file('adapter.jar'))\n"
                + "  adapterSha256.set('0' * 64)\n"
                + "  originalReport.set(layout.projectDirectory.file('target/original.json'))\n"
                + "  format.set('SEMGREP')\n"
                + "  originalStatus.set('PARTIAL')\n"
                + "}\n");
        var output = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3NormalizeAnalyzerXml", "--stacktrace")
                .buildAndFail().getOutput();
        assertTrue(output.contains("adapter binary does not match its SHA-256 pin"));
        assertTrue(!Files.exists(project.resolve("build/reports/m3/import/analysis.tsv")));
    }

    @Test
    void explicitlyPinnedOriginalMavenConverterRunsSemgrepJsonWithoutGradleJsonEngine() throws Exception {
        String supplied = System.getenv("M3_ADAPTER_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "Live converter JAR provided by the separate cross-fork CI job");
        Path originalJar = Path.of(supplied);
        assertTrue(Files.isRegularFile(originalJar), "CI's pinned Maven artifact must exist");

        Files.createDirectories(project.resolve("target"));
        Files.createDirectories(project.resolve("vendor"));
        Path jar = project.resolve("vendor/m3-analyzer-report-adapter.jar");
        Files.copy(originalJar, jar);
        String jarHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        String source = """
                {
                  "version":"1.166.0",
                  "results":[{
                    "check_id":"java.original-rule",
                    "path":"src/A.java",
                    "start":{"line":12,"col":2},
                    "end":{"line":12,"col":8},
                    "extra":{"severity":"WARNING","message":"Original finding",
                      "metadata":{},"fingerprint":"original/1",
                      "lines":"sensitive original source excerpt"}
                  }],
                  "errors":[],
                  "paths":{"scanned":["src/A.java"],"skipped":[]}
                }
                """;
        Files.writeString(project.resolve("target/original-semgrep.json"), source);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-semgrep-live-bridge'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3NormalizeAnalyzerXml') {\n"
                + "  adapterJar.set(layout.projectDirectory.file('vendor/m3-analyzer-report-adapter.jar'))\n"
                + "  adapterSha256.set('" + jarHash + "')\n"
                + "  originalReport.set(layout.projectDirectory.file('target/original-semgrep.json'))\n"
                + "  format.set('SEMGREP')\n"
                + "  originalStatus.set('PARTIAL')\n"
                + "}\n");

        var output = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3NormalizeAnalyzerXml", "--stacktrace")
                .build().getOutput();
        assertTrue(output.contains("status=PARTIAL, findings=1"));
        Path normalized = project.resolve("build/reports/m3/import/analysis.tsv");
        Path digest = project.resolve("build/reports/m3/import/analysis.sha256");
        assertTrue(Files.isRegularFile(normalized));
        assertTrue(Files.isRegularFile(digest));
        String text = Files.readString(normalized);
        assertTrue(text.contains("java.original-rule\tWARNING\tsrc/A.java\t12\t2\t"));
        assertTrue(!text.contains("sensitive original source excerpt"),
                "Semgrep source snippets must not be copied into native evidence");
        String expected = Files.readString(digest).strip();
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(normalized)));
        assertTrue(expected.equals(actual), "Gradle's real Maven adapter must publish matching v1 bytes");
    }

    @Test
    void reviewTasksRemainLazyAndNoAnalyzerIsInvokedForSimpleTaskDiscovery() throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-adapter-list'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n");
        var result = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("tasks", "--all")
                .build();
        assertTrue(result.getOutput().contains("m3NormalizeAnalyzerXml"));
        assertTrue(result.getOutput().contains("m3ReviewAnalysis"));
    }
}
