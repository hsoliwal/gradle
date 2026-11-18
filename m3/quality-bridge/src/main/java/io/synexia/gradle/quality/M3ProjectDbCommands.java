/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Command-only adapter to Maven's original project database and RAG CLI.
 * Gradle neither copies the project database nor implements its storage.
 */
public final class M3ProjectDbCommands {
    public static final String MAIN_CLASS = "org.apache.maven.plugins.projectdb.ProjectDbCli";
    private static final int MAX_QUESTION = 2048;
    private M3ProjectDbCommands() {}

    /** Immutable no-shell argv. Each dependency is installed and SHA-pinned by the task. */
    public static List<String> command(String javaExecutable, File projectDbJar, File polyglotJar,
            List<File> additionalJars, File workspace, File store, String mode, String question,
            int limit, File buildLog) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(projectDbJar, "projectDbJar");
        Objects.requireNonNull(polyglotJar, "polyglotJar");
        Objects.requireNonNull(additionalJars, "additionalJars");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(store, "store");
        if (!("query".equals(mode) || "ingest".equals(mode))) {
            throw new IllegalArgumentException("M3 project DB requires ingest or query");
        }
        if ("query".equals(mode) && (question == null || question.isBlank()
                || question.length() > MAX_QUESTION || !question.equals(question.trim())
                || question.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("M3 project DB question must be bounded text");
        }
        if (limit < 1 || limit > 50 || ("query".equals(mode) && buildLog != null)) {
            throw new IllegalArgumentException("M3 project DB limit or log is invalid");
        }
        List<File> jars = new ArrayList<>(additionalJars.size() + 1);
        jars.add(polyglotJar);
        jars.addAll(additionalJars);
        List<String> argv = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8", "-cp",
                M3PolyglotCommands.classpath(projectDbJar, jars), MAIN_CLASS, mode,
                workspace.getAbsolutePath()));
        if ("query".equals(mode)) argv.add(question);
        argv.add("--store");
        argv.add(store.getAbsolutePath());
        if ("query".equals(mode)) {
            argv.add("--k");
            argv.add(Integer.toString(limit));
            argv.add("--json");
        } else if (buildLog != null) {
            argv.add("--build-log");
            argv.add(buildLog.getAbsolutePath());
        }
        return List.copyOf(argv);
    }
}
