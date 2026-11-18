/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Java 17 no-shell argument contract for the source-owned Maven #210 CLI. */
public final class M3LlvmToolchainEvidenceArgsContractMain {
    private M3LlvmToolchainEvidenceArgsContractMain() {}

    public static void main(String[] args) {
        File jar = new File("vendor/original llvm tools.jar");
        File home = new File("llvm home with spaces");
        File manifest = new File("build/reports/m3/llvm-toolchain.tsv");

        List<String> scan = M3LlvmToolchainEvidenceCommands.command("java", jar, home,
                manifest, "scan", "clang++,opt");
        check(scan.equals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", jar.getAbsolutePath(),
                        M3LlvmToolchainEvidenceCommands.MAIN_CLASS, "scan",
                        home.getAbsolutePath(), manifest.getAbsolutePath(), "clang++,opt")),
                "scan preserves exact source CLI argv, native paths and tool list");

        List<String> verify = M3LlvmToolchainEvidenceCommands.command("/usr/bin/java", jar, home,
                manifest, "verify", "ignored");
        check(verify.equals(List.of("/usr/bin/java", "-Dfile.encoding=UTF-8", "-cp", jar.getAbsolutePath(),
                        M3LlvmToolchainEvidenceCommands.MAIN_CLASS, "verify",
                        home.getAbsolutePath(), manifest.getAbsolutePath())),
                "verify does not invent tool or cache switches");

        reject(() -> M3LlvmToolchainEvidenceCommands.command("java", jar, home, manifest,
                "run", "opt"));
        reject(() -> M3LlvmToolchainEvidenceCommands.command("java", jar, home, manifest,
                "scan", ""));
        reject(() -> M3LlvmToolchainEvidenceCommands.command("java", jar, home, manifest,
                "scan", "opt,,llvm-as"));
        reject(() -> M3LlvmToolchainEvidenceCommands.command("java", jar, home, manifest,
                "scan", "opt\n--execute"));
        reject(() -> M3LlvmToolchainEvidenceCommands.command("java", new File("not-a-jar"),
                home, manifest, "scan", "opt"));
        reject(() -> M3LlvmToolchainEvidenceCommands.command(" java ", jar, home,
                manifest, "verify", "opt"));

        System.out.println("PASS: Maven #210 LLVM toolchain evidence CLI argv/refusal contract");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid LLVM evidence option accepted");
    }
}
