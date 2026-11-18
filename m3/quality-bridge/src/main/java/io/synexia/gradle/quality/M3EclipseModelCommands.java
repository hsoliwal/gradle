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

    /** Original Corrosion/Cargo locked/offline metadata; never runs Cargo here. */
    public static List<String> cargo(String javaExecutable, File engineJar, File cargoFacts,
            File eclipseHandoff, List<File> providers) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(cargoFacts, "cargoFacts");
        Objects.requireNonNull(eclipseHandoff, "eclipseHandoff");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "cargo-facts", cargoFacts.getAbsolutePath(), eclipseHandoff.getAbsolutePath());
    }

    /** Original CDT scanner and per-configuration source-owner claims. */
    public static List<String> cdt(String javaExecutable, File engineJar, File cdtFacts,
            File eclipseHandoff, List<File> providers) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(cdtFacts, "cdtFacts");
        Objects.requireNonNull(eclipseHandoff, "eclipseHandoff");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "cdt-facts", cdtFacts.getAbsolutePath(), eclipseHandoff.getAbsolutePath());
    }

    /** Original JDT compiler claims, matched to an exact Eclipse invocation. */
    public static List<String> jdt(String javaExecutable, File engineJar, File jdtFacts,
            File eclipseHandoff, List<File> providers) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(jdtFacts, "jdtFacts");
        Objects.requireNonNull(eclipseHandoff, "eclipseHandoff");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "jdt-facts", jdtFacts.getAbsolutePath(), eclipseHandoff.getAbsolutePath());
    }

    /** Compare a pinned Eclipse handoff with the original current project declarations. */
    public static List<String> reconcile(String javaExecutable, File engineJar, File eclipseProject,
            File packet, List<File> providers) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(eclipseProject, "eclipseProject");
        Objects.requireNonNull(packet, "packet");
        Objects.requireNonNull(providers, "providers");
        StringBuilder classpath = new StringBuilder(engineJar.getAbsolutePath());
        for (File provider : providers) {
            classpath.append(File.pathSeparator).append(
                    Objects.requireNonNull(provider, "provider").getAbsolutePath());
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath.toString(),
                MAIN_CLASS, "reconcile", eclipseProject.getAbsolutePath(), packet.getAbsolutePath());
    }

    /** Review one m2e invocation containing its selected configuration and source delta. */
    public static List<String> handoff(String javaExecutable, File engineJar, File packet,
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
                MAIN_CLASS, "handoff", packet.getAbsolutePath());
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
