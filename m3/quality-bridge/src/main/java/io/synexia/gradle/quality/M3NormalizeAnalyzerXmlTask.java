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
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * Opt-in original XML or SARIF report import into the shared Maven/Gradle v1
 * diagnostics format. Runs only a separately built, explicitly digest-pinned
 * pure-Java adapter; does not run, download or configure analyzers.
 */
@UntrackedTask(because = "Original analyzer execution and provenance are not owned by this task")
public abstract class M3NormalizeAnalyzerXmlTask extends DefaultTask {
    private static final long MAX_JAR_BYTES = 64 * 1024 * 1024L;

    @Input
    public abstract Property<String> getJavaExecutable();

    @Input
    public abstract Property<String> getFormat();

    /** PARTIAL by default; the original analyzer owner must explicitly confirm COMPLETE. */
    @Input
    public abstract Property<String> getOriginalStatus();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAdapterJar();

    /** Pin the installed adapter binary, not merely its Maven group/artifact version. */
    @Input
    public abstract Property<String> getAdapterSha256();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getOriginalXml();

    /**
     * Additive format-neutral input alias. The original property remains the
     * only Gradle-tracked input and is kept for existing Checkstyle/PMD users.
     *
     * @return the same original report input property
     */
    @Internal
    public RegularFileProperty getOriginalReport() {
        return getOriginalXml();
    }

    @Internal
    public abstract DirectoryProperty getProjectRoot();

    @OutputFile
    public abstract RegularFileProperty getNormalizedReport();

    @OutputFile
    public abstract RegularFileProperty getNormalizedSha256();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void normalize() {
        Path root = getProjectRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path jar = getAdapterJar().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path original = getOriginalXml().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path report = getNormalizedReport().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path digestFile = getNormalizedSha256().get().getAsFile().toPath().toAbsolutePath().normalize();

        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !regular(jar, MAX_JAR_BYTES)
                || !regular(original, M3AnalysisV1.MAX_BYTES)) {
            throw new GradleException("Source project, installed adapter JAR and original XML are required");
        }
        String expectedJar = getAdapterSha256().get();
        if (!expectedJar.matches("[0-9a-f]{64}") || !sha256(jar, MAX_JAR_BYTES).equals(expectedJar)) {
            throw new GradleException("M3 analyzer adapter binary does not match its SHA-256 pin");
        }

        ensureOutput(root, report);
        ensureOutput(root, digestFile);
        List<String> command = M3AnalyzerAdapterCommands.normalize(
                getJavaExecutable().get(), jar.toFile(), root.toFile(), original.toFile(),
                report.toFile(), digestFile.toFile(), getFormat().get(), getOriginalStatus().get());
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
        });
        if (!regular(report, M3AnalysisV1.MAX_BYTES) || !regular(digestFile, 128)) {
            throw new GradleException("M3 normalizer did not publish its bounded report and digest");
        }
        try {
            String digest = Files.readString(digestFile).strip();
            if (!digest.matches("[0-9a-f]{64}")
                    || !sha256(report, M3AnalysisV1.MAX_BYTES).equals(digest)) {
                throw new GradleException("M3 normalizer output digest mismatch");
            }
            var normalized = M3AnalysisV1.parse(Files.readAllBytes(report));
            if (!normalized.status().name().equals(getOriginalStatus().get())) {
                throw new GradleException("M3 normalizer changed the original analyzer status");
            }
            getLogger().lifecycle("M3 original XML normalized: status={}, findings={}, sha256={}",
                    normalized.status(), normalized.size(), digest);
        } catch (IOException malformed) {
            throw new GradleException("Invalid M3 normalized report or digest", malformed);
        }
    }

    private static void ensureOutput(Path root, Path target) {
        if (target.equals(root) || !target.startsWith(root)
                || Files.isSymbolicLink(target) || Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new GradleException("M3 normalized outputs must stay inside the approved project root");
        }
        try {
            // Check pre-existing ancestors before making any directory,
            // then check again for the original tool's output boundary.
            rejectSymlinks(root, target.getParent());
            Files.createDirectories(target.getParent());
            rejectSymlinks(root, target.getParent());
        } catch (IOException cannotCreate) {
            throw new GradleException("M3 analyzer output directory unavailable", cannotCreate);
        }
    }

    private static void rejectSymlinks(Path root, Path parent) {
        for (Path at = parent; at != null && at.startsWith(root); at = at.getParent()) {
            if (Files.isSymbolicLink(at)) {
                throw new GradleException("M3 analyzer output cannot traverse symlinks");
            }
        }
    }

    private static boolean regular(Path file, long limit) {
        try {
            return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= limit;
        } catch (IOException failure) {
            return false;
        }
    }

    private static String sha256(Path file, long bound) {
        if (!regular(file, bound)) {
            throw new GradleException("M3 analyzer input is missing or beyond its byte budget");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new GradleException("Cannot hash M3 analyzer file", failure);
        }
    }
}
