/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-fork original-engine acceptance, separate from historical Maven #255.
 * Runs the Maven #324 merged binary through Gradle's existing untracked task.
 * Neither this test nor Gradle reimplements Clang or compilation database JSON.
 */
final class M3Llvm324ForwardedDepfileRealEngineTest {
    @TempDir
    Path root;

    @Test
    void mergedMaven324RejectsForwardedDepfileWithoutClobberingAcceptedDatabase()
            throws Exception {
        String engineSetting = System.getenv("M3_MAVEN_COMPILEDB_JAR");
        String fixtureSetting = System.getenv("M3_MAVEN_COMPILEDB_FIXTURES");
        assumeTrue(engineSetting != null && !engineSetting.isBlank()
                        && fixtureSetting != null && !fixtureSetting.isBlank(),
                "Exact merged Maven #324 JAR and original C17 fixture are mandatory");

        Path originalEngine = Path.of(engineSetting).toAbsolutePath().normalize();
        Path fixture = Path.of(fixtureSetting).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(originalEngine), "original Maven JAR must exist");
        assertTrue(Files.isRegularFile(fixture.resolve("m3-build.tsv")));
        assertTrue(Files.isRegularFile(fixture.resolve("native/hello.c")));
        assertTrue(Files.isRegularFile(fixture.resolve("native/message.h")));

        Path project = Files.createDirectory(root.resolve("m3 LLVM module with spaces"));
        Files.createDirectory(project.resolve("native"));
        Files.copy(fixture.resolve("native/hello.c"), project.resolve("native/hello.c"));
        Files.copy(fixture.resolve("native/message.h"), project.resolve("native/message.h"));
        Path manifest = project.resolve("m3-build.tsv");
        Files.copy(fixture.resolve("m3-build.tsv"), manifest);
        Files.copy(originalEngine, project.resolve("engine.jar"));

        Files.writeString(project.resolve("settings.gradle"),
                "rootProject.name='m3-llvm-324'\n", StandardCharsets.UTF_8);
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3LlvmCompileDb') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + sha(originalEngine) + "')\n"
                + "  target.set('all')\n"
                + "}\n", StandardCharsets.UTF_8);

        String accepted = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--stacktrace", "--rerun-tasks")
                .build().getOutput();
        assertTrue(accepted.contains("BUILD SUCCESSFUL"), accepted);
        Path database = project.resolve("target/m3-llvm/compile_commands.json");
        assertTrue(Files.isRegularFile(database));
        byte[] acceptedBytes = Files.readAllBytes(database);
        assertTrue(acceptedBytes.length > 3);
        var acceptedStamp = Files.getLastModifiedTime(database);
        assertFalse(Files.exists(project.resolve("target/hello.o")));
        assertFalse(Files.exists(project.resolve(".m3")));

        // The forwarded preprocessor option writes a real depfile even when
        // the driver command has no -MF matching that filename. Explicitly
        // claiming it as an output must not override Maven's opaque-flag gate.
        String sourceManifest = Files.readString(manifest, StandardCharsets.UTF_8);
        String forwardedManifest = sourceManifest
                .replace("arg\tcompile\t-c\n",
                        "arg\tcompile\t-c\n"
                        + "arg\tcompile\t-Wp,-MD,target/hidden.d\n")
                .replace("output\tcompile\ttarget/hello.d\n",
                        "output\tcompile\ttarget/hello.d\n"
                        + "output\tcompile\ttarget/hidden.d\n");
        assertNotEquals(sourceManifest, forwardedManifest);
        assertTrue(forwardedManifest.contains("arg\tcompile\t-Wp,-MD,target/hidden.d\n"));
        assertTrue(forwardedManifest.contains("output\tcompile\ttarget/hidden.d\n"));
        Files.writeString(manifest, forwardedManifest, StandardCharsets.UTF_8);

        String refused = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--stacktrace", "--rerun-tasks")
                .buildAndFail().getOutput();
        assertTrue(refused.contains("unqualified Clang output/forwarding option"),
                "Maven #324 must refuse the original forwarded Clang option: " + refused);
        assertTrue(refused.contains("-Wp,-MD,target/hidden.d"), refused);
        assertArrayEquals(acceptedBytes, Files.readAllBytes(database),
                "failed metadata export must not publish partial output");
        assertEquals(acceptedStamp, Files.getLastModifiedTime(database),
                "failed metadata export must preserve accepted output timestamp");
        assertFalse(Files.exists(project.resolve("target/hidden.d")),
                "Gradle must not execute native Clang during IDE metadata export");
        assertFalse(Files.exists(project.resolve("target/hello.o")));
        assertFalse(Files.exists(project.resolve(".m3")));
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
