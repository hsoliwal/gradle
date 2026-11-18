/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Dependency-free Java 17 contract for Maven #184's original CLI argv. */
public final class M3ProjectDbCommandsContractMain {
    private M3ProjectDbCommandsContractMain() {}

    public static void main(String[] ignored) {
        File db = new File("project-db.jar");
        File polyglot = new File("m3-polyglot.jar");
        File provider = new File("provider jar.jar");
        File root = new File("project with spaces");
        File store = new File(root, "build/m3/project-db");
        File log = new File(root, "build.log");

        List<String> ingest = M3ProjectDbCommands.command("java", db, polyglot,
                List.of(provider), root, store, "ingest", "", 5, log);
        require(ingest.get(0).equals("java"), "original Java executable");
        require(ingest.get(1).equals("-Dfile.encoding=UTF-8"), "reproducible UTF-8 output");
        require(ingest.get(3).equals(db.getAbsolutePath() + File.pathSeparator
                + polyglot.getAbsolutePath() + File.pathSeparator + provider.getAbsolutePath()),
                "deterministic separately pinned runtime classpath");
        require(ingest.get(4).equals(M3ProjectDbCommands.MAIN_CLASS), "Maven CLI main class");
        require(ingest.subList(5, 7).equals(List.of("ingest", root.getAbsolutePath())),
                "Maven ingest root and verb");
        require(ingest.subList(7, ingest.size()).equals(List.of("--store",
                store.getAbsolutePath(), "--build-log", log.getAbsolutePath())),
                "original ingest option order");

        List<String> query = M3ProjectDbCommands.command("java", db, polyglot,
                List.of(), root, store, "query", "why build failed", 8, null);
        require(query.subList(5, query.size()).equals(List.of("query", root.getAbsolutePath(),
                "why build failed", "--store", store.getAbsolutePath(), "--k", "8", "--json")),
                "deterministic JSON provenance query");
        require(!query.contains("--build-log"), "query never ingests sources");
        require(!query.contains("--approve-rewrite-apply"), "query cannot mutate sources");
        require(List.of("java", "mvn", "sh").stream().filter(query::contains).count() == 1,
                "no accidental child Maven or shell process");

        refuse(() -> invoke(db, polyglot, root, store, "mcp", "", 5, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "", 5, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "bad\nline", 5, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "bad ", 5, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "word".repeat(513), 5, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "what", 0, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "what", 51, null));
        refuse(() -> invoke(db, polyglot, root, store, "query", "what", 5, log));
        System.out.println("PASS: pinned Maven project DB argv, ingest/query isolation and refusals");
    }

    private static void invoke(File db, File polyglot, File root, File store,
            String mode, String question, int limit, File log) {
        M3ProjectDbCommands.command("java", db, polyglot, List.of(),
                root, store, mode, question, limit, log);
    }

    private static void require(boolean valid, String label) {
        if (!valid) throw new AssertionError(label);
    }

    private static void refuse(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("unqualified Maven project DB command was accepted");
    }
}
