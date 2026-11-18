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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task-boundary tests use a synthetic Maven CLI implementing only its two-line
 * response protocol. They do not assert that synthetic Java is a Clang build,
 * or that it validates a real Maven-native source manifest. A separate pinned
 * Maven consumer must qualify the authentic engine.
 */
final class M3LlvmCompileDatabaseTaskTest {
    @TempDir
    Path project;

    @Test
    void invokesOnlyExplicitPinnedMavenProtocolAndWritesModuleLocalDatabase() throws Exception {
        fixture();
        configuration(engine("correct"));
        assertFalse(Files.exists(project.resolve("target/m3-llvm/compile_commands.json")));
        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all", "--offline").build();
        assertFalse(Files.exists(project.resolve("target/m3-llvm/compile_commands.json")),
                "plugin application and listing must never execute Maven or Clang");

        var first = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--offline", "--stacktrace").build();
        assertEquals(TaskOutcome.SUCCESS, first.task(":m3LlvmCompileDb").getOutcome());
        assertTrue(first.getOutput().contains("Original M3 LLVM compilation database:"));
        Path db = project.resolve("target/m3-llvm/compile_commands.json");
        assertEquals("[]\n", Files.readString(db));
        assertFalse(Files.exists(project.resolve(".m3")), "never create M3DB state");
        assertFalse(Files.exists(project.resolve("target/hello.o")), "never execute a native compiler");

        var second = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--offline", "--configuration-cache").build();
        assertEquals(TaskOutcome.SUCCESS, second.task(":m3LlvmCompileDb").getOutcome());
        assertEquals("[]\n", Files.readString(db));
    }

    @Test
    void refusesWrongMavenEngineDigestBeforeAnyInvocation() throws Exception {
        fixture();
        engine("correct");
        configuration("f".repeat(64));
        var refused = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--offline", "--stacktrace")
                .buildAndFail();
        assertTrue(refused.getOutput().contains("M3 LLVM compilation database refused"),
                refused.getOutput());
        assertFalse(Files.exists(project.resolve("target/m3-llvm/compile_commands.json")));
    }

    @Test
    void refusesForgedOrUnexpectedMavenCliResponse() throws Exception {
        fixture();
        configuration(engine("wrong"));
        var refused = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--offline", "--stacktrace")
                .buildAndFail();
        assertTrue(refused.getOutput().contains("unexpected LLVM database receipt"),
                refused.getOutput());
    }

    @Test
    void refusesSymlinkOutputParentBeforeLaunchingMaven() throws Exception {
        if (System.getProperty("os.name", "").startsWith("Windows")) return;
        fixture();
        configuration(engine("correct"));
        Path escape = Files.createDirectory(project.resolve("escape"));
        Files.createSymbolicLink(project.resolve("target"), escape);
        var refused = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--offline", "--stacktrace")
                .buildAndFail();
        assertTrue(refused.getOutput().contains("M3 LLVM compilation database refused"),
                refused.getOutput());
        assertFalse(Files.exists(escape.resolve("m3-llvm/compile_commands.json")));
    }

    private void fixture() throws IOException {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'm3-compdb-test'\n");
        Files.writeString(project.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\ntask\tall\t-\t.\n");
    }

    private void configuration(String sha) throws IOException {
        Files.writeString(project.resolve("build.gradle"), """
                plugins { id 'io.synexia.m3.quality' }
                tasks.named('m3LlvmCompileDb') {
                    engineJar.set(layout.projectDirectory.file('engine.jar'))
                    engineSha256.set('%s')
                }
                """.formatted(sha));
    }

    /**
     * Compiles one small replacement CLI solely to exercise the pinned binary
     * and protocol boundary. The actual Maven exporter is never rewritten here.
     */
    private String engine(String mode) throws Exception {
        Path sourceRoot = Files.createDirectories(project.resolve("probe-source"));
        Path source = sourceRoot.resolve("PolyglotCli.java");
        String text = """
                package io.synexia.maven.polyglot;
                import java.nio.file.*;
                import java.nio.charset.StandardCharsets;
                public final class PolyglotCli {
                    public static void main(String[] argv) throws Exception {
                        if (argv.length != 4 || !"llvm-compile-db".equals(argv[0]))
                            throw new IllegalArgumentException("unexpected command");
                        Path output = Path.of(argv[1]).toAbsolutePath().normalize()
                            .resolve("target/m3-llvm/compile_commands.json");
                        Files.createDirectories(output.getParent());
                        Files.writeString(output, "[]\\n", StandardCharsets.UTF_8);
                        System.out.println("%s");
                        System.out.println("file\\t" + output);
                    }
                }
                """.formatted("correct".equals(mode)
                ? "M3-LLVM-COMPILE-DB-V1" : "FORGED-LLVM-PROTOCOL");
        Files.writeString(source, text, StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(project.resolve("probe-classes"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "TestKit must run with a full JDK");
        int compiled = compiler.run(null, null, null, "--release", "17",
                "-d", classes.toString(), source.toString());
        assertEquals(0, compiled);
        Path classFile = classes.resolve("io/synexia/maven/polyglot/PolyglotCli.class");
        Path jar = project.resolve("engine.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("io/synexia/maven/polyglot/PolyglotCli.class"));
            Files.copy(classFile, out);
            out.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jar)));
    }
}
