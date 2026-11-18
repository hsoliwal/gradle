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
import java.util.Arrays;
import java.util.HexFormat;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Qualified JDK reuse and output restoration remain owned by the original Maven engine. */
final class M3PolyglotProviderReuseRealEngineTest {
    @TempDir
    Path root;

    @Test
    void reusesQualifiedCompilerRestoresOutputsAndInvalidatesSameMetadataSourceChange() throws Exception {
        String supplied = System.getenv("M3_PROVIDER_REUSE_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "source-built provider-reuse Maven JAR required");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "source-pinned Maven JAR must exist");

        Path cache = Files.createDirectory(root.resolve("shared-cache"));
        Path checkout = Files.createDirectory(root.resolve("checkout"));
        fixture(checkout, original, cache);

        String cold = run(checkout);
        assertTrue(cold.contains("CACHED compile"), cold);
        Path output = checkout.resolve("classes/Hello.class");
        byte[] compiled = Files.readAllBytes(output);
        assertTrue(compiled.length > 0);

        String warm = run(checkout);
        assertTrue(warm.contains("UP-TO-DATE compile"), warm);
        assertArrayEquals(compiled, Files.readAllBytes(output));

        Files.delete(output);
        String restored = run(checkout);
        assertTrue(restored.contains("FROM-CACHE compile"), restored);
        assertArrayEquals(compiled, Files.readAllBytes(output));

        Path source = checkout.resolve("src/Hello.java");
        FileTime timestamp = Files.getLastModifiedTime(source);
        long size = Files.size(source);
        Files.writeString(source, Files.readString(source).replace("AAAA", "BBBB"));
        assertEquals(size, Files.size(source));
        Files.setLastModifiedTime(source, timestamp);
        String changed = run(checkout);
        assertFalse(changed.contains("UP-TO-DATE compile"), changed);
        assertFalse(changed.contains("FROM-CACHE compile"), changed);
        assertTrue(changed.contains("CACHED compile"), changed);
        assertFalse(Arrays.equals(compiled, Files.readAllBytes(output)), "changed source must be recompiled");
    }

    private void fixture(Path checkout, Path engine, Path cache) throws Exception {
        Files.copy(engine, checkout.resolve("engine.jar"));
        String pin = hex(Files.readAllBytes(engine));
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        assertTrue(Files.isRegularFile(java), "the test runtime must be a complete supported JDK");
        Files.writeString(checkout.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\n");
        Files.writeString(checkout.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + pin + "')\n"
                + "  javaExecutable.set('" + groovyPath(java) + "')\n"
                + "  target.set('compile')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('file')\n"
                + "  providerReuse.set(true)\n"
                + "  localArtifactCacheDirectory.fileValue(file('" + groovyPath(cache) + "'))\n"
                + "}\n");
        Files.createDirectory(checkout.resolve("src"));
        Files.createDirectory(checkout.resolve("empty-classpath"));
        Files.createDirectory(checkout.resolve("classes"));
        Files.writeString(checkout.resolve("src/Hello.java"),
                "public final class Hello { public static String value() { return \"AAAA\"; } }\n");
        Files.writeString(checkout.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tcompile\t@m3:jdk:compiler:java\t.\n"
                + "arg\tcompile\t-proc:none\n"
                + "arg\tcompile\t-implicit:none\n"
                + "arg\tcompile\t-Xprefer:source\n"
                + "arg\tcompile\t-encoding\n"
                + "arg\tcompile\tUTF-8\n"
                + "arg\tcompile\t--release\n"
                + "arg\tcompile\t17\n"
                + "arg\tcompile\t-classpath\n"
                + "arg\tcompile\tempty-classpath\n"
                + "arg\tcompile\t-sourcepath\n"
                + "arg\tcompile\tsrc\n"
                + "arg\tcompile\t-d\n"
                + "arg\tcompile\tclasses\n"
                + "arg\tcompile\tsrc/Hello.java\n"
                + "input\tcompile\tsrc\n"
                + "input\tcompile\tempty-classpath\n"
                + "output\tcompile\tclasses\n", StandardCharsets.UTF_8);
    }

    private String run(Path checkout) {
        BuildResult result = GradleRunner.create().withProjectDir(checkout.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build();
        assertNotNull(result.task(":m3PolyglotRun"));
        assertEquals(TaskOutcome.SUCCESS, result.task(":m3PolyglotRun").getOutcome(),
                "Maven reuse must never turn the untracked bridge into a Gradle cache hit");
        return result.getOutput();
    }

    private static String groovyPath(Path path) {
        return path.toString().replace('\\', '/').replace("'", "\\'");
    }

    private static String hex(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
