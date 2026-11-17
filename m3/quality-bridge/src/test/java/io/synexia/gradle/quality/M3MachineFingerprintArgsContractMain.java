/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Dependency-free Java 17 test for the original Maven Java 21 producer argv. */
public final class M3MachineFingerprintArgsContractMain {
    private M3MachineFingerprintArgsContractMain() {}

    public static void main(String[] args) {
        File jar = new File("smart-builder.jar");
        File root = new File("project");
        File source = new File(root, "src/main/java");
        File build = new File(root, "build");
        File preparation = new File(root, "receipts/preparation.json");
        File pattern = new File(root, "receipts/atom-pattern.tsv");
        List<String> command = M3MachineFingerprintCommands.command("java", jar, root,
                build, source, preparation, pattern, 4, true, true, "required", List.of());
        check(command.get(4).equals(M3MachineFingerprintCommands.MAIN_CLASS), "wrong Maven owner");
        check(command.subList(command.size() - 8, command.size())
                .equals(List.of("--workers", "4", "--attribute-types", "true",
                        "--analyze-flow", "true", "--module-db", "required")), "invalid flags");
        check(command.contains(source.getAbsolutePath()), "lost source scope");
        check(command.contains(preparation.getAbsolutePath()), "lost preparation receipt");
        check(command.contains(pattern.getAbsolutePath()), "lost atom/pattern receipt");
        check(!command.contains("bash") && !command.contains("sh"), "shell not allowed");
        refuse(() -> invoke(jar, root, source, build, preparation, pattern, 0, false, false, "auto"));
        refuse(() -> invoke(jar, root, source, build, preparation, pattern, 9, false, false, "auto"));
        refuse(() -> invoke(jar, root, source, build, preparation, pattern, 1, false, true, "auto"));
        refuse(() -> invoke(jar, root, source, build, preparation, pattern, 1, true, false, "m3db"));
        refuse(() -> invoke(jar, root, source, build, preparation, pattern, 1, true, false, null));
        System.out.println("PASS: original Maven AST fingerprint CLI argv and refusals");
    }

    private static List<String> invoke(File jar, File root, File source, File build,
            File preparation, File pattern, int workers, boolean attributed,
            boolean flow, String mode) {
        return M3MachineFingerprintCommands.command("java", jar, root, build, source,
                preparation, pattern, workers, attributed, flow, mode, List.of());
    }

    private static void refuse(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid Maven fingerprint option was accepted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
