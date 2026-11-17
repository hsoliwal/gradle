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

/** Cross-build refusal test against the *original* Maven Java 21 AST CLI. */
final class M3MachineFingerprintRealEngineTest {
    @TempDir Path root;

    @Test
    void realMavenProducerRejectsUnboundReceiptsWithoutPublishingSuccessfulFacts() throws Exception {
        String supplied = System.getenv("M3_SMART_BUILDER_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires reviewed Maven smart-builder JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "explicit Maven smart-builder JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("receipts"));
        Files.writeString(root.resolve("src/main/java/Example.java"), "class Example { int f() { return 1; } }\n");
        Files.writeString(root.resolve("receipts/preparation.json"), "{}\n");
        Files.writeString(root.resolve("receipts/atom-pattern.tsv"), "# invalid provenance\n");
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"maven-syntax-refusal\"\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3MachineFingerprints') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))\n"
                + "  atomPatternManifest.set(layout.projectDirectory.file('receipts/atom-pattern.tsv'))\n"
                + "  moduleDb.set('off')\n"
                + "}\n");
        String failed = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3MachineFingerprints", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failed.contains("M3 AST machine fingerprint producer refused"), failed);
        Path ledger = root.resolve("build/m3-smart/machine-evidence.tsv");
        assertTrue(Files.isRegularFile(ledger), "Maven must mark an attempted generation as refused");
        assertTrue(Files.readString(ledger).startsWith("# m3.machine-evidence.refused"));
        String receipt = Files.readString(root.resolve("build/m3-smart/machine-fingerprints.json"));
        assertTrue(receipt.contains("\"status\":\"refused\""));
        assertFalse(receipt.contains("\"semanticProofAuthenticated\":true"));
    }
}
