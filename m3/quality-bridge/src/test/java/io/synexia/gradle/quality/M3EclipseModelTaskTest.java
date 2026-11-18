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
    void runsReadOnlyBuilderOwnerInventoryOnlyWhenExplicitlyRequested() throws Exception {
        fixture();
        String report = "{\"schema\":\"m3-eclipse-builder-owners-v1\",\"project\":\"fixture\","
                + "\"builders\":[],\"unboundNatures\":[]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuilders");

        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        Path out = workspace.resolve("build/reports/m3/eclipse-builders.json");
        assertFalse(Files.exists(out), "registration must not execute any Eclipse adapter");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseBuilders", "--stacktrace").build();
        assertEquals(report.replace("\r\n", "\n"), Files.readString(out));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "owner inventory cannot silently activate the original model task");
    }

    @Test
    void importsOnlyExplicitEclipseConfigurationPacket() throws Exception {
        fixture();
        Path context = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-context.ide");
        Files.writeString(context, "M3-ECLIPSE-BUILD-CONTEXT-V1\nowner-source\n");
        String report = "{\"schema\":\"m3-eclipse-build-context-v1\","
                + "\"project\":\"fixture\",\"selectedConfiguration\":\"Debug\","
                + "\"activeConfiguration\":\"Release\"}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuildContext");
        Files.writeString(workspace.resolve("build.gradle"), "\ntasks.named('m3EclipseBuildContext') {\n"
                + "  contextPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-context.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-build-context.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "configuring a Gradle plugin cannot trigger Eclipse builds");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseBuildContext", "--stacktrace").build();
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertTrue(Files.readString(context).startsWith("M3-ECLIPSE-BUILD-CONTEXT-V1"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-builders.json")),
                "the build context cannot silently execute other Eclipse tasks");
    }

    @Test
    void importsOneEclipseBuildInvocationWithoutStartingAnyOtherBuilder() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\noriginal-owner-fixture\n");
        String report = "{\"schema\":\"m3-eclipse-build-handoff-v1\",\"project\":\"fixture\","
                + "\"selectedConfiguration\":\"Debug\",\"activeConfiguration\":\"Release\","
                + "\"deltaComplete\":true,\"changes\":[]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuildHandoff");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseBuildHandoff') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-build-handoff.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "task registration must not execute an Eclipse builder");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseBuildHandoff", "--stacktrace").build();
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertTrue(Files.readString(packet).startsWith("M3-ECLIPSE-BUILD-HANDOFF-V1"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-context.json")),
                "handoff cannot silently run the separate Eclipse context task");
    }

    @Test
    void reconcilesCurrentModelOnlyOnAnExplicitRequest() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\nowner-fixture\n");
        String report = "{\"schema\":\"m3-eclipse-reconciliation-v1\","
                + "\"disposition\":\"RECONCILE\",\"reasons\":[\"NATURES_CHANGED\"]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseReconcile");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseReconcile') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-reconciliation.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "reconciliation must not run at Gradle configuration time");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseReconcile", "--stacktrace").build();
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-handoff.json")),
                "reconciliation must not execute the other advisory task");
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "reconciliation must not create a second project-model report");
    }

    @Test
    void rejectsHandoffSchemaSubstitutionBeforePublishingReport() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\nsource\n");
        String digest = engine("{\"schema\":\"m3-eclipse-build-context-v1\"}\n");
        configuration(digest, "m3EclipseBuildHandoff");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseBuildHandoff') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);
        var result = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseBuildHandoff", "--stacktrace").buildAndFail();
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-handoff.json")));
    }

    @Test
    void refusesUnpinnedEclipseBuilderEngineWithoutCreatingAnOwnerReport() throws Exception {
        fixture();
        engine("{\"schema\":\"m3-eclipse-builder-owners-v1\",\"builders\":[]}\\n");
        configuration("0".repeat(64), "m3EclipseBuilders");
        var result = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3EclipseBuilders", "--stacktrace").buildAndFail();
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-builders.json")));
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
        configuration(digest, "m3EclipseModel");
    }

    private void configuration(String digest, String taskName) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('" + taskName + "') {\n"
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
