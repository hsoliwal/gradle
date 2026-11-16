/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Original CLI argv plans: no shell, model-selected command or implicit installation. */
public final class M3QualityCommands {
    private M3QualityCommands() {}

    public static List<String> codeql(String executable, File database, String suite, File sarif, int threads) {
        String command = executable(executable);
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(sarif, "sarif");
        if (!sarif.getName().endsWith(".sarif") || threads < 1) {
            throw new IllegalArgumentException("SARIF destination and positive thread count required");
        }
        List<String> result = new ArrayList<>(List.of(
                command, "database", "analyze", "--format=sarifv2.1.0",
                "--output=" + sarif.getAbsolutePath(), "--threads=" + threads,
                "--", database.getAbsolutePath()));
        if (suite != null && !suite.isBlank()) {
            if (suite.startsWith("-") || suite.indexOf('\n') >= 0 || suite.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("invalid CodeQL suite selector");
            }
            result.add(suite);
        }
        return List.copyOf(result);
    }

    /** The original M3 CodeQL runner (not a substitute query implementation). */
    public static List<String> m3Codeql(String javaExecutable, File jar, File codeqlRepository, File database,
            File evidenceDirectory, String codeqlExecutable, int threads, int ramMb, int timeoutSeconds) {
        String java = executable(javaExecutable);
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(codeqlRepository, "codeqlRepository");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(evidenceDirectory, "evidenceDirectory");
        String codeql = executable(codeqlExecutable);
        if (threads < 1 || ramMb < 512 || timeoutSeconds < 1) {
            throw new IllegalArgumentException("invalid M3 CodeQL execution budget");
        }
        return List.of(java, "-jar", jar.getAbsolutePath(), "analyze",
                "--root", codeqlRepository.getAbsolutePath(),
                "--database", database.getAbsolutePath(),
                "--output", evidenceDirectory.getAbsolutePath(),
                "--codeql", codeql,
                "--threads", Integer.toString(threads),
                "--ram", Integer.toString(ramMb),
                "--timeout-seconds", Integer.toString(timeoutSeconds), "--execute");
    }

    /** Explicit local rules only. Findings cause a nonzero Semgrep exit status. */
    public static List<String> semgrep(String executable, File rules, File source, File report) {
        String command = executable(executable);
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(report, "report");
        if (!report.getName().endsWith(".json")) {
            throw new IllegalArgumentException("Semgrep report must be JSON");
        }
        return List.of(command, "scan", "--metrics=off", "--config", rules.getAbsolutePath(),
                "--json", "--output", report.getAbsolutePath(), "--error", source.getAbsolutePath());
    }

    public static String executable(String value) {
        if (value == null || value.isBlank() || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("an explicit executable is required");
        }
        return value;
    }
}
