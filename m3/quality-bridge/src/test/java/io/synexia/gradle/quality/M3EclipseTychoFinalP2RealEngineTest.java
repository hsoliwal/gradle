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

/** Real Maven-owned Tycho FINAL p2 packet decoder through a pinned Gradle task. */
final class M3EclipseTychoFinalP2RealEngineTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    @TempDir Path workspace;

    @Test
    void acceptsOnlyActualFinalP2OwnerAndMatchingEclipseProjectGeneration() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "reviewed source-built Maven JAR with Tycho final-p2 codec is required");
        Path inputEngine = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(inputEngine), "Maven engine must be a real file");
        Path engine = workspace.resolve("engine.jar");
        Files.copy(inputEngine, engine);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(engine)));
        Path eclipse = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>EclipsePDE</name><buildSpec>"
                + "<buildCommand><name>org.eclipse.pde.ManifestBuilder</name></buildCommand>"
                + "</buildSpec><natures><nature>org.eclipse.pde.PluginNature</nature></natures>"
                + "</projectDescription>");
        Path original = Files.createDirectories(eclipse.resolve("target/ide"));
        Path tycho = Files.writeString(original.resolve("tycho-final-p2-facts.ide"), tychoFacts("final"));
        Path handoff = Files.writeString(original.resolve("build-handoff.ide"), eclipseHandoff());

        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='pde-original-source-owner'\n");
        Files.writeString(workspace.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseTychoFinalP2Facts') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  tychoPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/tycho-final-p2-facts.ide'))\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n");

        Path report = workspace.resolve("build/reports/m3/eclipse-tycho-final-p2-facts.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(report), "applying Gradle plugin cannot resolve a p2 target");

        runner().build();
        String accepted = Files.readString(report);
        assertTrue(accepted.startsWith("{\"schema\":\"m3-tycho-final-p2-facts-v1\""), accepted);
        assertTrue(accepted.contains("\"provider\":\"tycho.p2\""), accepted);
        assertTrue(accepted.contains("\"groupId\":\"org.example\""), accepted);
        assertTrue(accepted.contains("\"artifactId\":\"pde\""), accepted);
        assertTrue(accepted.contains("\"eclipseProject\":\"EclipsePDE\""), accepted);
        assertTrue(accepted.contains("\"stage\":\"final\""), accepted);
        assertTrue(accepted.contains("\"unitCount\":2"), accepted);
        assertTrue(accepted.contains("\"unitsSha256\":\"" + "a".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"eclipseBuildKind\":\"INCREMENTAL\""), accepted);
        assertTrue(accepted.contains("\"authorizesBuildSkipping\":false"), accepted);
        assertEquals(eclipseHandoff(), Files.readString(handoff), "original Eclipse packet immutable");

        Files.writeString(tycho, tychoFacts("preliminary"), StandardCharsets.UTF_8);
        var refused = runner().buildAndFail();
        assertTrue(refused.getOutput().contains("p2 source must be the original Tycho FINAL platform"),
                refused.getOutput());
        assertEquals(accepted, Files.readString(report),
                "rejected preliminary target must never replace previous accepted diagnostics");
    }

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseTychoFinalP2Facts", "--stacktrace", "--rerun-tasks");
    }

    private static String b64(String source) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }

    private static String tychoFacts(String stage) {
        return "M3-TYCHO-FINAL-P2-FACTS-V1\n"
                + "provider\ttycho.p2\n"
                + "group64\t" + b64("org.example") + "\n"
                + "artifact64\t" + b64("pde") + "\n"
                + "version64\t" + b64("1.0.0") + "\n"
                + "packaging64\t" + b64("eclipse-plugin") + "\n"
                + "eclipseProject64\t" + b64("EclipsePDE") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "eclipseSelected64\t" + b64("Debug") + "\n"
                + "os64\t" + b64("linux") + "\n"
                + "ws64\t" + b64("gtk") + "\n"
                + "arch64\t" + b64("x86_64") + "\n"
                + "stage\t" + stage + "\n"
                + "unitCount\t2\n"
                + "unitsSha256\t" + "a".repeat(64) + "\n";
    }

    private static String eclipseHandoff() {
        return "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + b64("EclipsePDE") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + b64("Debug") + "\n"
                + "active64\t" + b64("Debug") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + b64("Debug") + "\n"
                + "builder64\t" + b64("org.eclipse.pde.ManifestBuilder") + "\n"
                + "nature64\t" + b64("org.eclipse.pde.PluginNature") + "\n"
                + "change\tMODIFIED\tMETA-INF/MANIFEST.MF\n";
    }
}
