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
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TestKit proves actual process handoff; Maven's own tests prove the original parser/executor. */
final class M3PolyglotManifestTaskTest {
    @TempDir
    Path root;

    @Test
    void planAndRunDelegateToPinnedPortableMavenEngineWithoutShell() throws Exception {
        String digest = createFakePortableEngine(root);
        fixture(digest);
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotPlan", "--stacktrace").build();
        assertEquals(List.of("plan", root.toString(), root.resolve("m3-build.tsv").toString(),
                        "all", "--timeout-seconds", "900"),
                Files.readAllLines(root.resolve("cli-argv.txt"), StandardCharsets.UTF_8));

        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build();
        assertEquals(List.of("run", root.toString(), root.resolve("m3-build.tsv").toString(),
                        "all", "--timeout-seconds", "37", "--incremental"),
                Files.readAllLines(root.resolve("cli-argv.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void refusesChangedPinnedEngineBeforeAnyExecution() throws Exception {
        String digest = createFakePortableEngine(root);
        fixture(digest);
        Files.writeString(root.resolve("engine.jar"), "changed", StandardCharsets.UTF_8);
        String failure = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").buildAndFail().getOutput();
        assertTrue(failure.contains("SHA-256 mismatch"));
        assertTrue(Files.notExists(root.resolve("cli-argv.txt")));
    }

    private void fixture(String digest) throws IOException {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
        Files.writeString(root.resolve("m3-build.tsv"), "M3-POLYGLOT-1\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotPlan') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "}\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  timeoutSeconds.set(37)\n"
                + "  incremental.set(true)\n"
                + "}\n");
    }

    private static String createFakePortableEngine(Path root) throws Exception {
        Path source = root.resolve("source/io/synexia/maven/polyglot/PolyglotCli.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package io.synexia.maven.polyglot;\n"
                + "public final class PolyglotCli {\n"
                + "  public static void main(String[] args) throws Exception {\n"
                + "    java.nio.file.Files.writeString(java.nio.file.Path.of(\"cli-argv.txt\"), "
                + "String.join(\"\\n\", args) + \"\\n\");\n"
                + "  }\n}\n", StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(root.resolve("classes"));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, status);
        Path jar = root.resolve("engine.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("io/synexia/maven/polyglot/PolyglotCli.class"));
            Files.copy(classes.resolve("io/synexia/maven/polyglot/PolyglotCli.class"), output);
            output.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
