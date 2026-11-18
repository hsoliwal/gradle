/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional real Maven Corrosion/Cargo evidence receiver acceptance. */
final class M3EclipseCargoRealEngineTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    @TempDir Path workspace;

    @Test
    void originalCargoMetadataAndEclipseHandoffMustAgreeBeforePublishingReport() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires reviewed Maven polyglot JAR with Corrosion/Cargo decoder");
        Path originalEngine = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(originalEngine));
        Path engine = workspace.resolve("engine.jar");
        Files.copy(originalEngine, engine);
        String sha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Path eclipse = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>cargo-fixture</name><buildSpec/>"
                + "<natures><nature>vendor.corrosion.nature</nature></natures></projectDescription>");
        Path owner = Files.createDirectories(eclipse.resolve("target/ide"));
        Path cargo = Files.writeString(owner.resolve("cargo-build-facts.ide"), cargoFacts("Debug"));
        Path handoff = Files.writeString(owner.resolve("build-handoff.ide"), eclipseHandoff());

        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='rust-owner-acceptance'\n");
        Files.writeString(workspace.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseCargoFacts') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + sha + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  cargoPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/cargo-build-facts.ide'))\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n"
                + "}\n");
        Path report = workspace.resolve("build/reports/m3/eclipse-cargo-facts.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(report), "applying the Gradle plugin cannot invoke Cargo");

        run().build();
        String accepted = Files.readString(report);
        assertTrue(accepted.startsWith("{\"schema\":\"m3-cargo-build-facts-v1\""), accepted);
        assertTrue(accepted.contains("\"provider\":\"eclipse.corrosion\""), accepted);
        assertTrue(accepted.contains("\"project\":\"cargo-fixture\""), accepted);
        assertTrue(accepted.contains("\"eclipseSelected\":\"Debug\""), accepted);
        assertTrue(accepted.contains("\"lockSha256\":\"" + "b".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"cargoMetadataSha256\":\"" + "c".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"metadataBytes\":1234"), accepted);
        assertTrue(accepted.contains("\"originalDeltaComplete\":true"), accepted);
        assertTrue(accepted.contains("\"authorizesBuildSkipping\":false"), accepted);
        assertEquals(eclipseHandoff(), Files.readString(handoff), "original Eclipse source-owner packet is unchanged");

        Files.writeString(cargo, cargoFacts("Release"), StandardCharsets.UTF_8);
        var failed = run().buildAndFail();
        assertTrue(failed.getOutput().contains("foreign Cargo facts or original Eclipse build generation"),
                failed.getOutput());
        assertEquals(accepted, Files.readString(report),
                "invalid Corrosion metadata must not replace a prior valid report");
    }

    private GradleRunner run() {
        return GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseCargoFacts", "--stacktrace", "--rerun-tasks");
    }

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String cargoFacts(String selected) {
        return "M3-CARGO-BUILD-FACTS-V1\n"
                + "provider\teclipse.corrosion\n"
                + "project64\t" + b64("cargo-fixture") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "eclipseSelected64\t" + b64(selected) + "\n"
                + "manifestSha256\t" + "a".repeat(64) + "\n"
                + "lockSha256\t" + "b".repeat(64) + "\n"
                + "cargoConfigSha256\tabsent\n"
                + "rustToolchainSha256\tabsent\n"
                + "rustToolchainTomlSha256\tabsent\n"
                + "cargoMetadataSha256\t" + "c".repeat(64) + "\n"
                + "metadataBytes\t1234\n"
                + "cargoCommandSha256\t" + "d".repeat(64) + "\n";
    }

    private static String eclipseHandoff() {
        return "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64("cargo-fixture") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + b64("Debug") + "\n"
                + "active64\t" + b64("Debug") + "\n"
                + "kind\tAUTO\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("vendor.corrosion.builder") + "\n"
                + "nature64\t" + b64("vendor.corrosion.nature") + "\n"
                + "change\tMODIFIED\tsrc/lib.rs\n";
    }
}
