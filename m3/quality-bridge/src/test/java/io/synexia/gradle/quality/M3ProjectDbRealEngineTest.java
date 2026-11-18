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

/** Original Maven #184 JARs, not a fake database or synthetic JVM implementation. */
final class M3ProjectDbRealEngineTest {
    @TempDir Path root;

    @Test
    void ingestsActualBuildLogAndRetrievesOriginalProvenance() throws Exception {
        String db = System.getenv("M3_PROJECT_DB_JAR");
        String polyglot = System.getenv("M3_POLYGLOT_JAR");
        assumeTrue(db != null && !db.isBlank() && polyglot != null && !polyglot.isBlank(),
                "requires original source-pinned Maven #184 JARs");
        Path pluginJar = Path.of(db).toAbsolutePath().normalize();
        Path polyglotJar = Path.of(polyglot).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(pluginJar) && Files.isRegularFile(polyglotJar));

        Files.copy(pluginJar, root.resolve("project-db.jar"));
        Files.copy(polyglotJar, root.resolve("polyglot.jar"));
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name=\"maven-source\"\n");
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>example</groupId><artifactId>fixture</artifactId><version>1.0</version>
                </project>
                """);
        Files.writeString(root.resolve("build.log"), """
                [ERROR] compilation failed at src/main/java/Sample.java:[12,7]
                [ERROR] cannot find symbol for original TestHarness
                BUILD FAILURE
                """);
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'io.synexia.m3.quality' }\n"
                + "['m3ProjectDbIngest','m3ProjectDbQuery'].each { name ->\n"
                + "  tasks.named(name) {\n"
                + "    projectDbJar.set(layout.projectDirectory.file('project-db.jar'))\n"
                + "    projectDbSha256.set('" + sha(pluginJar) + "')\n"
                + "    polyglotJar.set(layout.projectDirectory.file('polyglot.jar'))\n"
                + "    polyglotSha256.set('" + sha(polyglotJar) + "')\n"
                + "  }\n"
                + "}\n"
                + "tasks.named('m3ProjectDbIngest') { buildLog.set(layout.projectDirectory.file('build.log')) }\n"
                + "tasks.named('m3ProjectDbQuery') { question.set('why did compilation fail') }\n");

        run("m3ProjectDbIngest");
        String receipt = Files.readString(root.resolve("build/reports/m3/project-db/ingest.json"));
        assertTrue(receipt.contains("\"build\""), receipt);
        assertTrue(Files.isRegularFile(root.resolve("build/m3/project-db/.gitignore")));

        run("m3ProjectDbQuery");
        String answer = Files.readString(root.resolve("build/reports/m3/project-db/query.json"),
                StandardCharsets.UTF_8);
        assertTrue(answer.contains("\"question\""), answer);
        assertTrue(answer.contains("\"answers\""), answer);
        assertTrue(answer.contains("\"build\""), answer);
        assertFalse(answer.contains("no evidence in the project database"),
                "the original CLI was invoked with JSON output, not text fallback");
    }

    private void run(String task) {
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build();
    }

    private static String sha(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }
}
