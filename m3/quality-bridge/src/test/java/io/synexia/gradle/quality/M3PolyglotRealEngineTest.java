/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration contract against Maven's actual pinned polyglot JAR, not a fake CLI.
 * Requires M3_POLYGLOT_JAR; ordinary offline unit tests skip this external fixture.
 */
final class M3PolyglotRealEngineTest {
    @TempDir
    Path root;

    @Test
    void mavenModuleStateSurvivesSeparateGradleInvocationsAndRefusesCorruption() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires reviewed Maven engine JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "explicit Maven engine JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
        String sha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"real-polyglot\"\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + sha256 + "')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('file')\n"
                + "  target.set('verify')\n"
                + "}\n");
        Files.writeString(root.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tverify\tjava\t.\n"
                + "arg\tverify\t-cp\n"
                + "arg\tverify\t.\n"
                + "arg\tverify\tManifestTool\n"
                + "input\tverify\tsource.txt\n"
                + "output\tverify\tresult.txt\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("source.txt"), "AAAA", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ManifestTool.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class ManifestTool {
                    public static void main(String[] args) throws Exception {
                        Path source = Path.of("source.txt");
                        Path calls = Path.of("calls.txt");
                        int next = Files.exists(calls) ? Integer.parseInt(Files.readString(calls)) + 1 : 1;
                        Files.writeString(calls, Integer.toString(next));
                        Files.writeString(Path.of("result.txt"), Files.readString(source));
                    }
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", root.toString(), root.resolve("ManifestTool.java").toString()));

        execute();
        Path state = root.resolve(".m3/module-state-v1.bin");
        assertTrue(Files.isRegularFile(state), "Maven #143 must persist module state");
        assertEquals("1", Files.readString(root.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));

        String second = execute();
        assertTrue(second.contains("UP-TO-DATE verify"), "second invocation must reuse validated state");
        assertEquals("1", Files.readString(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve(".m3/polyglot-stamps/verify.sha256")));

        Files.writeString(root.resolve("result.txt"), "tampered");
        execute();
        assertEquals("2", Files.readString(root.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));

        Path source = root.resolve("source.txt");
        FileTime oldMtime = Files.getLastModifiedTime(source);
        Files.writeString(source, "BBBB");
        Files.setLastModifiedTime(source, oldMtime);
        execute();
        assertEquals("3", Files.readString(root.resolve("calls.txt")));
        assertEquals("BBBB", Files.readString(root.resolve("result.txt")));

        Files.write(state, new byte[] {0, 1, 2, 3, 4});
        execute();
        assertEquals("4", Files.readString(root.resolve("calls.txt")),
                "corrupt module state must not authorize a skip");
    }

    private String execute() {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build().getOutput();
    }
}
