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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Real merged Maven #220 refusal, not an emulated Gradle database engine. */
final class M3JdkDbModuleAccessRealEngineTest {
    @TempDir
    Path root;

    @Test
    void currentJavaBaseExportDoesNotInventTheAbsentM3JdkDatabase() throws Exception {
        Path jar = fixture("current-java-base");
        String output = runAndFail();
        assertTrue(output.contains("jdk.m3.db") || output.contains("m3db backend"), output);
        assertTrue(Files.isRegularFile(jar), "Gradle may not modify the original Maven plugin");
        assertFalse(Files.exists(root.resolve(".m3/maven-build-db")),
                "missing M3JDK runtime must never fall back to an unqualified directory store");
        assertFalse(Files.exists(root.resolve("executed.txt")),
                "the Java target must not run when Maven refuses its native database");
    }

    @Test
    void rejectedJvmFlagCannotReachTheOriginalMavenProcess() throws Exception {
        fixture("--javaagent=/tmp/untrusted.jar");
        String output = runAndFail();
        assertTrue(output.contains("unsupported M3DB module selection"), output);
        assertFalse(Files.exists(root.resolve("executed.txt")));
        assertFalse(Files.exists(root.resolve(".m3/maven-build-db")));
    }

    private Path fixture(String moduleAccess) throws Exception {
        String source = System.getenv("M3_POLYGLOT_M3JDK_DB_JAR");
        assumeTrue(source != null && !source.isBlank(), "requires merged Maven #220 engine JAR");
        Path original = Path.of(source).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original));
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3jdk-module-access'\n");
        Files.writeString(root.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tverify\tjava\t.\n"
                + "arg\tverify\t-version\n"
                + "input\tverify\tsource.txt\n"
                + "output\tverify\texecuted.txt\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("source.txt"), "A", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('m3db')\n"
                + "  m3DbModuleAccess.set('" + moduleAccess + "')\n"
                + "  target.set('verify')\n"
                + "}\n");
        return engine;
    }

    private String runAndFail() {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace")
                .buildAndFail().getOutput();
    }
}
