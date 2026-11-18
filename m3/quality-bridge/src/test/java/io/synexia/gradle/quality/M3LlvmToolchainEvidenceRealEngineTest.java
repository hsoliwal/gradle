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

/** Actual Maven #210 CLI JAR: byte evidence only, with no LLVM execution. */
final class M3LlvmToolchainEvidenceRealEngineTest {
    @TempDir Path root;

    @Test
    void originalMavenCliSealsVerifiesAndRevokesChangedToolBytes() throws Exception {
        Path project = consumer(false);
        Path evidence = project.resolve("build/reports/m3/llvm-toolchain.tsv");
        Path executable = project.resolve("synthetic llvm home/bin/opt");

        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(evidence), "plugin application must not read or write toolchain evidence");

        String scan = execute(project, "m3LlvmToolchainScan");
        assertTrue(scan.contains("BUILD SUCCESSFUL"), scan);
        String manifest = Files.readString(evidence);
        assertTrue(manifest.startsWith("M3-LLVM-TOOLCHAIN-V1\ntool\topt\tbin/opt\tbin/opt\t"),
                "only Maven's original canonical manifest bytes may be published");
        assertTrue(execute(project, "m3LlvmToolchainVerify").contains("BUILD SUCCESSFUL"));

        Files.writeString(executable, "modified synthetic opt bytes\n");
        String failure = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmToolchainVerify", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("LLVM toolchain evidence changed"), failure);
        assertTrue(Files.readString(evidence).equals(manifest),
                "Maven verification must never overwrite a stale or rejected seal");
    }

    @Test
    void wrongMavenProviderJarDigestRefusesBeforeEvidenceIsPublished() throws Exception {
        Path project = consumer(true);
        String failed = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmToolchainScan", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failed.contains("M3 LLVM original toolchain evidence refused"), failed);
        assertFalse(Files.exists(project.resolve("build/reports/m3/llvm-toolchain.tsv")));
    }

    private Path consumer(boolean wrongPin) throws Exception {
        String selected = System.getenv("M3_MAVEN_LLVM_EVIDENCE_JAR");
        assumeTrue(selected != null && !selected.isBlank(),
                "requires original Maven #210 evidence provider JAR");
        Path source = Path.of(selected).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "source-pinned original Maven JAR is required");

        Path project = Files.createDirectories(root.resolve("evidence project with spaces"));
        Path jar = Files.createDirectories(project.resolve("vendor")).resolve("llvm-tools.jar");
        Files.copy(source, jar);
        Path bin = Files.createDirectories(project.resolve("synthetic llvm home/bin"));
        Path opt = bin.resolve("opt");
        Files.writeString(opt, "synthetic opt bytes; never executed\n");
        assertTrue(opt.toFile().setExecutable(true), "test-only synthetic file must be executable");
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-evidence-original'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3LlvmToolchainScan', 'm3LlvmToolchainVerify'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    originalProviderJar.set(layout.projectDirectory.file('vendor/llvm-tools.jar'))\n"
                + "    originalProviderSha256.set('"
                + (wrongPin ? "0".repeat(64) : sha(jar)) + "')\n"
                + "    llvmHome.set(layout.projectDirectory.dir('synthetic llvm home'))\n"
                + "    selectedTools.set('opt')\n"
                + "  }\n}\n");
        return project;
    }

    private static String execute(Path project, String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build().getOutput();
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
