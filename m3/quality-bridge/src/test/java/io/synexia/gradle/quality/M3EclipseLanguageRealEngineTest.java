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

/** Real Maven Java17 SPI and SHA-pinned installed provider, no native compiler. */
final class M3EclipseLanguageRealEngineTest {
    private static final String INVOCATION = "9ab5cc33-33bd-4f33-8aa9-6c3c11223344";
    @TempDir Path root;

    @Test
    void verifiesInstalledFutureLanguageWithoutAssumingItsCompilerOrCache() throws Exception {
        String mavenPath = System.getenv("M3_POLYGLOT_JAR");
        String adapterPath = System.getenv("M3_LANGUAGE_PROVIDER_JAR");
        assumeTrue(mavenPath != null && !mavenPath.isBlank()
                        && adapterPath != null && !adapterPath.isBlank(),
                "requires reviewed Maven engine and separately built synthetic provider JAR");
        Path approvedEngine = Path.of(mavenPath).toAbsolutePath().normalize();
        Path approvedAdapter = Path.of(adapterPath).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(approvedEngine), "source-built Maven engine missing");
        assertTrue(Files.isRegularFile(approvedAdapter), "reviewed provider fixture JAR missing");
        Path engine = root.resolve("engine.jar");
        Path adapter = root.resolve("test-pydev.jar");
        Files.copy(approvedEngine, engine);
        Files.copy(approvedAdapter, adapter);
        String engineSha = sha(engine);
        String adapterSha = sha(adapter);

        Path eclipse = Files.createDirectories(root.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>PyProject</name><natures>"
                + "<nature>org.python.pydev.pythonNature</nature>"
                + "</natures></projectDescription>");
        Path sourceDir = Files.createDirectories(eclipse.resolve("target/ide"));
        Path facts = Files.writeString(sourceDir.resolve("language-facts.ide"), source("Debug"));
        Path handoff = Files.writeString(sourceDir.resolve("build-handoff.ide"), owner());

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='future-language-fixture'\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseLanguageFacts') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + engineSha + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  languageProviderId.set('test.pydev')\n"
                + "  providerJars.from(layout.projectDirectory.file('test-pydev.jar'))\n"
                + "  providerJarSha256.put('test-pydev.jar', '" + adapterSha + "')\n"
                + "  languagePacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/language-facts.ide'))\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n"
                + "}\n");

        Path output = root.resolve("build/reports/m3/eclipse-language-facts.json");
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "installing a provider must not execute a language build");
        runner().build();
        String accepted = Files.readString(output);
        assertTrue(accepted.startsWith("{\"schema\":\"m3-eclipse-language-facts-v1\""), accepted);
        assertTrue(accepted.contains("\"providerId\":\"test.pydev\""), accepted);
        assertTrue(accepted.contains("\"sourceSchema\":\"M3-TEST-PYDEV-FACTS-V1\""), accepted);
        assertTrue(accepted.contains("\"project\":\"PyProject\""), accepted);
        assertTrue(accepted.contains("\"selectedConfiguration\":\"Debug\""), accepted);
        assertTrue(accepted.contains("\"observedSha256\":\"" + "a".repeat(64) + "\""), accepted);
        assertTrue(accepted.contains("\"observedCount\":7"), accepted);
        assertTrue(accepted.contains("\"authorizesBuildSkipping\":false"), accepted);
        assertEquals(owner(), Files.readString(handoff), "original Eclipse owner packet remains untouched");

        Files.writeString(facts, source("Release"), StandardCharsets.UTF_8);
        var failed = runner().buildAndFail();
        assertTrue(failed.getOutput().contains("foreign language evidence or original Eclipse build generation"),
                failed.getOutput());
        assertEquals(accepted, Files.readString(output),
                "foreign original-language facts may never overwrite a verified diagnostic report");
    }

    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3EclipseLanguageFacts", "--stacktrace", "--rerun-tasks");
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String source(String selected) {
        return "M3-TEST-PYDEV-FACTS-V1\n"
                + "project64\t" + base64("PyProject") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + base64(selected) + "\n"
                + "observedCount\t7\n"
                + "observedSha256\t" + "a".repeat(64) + "\n";
    }

    private static String owner() {
        return "M3-ECLIPSE-BUILD-HANDOFF-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\t" + base64("PyProject") + "\n"
                + "invocation\t" + INVOCATION + "\n"
                + "selected64\t" + base64("Debug") + "\n"
                + "active64\t" + base64("Debug") + "\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "configuration64\t" + base64("Debug") + "\n"
                + "configuration64\t" + base64("Release") + "\n"
                + "nature64\t" + base64("org.python.pydev.pythonNature") + "\n"
                + "change\tMODIFIED\tsrc/test.py\n";
    }
}
