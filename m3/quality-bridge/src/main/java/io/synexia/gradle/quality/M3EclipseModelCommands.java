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
        return inspect(javaExecutable, engineJar, eclipseProject, List.of());
    }

    public static List<String> inspect(String javaExecutable, File engineJar,
            File eclipseProject, List<File> providers) {
        return command(javaExecutable, engineJar, eclipseProject, providers, false);
    }

    public static List<String> owners(String javaExecutable, File engineJar, File eclipseProject) {
        return owners(javaExecutable, engineJar, eclipseProject, List.of());
    }

    public static List<String> owners(String javaExecutable, File engineJar,
            File eclipseProject, List<File> providers) {
        return command(javaExecutable, engineJar, eclipseProject, providers, true);
    }

    /** Review a caller-supplied Eclipse source-owner configuration packet. */
    public static List<String> context(String javaExecutable, File engineJar, File packet,
            List<File> providers) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "context", packet.getAbsolutePath());
    }

    private static List<String> command(String javaExecutable, File engineJar,
            File eclipseProject, List<File> providers, boolean owners) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(eclipseProject, "eclipseProject");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return owners ? List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "owners", eclipseProject.getAbsolutePath())
                : List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                        MAIN_CLASS, eclipseProject.getAbsolutePath());
    }

}
