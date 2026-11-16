/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.problems.Problems;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/**
 * Opt-in, read-only Gradle consumer of source-pinned M3 normalized analyzer
 * reports. The digest pins bytes only; the original analyzer remains the
 * authority for execution, source coverage and severity interpretation.
 */
@UntrackedTask(because = "External report provenance is independent of Gradle's input tracking")
public abstract class M3ReviewAnalysisTask extends DefaultTask {
    private static final int MAX_PUBLISHED_PROBLEMS = 512;

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getReportFile();

    @Input
    public abstract Property<String> getExpectedSha256();

    @Input
    public abstract Property<Boolean> getPublishProblems();

    @Input
    public abstract Property<Boolean> getFailOnReportedErrors();

    /** Previous report is used only when explicitly configured. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBaselineReportFile();

    @Optional
    @Input
    public abstract Property<String> getBaselineSha256();

    @Optional
    @Input
    public abstract Property<String> getBaselineProducer();

    @Optional
    @Input
    public abstract Property<String> getCurrentProducer();

    @Input
    public abstract Property<Boolean> getFailOnIntroducedErrors();

    @Inject
    protected abstract Problems getProblems();

    @TaskAction
    public void review() {
        M3AnalysisV1.Snapshot report = verifiedReport(
                getReportFile().get().getAsFile().toPath(), getExpectedSha256().get());
        getLogger().lifecycle("M3 analyzer claims: status={}, findings={}, errors={}",
                report.status(), report.size(), report.errors());

        reviewOptionalBaseline(report);
        if (getPublishProblems().get()) {
            publishProblems(report);
        }
        if (getFailOnReportedErrors().get()
                && (report.status() != M3AnalysisV1.Status.COMPLETE || report.errors() > 0)) {
            throw new GradleException("Opt-in M3 analysis gate rejected original analyzer claims");
        }
    }

    /**
     * Atom: explicitly opt into a second, independently verified report.
     * The wire format has no embedded producer certificate, so both producer
     * coordinates must be supplied and match; this never authorizes reuse.
     */
    private void reviewOptionalBaseline(M3AnalysisV1.Snapshot current) {
        boolean hasReport = getBaselineReportFile().isPresent();
        boolean hasHash = getBaselineSha256().isPresent();
        boolean hasPreviousProducer = getBaselineProducer().isPresent();
        boolean hasCurrentProducer = getCurrentProducer().isPresent();
        if (hasReport != hasHash || hasReport != hasPreviousProducer || hasReport != hasCurrentProducer) {
            throw new GradleException("Baseline comparison requires both SHA-256 digests and producer identities");
        }
        if (!hasReport) {
            if (getFailOnIntroducedErrors().get()) {
                throw new GradleException("New-error gating requires an explicit analysis baseline");
            }
            return;
        }
        M3AnalysisV1.Snapshot earlier = verifiedReport(
                getBaselineReportFile().get().getAsFile().toPath(), getBaselineSha256().get());
        final M3AnalysisV1.Comparison comparison;
        try {
            comparison = M3AnalysisV1.compare(
                    getBaselineProducer().get(), earlier, getCurrentProducer().get(), current);
        } catch (IllegalArgumentException unrelated) {
            throw new GradleException("Incompatible original analyzer reports", unrelated);
        }
        if (!comparison.comparable()) {
            getLogger().warn("M3 analysis difference is unavailable: an original analyzer is not COMPLETE");
            if (getFailOnIntroducedErrors().get()) {
                throw new GradleException("Cannot enforce new-error gate on incomplete original analysis");
            }
            return;
        }
        getLogger().lifecycle("M3 analysis difference: unchanged={}, introduced={}, resolved={}, newErrors={}",
                comparison.unchanged(), comparison.introduced(), comparison.resolved(),
                comparison.introducedErrors());
        if (getFailOnIntroducedErrors().get() && comparison.introducedErrors() > 0) {
            throw new GradleException("Opt-in M3 new-error analysis gate rejected introduced findings");
        }
    }

    /** Atom: original file bytes, bounded no-follow admission and exact SHA-256. */
    private static M3AnalysisV1.Snapshot verifiedReport(Path path, String expected) {
        byte[] bytes = checkedReport(path);
        if (expected == null || !expected.matches("[0-9a-f]{64}") || !digest(bytes).equals(expected)) {
            throw new GradleException("M3 analysis report digest does not match the declared source receipt");
        }
        try {
            return M3AnalysisV1.parse(bytes);
        } catch (IOException invalid) {
            throw new GradleException("Invalid canonical M3 analyzer report", invalid);
        }
    }

    private void publishProblems(M3AnalysisV1.Snapshot report) {
        if (report.size() > MAX_PUBLISHED_PROBLEMS) {
            throw new GradleException("M3 analysis report exceeds Gradle Problems publication budget");
        }
        var group = getProblems().getGroups().getOthers().group("M3 analyzer evidence");
        var reporter = getProblems().getReporter();
        for (int index = 0; index < report.size(); index++) {
            M3AnalysisV1.Finding finding = report.entry(index);
            var id = group.problemId("Analyzer-reported " + finding.ruleId());
            reporter.report(id, spec -> spec.details(
                    finding.relativePath() + ":" + finding.line() + ":" + finding.column()
                            + " [" + finding.severity() + "] " + finding.message()));
        }
    }

    private static byte[] checkedReport(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(path) > M3AnalysisV1.MAX_BYTES) {
                throw new GradleException("M3 report must be a bounded, regular non-symlink file");
            }
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length > M3AnalysisV1.MAX_BYTES) {
                throw new GradleException("M3 report grew beyond the byte budget");
            }
            return bytes;
        } catch (IOException unreadable) {
            throw new GradleException("Cannot read original M3 analyzer report", unreadable);
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
