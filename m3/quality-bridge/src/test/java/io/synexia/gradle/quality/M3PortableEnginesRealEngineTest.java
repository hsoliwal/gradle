/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

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
 * An isolated test-only file provider proves the pinned external SPI works;
 * it is not a substitute for native driver durability or crash qualification.
 */
final class M3PortableEnginesRealEngineTest {
    @TempDir Path root;

    @Test
    void registeredRocksMapAndLmdbBackendsFailClosedWithoutOptionalDrivers() throws Exception {
        String digest = prepareOriginalEngineAndFixture();

        for (String backend : List.of(
                "ext-rocksdb-v1", "ext-mapdb-v1", "ext-lmdb-v1", "ext-not-installed")) {
            Files.writeString(root.resolve("build.gradle"), taskConfig(digest, backend));
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

    @Test
    void separatelyPinnedExternalProviderReopensSealedImageWithoutGradleCache() throws Exception {
        String digest = prepareOriginalEngineAndFixture();
        Path source = root.resolve("provider-source/fixture/PortableStore.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package fixture;
                import java.io.IOException;
                import java.nio.file.Files;
                import java.nio.file.Path;
                import java.nio.file.StandardCopyOption;
                import io.synexia.maven.polyglot.M3ModuleDbBackendProvider;
                public final class PortableStore implements M3ModuleDbBackendProvider {
                    public String id() { return "ext-test-fs-v1"; }
                    public byte[] read(Path root) throws IOException {
                        Path image = root.resolve(".m3/portable-fixture.bin");
                        return Files.exists(image) ? Files.readAllBytes(image) : null;
                    }
                    public void write(Path root, byte[] image) throws IOException {
                        Path store = root.resolve(".m3/portable-fixture.bin");
                        Files.createDirectories(store.getParent());
                        Path staging = Files.createTempFile(store.getParent(), "portable-fixture-", ".tmp");
                        try {
                            Files.write(staging, image);
                            Files.move(staging, store, StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } finally {
                            Files.deleteIfExists(staging);
                        }
                    }
                }
                """);
        Path classes = Files.createDirectory(root.resolve("provider-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-cp", root.resolve("engine.jar").toString(),
                "-d", classes.toString(), source.toString()));
        Path provider = root.resolve("provider.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(provider))) {
            jar.putNextEntry(new JarEntry("fixture/PortableStore.class"));
            jar.write(Files.readAllBytes(classes.resolve("fixture/PortableStore.class")));
            jar.closeEntry();
            jar.putNextEntry(new JarEntry(
                    "META-INF/services/io.synexia.maven.polyglot.M3ModuleDbBackendProvider"));
            jar.write("fixture.PortableStore\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        String pin = sha256(provider);
        Files.writeString(root.resolve("build.gradle"), taskConfig(digest, "ext-test-fs-v1")
                + "tasks.named('m3PolyglotRun') {\n"
                + "  providerJars.from(layout.projectDirectory.file('provider.jar'))\n"
                + "  providerJarSha256.put('provider.jar', '" + pin + "')\n"
                + "}\n");

        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build();
        assertTrue(Files.exists(root.resolve("result.txt")), "original tool must run on cold state");
        assertTrue(Files.isRegularFile(root.resolve(".m3/portable-fixture.bin")),
                "external provider must own the original Maven sealed state image");
        assertFalse(Files.exists(root.resolve(".m3/module.m3db")));

        Files.delete(root.resolve("invoked.txt"));
        String replay = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").build().getOutput();
        assertTrue(replay.contains("UP-TO-DATE verify (M3DB input and output content verified)"), replay);
        assertFalse(Files.exists(root.resolve("invoked.txt")),
                "the original Maven backend must verify evidence before skipping execution");

        Files.write(provider, new byte[] { 0 }, StandardOpenOption.APPEND);
        String rejected = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotRun", "--stacktrace").buildAndFail().getOutput();
        assertTrue(rejected.contains("SHA-256 mismatch"), rejected);
        assertFalse(Files.exists(root.resolve("invoked.txt")),
                "changed provider bytes must fail Gradle's pin check before starting Maven");
    }

    private String prepareOriginalEngineAndFixture() throws Exception {
        String supplied = System.getenv("M3_PORTABLE_ENGINES_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires exact Maven #206 engine JAR");
        Path original = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original), "source-built Maven #206 JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(original, engine);
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
        return sha256(engine);
    }

    private static String taskConfig(String engineDigest, String backend) {
        return "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotRun') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + engineDigest + "')\n"
                + "  target.set('verify')\n"
                + "  incremental.set(true)\n"
                + "  stateBackend.set('" + backend + "')\n"
                + "}\n";
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }
}
