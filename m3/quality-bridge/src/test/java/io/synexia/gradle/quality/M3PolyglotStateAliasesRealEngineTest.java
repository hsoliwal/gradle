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

/** Integration against Maven #184+ itself, with native-runtime qualification kept explicit. */
final class M3PolyglotStateAliasesRealEngineTest {
    @TempDir Path root;

    @Test
    void embeddedAndLegacyReuseTheCanonicalMavenState() throws Exception {
        Path work = fixture(sourceJar(), "embedded");
        verifyAliasReuse(work, "embedded", "legacy", "");
    }

    @Test
    void nativeAndM3dbFailClosedWithoutAnInstalledRuntime() throws Exception {
        Path source = sourceJar();
        for (String backend : new String[] {"native", "m3db"}) {
            Path work = fixture(source, "missing-runtime-" + backend);
            select(work, backend, "");
            String failure = GradleRunner.create().withProjectDir(work.toFile()).withPluginClasspath()
                    .withArguments("m3PolyglotRun", "--stacktrace").buildAndFail().getOutput();
            assertTrue(failure.contains("M3DbModuleStore$BackendUnavailableException"), failure);
            assertTrue(failure.contains("the m3db backend needs jdk.m3.indexdb in the running JDK"
                    + " or MIndexDB installed on the polyglot plugin classpath"), failure);
            assertFalse(Files.exists(work.resolve("calls.txt")),
                    backend + " must refuse before starting the original language tool");
            assertFalse(Files.exists(work.resolve("result.txt")),
                    backend + " must not publish a tool output");
            for (String state : new String[] {".m3/module.m3db", ".m3/module-state-v1.bin",
                    ".m3/module.m3db.state", ".m3/maven-build-db", ".m3/polyglot-stamps"}) {
                assertFalse(Files.exists(work.resolve(state)),
                        backend + " must not fall back to or publish " + state);
            }
        }
    }

    @Test
    void nativeAndM3dbReuseStateWithAnExplicitHashPinnedRuntime() throws Exception {
        Path source = sourceJar();
        String supplied = System.getenv("M3_POLYGLOT_NATIVE_RUNTIME_JAR");
        String expected = System.getenv("M3_POLYGLOT_NATIVE_RUNTIME_SHA256");
        boolean hasJar = supplied != null && !supplied.isBlank();
        boolean hasPin = expected != null && !expected.isBlank();
        assumeTrue(hasJar || hasPin, "native positive NOT_RUN: requires an original installed runtime JAR and SHA-256");
        assertTrue(hasJar && hasPin, "native runtime JAR and reviewed SHA-256 must be supplied together");
        assertTrue(expected.matches("[0-9a-f]{64}"), "native runtime SHA-256 must be lowercase hexadecimal");
        Path runtime = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(runtime), "explicit native runtime JAR must exist");
        assertEquals(expected, sha256(runtime), "native runtime must match the separately supplied pin");

        Path work = fixture(source, "installed-native-runtime");
        Files.copy(runtime, work.resolve("mindexdb-runtime.jar"));
        verifyAliasReuse(work, "native", "m3db", expected);
    }

    private static Path sourceJar() {
        String supplied = System.getenv("M3_POLYGLOT_STATE_ALIAS_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires original Maven #184+ polyglot engine");
        Path source = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "source-pinned Maven engine must exist");
        return source;
    }

    private static void verifyAliasReuse(Path work, String backend, String alias, String runtimePin) throws Exception {
        select(work, backend, runtimePin);
        execute(work);
        assertEquals("1", Files.readString(work.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(work.resolve("result.txt")));
        if (backend.equals("embedded")) {
            assertTrue(Files.isRegularFile(work.resolve(".m3/module.m3db")),
                    "embedded persists the canonical Maven M3IndexDB archive");
            assertFalse(Files.exists(work.resolve(".m3/maven-build-db")));
        } else {
            assertTrue(Files.isDirectory(work.resolve(".m3/maven-build-db/refs")),
                    "native uses the separately installed original MIndexDB runtime");
            assertFalse(Files.exists(work.resolve(".m3/module.m3db")));
        }

        select(work, alias, runtimePin);
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

    private Path fixture(Path source, String name) throws Exception {
        Path work = root.resolve(name);
        Files.createDirectories(work);
        Files.copy(source, work.resolve("maven-engine.jar"));
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
        return work;
    }

    private static void select(Path work, String backend, String runtimePin) throws Exception {
        String provider = runtimePin.isEmpty() ? ""
                : "  providerJars.from(layout.projectDirectory.file('mindexdb-runtime.jar'))\n"
                  + "  providerJarSha256.put('mindexdb-runtime.jar', '" + runtimePin + "')\n";
        Files.writeString(work.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('maven-engine.jar'))\n"
                + "  engineSha256.set('" + sha256(work.resolve("maven-engine.jar")) + "')\n"
                + provider
                + "  incremental.set(true)\n"
                + "  stateBackend.set('" + backend + "')\n"
                + "  target.set('verify')\n"
                + "}\n");
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static String execute(Path work) {
        return GradleRunner.create().withProjectDir(work.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build().getOutput();
    }
}
