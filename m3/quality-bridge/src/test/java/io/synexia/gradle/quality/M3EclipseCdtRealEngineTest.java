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

/** Actual Maven CDT packet decoder on a Gradle task, never a fake JSON CLI. */
final class M3EclipseCdtRealEngineTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    @TempDir Path workspace;

    @Test
    void validatesSourceOwnedCdtConfigurationAndPreservesPriorReportOnForeignSelection() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires Maven polyglot source-built JAR with CDT facts decoder");
        Path originalEngine = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(originalEngine), "reviewed Maven engine must exist");
        Path engine = workspace.resolve("engine.jar");
        Files.copy(originalEngine, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Path eclipse = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>Mixed</name><buildSpec>"
                + "<buildCommand><name>org.eclipse.cdt.managedbuilder.core.genmakebuilder</name></buildCommand>"
                + "</buildSpec><natures><nature>org.eclipse.cdt.core.cnature</nature></natures>"
                + "</projectDescription>");
        Path originalPackets = Files.createDirectories(eclipse.resolve("target/ide"));
        Path cdtPacket = Files.writeString(originalPackets.resolve("cdt-compiler-facts.ide"), cdtFacts("Debug"));
        Files.writeString(originalPackets.resolve("build-handoff.ide"), eclipseHandoff());

        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='cdt-source-owner-acceptance'\n");
        Files.writeString(workspace.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseCdtFacts') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  cdtPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/cdt-compiler-facts.ide'))\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n"
                + "}\n");
        Path report = workspace.resolve("build/reports/m3/eclipse-cdt-facts.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(report), "applying the Gradle plugin never compiles CDT sources");
        runner().build();
        String originalReport = Files.readString(report);
        assertTrue(originalReport.startsWith("{\"schema\":\"m3-cdt-compiler-facts-v1\""), originalReport);
        assertTrue(originalReport.contains("\"provider\":\"eclipse.cdt\""), originalReport);
        assertTrue(originalReport.contains("\"project\":\"Mixed\""), originalReport);
        assertTrue(originalReport.contains("\"cdtConfigId\":\"cdt.native.debug\""), originalReport);
        assertTrue(originalReport.contains("\"cdtActiveConfigId\":\"cdt.native.release\""), originalReport);
        assertTrue(originalReport.contains("\"eclipseSelected\":\"Debug\""), originalReport);
        assertTrue(originalReport.contains("\"scannerSha256\":\"" + "a".repeat(64) + "\""), originalReport);
        assertTrue(originalReport.contains("\"originalDeltaComplete\":true"), originalReport);
        assertTrue(originalReport.contains("\"authorizesBuildSkipping\":false"), originalReport);

        Files.writeString(cdtPacket, cdtFacts("Release"), StandardCharsets.UTF_8);
        var result = runner().buildAndFail();
        assertTrue(result.getOutput().contains("foreign CDT compiler facts or Eclipse build generation"),
                result.getOutput());
        assertEquals(originalReport, Files.readString(report),
                "foreign CDT facts must never overwrite previously validated evidence");
    }

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseCdtFacts", "--stacktrace", "--rerun-tasks");
    }

    private static String b64(String text) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String cdtFacts(String eclipseSelected) {
        return "M3-CDT-COMPILER-FACTS-V1\n"
                + "provider\teclipse.cdt\n"
                + "project64\t" + b64("Mixed") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "eclipseSelected64\t" + b64(eclipseSelected) + "\n"
                + "cdtConfigId64\t" + b64("cdt.native.debug") + "\n"
                + "cdtConfigName64\t" + b64("Debug (native)") + "\n"
                + "cdtActiveId64\t" + b64("cdt.native.release") + "\n"
                + "buildSystem64\t" + b64("org.eclipse.cdt.managedbuilder") + "\n"
                + "scannerSha256\t" + "a".repeat(64) + "\n"
                + "sourcesSha256\t" + "b".repeat(64) + "\n"
                + "buildSettingSha256\t" + "c".repeat(64) + "\n"
                + "resourceCount\t2\n"
                + "languageCount\t3\n"
                + "entryCount\t17\n";
    }

    private static String eclipseHandoff() {
        return "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64("Mixed") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + b64("Debug") + "\n"
                + "active64\t" + b64("Release") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "configuration64\t" + b64("Release") + "\n"
                + "builder64\t" + b64("org.eclipse.cdt.managedbuilder.core.genmakebuilder") + "\n"
                + "nature64\t" + b64("org.eclipse.cdt.core.cnature") + "\n"
                + "change\tMODIFIED\tsrc/native.c\n";
    }
}
