/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.util.List;

/** Java 17, no Gradle dependency: original argv and Maven #149 cache contract. */
public final class M3PolyglotCacheArgsContractMain {
    private M3PolyglotCacheArgsContractMain() {}

    public static void main(String[] ignored) {
        File engine = new File("maven-engine.jar");
        File root = new File("project with spaces");
        File manifest = new File(root, "m3-build.tsv");
        File cache = new File("external cache with spaces");
        List<String> original = M3PolyglotCommands.command(
                "java", engine, root, manifest, "all", "run", 900, true, false,
                List.of(), "file");
        check(!original.contains("--build-cache"), "old overload and argv changed");
        List<String> selected = M3PolyglotCommands.command(
                "java", engine, root, manifest, "all", "run", 900, true, false,
                List.of(), "file", cache);
        check(selected.subList(selected.size() - 2, selected.size()).equals(
                List.of("--build-cache", cache.getAbsolutePath())), "cache argv must be literal");
        check(selected.contains("--state-backend") && selected.contains("--incremental"),
                "state flags lost with cache");
        List<String> cacheOnly = M3PolyglotCommands.command(
                "java", engine, root, manifest, "all", "run", 900, false, false,
                List.of(), "legacy", cache);
        check(cacheOnly.subList(cacheOnly.size() - 2, cacheOnly.size()).equals(
                List.of("--build-cache", cache.getAbsolutePath())), "cache requires implicit incremental");
        refuse(() -> M3PolyglotCommands.command(
                "java", engine, root, manifest, "all", "plan", 900, false, false,
                List.of(), "legacy", cache));
        refuse(() -> M3PolyglotCommands.command(
                "java", engine, root, manifest, "all", "run", 900, false, false,
                List.of(), "sqlite", cache));
        System.out.println("PASS: Gradle adapter forwards explicit Maven #149 local cache with old signatures intact");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void refuse(Runnable action) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("unqualified cache option was accepted");
    }
}
