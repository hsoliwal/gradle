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

/** TestKit validates SHA-pinned external CLI custody, arguments and report ownership. */
final class M3ProjectDbTaskTest {
    @TempDir Path root;

    @Test
    void ingestsAndQueriesOnlyWhenExplicitlyExecuted() throws Exception {
        fixture();
        run("help", false);
        assertFalse(Files.exists(report("ingest")));
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));

        run("m3ProjectDbIngest", false);
        assertTrue(Files.readString(report("ingest")).startsWith("{\"build\":\"b0001\""));
        assertEquals(List.of("ingest", root.toString(), "--store",
                root.resolve("build/m3/project-db").toString()),
                Files.readAllLines(root.resolve("m3-projectdb-argv.txt")));

        run("m3ProjectDbQuery", false);
        assertTrue(Files.readString(report("query")).contains("\"answers\":[]"));
        assertEquals(List.of("query", root.toString(), "why build failed", "--store",
                root.resolve("build/m3/project-db").toString(), "--k", "5", "--json"),
                Files.readAllLines(root.resolve("m3-projectdb-argv.txt")));
    }

    @Test
    void refusesTamperedJarBeforeStartingExternalEngine() throws Exception {
        fixture();
        Files.writeString(root.resolve("project-db.jar"), "replaced bytes");
        String error = run("m3ProjectDbIngest", true);
        assertTrue(error.contains("SHA-256 mismatch"), error);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
    }

    @Test
    void refusesOutOfProjectStoreAndEmptyQuery() throws Exception {
        fixture();
        String original = Files.readString(root.resolve("build.gradle"));
        Files.writeString(root.resolve("build.gradle"), original
                + "tasks.named('m3ProjectDbQuery') { question.set('') }\n");
        String empty = run("m3ProjectDbQuery", true);
        assertTrue(empty.contains("question must be bounded"), empty);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));

        Files.writeString(root.resolve("build.gradle"), original
                + "tasks.named('m3ProjectDbIngest') { storageDirectory.set(layout.projectDirectory.dir('../foreign-store')) }\n");
        String outside = run("m3ProjectDbIngest", true);
        assertTrue(outside.contains("project-local store"), outside);
        assertFalse(Files.exists(root.resolve("m3-projectdb-argv.txt")));
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
        var runner = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace");
        return fail ? runner.buildAndFail().getOutput() : runner.build().getOutput();
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
                        if ("ingest".equals(args[0])) System.out.println("{\\"build\\":\\"b0001\\",\\"status\\":\\"green\\"}");
                        else System.out.println("{\\"question\\":\\"why\\",\\"build\\":\\"b0001\\",\\"answers\\":[]}");
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
