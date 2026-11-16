/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
