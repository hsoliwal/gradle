/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * Consumer acceptance of Maven #274's original fused input observation.
 * Gradle neither owns the two cache keys nor admits native Gradle cache hits.
 */
final class M3FusedStateCacheRealEngineTest {
    @TempDir Path root;

    @Test
    void originalMavenFusedKeysPreservePortableRestoreLocalReuseAndContentInvalidation() throws Exception {
        Path cache = Files.createDirectory(root.resolve("portable-cache"));
        Path first = fixture("first-module", cache);
        String firstRun = run(first, true);
        assertTrue(hasLine(firstRun, "CACHED verify"), firstRun);
        assertEquals("1", Files.readString(first.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(first.resolve("result.txt")));
        assertTrue(Files.isRegularFile(first.resolve(".m3/module.m3db"), LinkOption.NOFOLLOW_LINKS));
        assertFalse(firstRun.contains("> Task :m3PolyglotRun UP-TO-DATE"),
                "Gradle itself must not skip the original Maven CLI");

        Path second = fixture("second-module", cache);
        String restored = run(second, true);
        assertTrue(hasLine(restored, "FROM-CACHE verify"), restored);
        assertFalse(Files.exists(second.resolve("calls.txt")),
                "restoration cannot invoke a second language tool");
        assertEquals("AAAA", Files.readString(second.resolve("result.txt")));
        assertTrue(Files.isRegularFile(second.resolve(".m3/module.m3db"), LinkOption.NOFOLLOW_LINKS));

        String warm = run(second, true);
        assertTrue(hasLine(warm, "UP-TO-DATE verify"), warm);
        assertFalse(Files.exists(second.resolve("calls.txt")),
                "verified local receipt must not execute the language tool");

        Files.writeString(second.resolve("result.txt"), "tampered");
        String tampered = run(second, true);
        assertTrue(hasLine(tampered, "FROM-CACHE verify"), tampered);
        assertEquals("AAAA", Files.readString(second.resolve("result.txt")),
                "changed declared output must be reverified and restored");
        assertFalse(Files.exists(second.resolve("calls.txt")));

        FileTime oldTime = Files.getLastModifiedTime(second.resolve("source.txt"));
        Files.writeString(second.resolve("source.txt"), "BBBB");
        Files.setLastModifiedTime(second.resolve("source.txt"), oldTime);
        String invalidated = run(second, true);
        assertTrue(hasLine(invalidated, "CACHED verify"), invalidated);
        assertEquals("BBBB", Files.readString(second.resolve("result.txt")),
                "identical size and restored mtime cannot reuse an older input hash");
        assertEquals("1", Files.readString(second.resolve("calls.txt")),
                "new bytes must run the original tool exactly once");
        assertFalse(invalidated.contains("> Task :m3PolyglotRun UP-TO-DATE"));
    }

    @Test
    void missingDeclaredInputMustNotAuthorizeStaleCacheRestoration() throws Exception {
        Path cache = Files.createDirectory(root.resolve("cache"));
        Path project = fixture("missing-input", cache);
        run(project, true);
        assertEquals("1", Files.readString(project.resolve("calls.txt")));
        Files.delete(project.resolve("source.txt"));
        String refused = run(project, false);
        assertTrue(refused.contains("input") || refused.contains("source.txt"), refused);
        assertEquals("1", Files.readString(project.resolve("calls.txt")),
                "missing file must refuse before any tool can rerun");
        assertFalse(hasLine(refused, "FROM-CACHE verify"), "missing input cannot restore a prior cache key");
    }

    @Test
    void wrongEngineShaAndUnsafeCachePathsRefuseBeforeRunningMaven() throws Exception {
        Path cache = Files.createDirectory(root.resolve("portable"));
        Path wrongDigest = fixture("wrong-digest", cache);
        Files.writeString(wrongDigest.resolve("build.gradle"),
                Files.readString(wrongDigest.resolve("build.gradle"))
                        + "tasks.named('m3PolyglotRun') { engineSha256.set('" + "0".repeat(64) + "') }\n");
        String refused = run(wrongDigest, false);
        assertTrue(refused.contains("SHA-256 mismatch"), refused);
        assertFalse(Files.exists(wrongDigest.resolve("calls.txt")));
        assertFalse(Files.exists(wrongDigest.resolve(".m3/module.m3db")));

        Path inside = fixture("inside-cache", cache);
        Path forbidden = Files.createDirectory(inside.resolve("internal-cache"));
        Files.writeString(inside.resolve("build.gradle"),
                Files.readString(inside.resolve("build.gradle"))
                        + "tasks.named('m3PolyglotRun') { localArtifactCacheDirectory.fileValue(file('"
                        + groovy(forbidden) + "')) }\n");
        refused = run(inside, false);
        assertTrue(refused.contains("cache must be an existing directory outside the workspace"), refused);
        assertFalse(Files.exists(inside.resolve("calls.txt")));

        Path parent = Files.createDirectory(root.resolve("canonical-cache-parent"));
        Files.createDirectory(parent.resolve("actual-cache"));
        Path alias = root.resolve("linked-parent");
        try {
            Files.createSymbolicLink(alias, parent);
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            throw new AssertionError("this Linux CI host must support symlink admission test", unavailable);
        }
        Path linked = fixture("linked-cache", cache);
        Files.writeString(linked.resolve("build.gradle"),
                Files.readString(linked.resolve("build.gradle"))
                        + "tasks.named('m3PolyglotRun') { localArtifactCacheDirectory.fileValue(file('"
                        + groovy(alias.resolve("actual-cache")) + "')) }\n");
        refused = run(linked, false);
        assertTrue(refused.contains("symlinked path component"), refused);
        assertFalse(Files.exists(linked.resolve("calls.txt")));
    }

    private Path fixture(String name, Path cache) throws Exception {
        String supplied = System.getenv("M3_FUSED_MAVEN_ENGINE_JAR");
        if ("1".equals(System.getenv("M3_REQUIRE_FUSED_REAL_ENGINE"))) {
            assertTrue(supplied != null && !supplied.isBlank(), "exact merged Maven #274 JAR required");
        } else {
            assumeTrue(supplied != null && !supplied.isBlank(),
                    "only the source-qualified original Maven engine may run this test");
        }
        Path original = Path.of(supplied).toAbsolutePath().normalize().toRealPath();
        assertTrue(Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS));
        Path project = Files.createDirectory(root.resolve(name));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-fused-cache'\n");
        Files.writeString(project.resolve("source.txt"), "AAAA");
        Files.writeString(project.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                        + "task\tverify\tjava\t.\n"
                        + "arg\tverify\t-cp\n"
                        + "arg\tverify\t.\n"
                        + "arg\tverify\tMarkerTool\n"
                        + "input\tverify\tsource.txt\n"
                        + "input\tverify\tMarkerTool.class\n"
                        + "output\tverify\tresult.txt\n");
        Path source = project.resolve("MarkerTool.java");
        Files.writeString(source, """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class MarkerTool {
                    public static void main(String[] unused) throws Exception {
                        Path calls = Path.of("calls.txt");
                        int count = Files.exists(calls)
                                ? Integer.parseInt(Files.readString(calls)) + 1 : 1;
                        Files.writeString(calls, Integer.toString(count));
                        Files.writeString(Path.of("result.txt"),
                                Files.readString(Path.of("source.txt")));
                    }
                }
                """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", project.toString(), source.toString()));
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                        + "tasks.named('m3PolyglotRun') {\n"
                        + "  engineJar.fileValue(file('" + groovy(original) + "'))\n"
                        + "  engineSha256.set('" + sha256(original) + "')\n"
                        + "  incremental.set(true)\n"
                        + "  stateBackend.set('embedded')\n"
                        + "  target.set('verify')\n"
                        + "  localArtifactCacheDirectory.fileValue(file('" + groovy(cache) + "'))\n"
                        + "}\n");
        return project;
    }

    private static String run(Path project, boolean success) {
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath().withArguments("m3PolyglotRun", "--stacktrace", "--rerun-tasks");
        return success ? runner.build().getOutput() : runner.buildAndFail().getOutput();
    }

    private static boolean hasLine(String output, String prefix) {
        return output.lines().anyMatch(line -> line.trim().startsWith(prefix));
    }

    private static String groovy(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/')
                .replace("'", "\\'");
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
