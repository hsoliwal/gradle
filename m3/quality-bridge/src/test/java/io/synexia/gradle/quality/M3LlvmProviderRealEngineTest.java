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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Original Maven #198 LLVM provider exercised only through Gradle's pinned CLI boundary. */
final class M3LlvmProviderRealEngineTest {
    @TempDir Path root;

    @Test
    void sourcePinnedMavenInventoryAndPlanNeverStartAnUninstalledClang() throws Exception {
        Path project = root.resolve("plan with spaces");
        String engineSha = installOriginalMavenEngine(project);
        Path fixtures = sourceFixtures();
        consumer(project, engineSha, fixtures);
        manifest(project, fixtures, "--m3-llvm-bin=not-installed-llvm/bin");

        String inventory = execute(project, "m3PolyglotProviders");
        assertTrue(inventory.contains("BUILD SUCCESSFUL"), inventory);
        String providers = Files.readString(project.resolve("build/reports/m3/polyglot-providers.tsv"));
        assertTrue(providers.contains("provider\tllvm\tcompiler\tc,cpp,fortran,llvm-ir,objc,objcpp\n"),
                "real Maven provider inventory must expose LLVM's original driver families");

        String plan = execute(project, "m3PolyglotPlan");
        assertTrue(plan.contains("PLAN compile"), plan);
        assertTrue(plan.contains("PLAN codegen"), "original Maven's two-stage LLVM plan is preserved");
        assertFalse(Files.exists(project.resolve("hello.ll")) || Files.exists(project.resolve("hello.s")),
                "Gradle planning must not invoke the absent original LLVM compiler");

        manifest(project, fixtures, "--m3-llvm-bin=first/bin", "--m3-llvm-bin=second/bin");
        String rejected = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotPlan", "--stacktrace").buildAndFail().getOutput();
        assertTrue(rejected.contains("LLVM bin directory must be specified only once"), rejected);
        assertFalse(Files.exists(project.resolve("hello.ll")), "invalid planning must have no compiler output");
    }

    @Test
    void realOriginalClangRunsTwiceWithoutPretendingToHaveAnIncrementalCacheKey() throws Exception {
        String selected = System.getenv("M3_LLVM_CLANG");
        String expected = System.getenv("M3_LLVM_CLANG_SHA256");
        assumeTrue(selected != null && !selected.isBlank() && expected != null && !expected.isBlank(),
                "requires an explicitly selected original LLVM Clang executable and byte digest");
        Path compiler = Path.of(selected).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(compiler) && Files.isExecutable(compiler),
                "real original compiler must be installed by the host");
        assertEquals(expected, sha(Files.readAllBytes(compiler)),
                "LLVM executable bytes must be pinned for this integration run");

        Path project = root.resolve("real clang with spaces");
        String engineSha = installOriginalMavenEngine(project);
        Path fixtures = sourceFixtures();
        consumer(project, engineSha, fixtures);
        manifest(project, fixtures, "--m3-llvm-bin=" + compiler.getParent());

        String first = execute(project, "m3PolyglotRun");
        assertTrue(first.contains("TASK compile tool=@m3:llvm:compiler:c"), first);
        Path ir = project.resolve("hello.ll");
        assertTrue(Files.isRegularFile(ir) && Files.size(ir) > 0
                        && Files.readString(ir).contains("m3_llvm_answer"),
                "the original Maven hello.c must compile into real host Clang LLVM IR");
        assertFalse(Files.exists(project.resolve("hello.s")), "the unrequested llc stage must not run");

        // Maven intentionally excludes external LLVM compilers from M3DB
        // cache admission until headers, sysroots and toolchain inputs are proven.
        String second = execute(project, "m3PolyglotRun");
        assertTrue(second.contains("TASK compile tool=@m3:llvm:compiler:c"), second);
        assertFalse(second.contains("UP-TO-DATE compile"),
                "a Gradle invocation may not manufacture a native compiler cache hit");
    }

    private static void consumer(Path project, String sha, Path fixtures) throws Exception {
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-llvm-original'\n");
        Files.writeString(project.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotPlan', 'm3PolyglotRun'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))\n"
                + "    engineSha256.set('" + sha + "')\n"
                + "    target.set(name == 'm3PolyglotPlan' ? 'codegen' : 'compile')\n"
                + "    incremental.set(name == 'm3PolyglotRun')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  engineJar.set(layout.projectDirectory.file('vendor/maven-polyglot.jar'))\n"
                + "  engineSha256.set('" + sha + "')\n"
                + "}\n");
        Files.copy(fixtures.resolve("hello.c"), project.resolve("hello.c"));
    }

    private static void manifest(Path project, Path fixtures, String... selectors) throws Exception {
        String original = Files.readString(fixtures.resolve("m3-build.tsv"));
        String anchor = "task\tcompile\t@m3:llvm:compiler:c\t.\n";
        assertTrue(original.contains(anchor), "source-pinned Maven manifest must have the expected compiler owner");
        StringBuilder selectorLines = new StringBuilder();
        for (String selector : selectors) selectorLines.append("arg\tcompile\t").append(selector).append('\n');
        Files.writeString(project.resolve("m3-build.tsv"),
                original.replace(anchor, anchor + selectorLines), StandardCharsets.UTF_8);
    }

    private static Path sourceFixtures() {
        String supplied = System.getenv("M3_MAVEN_LLVM_FIXTURES");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires original Maven #198 LLVM examples");
        Path fixtures = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(fixtures.resolve("hello.c"))
                        && Files.isRegularFile(fixtures.resolve("m3-build.tsv")),
                "LLVM fixtures must come from the exact pinned Maven source");
        return fixtures;
    }

    private String installOriginalMavenEngine(Path project) throws Exception {
        String env = System.getenv("M3_POLYGLOT_LLVM_JAR");
        assumeTrue(env != null && !env.isBlank(), "requires pinned Maven #198 provider JAR");
        Path source = Path.of(env).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "original Maven JAR must exist");
        Files.createDirectories(project.resolve("vendor"));
        Path target = project.resolve("vendor/maven-polyglot.jar");
        Files.copy(source, target);
        return sha(Files.readAllBytes(target));
    }

    private static String execute(Path project, String task) {
        return GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build().getOutput();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
