/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Maven #206 owns all three optional portable DB drivers. Without explicit
 * native runtime JARs they must fail before invoking a Gradle-requested tool.
 */
final class M3PortableEnginesRealEngineTest {
    @TempDir Path root;

    @Test
    void registeredRocksMapAndLmdbBackendsFailClosedWithoutOptionalDrivers() throws Exception {
        String supplied = System.getenv("M3_PORTABLE_ENGINES_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires exact Maven #206 engine JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "source-built Maven #206 JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-portable-drivers'\n");
        Files.writeString(root.resolve("source.txt"), "portable backend requested\n");
        Files.writeString(root.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tverify\tjava\t.\n"
                + "arg\tverify\t-cp\n"
                + "arg\tverify\t.\n"
                + "arg\tverify\tMarkerTool\n"
                + "input\tverify\tsource.txt\n"
                + "output\tverify\tresult.txt\n");
        Path java = root.resolve("MarkerTool.java");
        Files.writeString(java, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class MarkerTool {
                    public static void main(String[] args) throws Exception {
                        Files.writeString(Path.of("invoked.txt"), "executed");
                        Files.writeString(Path.of("result.txt"), "executed");
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.toString(), java.toString()));

        for (String backend : List.of(
                "ext-rocksdb-v1", "ext-mapdb-v1", "ext-lmdb-v1", "ext-not-installed")) {
            Files.writeString(root.resolve("build.gradle"),
                    "plugins { id 'io.synexia.m3.quality' }\n"
                    + "tasks.named('m3PolyglotRun') {\n"
                    + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                    + "  engineSha256.set('" + digest + "')\n"
                    + "  target.set('verify')\n"
                    + "  incremental.set(true)\n"
                    + "  stateBackend.set('" + backend + "')\n"
                    + "}\n");
            String refusal = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                    .withArguments("m3PolyglotRun", "--stacktrace").buildAndFail().getOutput();
            String expected = "ext-not-installed".equals(backend)
                    ? "requested M3 portable DB provider is absent: " + backend
                    : "requested M3 portable DB runtime is unavailable: " + backend;
            assertTrue(refusal.contains(expected), refusal);
            assertFalse(Files.exists(root.resolve("invoked.txt")),
                    "missing " + backend + " runtime cannot execute an original language tool");
            assertFalse(Files.exists(root.resolve("result.txt")));
            assertFalse(Files.exists(root.resolve(".m3/module.m3db")),
                    "missing optional runtime must never silently select embedded M3IndexDB");
        }
    }
}
