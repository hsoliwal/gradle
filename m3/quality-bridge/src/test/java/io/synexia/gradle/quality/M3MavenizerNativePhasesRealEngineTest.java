/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The original Maven #212 Mavenizer emits a manifest, and the existing Gradle
 * bridge plans it through the original PolyglotCli. Neither authority is
 * reimplemented by Gradle, and neither native build tool is launched.
 */
final class M3MavenizerNativePhasesRealEngineTest {
    @TempDir
    Path root;

    @Test
    void originalRebar3AndComposerPhasesRemainNativeThroughGradlePlan() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires source-pinned original Maven #212 polyglot JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "original Maven artifact must be installed");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(engine)));

        Files.writeString(root.resolve("rebar.config"), "{erl_opts, [debug_info]}.\n");
        Files.createDirectory(root.resolve("test"));
        Files.writeString(root.resolve("composer.json"), "{\"name\":\"example/native\"}\n");
        Files.writeString(root.resolve("phpunit.xml"), "<phpunit/>\n");

        List<String> planned = originalMavenize(engine, root, false);
        assertTrue(planned.contains("PLAN pom.xml") && planned.contains("PLAN m3-build.tsv"),
                "Mavenizer's planning path must report original native phases");
        assertFalse(Files.exists(root.resolve("pom.xml")), "planning must not write a Maven project");
        assertFalse(Files.exists(root.resolve("m3-build.tsv")), "planning must not write a manifest");

        List<String> generated = originalMavenize(engine, root, true);
        assertTrue(generated.contains("WRITE pom.xml") && generated.contains("WRITE m3-build.tsv"));
        String manifest = Files.readString(root.resolve("m3-build.tsv"));
        assertTrue(manifest.startsWith("M3-POLYGLOT-TASKS-V1\n"));
        assertTrue(manifest.contains("task\trebar3-compile\trebar3\t.\n"));
        assertTrue(manifest.contains("arg\trebar3-test\teunit\n"));
        assertTrue(manifest.contains("task\tcomposer-validate\tcomposer\t.\n"));
        assertTrue(manifest.contains("arg\tcomposer-validate\t--no-check-publish\n"));
        assertTrue(manifest.contains("arg\tcomposer-test\tphpunit\n"));
        assertFalse(manifest.contains("composer-install"), "Composer install must not be inferred");
        assertTrue(Files.readString(root.resolve("pom.xml")).contains("<id>m3-polyglot</id>"),
                "Maven's generated native lifecycle must remain opt-in");
        assertFalse(Files.exists(root.resolve("vendor")), "Mavenizer must not install PHP dependencies");
        assertFalse(Files.exists(root.resolve("_build")), "Mavenizer must not invoke Rebar3");

        // A Gradle build script is added only after Mavenizer's scan, so its
        // original language detection is not influenced by Gradle test setup.
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='native-manifest-consumer'\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotPlan') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  target.set(providers.gradleProperty('m3.native.target').orElse('compile'))\n"
                + "}\n");

        String compile = plan("compile");
        assertTrue(compile.contains("PLAN rebar3-compile tool=rebar3"), compile);
        assertFalse(compile.contains("TASK rebar3-compile"), "Gradle must not execute Erlang tools");
        String validate = plan("validate");
        assertTrue(validate.contains("PLAN composer-validate tool=composer"), validate);
        String test = plan("test");
        assertTrue(test.contains("PLAN rebar3-test tool=rebar3"), test);
        assertTrue(test.contains("PLAN composer-test tool=composer"), test);
        assertTrue(test.contains("PLANNED"), test);
        assertEquals("{\"name\":\"example/native\"}\n", Files.readString(root.resolve("composer.json")));
        assertFalse(Files.exists(root.resolve("vendor")) || Files.exists(root.resolve("_build")),
                "a dry-run Gradle plan must not invoke installed native tools");
    }

    @Test
    void nativeTestPhasesAreNotInferredWithoutTheirSourceDescriptors() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(),
                "requires source-pinned original Maven #212 polyglot JAR");
        Path engine = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(engine));
        Path workspace = Files.createDirectory(root.resolve("no-native-tests"));
        Files.writeString(workspace.resolve("rebar.config"), "{erl_opts, [debug_info]}.\n");
        Files.writeString(workspace.resolve("composer.json"), "{\"name\":\"example/native\"}\n");
        originalMavenize(engine, workspace, true);
        String manifest = Files.readString(workspace.resolve("m3-build.tsv"));
        assertTrue(manifest.contains("task\trebar3-compile\trebar3\t.\n"));
        assertTrue(manifest.contains("task\tcomposer-validate\tcomposer\t.\n"));
        assertFalse(manifest.contains("rebar3-test"), "no test/ directory means no Rebar3 eunit");
        assertFalse(manifest.contains("composer-test"), "no PHPUnit declaration means no Composer test");
        assertFalse(manifest.contains("install"), "Mavenizer must not infer Composer installation");
        assertFalse(Files.exists(workspace.resolve("vendor")) || Files.exists(workspace.resolve("_build")));
    }

    @SuppressWarnings("unchecked")
    private List<String> originalMavenize(Path originalJar, Path workspace, boolean write) throws Exception {
        // Isolate source-owned Mavenizer from Gradle/JUnit classpaths. The Maven
        // plugin's public API is dependency-free for this method.
        try (URLClassLoader source = new URLClassLoader(new java.net.URL[] {
                originalJar.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            Class<?> original = source.loadClass("io.synexia.maven.polyglot.Mavenizer");
            Method method = original.getMethod("mavenize", Path.class, String.class,
                    String.class, boolean.class);
            return (List<String>) method.invoke(null, workspace, "org.example", "1.0-SNAPSHOT", write);
        }
    }

    private String plan(String target) {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotPlan", "-Pm3.native.target=" + target, "--stacktrace")
                .build().getOutput();
    }
}
