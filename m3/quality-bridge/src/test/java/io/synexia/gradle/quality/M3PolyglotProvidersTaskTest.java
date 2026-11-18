/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.io.File;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.inject.Inject;
import javax.tools.ToolProvider;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gradle delegates discovery to the same pinned Maven CLI, never a forked registry. */
class M3PolyglotProvidersTaskTest {
    @TempDir Path workspace;
    private boolean declaredPluginClasspath;

    @Test
    void retainsOriginalBinaryTaskSurfaceWithOnlyConcreteLayoutInjection() throws Exception {
        Set<String> actual = Arrays.stream(M3PolyglotProvidersTask.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                .map(method -> method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                        .map(Class::getName).collect(Collectors.joining(",")) + "):"
                        + method.getReturnType().getName() + ":" + Modifier.toString(method.getModifiers()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "getEngineJar():org.gradle.api.file.RegularFileProperty:public abstract",
                "getEngineSha256():org.gradle.api.provider.Property:public abstract",
                "getExecOperations():org.gradle.process.ExecOperations:protected abstract",
                "getJavaExecutable():org.gradle.api.provider.Property:public abstract",
                "getM3ProjectLayout():org.gradle.api.file.ProjectLayout:protected",
                "getProviderJarSha256():org.gradle.api.provider.MapProperty:public abstract",
                "getProviderJars():org.gradle.api.file.ConfigurableFileCollection:public abstract",
                "getProviderReport():org.gradle.api.file.RegularFileProperty:public abstract",
                "inspectProviders():void:public"
                ), actual);
        assertEquals(1, M3PolyglotProvidersTask.class.getConstructors().length);
        assertEquals(0, M3PolyglotProvidersTask.class.getConstructors()[0].getParameterCount());
        var injection = M3PolyglotProvidersTask.class.getDeclaredMethod("getM3ProjectLayout");
        assertTrue(injection.isAnnotationPresent(Inject.class));
        assertFalse(Modifier.isAbstract(injection.getModifiers()));
    }

    @Test
    void delegatesProviderInventoryAndNormalizesWindowsLineEndings() throws Exception {
        fixture();
        String inventory = "M3-POLYGLOT-PROVIDERS-V1\r\n"
                + "provider\tacme\tatomizer,patternizer\tjava,rust\r\n"
                + "provider\tjdk\tcompiler\tjava\r\n";
        String digest = engine(inventory);
        configuration(digest);
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")),
                "plugin application must not run Maven or create reports");
        runTwiceWithConfigurationCache("m3PolyglotProviders", false);
        assertEquals(inventory.replace("\r\n", "\n"),
                Files.readString(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void refusesWrongEngineDigest() throws Exception {
        fixture();
        engine("M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n");
        configuration("f".repeat(64));
        String output = runTwiceWithConfigurationCache("m3PolyglotProviders", true).getOutput();
        assertTrue(output.contains("M3 Maven provider inventory refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void refusesMalformedOrUnsortedInventories() throws Exception {
        fixture();
        String digest = engine("M3-POLYGLOT-PROVIDERS-V1\n"
                + "provider\tjdk\tcompiler\tjava\n"
                + "provider\tacme\tatomizer\trust\n");
        configuration(digest);
        String output = runTwiceWithConfigurationCache("m3PolyglotProviders", true).getOutput();
        assertTrue(output.contains("M3 Maven provider inventory refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void acceptsMaven203NativeLlvmCapabilityInventory() throws Exception {
        fixture();
        String inventory = "M3-POLYGLOT-PROVIDERS-V1\n"
                + "provider\tllvm-tools\tassembler,interpreter,linker,optimizer,transpiler\t"
                + "coff,elf,filecheck,lit,llvm-ir,macho,mlir,wasm\n";
        configuration(engine(inventory));
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").build();
        assertEquals(inventory,
                Files.readString(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void refusesUnknownNativeCapabilityEvenFromPinnedCli() throws Exception {
        fixture();
        configuration(engine("M3-POLYGLOT-PROVIDERS-V1\n"
                + "provider\tllvm-tools\tassembler,rewriter\tllvm-ir\n"));
        String output = GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("m3PolyglotProviders", "--stacktrace").buildAndFail().getOutput();
        assertTrue(output.contains("M3 Maven provider inventory refused"), output);
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void loadsExplicitlyPinnedExternalProviderJarOnMavenRuntimeClasspath() throws Exception {
        fixture();
        String digest = engine("M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n");
        Path provider = workspace.resolve("external-provider.jar");
        Files.writeString(provider, "explicit provider bytes");
        String pin = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(provider)));
        configuration(digest);
        Files.writeString(workspace.resolve("build.gradle"), Files.readString(workspace.resolve("build.gradle"))
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  providerJars.from(layout.projectDirectory.file('external-provider.jar'))\n"
                + "  providerJarSha256.put('external-provider.jar', '" + pin + "')\n"
                + "}\n");
        runTwiceWithConfigurationCache("m3PolyglotProviders", false);
        assertTrue(Files.readString(workspace.resolve("cli-classpath.txt"))
                .contains(File.pathSeparator + provider));

        Files.delete(workspace.resolve("cli-classpath.txt"));
        Files.delete(workspace.resolve("build/reports/m3/polyglot-providers.tsv"));
        Files.writeString(provider, "provider changed after configuration was cached");
        BuildResult refused = runWithConfigurationCache("m3PolyglotProviders", true);
        assertTrue(refused.getOutput().contains("Reusing configuration cache"), refused.getOutput());
        assertTrue(refused.getOutput().contains("M3 Maven provider inventory refused"), refused.getOutput());
        assertFalse(Files.exists(workspace.resolve("cli-classpath.txt")), "changed provider must not execute");
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));
    }

    @Test
    void directTaskRetainsCustomBuildDirectoryAndRechecksEngineAfterReplay() throws Exception {
        fixture();
        String inventory = "M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n";
        String digest = engine(inventory);
        configuration(digest);
        declarePluginClasspath();
        Files.writeString(workspace.resolve("build.gradle"),
                "\n@org.gradle.api.tasks.UntrackedTask(because = 'Fixture keeps delegated execution untracked')\n"
                + "abstract class DerivedProvidersTask extends io.synexia.gradle.quality.M3PolyglotProvidersTask {}\n"
                + "tasks.register('directProviders', DerivedProvidersTask) {\n"
                + "  javaExecutable.set('java')\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  providerJarSha256.set([:])\n"
                + "  providerReport.set(layout.buildDirectory.file('reports/direct-providers.tsv'))\n"
                + "}\n"
                + "tasks.named('directProviders').get()\n"
                + "layout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path report = workspace.resolve("custom-build/reports/direct-providers.tsv");
        runTwiceWithConfigurationCache("directProviders", false);
        assertEquals(inventory, Files.readString(report));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/polyglot-providers.tsv")));

        Files.delete(report);
        Files.delete(workspace.resolve("cli-classpath.txt"));
        Files.writeString(workspace.resolve("engine.jar"), "changed after configuration was cached");
        BuildResult refused = runWithConfigurationCache("directProviders", true);
        assertTrue(refused.getOutput().contains("Reusing configuration cache"), refused.getOutput());
        assertTrue(refused.getOutput().contains("SHA-256 mismatch"), refused.getOutput());
        assertFalse(Files.exists(workspace.resolve("cli-classpath.txt")), "changed engine must not execute");
        assertFalse(Files.exists(report));
    }

    @Test
    void refusesReportOutsideCustomBuildDirectoryOnCachedRuns() throws Exception {
        fixture();
        String digest = engine("M3-POLYGLOT-PROVIDERS-V1\nprovider\tjdk\tcompiler\tjava\n");
        configuration(digest);
        Files.writeString(workspace.resolve("build.gradle"),
                "\nlayout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))\n"
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  providerReport.set(layout.projectDirectory.file('build/rejected.tsv'))\n"
                + "}\n", java.nio.file.StandardOpenOption.APPEND);

        BuildResult refused = runTwiceWithConfigurationCache("m3PolyglotProviders", true);
        assertTrue(refused.getOutput().contains("under the project build directory"), refused.getOutput());
        assertFalse(Files.exists(workspace.resolve("cli-classpath.txt")));
        assertFalse(Files.exists(workspace.resolve("build/rejected.tsv")));
    }

    /** The serialized script subclass needs its superclass on a declared buildscript classpath. */
    private void declarePluginClasspath() throws java.io.IOException {
        var metadata = new java.util.Properties();
        try (var stream = getClass().getClassLoader().getResourceAsStream("plugin-under-test-metadata.properties")) {
            if (stream == null) throw new java.io.IOException("plugin-under-test metadata is missing");
            metadata.load(stream);
        }
        String entries = metadata.getProperty("implementation-classpath");
        if (entries == null || entries.isBlank()) throw new java.io.IOException("plugin classpath is empty");
        String classpath = Arrays.stream(entries.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))
                .map(path -> "'" + path.replace("\\", "\\\\").replace("'", "\\'") + "'")
                .collect(Collectors.joining(", "));
        Path buildFile = workspace.resolve("build.gradle");
        String script = Files.readString(buildFile);
        String pluginDeclaration = "plugins { id 'io.synexia.m3.quality' }";
        assertTrue(script.startsWith(pluginDeclaration));
        Files.writeString(buildFile, "buildscript { dependencies { classpath files(" + classpath + ") } }\n"
                + "apply plugin: 'io.synexia.m3.quality'" + script.substring(pluginDeclaration.length()));
        declaredPluginClasspath = true;
    }

    private BuildResult runWithConfigurationCache(String task, boolean fail) {
        var runner = GradleRunner.create().withProjectDir(workspace.toFile())
                .withArguments(task, "--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace");
        if (!declaredPluginClasspath) runner.withPluginClasspath();
        return fail ? runner.buildAndFail() : runner.build();
    }

    private BuildResult runTwiceWithConfigurationCache(String task, boolean fail) {
        BuildResult first = runWithConfigurationCache(task, fail);
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        BuildResult second = runWithConfigurationCache(task, fail);
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertEquals(fail ? TaskOutcome.FAILED : TaskOutcome.SUCCESS, second.task(":" + task).getOutcome());
        return second;
    }

    private void fixture() throws IOException {
        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='m3-provider-proxy'\n");
    }

    private void configuration(String digest) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3PolyglotProviders') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "}\n");
    }

    private String engine(String output) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("producer/src/io/synexia/maven/polyglot"))
                .resolve("PolyglotCli.java");
        String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        Files.writeString(source, "package io.synexia.maven.polyglot;\n"
                + "public final class PolyglotCli { public static void main(String[] args) {\n"
                + "if (args.length != 1 || !\"providers\".equals(args[0])) throw new IllegalArgumentException();\n"
                + "try { java.nio.file.Files.writeString(java.nio.file.Path.of(\"cli-classpath.txt\"), "
                + "System.getProperty(\"java.class.path\")); } catch (java.io.IOException fail) { throw new RuntimeException(fail); }\n"
                + "System.out.print(\"" + escaped + "\"); } }\n", StandardCharsets.UTF_8);
        Path classes = Files.createDirectories(workspace.resolve("producer/classes"));
        int code = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, code);
        Path jar = workspace.resolve("engine.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("io/synexia/maven/polyglot/PolyglotCli.class"));
            stream.write(Files.readAllBytes(classes.resolve("io/synexia/maven/polyglot/PolyglotCli.class")));
            stream.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
