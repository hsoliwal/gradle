/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TestKit verifies the explicit process boundary, not Maven's AST proof. */
final class M3MachineFingerprintTaskTest {
    @TempDir Path root;

    @Test
    void delegatesCanonicalEvidencePathsToThePinnedOriginalMavenCli() throws Exception {
        fixture(createFakeOriginalProducer());
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3MachineFingerprints", "--stacktrace").build();
        List<String> arguments = Files.readAllLines(root.resolve("cli-argv.txt"), StandardCharsets.UTF_8);
        assertEquals(List.of("--project-dir", root.toString(), "--build-dir", root.resolve("build").toString(),
                "--source-root", root.resolve("src/main/java").toString(),
                "--preparation-report", root.resolve("receipts/preparation.json").toString(),
                "--atom-pattern-manifest", root.resolve("receipts/atom-pattern.tsv").toString(),
                "--workers", "2", "--attribute-types", "false", "--analyze-flow", "false",
                "--module-db", "off"), arguments);
        assertFalse(Files.exists(root.resolve("build/m3-smart/machine-evidence.tsv")),
                "only the original Maven producer may publish facts");
    }

    @Test
    void refusesFlowWithoutAttributionBeforeInvokingTheEngine() throws Exception {
        fixture(createFakeOriginalProducer());
        Files.writeString(root.resolve("build.gradle"),
                Files.readString(root.resolve("build.gradle"))
                        + "tasks.named('m3MachineFingerprints') { analyzeFlow.set(true) }\n");
        String failure = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3MachineFingerprints", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("invalid Maven M3 machine fingerprint option"));
        assertFalse(Files.exists(root.resolve("cli-argv.txt")));
    }

    @Test
    void refusesCanonicalReceiptInsideTheSourceRoot() throws Exception {
        fixture(createFakeOriginalProducer());
        Files.writeString(root.resolve("src/main/java/preparation.json"), "{}\n");
        Files.writeString(root.resolve("build.gradle"),
                Files.readString(root.resolve("build.gradle"))
                        + "tasks.named('m3MachineFingerprints') {\n"
                        + "  preparationReport.set(layout.projectDirectory.file('src/main/java/preparation.json'))\n"
                        + "}\n");
        String failure = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3MachineFingerprints", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("M3 fingerprint project, source, build or receipt scope refused"));
        assertFalse(Files.exists(root.resolve("cli-argv.txt")));
    }

    @Test
    void refusesModifiedEngineDigestWithoutStartingTheOriginalProducer() throws Exception {
        String actualDigest = createFakeOriginalProducer();
        fixture(actualDigest);
        Files.writeString(root.resolve("build.gradle"),
                Files.readString(root.resolve("build.gradle"))
                        .replace(actualDigest, "0".repeat(64)));
        String failure = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3MachineFingerprints", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("M3 polyglot engine SHA-256 mismatch"));
        assertFalse(Files.exists(root.resolve("cli-argv.txt")));
    }

    private void fixture(String digest) throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.createDirectories(root.resolve("receipts"));
        Files.writeString(root.resolve("src/main/java/Example.java"), "class Example {}\n");
        Files.writeString(root.resolve("receipts/preparation.json"), "{}\n");
        Files.writeString(root.resolve("receipts/atom-pattern.tsv"), "M3-ATOM-PATTERN-V1\n");
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fingerprint-fixture\"\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3MachineFingerprints') {\n"
                + "  engineJar.set(layout.projectDirectory.file('smart-builder.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  preparationReport.set(layout.projectDirectory.file('receipts/preparation.json'))\n"
                + "  atomPatternManifest.set(layout.projectDirectory.file('receipts/atom-pattern.tsv'))\n"
                + "  workers.set(2)\n"
                + "  moduleDb.set('off')\n"
                + "}\n");
    }

    private String createFakeOriginalProducer() throws Exception {
        Path source = root.resolve("generated/io/synexia/maven/smart/MachineFingerprintMain.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package io.synexia.maven.smart;\n"
                + "public final class MachineFingerprintMain {\n"
                + "  public static void main(String[] args) throws Exception {\n"
                + "    java.nio.file.Files.writeString(java.nio.file.Path.of(\"cli-argv.txt\"), "
                + "String.join(\"\\n\", args) + \"\\n\");\n"
                + "  }\n}\n", StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(root.resolve("generated-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()));
        Path jar = root.resolve("smart-builder.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("io/synexia/maven/smart/MachineFingerprintMain.class"));
            Files.copy(classes.resolve("io/synexia/maven/smart/MachineFingerprintMain.class"), output);
            output.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
