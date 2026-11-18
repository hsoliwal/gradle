/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Dependency-free Java 17 contract gate for the Maven portable-state argv. */
public final class M3PortableStateArgsContractMain {
    private M3PortableStateArgsContractMain() {}

    public static void main(String[] args) {
        File jar = new File("engine.jar");
        File root = new File("project");
        File manifest = new File("project/m3-build.tsv");
        List<String> original = M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, false);
        check(!original.contains("--state-backend"), "legacy overload changed");

        for (String backend : List.of("embedded", "file", "m3db", "native", "sqlite", "h2",
                "ext-operator-store")) {
            List<String> command = invoke(jar, root, manifest, "run", true, backend);
            check(command.subList(command.size() - 3, command.size())
                    .equals(List.of("--incremental", "--state-backend", backend)),
                    "incorrect Maven argv for " + backend);
        }

        for (String backend : List.of("derby", "ext-", "ext-UPPER", "ext-unsafe/path",
                "ext-" + "a".repeat(49), "jdbc:sqlite:example")) {
            refuse(() -> invoke(jar, root, manifest, "run", true, backend));
        }
        refuse(() -> invoke(jar, root, manifest, "run", true, null));
        refuse(() -> invoke(jar, root, manifest, "plan", false, "sqlite"));
        refuse(() -> invoke(jar, root, manifest, "plan", false, "embedded"));
        refuse(() -> invoke(jar, root, manifest, "run", false, "ext-operator-store"));
        refuse(() -> invoke(jar, root, manifest, "run", false, "native"));
        System.out.println("PASS: Maven portable backend argv parity and refusals");
    }

    private static List<String> invoke(File jar, File root, File manifest,
            String mode, boolean incremental, String backend) {
        return M3PolyglotCommands.command("java", jar, root, manifest,
                "all", mode, 900, incremental, false, List.of(), backend);
    }

    private static void refuse(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid backend admission was not refused");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
