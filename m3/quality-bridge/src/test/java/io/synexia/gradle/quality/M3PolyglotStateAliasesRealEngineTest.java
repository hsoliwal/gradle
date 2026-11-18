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

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Integration against Maven #184+ itself, not an emulated module database. */
final class M3PolyglotStateAliasesRealEngineTest {
    @TempDir Path root;

    @Test
    void embeddedAndNativeNamesShareTheCanonicalMavenAliasState() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_STATE_ALIAS_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires original Maven #184+ polyglot engine");
        Path sourceJar = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(sourceJar), "source-pinned Maven engine must exist");

        for (String backend : new String[] {"embedded", "native"}) {
            String alias = backend.equals("embedded") ? "legacy" : "m3db";
            Path work = root.resolve(backend);
            Files.createDirectories(work);
            Path jar = work.resolve("maven-engine.jar");
            Files.copy(sourceJar, jar);
            String sha = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
            fixture(work);

            select(work, sha, backend);
            execute(work);
            assertEquals("1", Files.readString(work.resolve("calls.txt")));
            assertEquals("AAAA", Files.readString(work.resolve("result.txt")));
            if (backend.equals("embedded")) {
                assertTrue(Files.isRegularFile(work.resolve(".m3/module.m3db")),
                        "embedded persists the canonical Maven M3IndexDB archive");
                assertFalse(Files.exists(work.resolve(".m3/maven-build-db")));
            } else {
                assertTrue(Files.isDirectory(work.resolve(".m3/maven-build-db/refs")),
                        "native uses Maven's original directory-backed M3IndexDB");
                assertFalse(Files.exists(work.resolve(".m3/module.m3db")));
            }

            select(work, sha, alias);
            assertTrue(execute(work).contains("UP-TO-DATE verify"),
                    "Maven " + backend + "/" + alias + " must reuse the same validated generation");
            assertEquals("1", Files.readString(work.resolve("calls.txt")),
                    "an alias may not cause duplicate native language work");

            Files.writeString(work.resolve("result.txt"), "tampered", StandardCharsets.UTF_8);
            execute(work);
            assertEquals("2", Files.readString(work.resolve("calls.txt")),
                    "Maven must re-execute on altered output even after alias reuse");
            assertEquals("AAAA", Files.readString(work.resolve("result.txt")));
        }
    }

    private static void fixture(Path work) throws Exception {
        Files.writeString(work.resolve("settings.gradle"), "rootProject.name = 'm3-state-aliases'\n");
        Files.writeString(work.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tverify\tjava\t.\n"
                + "arg\tverify\t-cp\n"
                + "arg\tverify\t.\n"
                + "arg\tverify\tManifestTool\n"
                + "input\tverify\tsource.txt\n"
                + "output\tverify\tresult.txt\n", StandardCharsets.UTF_8);
        Files.writeString(work.resolve("source.txt"), "AAAA", StandardCharsets.UTF_8);
        Files.writeString(work.resolve("ManifestTool.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class ManifestTool {
                    public static void main(String[] args) throws Exception {
                        Path calls = Path.of("calls.txt");
                        int next = Files.exists(calls) ? Integer.parseInt(Files.readString(calls)) + 1 : 1;
                        Files.writeString(calls, Integer.toString(next));
                        Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
                    }
                }
                """, StandardCharsets.UTF_8);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", work.toString(), work.resolve("ManifestTool.java").toString()));
    }

    private static void select(Path work, String sha, String backend) throws Exception {
        Files.writeString(work.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('maven-engine.jar'))\n"
                + "  engineSha256.set('" + sha + "')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('" + backend + "')\n"
                + "  target.set('verify')\n"
                + "}\n");
    }

    private static String execute(Path work) {
        return GradleRunner.create().withProjectDir(work.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build().getOutput();
    }
}
