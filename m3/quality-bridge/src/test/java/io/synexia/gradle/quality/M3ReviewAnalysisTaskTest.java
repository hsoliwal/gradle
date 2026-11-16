/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class M3ReviewAnalysisTaskTest {
    @TempDir
    Path project;

    @Test
    void explicitReviewReadsPinnedMavenReportWithoutRunningAnAnalyzer() throws Exception {
        configure("COMPLETE", false, false, false);
        var result = run(true);
        assertTrue(result.getOutput().contains("M3 analyzer claims: status=COMPLETE, findings=1, errors=1"));
    }

    @Test
    void optedInGradleProblemsApiAcceptsOriginalAnalyzerFindingsWithoutFixingSources() throws Exception {
        configure("COMPLETE", false, true, false);
        var result = run(true);
        assertTrue(result.getOutput().contains("M3 analyzer claims: status=COMPLETE"));
    }

    @Test
    void aPartialAnalyzerClaimIsNotSilentlyPromotedToComplete() throws Exception {
        configure("PARTIAL", false, false, false);
        var result = run(true);
        assertTrue(result.getOutput().contains("M3 analyzer claims: status=PARTIAL"));
    }

    @Test
    void optedInSeverityGateRejectsAlreadyReportedError() throws Exception {
        configure("COMPLETE", true, false, false);
        var result = run(false);
        assertTrue(result.getOutput().contains("Opt-in M3 analysis gate rejected original analyzer claims"));
    }

    @Test
    void staleOrTamperedReportDigestFailsClosed() throws Exception {
        configure("PARTIAL", false, false, true);
        var result = run(false);
        assertTrue(result.getOutput().contains("M3 analysis report digest does not match"));
    }

    private void configure(String status, boolean failOnErrors, boolean publishProblems, boolean wrongHash)
            throws IOException, NoSuchAlgorithmException {
        String message = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("unused import".getBytes(StandardCharsets.UTF_8));
        String content = "M3-STATIC-ANALYSIS-V1\nstatus\t" + status
                + "\nrule\tseverity\tpath\tline\tcolumn\tmessage64\n"
                + "OriginalRule\tERROR\tsrc/Example.java\t1\t2\t" + message + "\n";
        Files.writeString(project.resolve("analysis.tsv"), content);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
        if (wrongHash) {
            hash = "0".repeat(64);
        }
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name='m3-report-fixture'\n");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n"
                + "tasks.named('m3ReviewAnalysis') {\n"
                + "  reportFile.set(layout.projectDirectory.file('analysis.tsv'))\n"
                + "  expectedSha256.set('" + hash + "')\n"
                + "  failOnReportedErrors.set(" + failOnErrors + ")\n"
                + "  publishProblems.set(" + publishProblems + ")\n"
                + "}\n");
    }

    private org.gradle.testkit.runner.BuildResult run(boolean success) {
        var runner = GradleRunner.create()
                .withProjectDir(project.toFile())
                .withPluginClasspath()
                .withArguments("m3ReviewAnalysis", "--stacktrace");
        return success ? runner.build() : runner.buildAndFail();
    }
}
