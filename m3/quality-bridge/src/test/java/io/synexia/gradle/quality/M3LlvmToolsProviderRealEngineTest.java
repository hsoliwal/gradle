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

/** Original Maven #203 optional LLVM tools JAR, never an independently copied Gradle provider. */
final class M3LlvmToolsProviderRealEngineTest {
    @TempDir Path root;

    @Test
    void sourcePinnedExternalServiceLoaderIsDiscoveredAndThreeStagePipelineIsOnlyPlanned()
            throws Exception {
        Path project = consumer(true, false);
        assertFalse(Files.exists(project.resolve("hello.opt.ll")));

        String inventory = execute(project, "m3PolyglotProviders");
        assertTrue(inventory.contains("BUILD SUCCESSFUL"), inventory);
        String report = Files.readString(project.resolve("build/reports/m3/polyglot-providers.tsv"));
        assertTrue(report.contains("provider\tllvm-tools\t"
                + "assembler,interpreter,linker,optimizer,transpiler\t"
                + "coff,elf,filecheck,lit,llvm-ir,macho,mlir,wasm\n"), report);

        String plan = execute(project, "m3PolyglotPlan");
        assertTrue(plan.contains("PLAN optimize"), plan);
        assertTrue(plan.contains("PLAN assemble"), plan);
        assertTrue(plan.contains("PLAN link"), plan);
        assertFalse(Files.exists(project.resolve("hello.opt.ll")),
                "Gradle planning must not invoke original LLVM opt");
        assertFalse(Files.exists(project.resolve("hello.bc")),
                "Gradle planning must not invoke original llvm-as");
        assertFalse(Files.exists(project.resolve("hello.linked.bc")),
                "Gradle planning must not invoke original llvm-link");
    }

    @Test
    void missingOptionalProviderFailsClosedWithoutDuplicatingMavenRegistry() throws Exception {
        Path project = consumer(false, false);
        String inventory = execute(project, "m3PolyglotProviders");
        assertTrue(inventory.contains("BUILD SUCCESSFUL"), inventory);
        assertFalse(Files.readString(project.resolve("build/reports/m3/polyglot-providers.tsv"))
                .contains("provider\tllvm-tools\t"));
        String failure = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotPlan", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("M3 provider is not installed: llvm-tools"), failure);
        assertFalse(Files.exists(project.resolve("hello.opt.ll")));
    }

    @Test
    void wrongExternalProviderShaNeverInvokesMavenCli() throws Exception {
        Path project = consumer(true, true);
        String failure = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("M3 Maven provider inventory refused"), failure);
        assertFalse(Files.exists(project.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    private Path consumer(boolean installedProvider, boolean wrongPin) throws Exception {
        String engineEnv = System.getenv("M3_MAVEN_LLVM_PIPELINE_ENGINE_JAR");
        String providerEnv = System.getenv("M3_MAVEN_LLVM_PIPELINE_PROVIDER_JAR");
        String fixturesEnv = System.getenv("M3_MAVEN_LLVM_PIPELINE_FIXTURES");
        assumeTrue(engineEnv != null && !engineEnv.isBlank()
                        && providerEnv != null && !providerEnv.isBlank()
                        && fixturesEnv != null && !fixturesEnv.isBlank(),
                "requires merged Maven #203 original engine, separate SPI JAR, and fixtures");
        Path engine = Path.of(engineEnv).toAbsolutePath().normalize();
        Path provider = Path.of(providerEnv).toAbsolutePath().normalize();
        Path fixtures = Path.of(fixturesEnv).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(engine) && Files.isRegularFile(provider));
        assertTrue(Files.isRegularFile(fixtures.resolve("hello.ll"))
                        && Files.isRegularFile(fixtures.resolve("m3-build.tsv")),
                "source-pinned Maven #203 LLVM IR fixtures must be available");

        Path project = Files.createDirectories(root.resolve("pipeline with spaces"));
        Path vendor = Files.createDirectories(project.resolve("vendor"));
        Files.copy(engine, vendor.resolve("maven-polyglot.jar"));
        Files.copy(provider, vendor.resolve("llvm-tools.jar"));
        Files.copy(fixtures.resolve("hello.ll"), project.resolve("hello.ll"));
        Files.copy(fixtures.resolve("m3-build.tsv"), project.resolve("m3-build.tsv"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-llvm-pipeline'\n");

        String pins = "  engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))\n"
                + "  engineSha256.set('" + sha(engine) + "')\n";
        if (installedProvider) {
            pins += "  providerJars.from(layout.projectDirectory.file('vendor/llvm-tools.jar'))\n"
                    + "  providerJarSha256.put('llvm-tools.jar', '"
                    + (wrongPin ? "0".repeat(64) : sha(provider)) + "')\n";
        }
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotProviders', 'm3PolyglotPlan'].each { name ->\n"
                + "  tasks.named(name) {\n" + pins + "  }\n}\n"
                + "tasks.named('m3PolyglotPlan') { target.set('link') }\n");
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
