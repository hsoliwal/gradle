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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real merged Maven #190 project DB receives Gradle-only JUnit evidence, never a synthetic parser. */
final class M3ProjectDbGradleNativeRealEngineTest {
    @TempDir Path root;

    @Test
    void nativeGradleTestFailureIsIndexedWithSourceProvenanceWithoutPom() throws Exception {
        String projectDb = System.getenv("M3_PROJECT_DB_JAR");
        String polyglot = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(projectDb != null && !projectDb.isBlank()
                && polyglot != null && !polyglot.isBlank(), "merged Maven #190 JARs required");
        Path dbJar = Path.of(projectDb).toAbsolutePath().normalize();
        Path engineJar = Path.of(polyglot).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(dbJar) && Files.isRegularFile(engineJar));
        fixture(dbJar, engineJar);
        assertFalse(Files.exists(root.resolve("pom.xml")), "must remain a Gradle-only project");

        run("m3ProjectDbIngest");
        String first = report("ingest");
        assertTrue(first.contains("\"parsed\":1"), first);
        assertTrue(first.contains("\"status\":\"red\""), first);

        run("m3ProjectDbQuery");
        String answers = report("query");
        assertTrue(answers.contains("\"answers\":[{"), "no native Gradle test evidence: " + answers);
        assertTrue(answers.contains("\"status\":\"failed\""), "test outcome was lost: " + answers);
        assertTrue(answers.contains("\"file\":\"src/test/java/org/example/NativeTest.java\""),
                "Gradle JUnit stack frame must resolve to source: " + answers);
        assertTrue(answers.contains("\"line\":11"), "original test source line lost: " + answers);
        assertTrue(answers.contains("\"build\":\"b"), "Maven provenance build id missing: " + answers);

        run("m3ProjectDbIngest");
        String again = report("ingest");
        assertTrue(again.contains("\"parsed\":0"), "unchanged Gradle report was reparsed: " + again);
        assertTrue(again.contains("\"reused\":1"), "unchanged report was not reused: " + again);
    }

    private void fixture(Path db, Path engine) throws Exception {
        Files.copy(db, root.resolve("project-db.jar"));
        Files.copy(engine, root.resolve("polyglot.jar"));
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name=\"gradle-native\"\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3ProjectDbIngest','m3ProjectDbQuery'].each { taskName ->\n"
                + "  tasks.named(taskName) {\n"
                + "    projectDbJar.set(layout.projectDirectory.file('project-db.jar'))\n"
                + "    projectDbSha256.set('" + sha(db) + "')\n"
                + "    polyglotJar.set(layout.projectDirectory.file('polyglot.jar'))\n"
                + "    polyglotSha256.set('" + sha(engine) + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3ProjectDbQuery') { question.set('which tests failed') }\n");
        Path source = root.resolve("src/test/java/org/example/NativeTest.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.example;\n" + "\n".repeat(9)
                + "class NativeTest { void fails() {} }\n");
        Path report = root.resolve("build/test-results/test/TEST-org.example.NativeTest.xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<testsuite name=\"org.example.NativeTest\">\n"
                + "  <testcase classname=\"org.example.NativeTest\" name=\"fails\">\n"
                + "    <failure message=\"native Gradle failure\">"
                + "at org.example.NativeTest.fails(NativeTest.java:11)\n"
                + "    </failure>\n  </testcase>\n</testsuite>\n");
    }

    private String report(String mode) throws Exception {
        return Files.readString(root.resolve("build/reports/m3/project-db/" + mode + ".json"));
    }

    private void run(String name) {
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(name, "--stacktrace").build();
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
