/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.List;
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

/** TestKit validates SHA-pinned external CLI custody, arguments and report ownership. */
final class M3ProjectDbTaskTest {
    @TempDir Path root;
    private boolean declaredPluginClasspath;

    @Test
    void retainsOriginalBinaryTaskSurfaceWithOnlyConcreteLayoutInjection() throws Exception {
        Set<String> actual = Arrays.stream(M3ProjectDbTask.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) || Modifier.isProtected(method.getModifiers()))
                .map(method -> method.getName() + "(" + Arrays.stream(method.getParameterTypes())
                        .map(Class::getName).collect(Collectors.joining(",")) + "):"
                        + method.getReturnType().getName() + ":" + Modifier.toString(method.getModifiers()))
                .collect(Collectors.toSet());
        assertEquals(Set.of(
                "getAdditionalJarSha256():org.gradle.api.provider.MapProperty:public abstract",
                "getAdditionalJars():org.gradle.api.file.ConfigurableFileCollection:public abstract",
                "getBuildLog():org.gradle.api.file.RegularFileProperty:public abstract",
                "getExecOperations():org.gradle.process.ExecOperations:protected abstract",
                "getJavaExecutable():org.gradle.api.provider.Property:public abstract",
                "getM3ProjectLayout():org.gradle.api.file.ProjectLayout:protected",
                "getMode():org.gradle.api.provider.Property:public abstract",
                "getPolyglotJar():org.gradle.api.file.RegularFileProperty:public abstract",
                "getPolyglotSha256():org.gradle.api.provider.Property:public abstract",
                "getProjectDbJar():org.gradle.api.file.RegularFileProperty:public abstract",
                "getProjectDbSha256():org.gradle.api.provider.Property:public abstract",
                "getQuestion():org.gradle.api.provider.Property:public abstract",
                "getResultReport():org.gradle.api.file.RegularFileProperty:public abstract",
                "getSourceProject():org.gradle.api.file.DirectoryProperty:public abstract",
                "getStorageDirectory():org.gradle.api.file.DirectoryProperty:public abstract",
                "getTopK():org.gradle.api.provider.Property:public abstract",
                "runProjectDb():void:public"
                ), actual);
        assertEquals(1, M3ProjectDbTask.class.getConstructors().length);
        assertEquals(0, M3ProjectDbTask.class.getConstructors()[0].getParameterCount());
        var injection = M3ProjectDbTask.class.getDeclaredMethod("getM3ProjectLayout");
        assertTrue(injection.isAnnotationPresent(Inject.class));
        assertFalse(Modifier.isAbstract(injection.getModifiers()));
    }

    @Test
    void ingestsAndQueriesOnlyWhenExplicitlyExecuted() throws Exception {
        fixture();
        run("help", false);
        assertFalse(Files.exists(report("ingest")));
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));

        runTwiceWithConfigurationCache("m3ProjectDbIngest", false);
        assertTrue(Files.readString(report("ingest")).startsWith("{\"build\":\"b0001\""));
        assertEquals(List.of("ingest", root.toString(), "--store",
                root.resolve("build/m3/project-db").toString()),
                Files.readAllLines(root.resolve("m3-projectdb-argv.txt")));

        runTwiceWithConfigurationCache("m3ProjectDbQuery", false);
        assertTrue(Files.readString(report("query")).contains("\"answers\":[]"));
        assertEquals(List.of("query", root.toString(), "why build failed", "--store",
                root.resolve("build/m3/project-db").toString(), "--k", "5", "--json"),
                Files.readAllLines(root.resolve("m3-projectdb-argv.txt")));

        Files.delete(root.resolve("m3-projectdb-argv.txt"));
        Files.delete(root.resolve("build/m3/project-db/.gitignore"));
        Files.delete(report("query"));
        BuildResult refused = runWithConfigurationCache("m3ProjectDbQuery", true);
        assertTrue(refused.getOutput().contains("Reusing configuration cache"), refused.getOutput());
        assertTrue(refused.getOutput().contains("query requires an existing ingested database"),
                refused.getOutput());
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
        assertFalse(Files.exists(report("query")), "query must revalidate the existing store after replay");
    }

    @Test
    void queryRequiresPriorIngestWithoutCreatingAnEmptyDatabase() throws Exception {
        fixture();
        String output = runTwiceWithConfigurationCache("m3ProjectDbQuery", true).getOutput();
        assertTrue(output.contains("query requires an existing ingested database"), output);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
        assertFalse(Files.exists(root.resolve("build/m3/project-db")));
    }

    @Test
    void refusesTamperedJarBeforeStartingExternalEngine() throws Exception {
        fixture();
        Files.writeString(root.resolve("project-db.jar"), "replaced bytes");
        String error = runTwiceWithConfigurationCache("m3ProjectDbIngest", true).getOutput();
        assertTrue(error.contains("SHA-256 mismatch"), error);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
    }

    @Test
    void refusesOutOfProjectStoreAndEmptyQuery() throws Exception {
        fixture();
        String original = Files.readString(root.resolve("build.gradle"));
        Files.writeString(root.resolve("build.gradle"), original
                + "tasks.named('m3ProjectDbQuery') { question.set('') }\n");
        String empty = runTwiceWithConfigurationCache("m3ProjectDbQuery", true).getOutput();
        assertTrue(empty.contains("question must be bounded"), empty);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));

        Files.writeString(root.resolve("build.gradle"), original
                + "tasks.named('m3ProjectDbIngest') { storageDirectory.set(layout.projectDirectory.dir('../foreign-store')) }\n");
        String outside = runTwiceWithConfigurationCache("m3ProjectDbIngest", true).getOutput();
        assertTrue(outside.contains("project-local store"), outside);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));

        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("build.gradle"), original
                + "tasks.named('m3ProjectDbIngest') { storageDirectory.set(layout.projectDirectory.dir('src')) }\n");
        String sourceStore = runTwiceWithConfigurationCache("m3ProjectDbIngest", true).getOutput();
        assertTrue(sourceStore.contains("project-local store"), sourceStore);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
    }

    @Test
    void directTaskRetainsCustomBuildDirectoryAndRechecksStorageEngineAfterReplay() throws Exception {
        fixture();
        String projectDbSha = sha(Files.readAllBytes(root.resolve("project-db.jar")));
        String polyglotSha = sha(Files.readAllBytes(root.resolve("polyglot.jar")));
        declarePluginClasspath();
        Files.writeString(root.resolve("build.gradle"),
                "\n@org.gradle.api.tasks.UntrackedTask(because = 'Fixture keeps delegated execution untracked')\n"
                + "abstract class DerivedProjectDbTask extends io.synexia.gradle.quality.M3ProjectDbTask {}\n"
                + "tasks.register('directIngest', DerivedProjectDbTask) {\n"
                + "  javaExecutable.set('java')\n"
                + "  projectDbJar.set(layout.projectDirectory.file('project-db.jar'))\n"
                + "  projectDbSha256.set('" + projectDbSha + "')\n"
                + "  polyglotJar.set(layout.projectDirectory.file('polyglot.jar'))\n"
                + "  polyglotSha256.set('" + polyglotSha + "')\n"
                + "  additionalJarSha256.set([:])\n"
                + "  sourceProject.set(layout.projectDirectory)\n"
                + "  storageDirectory.set(layout.buildDirectory.dir('m3/project-db'))\n"
                + "  mode.set('ingest')\n"
                + "  question.set('')\n"
                + "  topK.set(5)\n"
                + "  resultReport.set(layout.buildDirectory.file('reports/direct-ingest.json'))\n"
                + "}\n"
                + "tasks.named('directIngest').get()\n"
                + "layout.buildDirectory.set(layout.projectDirectory.dir('custom-build'))\n",
                java.nio.file.StandardOpenOption.APPEND);

        Path report = root.resolve("custom-build/reports/direct-ingest.json");
        runTwiceWithConfigurationCache("directIngest", false);
        assertTrue(Files.readString(report).startsWith("{\"build\":\"b0001\""));
        assertEquals(List.of("ingest", root.toString(), "--store",
                root.resolve("custom-build/m3/project-db").toString()),
                Files.readAllLines(root.resolve("m3-projectdb-argv.txt")));
        assertFalse(Files.exists(root.resolve("build/m3/project-db")));

        Files.delete(report);
        Files.delete(root.resolve("m3-projectdb-argv.txt"));
        Files.writeString(root.resolve("polyglot.jar"), "changed after configuration was cached");
        BuildResult refused = runWithConfigurationCache("directIngest", true);
        assertTrue(refused.getOutput().contains("Reusing configuration cache"), refused.getOutput());
        assertTrue(refused.getOutput().contains("SHA-256 mismatch"), refused.getOutput());
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
        assertFalse(Files.exists(report));
    }

    private void fixture() throws Exception {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\n");
        String pluginSha = fakeCliJar(root.resolve("project-db.jar"));
        Path polyglot = root.resolve("polyglot.jar");
        Files.writeString(polyglot, "separately reviewed Maven polyglot JAR", StandardCharsets.UTF_8);
        String polyglotSha = sha(Files.readAllBytes(polyglot));
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3ProjectDbIngest','m3ProjectDbQuery'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    projectDbJar.set(layout.projectDirectory.file('project-db.jar'))\n"
                + "    projectDbSha256.set('" + pluginSha + "')\n"
                + "    polyglotJar.set(layout.projectDirectory.file('polyglot.jar'))\n"
                + "    polyglotSha256.set('" + polyglotSha + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3ProjectDbQuery') { question.set('why build failed') }\n");
    }

    private String run(String task, boolean fail) {
        return runWithConfigurationCache(task, fail).getOutput();
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
        Path buildFile = root.resolve("build.gradle");
        String script = Files.readString(buildFile);
        String pluginDeclaration = "plugins { id 'io.synexia.m3.quality' }";
        assertTrue(script.startsWith(pluginDeclaration));
        Files.writeString(buildFile, "buildscript { dependencies { classpath files(" + classpath + ") } }\n"
                + "apply plugin: 'io.synexia.m3.quality'" + script.substring(pluginDeclaration.length()));
        declaredPluginClasspath = true;
    }

    private BuildResult runWithConfigurationCache(String task, boolean fail) {
        var runner = GradleRunner.create().withProjectDir(root.toFile())
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

    private Path report(String mode) {
        return root.resolve("build/reports/m3/project-db/" + mode + ".json");
    }

    private static String fakeCliJar(Path target) throws Exception {
        Path source = target.getParent().resolve("fake/org/apache/maven/plugins/projectdb/ProjectDbCli.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.apache.maven.plugins.projectdb;
                public final class ProjectDbCli {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Files.writeString(java.nio.file.Path.of("m3-projectdb-argv.txt"),
                                String.join("\\n", args) + "\\n");
                        if ("ingest".equals(args[0])) {
                            java.nio.file.Path store = java.nio.file.Path.of(args[3]);
                            java.nio.file.Files.createDirectories(store.resolve("db"));
                            java.nio.file.Files.writeString(store.resolve(".gitignore"), "*\\n");
                            System.out.println("{\\"build\\":\\"b0001\\",\\"status\\":\\"green\\"}");
                        } else System.out.println("{\\"question\\":\\"why\\",\\"build\\":\\"b0001\\",\\"answers\\":[]}");
                    }
                }
                """);
        Path classes = Files.createDirectories(target.getParent().resolve("fake-classes"));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), source.toString()));
        Path compiled = classes.resolve("org/apache/maven/plugins/projectdb/ProjectDbCli.class");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target))) {
            jar.putNextEntry(new JarEntry("org/apache/maven/plugins/projectdb/ProjectDbCli.class"));
            Files.copy(compiled, jar);
            jar.closeEntry();
        }
        return sha(Files.readAllBytes(target));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
