/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

class M3QualityPluginTest {
    @TempDir
    Path projectDirectory;

    @Test
    void registersLazyTasksWithoutDownloadingToolsOrApplyingRewrite() throws IOException {
        Files.writeString(projectDirectory.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\n");
        Files.writeString(projectDirectory.resolve("build.gradle.kts"),
                "plugins { id(\"io.synexia.m3.quality\") }\n");
        String output = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments("tasks", "--all", "--stacktrace")
                .build()
                .getOutput();
        assertTrue(output.contains("m3CodeqlSarif"));
        assertTrue(output.contains("m3CodeqlEvidence"));
        assertTrue(output.contains("m3SemgrepScan"));
        assertTrue(output.contains("m3ReviewAnalysis"));
        assertTrue(output.contains("m3NormalizeAnalyzerXml"));
        assertTrue(output.contains("m3PolyglotPlan"));
        assertTrue(output.contains("m3PolyglotRun"));
        assertTrue(output.contains("m3PolyglotProviders"));
        assertTrue(output.contains("m3EclipseModel"));
    }
}
