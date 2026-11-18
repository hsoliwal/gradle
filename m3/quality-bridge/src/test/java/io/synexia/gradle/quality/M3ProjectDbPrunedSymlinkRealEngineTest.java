/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
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

/**
 * Original Maven #208 project DB keeps the legacy symlinked Gradle module
 * identity while pruning generated and vendor subtrees before traversal.
 */
final class M3ProjectDbPrunedSymlinkRealEngineTest {
    @TempDir Path root;

    @Test
    void sourcePinnedMavenRecognizesLinkedGradleModuleAndIgnoresVendorOutputs() throws Exception {
        String dbEnv = System.getenv("M3_PRUNED_PROJECT_DB_JAR");
        String engineEnv = System.getenv("M3_PRUNED_POLYGLOT_JAR");
        assumeTrue(dbEnv != null && !dbEnv.isBlank() && engineEnv != null && !engineEnv.isBlank(),
                "requires both original Maven #208 source-built JARs");
        Path db = Path.of(dbEnv).toAbsolutePath().normalize();
        Path engine = Path.of(engineEnv).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(db) && Files.isRegularFile(engine));
        Files.copy(db, root.resolve("project-db.jar"));
        Files.copy(engine, root.resolve("polyglot.jar"));

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-linked-module'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3ProjectDbIngest','m3ProjectDbQuery'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    projectDbJar.set(layout.projectDirectory.file('project-db.jar'))\n"
                + "    projectDbSha256.set('" + sha256(db) + "')\n"
                + "    polyglotJar.set(layout.projectDirectory.file('polyglot.jar'))\n"
                + "    polyglotSha256.set('" + sha256(engine) + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3ProjectDbQuery') { question.set('LinkedTest failed in linked module') }\n");

        Path linked = root.resolve("linked");
        Files.createDirectories(linked);
        Path descriptor = root.resolve("descriptor.gradle");
        Files.writeString(descriptor, "// source-owned linked Gradle descriptor\n");
        try {
            Files.createSymbolicLink(linked.resolve("build.gradle"), descriptor);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "filesystem does not permit a linked Gradle descriptor: " + unavailable);
        }
        assertTrue(Files.isSymbolicLink(linked.resolve("build.gradle")));
        assertTrue(Files.isRegularFile(linked.resolve("build.gradle")));

        writeFailedGradleReport(linked, "LinkedTest", "linked module failure");
        writeFailedGradleReport(root.resolve("node_modules/ignored"), "ExcludedTest", "vendor failure");
        writeFailedGradleReport(root.resolve("target/ignored"), "TargetTest", "target failure");

        run("m3ProjectDbIngest");
        String receipt = Files.readString(root.resolve("build/reports/m3/project-db/ingest.json"));
        assertTrue(receipt.contains("\"sources\":1"),
                "only the symlinked Gradle module may contribute evidence: " + receipt);
        assertTrue(receipt.contains("\"parsed\":1"), receipt);

        run("m3ProjectDbQuery");
        String answer = Files.readString(root.resolve("build/reports/m3/project-db/query.json"));
        assertTrue(answer.contains("\"status\":\"failed\""), answer);
        assertTrue(answer.contains("\"file\":\"linked/src/test/java/org/example/LinkedTest.java\""),
                "Maven must keep the original module and source-line provenance: " + answer);
        assertTrue(answer.contains("\"line\":11"), answer);
        assertFalse(answer.contains("ExcludedTest") || answer.contains("TargetTest"),
                "generated and vendor descriptor trees must be pruned: " + answer);

        run("m3ProjectDbIngest");
        String reused = Files.readString(root.resolve("build/reports/m3/project-db/ingest.json"));
        assertTrue(reused.contains("\"parsed\":0"), reused);
        assertTrue(reused.contains("\"reused\":1"), reused);
    }

    private static void writeFailedGradleReport(Path module, String test, String message) throws IOException {
        Path source = module.resolve("src/test/java/org/example/" + test + ".java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package org.example;\n" + "\n".repeat(9)
                + "class " + test + " { void fails() {} }\n");
        Path report = module.resolve("build/test-results/test/TEST-org.example." + test + ".xml");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "<testsuite name=\"org.example." + test + "\">\n"
                + "  <testcase classname=\"org.example." + test + "\" name=\"fails\">\n"
                + "    <failure message=\"" + message + "\">"
                + "at org.example." + test + ".fails(" + test + ".java:11)\n"
                + "    </failure>\n  </testcase>\n</testsuite>\n");
    }

    private void run(String task) {
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build();
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
