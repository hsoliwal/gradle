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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Source-pinned Maven LLVM provider discovery and dry-run proof, without an LLVM installation. */
final class M3PolyglotLlvmRealEngineTest {
    @TempDir
    Path root;

    @Test
    void originalMavenLlvmProviderIsDiscoveredAndPlansWithoutRunningCompilers() throws Exception {
        String supplied = System.getenv("M3_POLYGLOT_LLVM_JAR");
        assumeTrue(supplied != null && !supplied.isBlank(), "requires Maven #198 engine JAR");
        Path source = Path.of(supplied).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(source), "the exact Maven source-built JAR must exist");
        Path engine = root.resolve("engine.jar");
        Files.copy(source, engine);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(engine)));

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-llvm-provider'\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotPlan', 'm3PolyglotProviders'].each { taskName ->\n"
                + "  tasks.named(taskName) {\n"
                + "    engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "    engineSha256.set('" + digest + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3PolyglotPlan') { target.set('compile') }\n");
        Files.writeString(root.resolve("hello.c"), "int main(void) { return 0; }\n");
        Files.writeString(root.resolve("m3-build.tsv"), manifest("--m3-llvm-bin=not-installed/bin"),
                StandardCharsets.UTF_8);

        run("tasks", "--all");
        Path inventory = root.resolve("build/reports/m3/polyglot-providers.tsv");
        assertFalse(Files.exists(inventory), "lazy registration cannot execute Maven providers");
        run("m3PolyglotProviders");
        assertTrue(Files.readString(inventory).contains(
                "provider\tllvm\tcompiler\tc,cpp,fortran,llvm-ir,objc,objcpp\n"),
                "Gradle must use the actual Maven #198 ServiceLoader inventory");

        String plan = run("m3PolyglotPlan");
        assertTrue(plan.contains("PLAN compile tool=@m3:llvm:compiler:c"), plan);
        assertFalse(Files.exists(root.resolve("hello.ll")), "planning must not run clang or write outputs");

        for (String invalid : new String[] {"--m3-llvm-bin=", "--m3-llvm-bin=one\narg\tcompile\t--m3-llvm-bin=two"}) {
            Files.writeString(root.resolve("m3-build.tsv"), manifest(invalid), StandardCharsets.UTF_8);
            String refusal = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                    .withArguments("m3PolyglotPlan", "--stacktrace").buildAndFail().getOutput();
            assertTrue(refusal.contains("LLVM bin directory"), refusal);
            assertFalse(Files.exists(root.resolve("hello.ll")));
        }
    }

    private String run(String task, String... options) {
        String[] arguments = new String[options.length + 2];
        arguments[0] = task;
        System.arraycopy(options, 0, arguments, 1, options.length);
        arguments[arguments.length - 1] = "--stacktrace";
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(arguments).build().getOutput();
    }

    private static String manifest(String binSelector) {
        return "M3-POLYGLOT-TASKS-V1\n"
                + "task\tcompile\t@m3:llvm:compiler:c\t.\n"
                + "arg\tcompile\t" + binSelector + "\n"
                + "arg\tcompile\t-S\n"
                + "arg\tcompile\t-emit-llvm\n"
                + "arg\tcompile\thello.c\n"
                + "arg\tcompile\t-o\n"
                + "arg\tcompile\thello.ll\n"
                + "input\tcompile\thello.c\n"
                + "output\tcompile\thello.ll\n";
    }
}
