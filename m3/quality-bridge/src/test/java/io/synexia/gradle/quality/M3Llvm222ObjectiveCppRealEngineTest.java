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

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Merged Maven #222 keeps both Objective-C++ driver names and byte pins source-owned. */
final class M3Llvm222ObjectiveCppRealEngineTest {
    @TempDir Path root;

    @Test
    void distinctSyntheticDriverBytesProveBothAliasesPinClangxxNotClang() throws Exception {
        // Plan-only fixture: these are deliberately distinct non-executable
        // proof bytes, never represented as installed native compilers.
        Path home = root.resolve("synthetic LLVM installation with spaces");
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path clang = Files.writeString(bin.resolve("clang"),
                "not a compiler: original clang proof bytes\n", StandardCharsets.UTF_8);
        Path clangxx = Files.writeString(bin.resolve("clang++"),
                "not a compiler: distinct clang++ proof bytes\n", StandardCharsets.UTF_8);
        String cDigest = sha(clang);
        String cppDigest = sha(clangxx);
        assertFalse(cDigest.equals(cppDigest), "synthetic drivers must not share a digest");

        Path project = project("original LLVM selector fixture");
        for (String language : new String[] {"objc++", "objcpp"}) {
            String output = "target/probe-" + (language.equals("objc++") ? "plus" : "legacy") + ".o";
            manifest(project, language, home, cppDigest, output);
            String selected = execute(project, "m3PolyglotPlan");
            assertTrue(selected.contains("PLAN probe"), selected);
            assertFalse(Files.exists(project.resolve(output)),
                    "plan must not execute synthetic driver bytes");

            manifest(project, language, home, cDigest, output);
            String rejected = executeAndFail(project, "m3PolyglotPlan");
            assertTrue(rejected.contains("LLVM compiler SHA-256 mismatch"), rejected);
            assertFalse(Files.exists(project.resolve(output)),
                    "the digest of clang must not authorize clang++ for either spelling");
        }
    }

    @Test
    void actualHostClangxxCompilesBothObjectiveCppSpellingsWithoutLinkerOrRuntime() throws Exception {
        String selected = System.getenv("M3_LLVM222_HOME");
        String declared = System.getenv("M3_LLVM222_CLANGXX_SHA256");
        assumeTrue(selected != null && !selected.isBlank()
                && declared != null && !declared.isBlank(),
                "requires separately installed host Clang++ and its executable-byte digest");
        Path home = Path.of(selected).toAbsolutePath().normalize();
        Path clangxx = home.resolve("bin/clang++");
        assertTrue(Files.isRegularFile(clangxx.toRealPath()) && Files.isExecutable(clangxx));
        assertEquals(declared, sha(clangxx.toRealPath()),
                "original Maven must receive the selected installed Clang++ digest");

        Path project = project("real ObjC++ source with spaces");
        for (String language : new String[] {"objc++", "objcpp"}) {
            String output = "target/real-" + (language.equals("objc++") ? "plus" : "legacy") + ".o";
            manifest(project, language, home, declared, output);
            String plan = execute(project, "m3PolyglotPlan");
            assertTrue(plan.contains("PLAN probe"), plan);
            assertFalse(Files.exists(project.resolve(output)),
                    "native compiler execution requires explicit Gradle run");

            String executed = execute(project, "m3PolyglotRun");
            assertTrue(executed.contains("TASK probe tool=@m3:llvm:compiler:" + language), executed);
            assertTrue(Files.isRegularFile(project.resolve(output)) && Files.size(project.resolve(output)) > 0,
                    "original Maven's native ObjC++ compiler must produce an object for " + language);
            assertFalse(executed.contains("UP-TO-DATE probe"),
                    "a compiler executable digest is not a complete native cache key");
        }
    }

    private Path project(String name) throws Exception {
        String source = System.getenv("M3_POLYGLOT_LLVM222_JAR");
        assumeTrue(source != null && !source.isBlank(),
                "requires the source-pinned original Maven #222 polyglot engine JAR");
        Path original = Path.of(source).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(original));

        Path project = Files.createDirectories(root.resolve(name));
        Path vendor = Files.createDirectories(project.resolve("vendor"));
        Files.createDirectories(project.resolve("src"));
        Files.createDirectories(project.resolve("target"));
        Files.copy(original, vendor.resolve("maven-polyglot.jar"));
        Files.writeString(project.resolve("src/probe.mm"),
                "template <class T> T twice(T v) { return v + v; }\n"
                + "extern \"C\" int m3_objcxx_probe() { return twice(7); }\n",
                StandardCharsets.UTF_8);
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-objcxx-222'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotPlan', 'm3PolyglotRun'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))\n"
                + "    engineSha256.set('" + sha(original) + "')\n"
                + "    target.set('probe')\n"
                + "    incremental.set(name == 'm3PolyglotRun')\n"
                + "  }\n"
                + "}\n");
        return project;
    }

    private static void manifest(Path project, String language, Path home,
            String digest, String output) throws Exception {
        Files.writeString(project.resolve("m3-build.tsv"),
                "M3-POLYGLOT-TASKS-V1\n"
                + "task\tprobe\t@m3:llvm:compiler:" + language + "\t.\n"
                + "arg\tprobe\t--m3-llvm-home=" + home + "\n"
                + "arg\tprobe\t--m3-llvm-sha256=" + digest + "\n"
                + "arg\tprobe\t-std=c++17\n"
                + "arg\tprobe\t-x\n"
                + "arg\tprobe\tobjective-c++\n"
                + "arg\tprobe\t-c\n"
                + "arg\tprobe\tsrc/probe.mm\n"
                + "arg\tprobe\t-o\n"
                + "arg\tprobe\t" + output + "\n"
                + "input\tprobe\tsrc/probe.mm\n"
                + "output\tprobe\t" + output + "\n", StandardCharsets.UTF_8);
    }

    private static String execute(Path project, String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build().getOutput();
    }

    private static String executeAndFail(Path project, String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").buildAndFail().getOutput();
    }

    private static String sha(Path source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source)));
    }
}
