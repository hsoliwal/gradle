/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Literal Java argv for Maven's original Java 21 AST/atom fingerprint producer.
 * <p>
 * Gradle does not parse, cache or reinterpret the producer's semantic facts.
 */
public final class M3MachineFingerprintCommands {
    public static final String MAIN_CLASS = "io.synexia.maven.smart.MachineFingerprintMain";

    private M3MachineFingerprintCommands() {}

    /**
     * Builds the sole original producer invocation after validating admission flags.
     *
     * @return immutable, shell-free Java argument vector
     */
    public static List<String> command(String javaExecutable, File engineJar,
            File project, File build, File source, File preparation, File atomPattern,
            int workers, boolean attributeTypes, boolean analyzeFlow, String moduleDb,
            List<File> providerJars) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(build, "build");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(preparation, "preparation");
        Objects.requireNonNull(atomPattern, "atomPattern");
        if (workers < 1 || workers > 8 || (analyzeFlow && !attributeTypes)
                || !("auto".equals(moduleDb) || "off".equals(moduleDb)
                        || "required".equals(moduleDb))) {
            throw new IllegalArgumentException("invalid Maven M3 machine fingerprint option");
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp",
                M3PolyglotCommands.classpath(engineJar, providerJars), MAIN_CLASS,
                "--project-dir", project.getAbsolutePath(),
                "--build-dir", build.getAbsolutePath(),
                "--source-root", source.getAbsolutePath(),
                "--preparation-report", preparation.getAbsolutePath(),
                "--atom-pattern-manifest", atomPattern.getAbsolutePath(),
                "--workers", Integer.toString(workers),
                "--attribute-types", Boolean.toString(attributeTypes),
                "--analyze-flow", Boolean.toString(analyzeFlow),
                "--module-db", moduleDb);
    }
}
