/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Java 17-only source boundary for Maven #160/#178 compiler diagnostics. */
public final class M3CompilerDiagnosticsArgsContractMain {
    private M3CompilerDiagnosticsArgsContractMain() {}

    public static void main(String[] args) {
        for (String format : List.of("ECJ", "JAVAC")) {
            List<String> argv = command(format, "PARTIAL");
            check(argv.size() == 10, "original CLI shape changed");
            check(argv.get(3).equals("io.synexia.maven.analysis.NormalizeCli"), "new parser owner");
            check(argv.get(4).equals(format) && argv.get(5).equals("PARTIAL"), "compiler mode lost");
            check(!argv.contains("bash") && !argv.contains("sh") && !argv.contains("mvn"), "shell command");
            try {
                argv.add("--run-compiler");
                throw new AssertionError("original argv is mutable");
            } catch (UnsupportedOperationException expected) {
                // The Gradle adapter cannot append implicit compilation flags.
            }
        }
        for (String invalid : List.of("", "ECJ_LOG", "javac", "KOTLINC", "DIAGNOSTICS")) {
            refuse(() -> command(invalid, "PARTIAL"));
        }
        refuse(() -> command("JAVAC", "SUCCESS"));
        refuse(() -> command("ECJ", ""));
        System.out.println("PASS: Maven ECJ/javac CLI argv, status and refusal contract");
    }

    private static List<String> command(String format, String status) {
        return M3AnalyzerAdapterCommands.normalize("java", new File("adapter.jar"), new File("project"),
                new File("project/reports/original.log"), new File("project/build/compiler-analysis.tsv"),
                new File("project/build/compiler-analysis.sha256"), format, status);
    }

    private static void refuse(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("unknown or unqualified compiler report mode accepted");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
