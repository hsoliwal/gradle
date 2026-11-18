/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

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

/**
 * Original merged Maven #227 owns HSQLDB JDBC, generation seals and task reuse.
 * Gradle admits only the explicit backend and separately SHA-pinned driver.
 */
final class M3HsqlDbRealEngineTest {
    @TempDir Path root;

    @Test
    void missingHsqlDbDriverFailsBeforeToolExecutionWithoutFallingBackToEmbedded() throws Exception {
        String engineSha = prepareFixture();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, null));

        String failure = run(false);
        assertTrue(failure.contains("explicit hsqldb JDBC driver is unavailable on the plugin classpath"),
                failure);
        assertFalse(Files.exists(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve("result.txt")));
        assertFalse(Files.exists(root.resolve(".m3/module.m3db")),
                "missing HSQLDB driver must not select embedded M3IndexDB");
        assertFalse(Files.exists(root.resolve(".m3/module-state-hsqldb.properties")),
                "missing HSQLDB driver must not create a local JDBC database");
    }

    @Test
    void originalHsqlDbDriverReopensAndReusesContentVerifiedSealedState() throws Exception {
        String engineSha = prepareFixture();
        String driverSha = stageDriver();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, driverSha));

        run(true);
        assertEquals("1", Files.readString(root.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));
        assertTrue(Files.isRegularFile(root.resolve(".m3/module-state-hsqldb.properties")),
                "original HSQLDB file backend must persist the Maven-owned state");
        assertFalse(Files.exists(root.resolve(".m3/module.m3db")));

        String replay = run(true);
        assertTrue(replay.contains("UP-TO-DATE verify (M3DB input and output content verified)"), replay);
        assertEquals("1", Files.readString(root.resolve("calls.txt")),
                "new Gradle JVM must not rerun unchanged Maven task");

        Files.writeString(root.resolve("result.txt"), "tampered");
        run(true);
        assertEquals("2", Files.readString(root.resolve("calls.txt")),
                "tampered output invalidates original Maven sealed receipt");
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));

        Files.writeString(root.resolve("source.txt"), "BBBB");
        run(true);
        assertEquals("3", Files.readString(root.resolve("calls.txt")),
                "changed declared input must re-execute through original Maven engine");
        assertEquals("BBBB", Files.readString(root.resolve("result.txt")));
    }

    @Test
    void changedDriverShaIsRejectedByGradleBeforeStartingMaven() throws Exception {
        String engineSha = prepareFixture();
        String driverSha = stageDriver();
        String incorrectSha = (driverSha.charAt(0) == '0' ? "1" : "0") + driverSha.substring(1);
        Files.writeString(root.resolve("build.gradle"), config(engineSha, incorrectSha));

        assertTrue(run(false).contains("SHA-256 mismatch"));
        assertFalse(Files.exists(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve(".m3/module-state-hsqldb.properties")));
    }

    private String prepareFixture() throws Exception {
        String supplied = requiredSource("M3_POLYGLOT_HSQLDB_JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "original Maven #227 engine JAR must exist");
        Files.copy(original, root.resolve("engine.jar"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-hsqldb-native'\n");
        Files.writeString(root.resolve("source.txt"), "AAAA");
        Files.writeString(root.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tverify\tjava\t.\n"
                + "arg\tverify\t-cp\n"
                + "arg\tverify\t.\n"
                + "arg\tverify\tMarkerTool\n"
                + "input\tverify\tsource.txt\n"
                + "output\tverify\tresult.txt\n");
        Path source = root.resolve("MarkerTool.java");
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class MarkerTool {
                    public static void main(String[] ignored) throws Exception {
                        Path calls = Path.of("calls.txt");
                        int next = Files.exists(calls) ? Integer.parseInt(Files.readString(calls)) + 1 : 1;
                        Files.writeString(calls, Integer.toString(next));
                        Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", root.toString(), source.toString()));
        return sha256(root.resolve("engine.jar"));
    }

    private String stageDriver() throws Exception {
        String supplied = requiredSource("M3_HSQLDB_DRIVER_JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "original HSQLDB 2.7.4 driver JAR must exist");
        Files.copy(original, root.resolve("hsqldb-driver.jar"));
        return sha256(root.resolve("hsqldb-driver.jar"));
    }

    private static String requiredSource(String name) {
        String supplied = System.getenv(name);
        if ("1".equals(System.getenv("M3_REQUIRE_HSQLDB_REAL_ENGINE"))) {
            assertTrue(supplied != null && !supplied.isBlank(), "hosted source-qualified job requires " + name);
        } else {
            assumeTrue(supplied != null && !supplied.isBlank(), "opt-in original Maven #227 HSQLDB integration");
        }
        return supplied;
    }

    private static String config(String engineSha, String driverSha) {
        return "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + engineSha + "')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('hsqldb')\n"
                + "  target.set('verify')\n"
                + (driverSha == null ? "" :
                        "  providerJars.from(layout.projectDirectory.file('hsqldb-driver.jar'))\n"
                        + "  providerJarSha256.put('hsqldb-driver.jar', '" + driverSha + "')\n")
                + "}\n";
    }

    private String run(boolean successful) {
        GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile())
                .withPluginClasspath().withArguments("m3PolyglotRun", "--stacktrace");
        return successful ? runner.build().getOutput() : runner.buildAndFail().getOutput();
    }

    private static String sha256(Path source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source)));
    }
}
