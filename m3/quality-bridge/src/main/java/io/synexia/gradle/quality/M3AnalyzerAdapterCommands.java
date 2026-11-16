/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;
import java.util.Objects;

/** A non-shell Java argv plan for an explicitly installed M3 XML adapter JAR. */
public final class M3AnalyzerAdapterCommands {
    private M3AnalyzerAdapterCommands() {}

    public static List<String> normalize(
            String javaExecutable, File adapterJar, File projectRoot, File originalXml,
            File normalizedReport, File digestFile, String format, String originalStatus) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(adapterJar, "adapterJar");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(originalXml, "originalXml");
        Objects.requireNonNull(normalizedReport, "normalizedReport");
        Objects.requireNonNull(digestFile, "digestFile");
        if (!adapterJar.getName().endsWith(".jar")
                || !("CHECKSTYLE".equals(format) || "PMD".equals(format) || "SPOTBUGS".equals(format))
                || !("COMPLETE".equals(originalStatus) || "PARTIAL".equals(originalStatus)
                        || "FAILED".equals(originalStatus))) {
            throw new IllegalArgumentException("explicit analyzer adapter, format and source status required");
        }
        return List.of(
                java, "-cp", adapterJar.getAbsolutePath(),
                "io.synexia.maven.analysis.NormalizeCli",
                format, originalStatus,
                projectRoot.getAbsolutePath(), originalXml.getAbsolutePath(),
                normalizedReport.getAbsolutePath(), digestFile.getAbsolutePath());
    }
}
