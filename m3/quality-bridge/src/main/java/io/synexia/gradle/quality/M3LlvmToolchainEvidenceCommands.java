/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;
import java.util.Objects;

/**
 * Direct argv proxy for the original Maven #210 LLVM toolchain evidence CLI.
 * Gradle does not parse, hash or certify LLVM executables or TSV contents.
 */
public final class M3LlvmToolchainEvidenceCommands {
    public static final String MAIN_CLASS = "io.synexia.maven.llvm.LlvmToolchainEvidence";

    private M3LlvmToolchainEvidenceCommands() {}

    public static List<String> command(String javaExecutable, File originalProvider,
            File llvmHome, File manifest, String mode, String selectedTools) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(originalProvider, "originalProvider");
        Objects.requireNonNull(llvmHome, "llvmHome");
        Objects.requireNonNull(manifest, "manifest");
        String jar = originalProvider.getAbsolutePath();
        if (!originalProvider.getName().endsWith(".jar") || jar.contains(File.pathSeparator)) {
            throw new IllegalArgumentException("M3 LLVM evidence requires a dedicated provider JAR");
        }
        if (!"scan".equals(mode) && !"verify".equals(mode)) {
            throw new IllegalArgumentException("M3 LLVM evidence mode must be scan or verify");
        }
        List<String> args = List.of(java, "-Dfile.encoding=UTF-8", "-cp", jar,
                MAIN_CLASS, mode, llvmHome.getAbsolutePath(), manifest.getAbsolutePath());
        if ("verify".equals(mode)) return args;
        if (selectedTools == null || !selectedTools.matches("[A-Za-z0-9+._-]+(,[A-Za-z0-9+._-]+)*")) {
            throw new IllegalArgumentException("M3 LLVM evidence requires comma-separated tool names");
        }
        var withSelection = new java.util.ArrayList<>(args);
        withSelection.add(selectedTools);
        return List.copyOf(withSelection);
    }
}
