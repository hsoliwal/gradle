/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The original Maven #235/#236 engine owns Derby SQL, sealed images, leases,
 * recovery and all skip decisions. Gradle supplies only the explicitly
 * selected backend and separately SHA-256-pinned optional runtime JARs.
 */
final class M3DerbyRealEngineTest {
    @TempDir Path root;

    @Test
    void missingDerbyDriverRefusesBeforeExecutingToolOrFallingBack() throws Exception {
        String engineSha = prepareFixture();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, false, false));

        String failure = run(false);
        assertTrue(failure.contains("ext-derby-v1") && failure.contains("unavailable"), failure);
        assertFalse(Files.exists(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve("result.txt")));
        assertFalse(Files.exists(root.resolve(".m3/module.m3db")));
        assertFalse(Files.exists(root.resolve(".m3/module-state-ext-derby-v1")));
    }

    @Test
    void originalDerbyDriverReopensAndVerifiesSealedStateAcrossGradleInvocations() throws Exception {
        String engineSha = prepareFixture();
        List<Path> drivers = stageDrivers();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, true, false));

        run(true);
        assertEquals("1", Files.readString(root.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));
        assertTrue(Files.isRegularFile(root.resolve(
                ".m3/module-state-ext-derby-v1/service.properties"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(root.resolve(".m3/module.m3db")));
        assertTrue(drivers.size() >= 2);
        if ("1".equals(System.getenv("M3_REQUIRE_DERBY_REAL_ENGINE"))) {
            assertFalse(Files.exists(root.resolve("derby.log")),
                    "hosted lane must redirect Derby's process-global diagnostic stream");
        }

        String warm = run(true);
        assertTrue(warm.contains("UP-TO-DATE verify (M3DB input and output content verified)"), warm);
        assertEquals("1", Files.readString(root.resolve("calls.txt")));

        Files.writeString(root.resolve("result.txt"), "tampered");
        run(true);
        assertEquals("2", Files.readString(root.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(root.resolve("result.txt")));

        var oldTime = Files.getLastModifiedTime(root.resolve("source.txt"));
        Files.writeString(root.resolve("source.txt"), "BBBB");
        Files.setLastModifiedTime(root.resolve("source.txt"), oldTime);
        run(true);
        assertEquals("3", Files.readString(root.resolve("calls.txt")),
                "same-length, restored-mtime source change must invalidate Maven sealed receipt");
        assertEquals("BBBB", Files.readString(root.resolve("result.txt")));
    }

    @Test
    void wrongDriverShaIsRejectedByGradleBeforeStartingMaven() throws Exception {
        String engineSha = prepareFixture();
        stageDrivers();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, true, true));
        String failure = run(false);
        assertTrue(failure.contains("SHA-256 mismatch"), failure);
        assertFalse(Files.exists(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve(".m3/module-state-ext-derby-v1")));
    }

    @Test
    void symlinkedDerbyDatabaseDirectoryFailsClosedWithoutToolExecution() throws Exception {
        String engineSha = prepareFixture();
        stageDrivers();
        Files.writeString(root.resolve("build.gradle"), config(engineSha, true, false));
        Path state = Files.createDirectory(root.resolve(".m3"));
        Path outside = Files.createDirectory(root.resolve("outside"));
        try {
            Files.createSymbolicLink(state.resolve("module-state-ext-derby-v1"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            assumeTrue(false, "filesystem symlinks unsupported: " + unavailable);
            return;
        }
        String failure = run(false);
        assertTrue(failure.toLowerCase(java.util.Locale.ROOT).contains("symlink"), failure);
        assertFalse(Files.exists(root.resolve("calls.txt")));
        assertFalse(Files.exists(root.resolve("result.txt")));
    }

    private String prepareFixture() throws Exception {
        Path engineSource = requiredFile("M3_MAVEN_DERBY_ENGINE_JAR");
        Files.copy(engineSource, root.resolve("engine.jar"));
        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-derby-native'\n");
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
                    public static void main(String[] args) throws Exception {
                        Path calls = Path.of("calls.txt");
                        int count = Files.exists(calls) ? Integer.parseInt(Files.readString(calls)) + 1 : 1;
                        Files.writeString(calls, Integer.toString(count));
                        Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", root.toString(), source.toString()));
        return sha256(root.resolve("engine.jar"));
    }

    private List<Path> stageDrivers() throws Exception {
        String provided = System.getenv("M3_DERBY_DRIVER_DIRECTORY");
        if ("1".equals(System.getenv("M3_REQUIRE_DERBY_REAL_ENGINE"))) {
            assertTrue(provided != null && !provided.isBlank(),
                    "hosted source-qualified lane requires M3_DERBY_DRIVER_DIRECTORY");
        } else {
            assumeTrue(provided != null && !provided.isBlank(),
                    "source-qualified Derby 10.16 dependency directory is required");
        }
        Path dir = Path.of(provided).toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS));
        try (Stream<Path> entries = Files.list(dir)) {
            List<Path> originals = entries.filter(p -> p.getFileName().toString()
                            .matches("derby[a-z]*-10\\.16\\.1\\.1\\.jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            assertTrue(originals.size() >= 2 && originals.size() <= 8, "bounded Derby runtime closure");
            assertTrue(originals.stream().anyMatch(p -> p.getFileName().toString()
                    .equals("derby-10.16.1.1.jar")), "Apache Derby engine required");
            assertTrue(originals.stream().anyMatch(p -> p.getFileName().toString()
                    .equals("derbytools-10.16.1.1.jar")), "EmbeddedDriver compatibility JAR required");
            for (Path from : originals) {
                assertTrue(Files.isRegularFile(from, LinkOption.NOFOLLOW_LINKS)
                        && Files.size(from) > 0, "regular original Derby runtime JAR required");
                Files.copy(from, root.resolve(from.getFileName()));
            }
            return originals;
        }
    }

    private String config(String engineSha, boolean installed, boolean wrongPin) throws Exception {
        StringBuilder value = new StringBuilder("plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + engineSha + "')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('ext-derby-v1')\n"
                + "  target.set('verify')\n");
        if (installed) {
            try (Stream<Path> entries = Files.list(root)) {
                List<Path> jars = entries.filter(p -> p.getFileName().toString()
                        .matches("derby[a-z]*-10\\.16\\.1\\.1\\.jar"))
                        .sorted().toList();
                assertTrue(jars.size() >= 2);
                for (int i = 0; i < jars.size(); i++) {
                    Path jar = jars.get(i);
                    String pin = sha256(jar);
                    if (wrongPin && i == 0) pin = (pin.charAt(0) == '0' ? "1" : "0") + pin.substring(1);
                    String filename = jar.getFileName().toString();
                    value.append("  providerJars.from(layout.projectDirectory.file('")
                            .append(filename).append("'))\n")
                            .append("  providerJarSha256.put('").append(filename)
                            .append("', '").append(pin).append("')\n");
                }
            }
        }
        return value.append("}\n").toString();
    }

    private String run(boolean success) {
        GradleRunner runner = GradleRunner.create().withProjectDir(root.toFile())
                .withPluginClasspath().withArguments("m3PolyglotRun", "--stacktrace", "--rerun-tasks");
        return success ? runner.build().getOutput() : runner.buildAndFail().getOutput();
    }

    private static Path requiredFile(String name) throws Exception {
        String value = System.getenv(name);
        if ("1".equals(System.getenv("M3_REQUIRE_DERBY_REAL_ENGINE"))) {
            assertTrue(value != null && !value.isBlank(), "hosted job requires " + name);
        } else {
            assumeTrue(value != null && !value.isBlank(), "opt-in source-built Maven Derby engine required");
        }
        Path file = Path.of(value).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                && Files.size(file) > 0, "source-built Maven plugin JAR required");
        return file;
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
