/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Exact Maven #160/#178 converter and real ECJ/javac fixture integration. */
final class M3CompilerDiagnosticsRealEngineTest {
    @TempDir Path project;

    @Test
    void realMavenAdapterNormalizesEcjAndJavacToTheSameSourceLocations() throws Exception {
        Path fixtureRoot = fixtureRoot();
        String jarSha = installPinnedAdapter();
        M3AnalysisV1.Snapshot javac = run("JAVAC", "javac-21.txt", "COMPLETE", fixtureRoot, jarSha);
        assertEquals(M3AnalysisV1.Status.COMPLETE, javac.status());
        assertEquals(7, javac.size());
        assertEquals(2, javac.errors());
        assertTrue(IntStream.range(0, javac.size())
                .mapToObj(javac::entry).anyMatch(f -> "javac.rawtypes".equals(f.ruleId())));

        M3AnalysisV1.Snapshot ecj = run("ECJ", "ecj-3.45.0.xml", "COMPLETE", fixtureRoot, jarSha);
        assertEquals(M3AnalysisV1.Status.COMPLETE, ecj.status());
        assertEquals(7, ecj.size());
        assertEquals(2, ecj.errors());
        assertTrue(IntStream.range(0, ecj.size())
                .mapToObj(ecj::entry).anyMatch(f -> "ecj.TypeMismatch".equals(f.ruleId())));
        assertEquals(locations(javac), locations(ecj), "both original compiler logs identify the same source problems");
    }

    @Test
    void unreadableForeignLocaleJavacLogCannotBePublishedAsComplete() throws Exception {
        Path fixtures = fixtureRoot();
        String jarSha = installPinnedAdapter();
        prepare("JAVAC", "COMPLETE", "javac-21-de.txt", fixtures, jarSha);
        String failure = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3NormalizeCompilerDiagnostics", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("javac diagnostics not in English or not UTF-8"), failure);
        assertFalse(Files.exists(report()), "a failed Maven conversion may not certify a complete Gradle report");

        prepare("JAVAC", "PARTIAL", "javac-21-de.txt", fixtures, jarSha);
        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3NormalizeCompilerDiagnostics", "--stacktrace").build();
        M3AnalysisV1.Snapshot partial = M3AnalysisV1.parse(Files.readAllBytes(report()));
        assertEquals(M3AnalysisV1.Status.PARTIAL, partial.status());
        assertEquals(0, partial.size(), "unreadable input is not silently represented as clean COMPLETE");
    }

    private M3AnalysisV1.Snapshot run(String format, String fixture, String status,
            Path fixtureRoot, String sha) throws Exception {
        prepare(format, status, fixture, fixtureRoot, sha);
        String output = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3NormalizeCompilerDiagnostics", "--stacktrace").build().getOutput();
        assertTrue(output.contains("status=" + status + ", findings=7"), output);
        Path report = report();
        Path sidecar = report.resolveSibling("compiler-analysis.sha256");
        assertTrue(Files.isRegularFile(report) && Files.isRegularFile(sidecar));
        String actual = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(report)));
        assertEquals(actual, Files.readString(sidecar).strip(), "Maven output must match the Gradle input digest");
        return M3AnalysisV1.parse(Files.readAllBytes(report));
    }

    private void prepare(String format, String status, String fixture, Path fixtureRoot, String sha)
            throws Exception {
        Files.createDirectories(project.resolve("reports"));
        String original = Files.readString(fixtureRoot.resolve(fixture)).replace("@ROOT@", project.toString());
        Files.writeString(project.resolve("reports/compiler-original.txt"), original);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-compiler-diagnostics'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3NormalizeCompilerDiagnostics') {\n"
                + "  adapterJar.set(layout.projectDirectory.file('vendor/adapter.jar'))\n"
                + "  adapterSha256.set('" + sha + "')\n"
                + "  originalReport.set(layout.projectDirectory.file('reports/compiler-original.txt'))\n"
                + "  format.set('" + format + "')\n"
                + "  originalStatus.set('" + status + "')\n"
                + "}\n");
    }

    private String installPinnedAdapter() throws Exception {
        String input = System.getenv("M3_ADAPTER_JAR");
        assumeTrue(input != null && !input.isBlank(), "requires original Maven analyzer adapter JAR");
        Path original = Path.of(input).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "source-pinned original Maven adapter must exist");
        Files.createDirectories(project.resolve("vendor"));
        Path jar = project.resolve("vendor/adapter.jar");
        Files.copy(original, jar);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }

    private static Path fixtureRoot() {
        String location = System.getenv("M3_ADAPTER_COMPILER_FIXTURES");
        assumeTrue(location != null && !location.isBlank(), "requires original Maven ECJ/javac fixtures");
        Path fixtures = Path.of(location).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(fixtures), "fixture directory must be source-pinned");
        return fixtures;
    }

    private Path report() {
        return project.resolve("build/reports/m3/import/compiler-analysis.tsv");
    }

    private static List<String> locations(M3AnalysisV1.Snapshot snapshot) {
        return IntStream.range(0, snapshot.size()).mapToObj(snapshot::entry)
                .map(f -> f.severity() + ":" + f.relativePath() + ":" + f.line())
                .distinct().sorted().toList();
    }
}
