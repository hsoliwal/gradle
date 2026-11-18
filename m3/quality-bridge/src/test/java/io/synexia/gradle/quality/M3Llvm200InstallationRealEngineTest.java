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

/**
 * Original Maven #200's installed-LLVM provider, selected through an unchanged
 * Gradle manifest handoff. Maven retains driver/selector and cache ownership.
 */
final class M3Llvm200InstallationRealEngineTest {
    @TempDir Path root;

    @Test
    void sourcePinnedClangHomeAndExecutableDigestCompileAndReexecute() throws Exception {
        Path home = llvmHome();
        String expected = System.getenv("M3_LLVM200_CLANG_SHA256");
        assumeTrue(expected != null && !expected.isBlank(), "requires the host Clang binary SHA-256 receipt");
        assertEquals(expected, sha(home.resolve("bin/clang").toRealPath()),
                "the declared host compiler bytes must match before original Maven is launched");

        Path project = project("original Clang with spaces");
        manifest(project, "c", "--m3-llvm-home=" + home,
                "--m3-llvm-sha256=" + expected);

        String plan = execute(project, "m3PolyglotPlan");
        assertTrue(plan.contains("PLAN compile"), plan);
        assertFalse(Files.exists(project.resolve("target/hello.o")),
                "a Gradle plan may not execute the installed native compiler");

        String cold = execute(project, "m3PolyglotRun");
        assertTrue(cold.contains("TASK compile tool=@m3:llvm:compiler:c"), cold);
        assertTrue(Files.isRegularFile(project.resolve("target/hello.o"))
                && Files.size(project.resolve("target/hello.o")) > 0,
                "the source-pinned original Maven fixture must produce a native object file");
        assertTrue(Files.isRegularFile(project.resolve("target/hello.d")),
                "the original Clang compiler must emit its declared header dependency file");

        String repeated = execute(project, "m3PolyglotRun");
        assertTrue(repeated.contains("TASK compile tool=@m3:llvm:compiler:c"), repeated);
        assertFalse(repeated.contains("UP-TO-DATE compile"),
                "a selected binary digest alone is not a complete native toolchain cache key");
    }

    @Test
    void wrongDigestAndMissingHomeFailDuringPlanWithoutNativeExecution() throws Exception {
        Path home = llvmHome();
        Path project = project("invalid LLVM selection");
        manifest(project, "c", "--m3-llvm-home=" + home,
                "--m3-llvm-sha256=" + "0".repeat(64));
        String wrongDigest = executeAndFail(project, "m3PolyglotPlan");
        assertTrue(wrongDigest.contains("LLVM compiler SHA-256 mismatch"), wrongDigest);
        assertFalse(Files.exists(project.resolve("target/hello.o")));

        manifest(project, "c", "--m3-llvm-home=" + project.resolve("missing LLVM installation"));
        String missing = executeAndFail(project, "m3PolyglotPlan");
        assertTrue(missing.contains("LLVM driver not found under toolchain"), missing);
        assertFalse(Files.exists(project.resolve("target/hello.o")),
                "an explicitly missing LLVM installation must not fall back to PATH");
    }

    @Test
    void mixedHomeAndBinAndDuplicateDirectivesFailClosed() throws Exception {
        Path home = llvmHome();
        Path project = project("conflicting selectors");
        manifest(project, "c", "--m3-llvm-home=" + home,
                "--m3-llvm-bin=" + home.resolve("bin"));
        String conflict = executeAndFail(project, "m3PolyglotPlan");
        assertTrue(conflict.contains("LLVM home and bin selectors cannot both be set"), conflict);

        manifest(project, "c", "--m3-llvm-home=" + home, "--m3-llvm-home=" + home);
        String duplicate = executeAndFail(project, "m3PolyglotPlan");
        assertTrue(duplicate.contains("duplicate or empty --m3-llvm-home="), duplicate);
        assertFalse(Files.exists(project.resolve("target/hello.o")));
    }

    @Test
    void objectiveCppSpellingsRemainMavenOwnedAndPlanOnly() throws Exception {
        Path home = llvmHome();
        assertTrue(Files.isRegularFile(home.resolve("bin/clang++").toRealPath()),
                "the separately installed native Clang++ driver must exist");
        Path project = project("Objective C++ aliases");
        String inventory = execute(project, "m3PolyglotProviders");
        String report = Files.readString(project.resolve("build/reports/m3/polyglot-providers.tsv"));
        assertTrue(inventory.contains("BUILD SUCCESSFUL"), inventory);
        assertTrue(report.contains("provider\tllvm\tcompiler\t"), report);
        assertTrue(report.contains("objc++") && report.contains("objcpp"), report);
        for (String language : new String[] {"objc++", "objcpp"}) {
            manifest(project, language, "--m3-llvm-home=" + home);
            assertTrue(execute(project, "m3PolyglotPlan").contains("PLAN compile"), language);
            assertFalse(Files.exists(project.resolve("target/hello.o")),
                    "an Objective-C++ plan must never run an LLVM driver");
        }
    }

    private Path project(String name) throws Exception {
        String engineEnv = System.getenv("M3_POLYGLOT_LLVM200_JAR");
        String fixturesEnv = System.getenv("M3_MAVEN_LLVM200_FIXTURES");
        assumeTrue(engineEnv != null && !engineEnv.isBlank()
                && fixturesEnv != null && !fixturesEnv.isBlank(),
                "requires source-pinned merged Maven #200 JAR and original LLVM examples");
        Path source = Path.of(engineEnv).toAbsolutePath().normalize();
        Path fixtures = Path.of(fixturesEnv).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source)
                && Files.isRegularFile(fixtures.resolve("m3-build.tsv"))
                && Files.isRegularFile(fixtures.resolve("native/hello.c"))
                && Files.isRegularFile(fixtures.resolve("native/message.h")));

        Path project = Files.createDirectories(root.resolve(name));
        Files.createDirectories(project.resolve("vendor"));
        Files.createDirectories(project.resolve("native"));
        Files.createDirectories(project.resolve("target"));
        Files.copy(source, project.resolve("vendor/maven-polyglot.jar"));
        Files.copy(fixtures.resolve("m3-build.tsv"), project.resolve("m3-build.tsv"));
        Files.copy(fixtures.resolve("native/hello.c"), project.resolve("native/hello.c"));
        Files.copy(fixtures.resolve("native/message.h"), project.resolve("native/message.h"));
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-llvm200'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotPlan', 'm3PolyglotRun', 'm3PolyglotProviders'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))\n"
                + "    engineSha256.set('" + sha(source) + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3PolyglotPlan') { target.set('compile') }\n"
                + "tasks.named('m3PolyglotRun') { target.set('compile'); incremental.set(true) }\n");
        return project;
    }

    private static void manifest(Path project, String language, String... selectors) throws Exception {
        // The immutable original manifest is reinstated on each test selection.
        Path originals = Path.of(System.getenv("M3_MAVEN_LLVM200_FIXTURES"));
        String original = Files.readString(originals.resolve("m3-build.tsv"));
        String originalAnchor = "task\tcompile\t@m3:llvm:compiler:c\t.\n";
        assertTrue(original.contains(originalAnchor), "Maven #200 example schema unexpectedly changed");
        StringBuilder directives = new StringBuilder();
        for (String selector : selectors) directives.append("arg\tcompile\t").append(selector).append('\n');
        Files.writeString(project.resolve("m3-build.tsv"),
                original.replace(originalAnchor,
                        "task\tcompile\t@m3:llvm:compiler:" + language + "\t.\n" + directives),
                StandardCharsets.UTF_8);
    }

    private static Path llvmHome() throws Exception {
        String selected = System.getenv("M3_LLVM200_HOME");
        assumeTrue(selected != null && !selected.isBlank(), "requires an installed LLVM home");
        Path home = Path.of(selected).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(home.resolve("bin/clang").toRealPath()));
        return home;
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
