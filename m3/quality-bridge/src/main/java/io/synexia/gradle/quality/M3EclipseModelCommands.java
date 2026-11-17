/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Invoke the Maven-owned Eclipse Core/JDT/CDT/PyDev/Rust project descriptor
 * reader without implementing an alternative Gradle or Eclipse parser.
 */
public final class M3EclipseModelCommands {
    public static final String MAIN_CLASS = "io.synexia.maven.polyglot.EclipseModelCli";

    private M3EclipseModelCommands() {}

    public static List<String> inspect(String javaExecutable, File engineJar, File eclipseProject) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(eclipseProject, "eclipseProject");
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", engineJar.getAbsolutePath(), MAIN_CLASS,
                eclipseProject.getAbsolutePath());
    }
}
