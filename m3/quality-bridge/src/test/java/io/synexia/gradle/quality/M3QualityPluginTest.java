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
        GradleRunner runner = GradleRunner.create()
                .withProjectDir(projectDirectory.toFile())
                .withPluginClasspath()
                .withArguments("tasks", "--all", "--configuration-cache",
                        "--configuration-cache-problems=fail", "--stacktrace");
        String output = runner.build().getOutput();
        assertTrue(output.contains("Configuration cache entry stored"), output);
        String replay = runner.build().getOutput();
        assertTrue(replay.contains("Reusing configuration cache"), replay);
        assertTrue(output.contains("m3CodeqlSarif"));
        assertTrue(output.contains("m3CodeqlEvidence"));
        assertTrue(output.contains("m3SemgrepScan"));
        assertTrue(output.contains("m3ReviewAnalysis"));
        assertTrue(output.contains("m3NormalizeAnalyzerXml"));
        assertTrue(output.contains("m3PolyglotPlan"));
        assertTrue(output.contains("m3PolyglotRun"));
        assertTrue(output.contains("m3PolyglotProviders"));
        assertTrue(output.contains("m3EclipseModel"));
        assertTrue(output.contains("m3ProjectDbIngest"));
        assertTrue(output.contains("m3ProjectDbQuery"));
        assertTrue(output.contains("m3EclipseBuilders"));
        assertTrue(output.contains("m3EclipseBuildContext"));
        assertTrue(output.contains("m3EclipseBuildHandoff"));
        assertTrue(output.contains("m3EclipseReconcile"));
        assertTrue(output.contains("m3EclipseJdtFacts"));
        assertTrue(output.contains("m3EclipseCdtFacts"));
        assertTrue(output.contains("m3EclipseCargoFacts"));
        assertTrue(output.contains("m3EclipseLanguageFacts"));
        assertTrue(output.contains("m3IdeDeltaExport"));
        assertTrue(output.contains("m3IdeDeltaReview"));
        assertTrue(output.contains("m3PreBuildAdvice"));
        assertTrue(output.contains("m3PostBuildAdvice"));
        assertTrue(output.contains("m3CodeAgent"));
    }
}
