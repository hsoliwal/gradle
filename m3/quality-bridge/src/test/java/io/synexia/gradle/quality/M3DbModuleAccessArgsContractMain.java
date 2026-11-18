/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Dependency-free Java 17 proof of the exact M3JDK database launcher argv. */
public final class M3DbModuleAccessArgsContractMain {
    private M3DbModuleAccessArgsContractMain() { }

    public static void main(String[] args) {
        File engine = new File("engine.jar");
        File workspace = new File("fixture with spaces");
        File manifest = new File(workspace, "m3-build.tsv");
        List<String> original = command(engine, workspace, manifest, "run", true, "m3db", "none");
        check(original.get(1).equals("-cp"), "old Java launcher ABI changed");
        check(original.equals(M3PolyglotCommands.command("java", engine, workspace, manifest,
                "verify", "run", 900, true, false, List.of(), "m3db", null)),
                "new default must be byte-for-byte compatible with previous signatures");

        Map<String, List<String>> options = Map.of(
                "current-java-base", List.of("--add-exports=java.base/jdk.m3.db=ALL-UNNAMED"),
                "current-named", List.of("--add-modules=jdk.m3.db",
                        "--add-exports=jdk.m3.db/jdk.m3.db=ALL-UNNAMED"),
                "legacy-java-base", List.of("--add-exports=java.base/jdk.m3.indexdb=ALL-UNNAMED"),
                "legacy-named", List.of("--add-modules=jdk.m3.indexdb",
                        "--add-exports=jdk.m3.indexdb/jdk.m3.indexdb=ALL-UNNAMED"));
        for (Map.Entry<String, List<String>> selected : options.entrySet()) {
            List<String> command = command(engine, workspace, manifest, "run", true,
                    "m3db", selected.getKey());
            check(command.subList(1, selected.getValue().size() + 1).equals(selected.getValue()),
                    "incorrect fixed Java module args for " + selected.getKey());
            check(command.subList(selected.getValue().size() + 1, command.size())
                    .equals(original.subList(1, original.size())),
                    "Maven CLI argument vector changed for " + selected.getKey());
        }

        for (String invalid : List.of("", "auto", "Current-Named", "--javaagent=unsafe.jar",
                "current-java-base --add-opens=java.base/java.lang", "current-named\n--add-modules=ALL-SYSTEM")) {
            refuse(() -> command(engine, workspace, manifest, "run", true, "m3db", invalid));
        }
        refuse(() -> command(engine, workspace, manifest, "run", true, "m3db", null));
        refuse(() -> command(engine, workspace, manifest, "plan", false, "legacy", "current-java-base"));
        refuse(() -> command(engine, workspace, manifest, "run", false, "m3db", "current-java-base"));
        for (String other : List.of("embedded", "legacy", "file", "sqlite", "h2", "ext-local")) {
            refuse(() -> command(engine, workspace, manifest, "run", true, other, "current-java-base"));
        }
        check(command(engine, workspace, manifest, "run", true, "native", "current-java-base")
                .contains("--state-backend"), "native alias was lost");
        System.out.println("PASS: M3JDK current/legacy module launcher and refusal contract");
    }

    private static List<String> command(File engine, File workspace, File manifest,
            String mode, boolean incremental, String backend, String access) {
        return M3PolyglotCommands.command("java", engine, workspace, manifest, "verify", mode,
                900, incremental, false, List.of(), backend, null, access);
    }

    private static void refuse(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unsupported M3JDK module mode was admitted");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
