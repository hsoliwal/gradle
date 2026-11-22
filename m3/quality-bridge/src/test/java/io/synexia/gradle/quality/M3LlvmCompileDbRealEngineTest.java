/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

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

/** Real original Maven #255 CLI through Gradle: export only, never run Clang. */
final class M3LlvmCompileDbRealEngineTest {
    @TempDir Path root;

    @Test
    void publishedDatabaseMatchesOriginalCompilerVectorsAndIsStable() throws Exception {
        Path project = fixture(false);
        String result = run(project, true);
        Path db = project.resolve("target/m3-llvm/compile_commands.json");
        assertTrue(result.contains("BUILD SUCCESSFUL"), result);
        assertTrue(Files.isRegularFile(db));
        String original = Files.readString(db);
        assertTrue(original.contains("\"arguments\":["), original);
        assertTrue(original.contains("native/hello.c"), original);
        assertTrue(original.contains("\"directory\":"), original);
        var stamp = Files.getLastModifiedTime(db);
        run(project, true);
        assertEquals(original, Files.readString(db));
        assertEquals(stamp, Files.getLastModifiedTime(db));
        assertFalse(Files.exists(project.resolve("target/hello.o")));
        assertFalse(Files.exists(project.resolve("target/hello")));
        assertFalse(Files.exists(project.resolve(".m3")));
        assertFalse(Files.exists(project.resolve("build/cmake/compile_commands.json")));
    }

    @Test
    void wrongEngineDigestRefusesBeforeMavenCanPublish() throws Exception {
        Path project = fixture(true);
        String failure = run(project, false);
        assertTrue(failure.contains("SHA-256 mismatch"), failure);
        assertFalse(Files.exists(project.resolve("target/m3-llvm/compile_commands.json")));
    }

    private Path fixture(boolean wrongDigest) throws Exception {
        String source = System.getenv("M3_MAVEN_COMPILEDB_JAR");
        String fixture = System.getenv("M3_MAVEN_COMPILEDB_FIXTURES");
        assumeTrue(source != null && !source.isBlank() && fixture != null && !fixture.isBlank(),
                "source-built merged Maven #255 artifact and original C17 fixture required");
        Path originalJar = Path.of(source).toAbsolutePath().normalize();
        Path originalFixture = Path.of(fixture).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(originalJar));
        assertTrue(Files.isRegularFile(originalFixture.resolve("m3-build.tsv")));
        assertTrue(Files.isRegularFile(originalFixture.resolve("native/hello.c")));
        Path workspace = Files.createDirectory(root.resolve("compdb with spaces"));
        Files.createDirectory(workspace.resolve("native"));
        Files.copy(originalFixture.resolve("native/hello.c"), workspace.resolve("native/hello.c"));
        Path header = originalFixture.resolve("native/message.h");
        if (Files.isRegularFile(header)) {
            Files.copy(header, workspace.resolve("native/message.h"));
        }
        Files.copy(originalFixture.resolve("m3-build.tsv"), workspace.resolve("m3-build.tsv"));
        Files.copy(originalJar, workspace.resolve("engine.jar"));
        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='compdb'\n");
        Files.writeString(workspace.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3LlvmCompileDb') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + (wrongDigest ? "0".repeat(64) : sha(originalJar)) + "')\n"
                + "  target.set('all')\n"
                + "}\n");
        return workspace;
    }

    private String run(Path project, boolean success) {
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath().withArguments("m3LlvmCompileDb", "--stacktrace", "--rerun-tasks");
        return success ? runner.build().getOutput() : runner.buildAndFail().getOutput();
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
