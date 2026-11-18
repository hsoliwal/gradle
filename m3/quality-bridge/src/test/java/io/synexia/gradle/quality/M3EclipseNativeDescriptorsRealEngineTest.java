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

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Optional end-to-end acceptance against the actual Maven source-owned reader,
 * not a synthetic Gradle JSON parser or a duplicate language compiler.
 */
final class M3EclipseNativeDescriptorsRealEngineTest {
    @TempDir
    Path root;

    @Test
    void nativePdeCdtRustAndSwiftDescriptorsPassThroughTheOriginalMavenEngine() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires a reviewed Maven polyglot engine JAR containing native descriptor support");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "engine JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = sha256(Files.readAllBytes(engine));

        Path eclipse = Files.createDirectories(root.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>Universal</name><buildSpec>"
                + "<buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand>"
                + "<buildCommand><name>org.eclipse.cdt.managedbuilder.core.genmakebuilder</name></buildCommand>"
                + "</buildSpec><natures><nature>vendor.future.language</nature></natures>"
                + "</projectDescription>");
        Path meta = Files.createDirectories(eclipse.resolve("META-INF"))
                .resolve("MANIFEST.MF");
        Files.writeString(meta, "Manifest-Version: 1.0\nBundle-SymbolicName: universal\n");
        Path target = Files.writeString(eclipse.resolve("native.target"), "<target name=\"One\"/>");
        Files.writeString(eclipse.resolve("CMakeLists.txt"), "project(universal)");
        Files.writeString(eclipse.resolve("Cargo.lock"), "# Cargo lock\n");
        Files.writeString(eclipse.resolve("Package.swift"), "// swift-tools-version:5.9\n");
        Files.writeString(eclipse.resolve("pyproject.toml"), "[project]\nname='universal'\n");
        Files.writeString(eclipse.resolve("package.json"), "{\"name\":\"universal\"}");
        Files.createDirectories(eclipse.resolve("target"));
        Files.writeString(eclipse.resolve("target/generated.class"), "not a source descriptor");

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='universal-eclipse'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseModel') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "}\n");

        Path jsonFile = root.resolve("build/reports/m3/eclipse-model.json");
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(jsonFile), "lazy Gradle registration must not read source");
        run();
        String first = Files.readString(jsonFile);
        assertTrue(first.startsWith("{\"schema\":\"m3-eclipse-project-v1\""), first);
        assertTrue(first.contains("\"META-INF/MANIFEST.MF\":\"" + sha256(Files.readAllBytes(meta)) + "\""));
        assertTrue(first.contains("\"native.target\":\"" + sha256(Files.readAllBytes(target)) + "\""));
        assertTrue(first.contains("\"CMakeLists.txt\":"), first);
        assertTrue(first.contains("\"Cargo.lock\":"), first);
        assertTrue(first.contains("\"Package.swift\":"), first);
        assertTrue(first.contains("\"pyproject.toml\":"), first);
        assertTrue(first.contains("\"package.json\":"), first);
        assertFalse(first.contains("target/generated.class"), "generated outputs never enter descriptor facts");

        Files.writeString(target, "<target name=\"Two\"/>", StandardCharsets.UTF_8);
        run();
        String second = Files.readString(jsonFile);
        assertTrue(second.contains("\"native.target\":\"" + sha256(Files.readAllBytes(target)) + "\""));
        assertFalse(second.equals(first), "changed PDE target is reflected in Maven's original model");
        assertEquals("<target name=\"Two\"/>", Files.readString(target),
                "Gradle review does not rewrite source");
    }

    @Test
    void originalMavenEngineRefusesMoreThan128DynamicPdeDescriptors() throws Exception {
        Path eclipse = refusalProject();
        for (int i = 0; i < 129; i++) {
            Files.writeString(eclipse.resolve(String.format("target-%03d.target", i)), "<target/>");
        }
        String output = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3EclipseModel", "--stacktrace").buildAndFail().getOutput();
        assertTrue(output.contains("Eclipse target/product descriptor count exceeded"), output);
        assertFalse(Files.exists(root.resolve("build/reports/m3/eclipse-model.json")),
                "invalid Maven model must not become a successful Gradle report");
    }

    @Test
    void originalMavenEngineRejectsSymlinkedPdeDescriptorWithoutReadingItsTarget() throws Exception {
        Path eclipse = refusalProject();
        Path outside = Files.writeString(root.resolve("outside-manifest.xml"), "<plugin/>");
        try {
            Files.createSymbolicLink(eclipse.resolve("plugin.xml"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            assumeTrue(false, "host does not permit this symlink-refusal fixture");
        }
        String output = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3EclipseModel", "--stacktrace").buildAndFail().getOutput();
        assertTrue(output.contains("Eclipse language descriptor must be a regular file"), output);
        assertEquals("<plugin/>", Files.readString(outside), "Maven must not mutate external source");
        assertFalse(Files.exists(root.resolve("build/reports/m3/eclipse-model.json")));
    }

    private Path refusalProject() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires exact merged Maven #211 descriptor engine JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original));
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        Path eclipse = Files.createDirectories(root.resolve("eclipse-project"));
        Files.writeString(eclipse.resolve(".project"),
                "<projectDescription><name>Native</name><buildSpec/>"
                + "<natures/></projectDescription>");
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-pde-refusal'\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3EclipseModel') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + sha256(Files.readAllBytes(engine)) + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "}\n");
        return eclipse;
    }

    private String run() {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3EclipseModel", "--stacktrace").build().getOutput();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
