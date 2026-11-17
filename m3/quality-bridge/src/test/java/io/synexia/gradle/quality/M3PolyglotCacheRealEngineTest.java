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

/** Cross-checkout restoration by the original merged Maven #149 engine, never a fake cache. */
final class M3PolyglotCacheRealEngineTest {
    @TempDir
    Path root;

    @Test
    void restoresInFreshCheckoutAndRejectsChangedInputAndCorruptedBlob() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "reviewed Maven #149 JAR required");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "source-pinned Maven JAR must exist");

        Path cache = Files.createDirectory(root.resolve("shared-cache"));
        Path first = Files.createDirectory(root.resolve("checkout-a"));
        Path second = Files.createDirectory(root.resolve("checkout-b"));
        fixture(first, original, cache);
        fixture(second, original, cache);

        String cold = run(first);
        assertTrue(cold.contains("CACHED compile"), cold);
        assertEquals("1", Files.readString(first.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(first.resolve("result.txt")));

        String warm = run(second);
        assertTrue(warm.contains("FROM-CACHE compile"), warm);
        assertFalse(Files.exists(second.resolve("calls.txt")), "a hit must not execute the original tool");
        assertEquals("AAAA", Files.readString(second.resolve("result.txt")));

        Files.writeString(second.resolve("source.txt"), "BBBB");
        String changed = run(second);
        assertFalse(changed.contains("FROM-CACHE compile"), changed);
        assertEquals("1", Files.readString(second.resolve("calls.txt")));
        assertEquals("BBBB", Files.readString(second.resolve("result.txt")));

        Files.writeString(second.resolve("source.txt"), "AAAA");
        String digest = hex(Files.readAllBytes(first.resolve("result.txt")));
        Path blob = cache.resolve("blobs").resolve(digest.substring(0, 2)).resolve(digest);
        assertTrue(Files.isRegularFile(blob));
        Files.writeString(blob, "tampered");
        String repaired = run(second);
        assertFalse(repaired.contains("FROM-CACHE compile"), repaired);
        assertEquals("2", Files.readString(second.resolve("calls.txt")));
        assertEquals("AAAA", Files.readString(second.resolve("result.txt")));
    }

    private void fixture(Path checkout, Path engine, Path cache) throws Exception {
        Files.copy(engine, checkout.resolve("engine.jar"));
        String pin = hex(Files.readAllBytes(engine));
        Files.writeString(checkout.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\n");
        Files.writeString(checkout.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + pin + "')\n"
                + "  target.set('compile')\n"
                + "  localArtifactCacheDirectory.fileValue(file('"
                + cache.toString().replace('\\', '/') + "'))\n"
                + "}\n");
        Files.writeString(checkout.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tcompile\tjava\t.\n"
                + "arg\tcompile\t-cp\n"
                + "arg\tcompile\t.\n"
                + "arg\tcompile\tManifestTool\n"
                + "input\tcompile\tsource.txt\n"
                + "output\tcompile\tresult.txt\n", StandardCharsets.UTF_8);
        Files.writeString(checkout.resolve("source.txt"), "AAAA");
        Files.writeString(checkout.resolve("ManifestTool.java"), """
                import java.nio.file.Files;
                import java.nio.file.Path;
                public final class ManifestTool {
                    public static void main(String[] ignored) throws Exception {
                        Path counter = Path.of("calls.txt");
                        int calls = Files.exists(counter) ? Integer.parseInt(Files.readString(counter)) + 1 : 1;
                        Files.writeString(counter, Integer.toString(calls));
                        Files.writeString(Path.of("result.txt"), Files.readString(Path.of("source.txt")));
                    }
                }
                """);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", checkout.toString(), checkout.resolve("ManifestTool.java").toString());
        assertEquals(0, result);
    }

    private String run(Path checkout) {
        return GradleRunner.create().withProjectDir(checkout.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build().getOutput();
    }

    private static String hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
