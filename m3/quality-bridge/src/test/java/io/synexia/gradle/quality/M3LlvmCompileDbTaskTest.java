/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Direct Maven argv/receipt handoff without a local second compdb renderer. */
final class M3LlvmCompileDbTaskTest {
    @TempDir Path root;

    @Test
    void delegatesTwiceWithoutNativeExecutionOrTaskCaching() throws Exception {
        Path project = fixture();
        String first = execute(project, true);
        Path database = project.resolve("target/m3-llvm/compile_commands.json");
        assertTrue(first.contains("BUILD SUCCESSFUL"), first);
        assertTrue(Files.isRegularFile(database));
        assertEquals(List.of("llvm-compile-db", project.toString(),
                project.resolve("m3-build.tsv").toString(), "all"),
                Files.readAllLines(project.resolve("argv.txt")));
        String original = Files.readString(database);
        var timestamp = Files.getLastModifiedTime(database);
        assertTrue(execute(project, true).contains("BUILD SUCCESSFUL"));
        assertEquals(original, Files.readString(database));
        assertEquals(timestamp, Files.getLastModifiedTime(database));
        assertFalse(Files.exists(project.resolve("target/hello.o")));
        assertFalse(Files.exists(project.resolve(".m3")));
    }

    @Test
    void refusesWrongDigestUnknownTargetAndLinkedOutputBeforeStartingMaven() throws Exception {
        Path project = fixture();
        Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
                + "tasks.named('m3LlvmCompileDb') { engineSha256.set('" + "0".repeat(64) + "') }\n");
        assertTrue(execute(project, false).contains("SHA-256 mismatch"));
        assertFalse(Files.exists(project.resolve("argv.txt")));
        Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
                + "tasks.named('m3LlvmCompileDb') { engineSha256.set('" + sha(project.resolve("engine.jar"))
                + "'); target.set('../escape') }\n");
        assertTrue(execute(project, false).contains("invalid M3 LLVM compilation database target"));
        assertFalse(Files.exists(project.resolve("argv.txt")));
        Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
                + "tasks.named('m3LlvmCompileDb') { target.set('all') }\n");
        Path outputParent = Files.createDirectories(project.resolve("target/m3-llvm"));
        Path output = outputParent.resolve("compile_commands.json");
        Path external = Files.createDirectory(root.resolve("external compile db"));
        Path sentinel = external.resolve("compile_commands.json");
        byte[] original = "external-owner compilation database\n".getBytes(StandardCharsets.UTF_8);
        Files.write(sentinel, original);

        createRequiredSymbolicLink(output, sentinel);
        String linkedOutput = execute(project, false);
        assertTrue(linkedOutput.contains("symlinked M3 LLVM compilation database path"), linkedOutput);
        assertFalse(Files.exists(project.resolve("argv.txt")), linkedOutput);
        assertArrayEquals(original, Files.readAllBytes(sentinel));
        assertEquals(sentinel, Files.readSymbolicLink(output));

        Files.delete(output);
        Files.delete(outputParent);
        createRequiredSymbolicLink(outputParent, external);
        String linkedParent = execute(project, false);
        assertTrue(linkedParent.contains("symlinked M3 LLVM compilation database path"), linkedParent);
        assertFalse(Files.exists(project.resolve("argv.txt")), linkedParent);
        assertArrayEquals(original, Files.readAllBytes(sentinel));
        assertEquals(external, Files.readSymbolicLink(outputParent));
    }

    @Test
    void rejectsForeignReceiptAndUnpinnedProvider() throws Exception {
        Path project = fixture();
        Files.writeString(project.resolve("spoof.txt"), "1");
        assertTrue(execute(project, false).contains("foreign or malformed receipt"));
        Files.deleteIfExists(project.resolve("spoof.txt"));
        Files.writeString(project.resolve("external.jar"), "provider bytes");
        Files.writeString(project.resolve("build.gradle"), Files.readString(project.resolve("build.gradle"))
                + "tasks.named('m3LlvmCompileDb') { providerJars.from(layout.projectDirectory.file('external.jar')) }\n");
        assertTrue(execute(project, false).contains("exact SHA-256 pin"));
    }

    @Test
    void refusesEmptyAndTruncatedDatabaseEvenWithCanonicalMavenReceipt() throws Exception {
        Path project = fixture();
        Path db = project.resolve("target/m3-llvm/compile_commands.json");
        Files.writeString(project.resolve("empty.txt"), "1");
        String empty = execute(project, false);
        assertTrue(empty.contains("Maven LLVM compile DB output is empty, truncated or oversized"), empty);
        assertTrue(Files.exists(project.resolve("argv.txt")),
                "synthetic Maven producer actually ran and returned its canonical protocol");
        assertEquals(0, Files.size(db));

        Files.delete(project.resolve("empty.txt"));
        Files.delete(db);
        Files.writeString(project.resolve("truncated.txt"), "1");
        String truncated = execute(project, false);
        assertTrue(truncated.contains("Maven LLVM compile DB output is empty, truncated or oversized"),
                truncated);
        assertEquals(1, Files.size(db));
        assertFalse(Files.exists(project.resolve(".m3")),
                "failing IDE metadata admission does not create Gradle or Maven state");
    }

    /** Linux acceptance requires real symlinks; other hosts may lack link support or permission. */
    private static void createRequiredSymbolicLink(Path link, Path target) throws Exception {
        boolean linux = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("linux");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException unavailable) {
            if (linux) {
                throw unavailable;
            }
            assumeTrue(false, "symbolic links are unavailable on this host: " + unavailable);
        } catch (FileSystemException unavailable) {
            String reason = String.valueOf(unavailable.getReason()).toLowerCase(Locale.ROOT);
            if (linux || !(unavailable instanceof AccessDeniedException
                    || reason.contains("privilege") || reason.contains("not supported"))) {
                throw unavailable;
            }
            assumeTrue(false, "symbolic links are unavailable on this host: " + unavailable);
        }
    }

    private Path fixture() throws Exception {
        Path project = Files.createDirectory(root.resolve("native with spaces"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='native'\n");
        Files.writeString(project.resolve("m3-build.tsv"), "M3-POLYGLOT-TASKS-V1\n");
        Path source = project.resolve("src/io/synexia/maven/polyglot/PolyglotCli.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package io.synexia.maven.polyglot;
                public final class PolyglotCli {
                  public static void main(String[] args) throws Exception {
                    java.nio.file.Path root = java.nio.file.Path.of(args[1]);
                    java.nio.file.Files.writeString(root.resolve("argv.txt"),
                        String.join("\\n", args) + "\\n");
                    java.nio.file.Path output = root.resolve("target/m3-llvm/compile_commands.json");
                    java.nio.file.Files.createDirectories(output.getParent());
                    if (!java.nio.file.Files.exists(output)) {
                      java.nio.file.Files.writeString(output,
                          java.nio.file.Files.exists(root.resolve("empty.txt")) ? ""
                          : java.nio.file.Files.exists(root.resolve("truncated.txt")) ? "["
                          : "[]\\n");
                    }
                    System.out.println("M3-LLVM-COMPILE-DB-V1");
                    System.out.println("file\\t" + (java.nio.file.Files.exists(root.resolve("spoof.txt"))
                        ? root.resolve("foreign.json") : output));
                  }
                }
                """);
        Path classes = Files.createDirectories(project.resolve("classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "17", "-d", classes.toString(), source.toString()));
        Path jar = project.resolve("engine.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("io/synexia/maven/polyglot/PolyglotCli.class"));
            Files.copy(classes.resolve("io/synexia/maven/polyglot/PolyglotCli.class"), output);
            output.closeEntry();
        }
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3LlvmCompileDb') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + sha(jar) + "')\n"
                + "}\n");
        return project;
    }

    private static String execute(Path project, boolean success) {
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3LlvmCompileDb", "--stacktrace", "--rerun-tasks");
        return success ? runner.build().getOutput() : runner.buildAndFail().getOutput();
    }

    private static String sha(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }
}
