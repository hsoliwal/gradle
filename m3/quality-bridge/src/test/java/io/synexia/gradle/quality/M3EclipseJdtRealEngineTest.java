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

/** Optional real Maven receiver acceptance, without an Eclipse compiler process. */
final class M3EclipseJdtRealEngineTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";

    @TempDir Path workspace;

    @Test
    void acceptsOriginalJdtFactsAndRejectsForeignConfigurationWithoutRepublishing() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires source-built Maven polyglot JAR with JDT facts decoder");
        Path originalEngine = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(originalEngine), "reviewed Maven engine must exist");
        Path engine = workspace.resolve("engine.jar");
        Files.copy(originalEngine, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Path eclipse = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"), """
                <projectDescription><name>Mixed</name><buildSpec>
                  <buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand>
                  <buildCommand><name>org.eclipse.cdt.managedbuilder.core.genmakebuilder</name></buildCommand>
                </buildSpec><natures><nature>vendor.rustdt.nature</nature></natures>
                </projectDescription>
                """);
        Path originals = Files.createDirectories(eclipse.resolve("target/ide"));
        Path handoff = Files.writeString(originals.resolve("build-handoff.ide"), handoff());
        Path jdt = Files.writeString(originals.resolve("jdt-compiler-facts.ide"), jdtFacts("Debug"));

        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='jdt-owner-acceptance'\n");
        Files.writeString(workspace.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseJdtFacts') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  jdtPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/jdt-compiler-facts.ide'))\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n"
                + "}\n");
        Path report = workspace.resolve("build/reports/m3/eclipse-jdt-facts.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(report), "applying the Gradle plugin never executes JDT");
        run().build();
        String accepted = Files.readString(report);
        assertTrue(accepted.startsWith("{\"schema\":\"m3-jdt-compiler-facts-v1\""), accepted);
        assertTrue(accepted.contains("\"project\":\"Mixed\""), accepted);
        assertTrue(accepted.contains("\"selectedConfiguration\":\"Debug\""), accepted);
        assertTrue(accepted.contains("\"rawClasspathSha256\":\"" + "a".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"resolvedClasspathSha256\":\"" + "b".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"compilerOptionsSha256\":\"" + "c".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"eclipseBuildKind\":\"INCREMENTAL\""), accepted);
        assertTrue(accepted.contains("\"originalDeltaComplete\":true"), accepted);
        assertTrue(accepted.contains("\"authorizesBuildSkipping\":false"), accepted);
        assertEquals(handoff(), Files.readString(handoff), "original Eclipse packet is immutable");

        Files.writeString(jdt, jdtFacts("Release"), StandardCharsets.UTF_8);
        var failed = run().buildAndFail();
        assertTrue(failed.getOutput().contains("foreign JDT compiler facts or Eclipse build generation"),
                failed.getOutput());
        assertEquals(accepted, Files.readString(report),
                "foreign JDT metadata must never replace an earlier valid report");
    }

    private GradleRunner run() {
        return GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseJdtFacts", "--stacktrace", "--rerun-tasks");
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String jdtFacts(String selected) {
        return "M3-JDT-COMPILER-FACTS-V1\n"
                + "provider\teclipse.jdt\n"
                + "project64\t" + b64("Mixed") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + b64(selected) + "\n"
                + "output64\t" + b64("/Mixed/target/classes") + "\n"
                + "rawSha256\t" + "a".repeat(64) + "\n"
                + "resolvedSha256\t" + "b".repeat(64) + "\n"
                + "optionsSha256\t" + "c".repeat(64) + "\n"
                + "rawCount\t3\nresolvedCount\t5\n";
    }

    private static String handoff() {
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
                + "builder64\t" + b64("org.eclipse.jdt.core.javabuilder") + "\n"
                + "builder64\t" + b64("org.eclipse.cdt.managedbuilder.core.genmakebuilder") + "\n"
                + "nature64\t" + b64("vendor.rustdt.nature") + "\n"
                + "change\tADDED\tsrc/A.java\n"
                + "change\tMODIFIED\tsrc/B.java\n";
    }
}
