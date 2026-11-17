/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
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

/** A synthetic pinned CLI verifies delegation, output custody, and refusal. */
class M3EclipseModelTaskTest {
    @TempDir
    Path workspace;

    @Test
    void explicitlyDelegatesToPinnedMavenReaderWithoutTouchingTheDescriptor() throws Exception {
        fixture();
        String validJson = "{\"schema\":\"m3-eclipse-project-v1\",\"source\":\"synthetic\"}\r\n";
        String digest = engine(validJson);
        configuration(digest);

        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "registering the plugin must not invoke its Maven CLI");

        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseModel", "--stacktrace").build();
        assertEquals(validJson.replace("\r\n", "\n"),
                Files.readString(workspace.resolve("build/reports/m3/eclipse-model.json")));
        assertEquals("<projectDescription><name>fixture</name></projectDescription>\n",
                Files.readString(workspace.resolve("eclipse-project/.project")));
    }

    @Test
    void refusesWrongDigestWithoutPublishingAnUnverifiedReport() throws Exception {
        fixture();
        engine("{\"schema\":\"m3-eclipse-project-v1\"}\n");
        configuration("0".repeat(64));
        var result = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseModel", "--stacktrace").buildAndFail();
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")));
    }

    private void fixture() throws IOException {
        Path descriptorDirectory = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(descriptorDirectory.resolve(".project"),
                "<projectDescription><name>fixture</name></projectDescription>\n");
        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='eclipse-adapter-fixture'\n");
    }

    private void configuration(String digest) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseModel') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "}\n");
    }

    private String engine(String output) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("producer/src/io/synexia/maven/polyglot"))
                .resolve("EclipseModelCli.java");
        String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        Files.writeString(source, "package io.synexia.maven.polyglot;\n"
                + "public class EclipseModelCli { public static void main(String[] args) {\n"
                + "System.out.print(\"" + escaped + "\"); } }\n");
        Path classes = Files.createDirectories(workspace.resolve("producer/classes"));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, status, "synthetic Maven-compatible CLI must compile");
        Path jar = workspace.resolve("engine.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("io/synexia/maven/polyglot/EclipseModelCli.class"));
            stream.write(Files.readAllBytes(classes.resolve("io/synexia/maven/polyglot/EclipseModelCli.class")));
            stream.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
