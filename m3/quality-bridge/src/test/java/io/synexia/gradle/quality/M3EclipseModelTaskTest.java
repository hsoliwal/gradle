/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
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

/** A synthetic pinned CLI verifies delegation, output custody, and refusal. */
class M3EclipseModelTaskTest {
    @TempDir
    Path workspace;
    private boolean declaredPluginClasspath;

    @Test
    void retainsOriginalBinaryTaskSurfaceWithOnlyConcreteLayoutInjection() throws Exception {
        Set<String> actual = Arrays.stream(M3EclipseModelTask.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                .map(method -> method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                        .map(Class::getName).collect(Collectors.joining(",")) + "):"
                        + method.getReturnType().getName() + ":" + Modifier.toString(method.getModifiers()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "getContextPacket():org.gradle.api.file.RegularFileProperty:public abstract",
                "getEclipseProject():org.gradle.api.file.DirectoryProperty:public abstract",
                "getEngineJar():org.gradle.api.file.RegularFileProperty:public abstract",
                "getEngineSha256():org.gradle.api.provider.Property:public abstract",
                "getExecOperations():org.gradle.process.ExecOperations:protected abstract",
                "getHandoffPacket():org.gradle.api.file.RegularFileProperty:public abstract",
                "getJavaExecutable():org.gradle.api.provider.Property:public abstract",
                "getM3ProjectLayout():org.gradle.api.file.ProjectLayout:protected",
                "getModelReport():org.gradle.api.file.RegularFileProperty:public abstract",
                "getProviderJarSha256():org.gradle.api.provider.MapProperty:public abstract",
                "getProviderJars():org.gradle.api.file.ConfigurableFileCollection:public abstract",
                "getReportMode():org.gradle.api.provider.Property:public abstract",
                "inspectEclipseProject():void:public"
                ), actual);
        assertEquals(1, M3EclipseModelTask.class.getConstructors().length);
        assertEquals(0, M3EclipseModelTask.class.getConstructors()[0].getParameterCount());
        var injection = M3EclipseModelTask.class.getDeclaredMethod("getM3ProjectLayout");
        assertTrue(injection.isAnnotationPresent(Inject.class));
        assertFalse(Modifier.isAbstract(injection.getModifiers()));
    }

    @Test
    void explicitlyDelegatesToPinnedMavenReaderWithoutTouchingTheDescriptor() throws Exception {
        fixture();
        String validJson = "{\"schema\":\"m3-eclipse-project-v1\",\"source\":\"synthetic\"}\r\n";
        String digest = engine(validJson);
        configuration(digest);

        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "registering the plugin must not invoke its Maven CLI");

        runTwiceWithConfigurationCache("m3EclipseModel", false);
        assertEquals(validJson.replace("\r\n", "\n"),
                Files.readString(workspace.resolve("build/reports/m3/eclipse-model.json")));
        assertEquals("<projectDescription><name>fixture</name></projectDescription>\n",
                Files.readString(workspace.resolve("eclipse-project/.project")));
    }

    @Test
    void runsReadOnlyBuilderOwnerInventoryOnlyWhenExplicitlyRequested() throws Exception {
        fixture();
        String report = "{\"schema\":\"m3-eclipse-builder-owners-v1\",\"project\":\"fixture\","
                + "\"builders\":[],\"unboundNatures\":[]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuilders");

        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        Path out = workspace.resolve("build/reports/m3/eclipse-builders.json");
        assertFalse(Files.exists(out), "registration must not execute any Eclipse adapter");
        runTwiceWithConfigurationCache("m3EclipseBuilders", false);
        assertEquals(report.replace("\r\n", "\n"), Files.readString(out));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "owner inventory cannot silently activate the original model task");
    }

    @Test
    void importsOnlyExplicitEclipseConfigurationPacket() throws Exception {
        fixture();
        Path context = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-context.ide");
        Files.writeString(context, "M3-ECLIPSE-BUILD-CONTEXT-V1\nowner-source\n");
        String report = "{\"schema\":\"m3-eclipse-build-context-v1\","
                + "\"project\":\"fixture\",\"selectedConfiguration\":\"Debug\","
                + "\"activeConfiguration\":\"Release\"}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuildContext");
        Files.writeString(workspace.resolve("build.gradle"), "\ntasks.named('m3EclipseBuildContext') {\n"
                + "  contextPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-context.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-build-context.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "configuring a Gradle plugin cannot trigger Eclipse builds");
        runTwiceWithConfigurationCache("m3EclipseBuildContext", false);
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertTrue(Files.readString(context).startsWith("M3-ECLIPSE-BUILD-CONTEXT-V1"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-builders.json")),
                "the build context cannot silently execute other Eclipse tasks");
    }

    @Test
    void importsOneEclipseBuildInvocationWithoutStartingAnyOtherBuilder() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\noriginal-owner-fixture\n");
        String report = "{\"schema\":\"m3-eclipse-build-handoff-v1\",\"project\":\"fixture\","
                + "\"selectedConfiguration\":\"Debug\",\"activeConfiguration\":\"Release\","
                + "\"deltaComplete\":true,\"changes\":[]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseBuildHandoff");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseBuildHandoff') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-build-handoff.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "task registration must not execute an Eclipse builder");
        runTwiceWithConfigurationCache("m3EclipseBuildHandoff", false);
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertTrue(Files.readString(packet).startsWith("M3-ECLIPSE-BUILD-HANDOFF-V1"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-context.json")),
                "handoff cannot silently run the separate Eclipse context task");
    }

    @Test
    void reconcilesCurrentModelOnlyOnAnExplicitRequest() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\nowner-fixture\n");
        String report = "{\"schema\":\"m3-eclipse-reconciliation-v1\","
                + "\"disposition\":\"RECONCILE\",\"reasons\":[\"NATURES_CHANGED\"]}\r\n";
        String digest = engine(report);
        configuration(digest, "m3EclipseReconcile");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseReconcile') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path output = workspace.resolve("build/reports/m3/eclipse-reconciliation.json");
        GradleRunner.create().withProjectDir(workspace.toFile()).withPluginClasspath()
                .withArguments("tasks", "--all").build();
        assertFalse(Files.exists(output), "reconciliation must not run at Gradle configuration time");
        runTwiceWithConfigurationCache("m3EclipseReconcile", false);
        assertEquals(report.replace("\r\n", "\n"), Files.readString(output));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-handoff.json")),
                "reconciliation must not execute the other advisory task");
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")),
                "reconciliation must not create a second project-model report");
    }

    @Test
    void rejectsHandoffSchemaSubstitutionBeforePublishingReport() throws Exception {
        fixture();
        Path packet = Files.createDirectories(workspace.resolve("eclipse-project/target/ide"))
                .resolve("build-handoff.ide");
        Files.writeString(packet, "M3-ECLIPSE-BUILD-HANDOFF-V1\nsource\n");
        String digest = engine("{\"schema\":\"m3-eclipse-build-context-v1\"}\n");
        configuration(digest, "m3EclipseBuildHandoff");
        Files.writeString(workspace.resolve("build.gradle"),
                "\ntasks.named('m3EclipseBuildHandoff') {\n"
                + "  handoffPacket.set(layout.projectDirectory.file("
                + "'eclipse-project/target/ide/build-handoff.ide'))\n}\n",
                java.nio.file.StandardOpenOption.APPEND);
        var result = runTwiceWithConfigurationCache("m3EclipseBuildHandoff", true);
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-build-handoff.json")));
    }

    @Test
    void refusesUnpinnedEclipseBuilderEngineWithoutCreatingAnOwnerReport() throws Exception {
        fixture();
        engine("{\"schema\":\"m3-eclipse-builder-owners-v1\",\"builders\":[]}\\n");
        configuration("0".repeat(64), "m3EclipseBuilders");
        var result = runTwiceWithConfigurationCache("m3EclipseBuilders", true);
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-builders.json")));
    }

    @Test
    void refusesWrongDigestWithoutPublishingAnUnverifiedReport() throws Exception {
        fixture();
        engine("{\"schema\":\"m3-eclipse-project-v1\"}\n");
        configuration("0".repeat(64));
        var result = runTwiceWithConfigurationCache("m3EclipseModel", true);
        assertTrue(result.getOutput().contains("M3 Eclipse model inspection refused"));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")));
    }

    @Test
    void directTaskRetainsCustomBuildDirectoryAndRechecksEngineAfterReplay() throws Exception {
        fixture();
        String json = "{\"schema\":\"m3-eclipse-project-v1\",\"source\":\"synthetic\"}\n";
        String digest = engine(json);
        configuration(digest);
        declarePluginClasspath();
        Files.writeString(workspace.resolve("build.gradle"),
                "\n@org.gradle.api.tasks.UntrackedTask(because = 'Fixture keeps delegated execution untracked')\n"
                + "abstract class DerivedEclipseTask extends io.synexia.gradle.quality.M3EclipseModelTask {}\n"
                + "tasks.register('directEclipse', DerivedEclipseTask) {\n"
                + "  javaExecutable.set('java')\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  providerJarSha256.set([:])\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "  reportMode.set('model')\n"
                + "  modelReport.set(layout.buildDirectory.file('reports/direct-eclipse.json'))\n"
                + "}\n"
                + "tasks.named('directEclipse').get()\n"
                + "layout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path report = workspace.resolve("custom-build/reports/direct-eclipse.json");
        runTwiceWithConfigurationCache("directEclipse", false);
        assertEquals(json, Files.readString(report));
        assertFalse(Files.exists(workspace.resolve("build/reports/m3/eclipse-model.json")));

        Files.delete(report);
        Files.writeString(workspace.resolve("engine.jar"), "changed after configuration was cached");
        BuildResult refused = runWithConfigurationCache("directEclipse", true);
        assertTrue(refused.getOutput().contains("Reusing configuration cache"), refused.getOutput());
        assertTrue(refused.getOutput().contains("SHA-256 mismatch"), refused.getOutput());
        assertFalse(Files.exists(report), "configuration reuse must not bypass the original engine digest check");
    }

    @Test
    void refusesReportOutsideCustomBuildDirectoryOnCachedRuns() throws Exception {
        fixture();
        String digest = engine("{\"schema\":\"m3-eclipse-project-v1\"}\n");
        configuration(digest);
        Files.writeString(workspace.resolve("build.gradle"),
                "\nlayout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))\n"
                + "tasks.named('m3EclipseModel') {\n"
                + "  modelReport.set(layout.projectDirectory.file('build/rejected.json'))\n"
                + "}\n", java.nio.file.StandardOpenOption.APPEND);

        BuildResult refused = runTwiceWithConfigurationCache("m3EclipseModel", true);
        assertTrue(refused.getOutput().contains("project-local Eclipse descriptor and build JSON report"),
                refused.getOutput());
        assertFalse(Files.exists(workspace.resolve("build/rejected.json")));
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
        Path descriptorDirectory = Files.createDirectories(workspace.resolve("eclipse-project"));
        Files.writeString(descriptorDirectory.resolve(".project"),
                "<projectDescription><name>fixture</name></projectDescription>\n");
        Files.writeString(workspace.resolve("settings.gradle"), "rootProject.name='eclipse-adapter-fixture'\n");
    }

    private void configuration(String digest) throws IOException {
        configuration(digest, "m3EclipseModel");
    }

    private void configuration(String digest, String taskName) throws IOException {
        Files.writeString(workspace.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('" + taskName + "') {\n"
                + "  engineJar.set(layout.projectDirectory.file('engine.jar'))\n"
                + "  engineSha256.set('" + digest + "')\n"
                + "  eclipseProject.set(layout.projectDirectory.dir('eclipse-project'))\n"
                + "}\n");
    }

    private String engine(String output) throws Exception {
        Path source = Files.createDirectories(workspace.resolve("producer/src/io/synexia/maven/polyglot"))
                .resolve("EclipseModelCli.java");
        String escaped = output.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
        Files.writeString(source, "package io.synexia.maven.polyglot;\n"
                + "public class EclipseModelCli { public static void main(String[] args) {\n"
                + "System.out.print(\"" + escaped + "\"); } }\n");
        Path classes = Files.createDirectories(workspace.resolve("producer/classes"));
        int status = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString());
        assertEquals(0, status, "synthetic Maven-compatible CLI must compile");
        Path jar = workspace.resolve("engine.jar");
        try (JarOutputStream stream = new JarOutputStream(Files.newOutputStream(jar))) {
            stream.putNextEntry(new JarEntry("io/synexia/maven/polyglot/EclipseModelCli.class"));
            stream.write(Files.readAllBytes(classes.resolve("io/synexia/maven/polyglot/EclipseModelCli.class")));
            stream.closeEntry();
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
    }
}
