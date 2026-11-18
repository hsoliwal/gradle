/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gradle delegates discovery to the same pinned Maven CLI, never a forked registry. */
class M3PolyglotProvidersTaskTest {
    @TempDir Path workspace;

    @Test
    void delegatesProviderInventoryAndNormalizesWindowsLineEndings() throws Exception {
        fixture();
        String inventory = "M3-POLYGLOT-PROVIDERS-V1\r\n"
                + "provider\tacme\tatomizer,patternizer\tjava,rust\r\n"
                + "provider\tjdk\tcompiler\tjava\r\n";
        String digest = engine(inventory);
        configuration(digest);
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")),
                "plugin application must not run Maven or create reports");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").build();
        assertEquals(inventory.replace("\r\n", "\n"),
                Files.readString(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void refusesWrongEngineDigest() throws Exception {
        fixture();
        engine("M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n");
        configuration("f".repeat(64));
        String output = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").buildAndFail().getOutput();
        assertTrue(output.contains("M3 Maven provider inventory refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void refusesMalformedOrUnsortedInventories() throws Exception {
        fixture();
        String digest = engine("M3-POLYGLOT-PROVIDERS-V1\n"
                + "provider\tjdk\tcompiler\tjava\n"
                + "provider\tacme\tatomizer\trust\n");
        configuration(digest);
        String output = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").buildAndFail().getOutput();
        assertTrue(output.contains("M3 Maven provider inventory refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void loadsExplicitlyPinnedExternalProviderJarOnMavenRuntimeClasspath() throws Exception {
        fixture();
        String digest = engine("M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n");
        Path provider = workspace.resolve("external-provider.jar");
        Files.writeString(provider, "explicit provider bytes");
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(provider)));
        configuration(digest);
        Files.writeString(workspace.resolve("build.gradle"), Files.readString(workspace.resolve("build.gradle"))
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  providerJars.from(layout.projectDirectory.file('external-provider.jar'))\n"
                + "  providerJarSha256.put('external-provider.jar', '" + pin + "')\n"
                + "}\n");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").build();
        assertTrue(Files.readString(workspace.resolve("cli-classpath.txt"))
                .contains(File.pathSeparator + provider));
    }

    private void fixture() throws IOException {
        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='m3-provider-proxy'\n");
    }

    private void configuration(String digest) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "}\n");
    }

    private String engine(String output) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("producer/src/io/synexia/maven/polyglot"))
                .resolve("PolyglotCli.java");
        String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        Files.writeString(source, "package io.synexia.maven.polyglot;\n"
                + "public final class PolyglotCli { public static void main(String[] args) {\n"
                + "if (args.length != 1 || !\"providers\".equals(args[0])) throw new IllegalArgumentException();\n"
                + "try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"cli-classpath.txt\"), "
                + "System.getProperty(\"java.class.path\")); } catch (java.io.IOException fail) { throw new RuntimeException(fail); }\n"
                + "System.out.print(\"" + escaped + "\"); } }\n", StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(workspace.resolve("producer/classes"));
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, code);
        Path jar = workspace.resolve("engine.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("io/synexia/maven/polyglot/PolyglotCli.class"));
            stream.write(Files.readAllBytes(classes.resolve("io/synexia/maven/polyglot/PolyglotCli.class")));
            stream.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
