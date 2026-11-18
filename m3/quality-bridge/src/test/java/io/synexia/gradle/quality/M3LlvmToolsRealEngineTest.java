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

/** Real Maven #203 SPI and LLVM-tools JARs; no compiler, linker or optimizer is executed. */
final class M3LlvmToolsRealEngineTest {
    @TempDir Path root;

    @Test
    void externalProviderIsDiscoverableAndPlansThreeStagesWithoutRunningTools() throws Exception {
        String maven = System.getenv("M3_LLVM_TOOLS_ENGINE_JAR");
        String tools = System.getenv("M3_LLVM_TOOLS_PROVIDER_JAR");
        String source = System.getenv("M3_LLVM_TOOLS_FIXTURES");
        assumeTrue(maven != null && !maven.isBlank() && tools != null && !tools.isBlank()
                && source != null && !source.isBlank(), "requires exact Maven #203+ source artifacts");
        Path engineSource = Path.of(maven).toAbsolutePath().normalize();
        Path toolsSource = Path.of(tools).toAbsolutePath().normalize();
        Path fixtures = Path.of(source).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(engineSource) && Files.isRegularFile(toolsSource)
                && Files.isRegularFile(fixtures.resolve("m3-build.tsv"))
                && Files.isRegularFile(fixtures.resolve("hello.ll")));

        Path engine = root.resolve("engine.jar");
        Path provider = root.resolve("llvm-tools.jar");
        Files.copy(engineSource, engine);
        Files.copy(toolsSource, provider);
        Files.copy(fixtures.resolve("m3-build.tsv"), root.resolve("m3-build.tsv"));
        Files.copy(fixtures.resolve("hello.ll"), root.resolve("hello.ll"));
        String originalManifest = Files.readString(root.resolve("m3-build.tsv"), StandardCharsets.UTF_8);
        // Explicit inert executables prevent host LLVM_HOME/PATH settings
        // from changing a read-only plan, even on a developer's machine.
        Path bin = Files.createDirectories(root.resolve("native tools/bin"));
        String suffix = System.getProperty("os.name", "").startsWith("Windows") ? ".exe" : "";
        for (String name : new String[] {"opt", "llvm-as", "llvm-link"}) {
            Path tool = bin.resolve(name + suffix);
            Files.writeString(tool, "#!/bin/sh\nexit 99\n");
            assertTrue(tool.toFile().setExecutable(true) || Files.isExecutable(tool));
        }
        String inertManifest = originalManifest;
        for (String target : new String[] {"optimize", "assemble", "link"}) {
            int insertion = inertManifest.indexOf("arg\t" + target + "\t");
            assertTrue(insertion >= 0, "Maven's original LLVM graph changed: " + target);
            inertManifest = inertManifest.substring(0, insertion)
                    + "arg\t" + target + "\t--m3-llvm-bin=native tools/bin\n"
                    + inertManifest.substring(insertion);
        }
        Files.writeString(root.resolve("m3-build.tsv"), inertManifest);

        Files.writeString(root.resolve("settings.gradle"), "rootProject.name='m3-llvm-tools'\n");
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3PolyglotProviders', 'm3PolyglotPlan'].each { taskName ->\n"
                + "  tasks.named(taskName) {\n"
                + "    engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "    engineSha256.set('" + sha(engine) + "')\n"
                + "    providerJars.from(layout.projectDirectory.file('llvm-tools.jar'))\n"
                + "    providerJarSha256.put('llvm-tools.jar', '" + sha(provider) + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3PolyglotPlan') { target.set('link') }\n");

        execute("tasks", "--all");
        Path inventory = root.resolve("build/reports/m3/polyglot-providers.tsv");
        assertFalse(Files.exists(inventory), "configuration must not discover or execute providers");
        execute("m3PolyglotProviders");
        assertTrue(Files.readString(inventory).contains(
                "provider\tllvm-tools\tassembler,interpreter,linker,optimizer,transpiler"
                + "\tcoff,elf,filecheck,lit,llvm-ir,macho,mlir,wasm\n"),
                "Gradle must accept Maven #203's additive capability inventory");
        String plan = execute("m3PolyglotPlan");
        assertTrue(plan.contains("PLAN optimize tool=@m3:llvm-tools:optimizer:llvm-ir"), plan);
        assertTrue(plan.contains("PLAN assemble tool=@m3:llvm-tools:assembler:llvm-ir"), plan);
        assertTrue(plan.contains("PLAN link tool=@m3:llvm-tools:linker:llvm-ir"), plan);
        noNativeOutputs();

        Path fake = bin.resolve("opt" + suffix);
        String anchor = "arg\toptimize\t--m3-llvm-bin=native tools/bin\n";
        String selected = inertManifest.replace(anchor,
                anchor + "arg\toptimize\t--m3-llvm-sha256=" + sha(fake) + "\n");
        Files.writeString(root.resolve("m3-build.tsv"), selected);
        execute("m3PolyglotPlan");
        noNativeOutputs();

        Files.writeString(root.resolve("m3-build.tsv"),
                selected.replace(sha(fake), "0".repeat(64)));
        String mismatch = refuse();
        assertTrue(mismatch.contains("LLVM tool SHA-256 mismatch"), mismatch);
        noNativeOutputs();

        Files.writeString(root.resolve("m3-build.tsv"),
                selected.replace(anchor, anchor + "arg\toptimize\t--m3-llvm-home=another/root\n"));
        String conflicting = refuse();
        assertTrue(conflicting.contains("duplicate or conflicting LLVM toolchain selector"), conflicting);
        noNativeOutputs();

        Files.writeString(root.resolve("m3-build.tsv"),
                selected.replace("native tools/bin", "missing/bin"));
        String missing = refuse();
        assertTrue(missing.contains("selected LLVM executable unavailable"), missing);
        noNativeOutputs();

        Files.writeString(root.resolve("m3-build.tsv"), inertManifest);
        Files.writeString(provider, "tampered external provider bytes");
        String tampered = refuse();
        assertTrue(tampered.contains("Invalid M3 polyglot engine or manifest"), tampered);
        noNativeOutputs();
    }

    private void noNativeOutputs() {
        assertFalse(Files.exists(root.resolve("hello.opt.ll")));
        assertFalse(Files.exists(root.resolve("hello.bc")));
        assertFalse(Files.exists(root.resolve("hello.linked.bc")));
    }

    private String execute(String task, String... options) {
        String[] arguments = new String[options.length + 2];
        arguments[0] = task;
        System.arraycopy(options, 0, arguments, 1, options.length);
        arguments[arguments.length - 1] = "--stacktrace";
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(arguments).build().getOutput();
    }

    private String refuse() {
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotPlan", "--stacktrace").buildAndFail().getOutput();
    }

    private static String sha(Path source) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(source)));
    }
}
