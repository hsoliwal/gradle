/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The original Maven CLI stays the only graph parser and executor. */
final class M3PolyglotCommandsTest {
    @TempDir
    Path temp;

    @Test
    void literalArgsPreserveSpacesAndExistingOriginalCliForms() {
        var jar = temp.resolve("engine with spaces.jar").toFile();
        var workspace = temp.resolve("project with spaces").toFile();
        var manifest = temp.resolve("project with spaces/m3-build.tsv").toFile();
        List<String> plan = M3PolyglotCommands.command(
                "java", jar, workspace, manifest, "all", "plan", 900, false, false);
        assertEquals("java", plan.get(0));
        assertEquals("-cp", plan.get(1));
        assertEquals(jar.getAbsolutePath(), plan.get(2));
        assertEquals(M3PolyglotCommands.MAIN_CLASS, plan.get(3));
        assertEquals("plan", plan.get(4));
        assertEquals(workspace.getAbsolutePath(), plan.get(5));
        assertEquals(manifest.getAbsolutePath(), plan.get(6));
        assertEquals(List.of("--timeout-seconds", "900"), plan.subList(8, 10));
        assertFalse(plan.contains("sh"));
        assertFalse(plan.contains("bash"));
        assertThrows(UnsupportedOperationException.class, () -> plan.add("--unsafe"));
    }

    @Test
    void onlyExplicitRunUsesIncrementalAndMutationApproval() {
        var jar = temp.resolve("engine.jar").toFile();
        var root = temp.toFile();
        var manifest = temp.resolve("m3-build.tsv").toFile();
        List<String> run = M3PolyglotCommands.command(
                "java", jar, root, manifest, "verify", "run", 30, true, true);
        assertEquals(List.of("--incremental", "--approve-rewrite-apply"),
                run.subList(run.size() - 2, run.size()));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "plan", 900, true, false));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "plan", 900, false, true));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "../outside", "run", 900, false, false));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 0, false, false));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 43_201, false, false));
    }

    @Test
    void onlyExplicitIncrementalRunCanSelectMavensPersistentModuleState() {
        var jar = temp.resolve("engine.jar").toFile();
        var root = temp.toFile();
        var manifest = temp.resolve("m3-build.tsv").toFile();

        List<String> legacy = M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, false, List.of());
        assertFalse(legacy.contains("--state-backend"));

        List<String> file = M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), "file");
        assertEquals(List.of("--incremental", "--state-backend", "file"),
                file.subList(file.size() - 3, file.size()));

        List<String> nativeDb = M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, true, List.of(), "m3db");
        assertEquals(List.of("--incremental", "--state-backend", "m3db", "--approve-rewrite-apply"),
                nativeDb.subList(nativeDb.size() - 4, nativeDb.size()));

        for (String backend : List.of("embedded", "native", "sqlite", "h2", "hsqldb", "ext-operator-store", "ext-a")) {
            List<String> portable = M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), backend);
            assertEquals(List.of("--incremental", "--state-backend", backend),
                    portable.subList(portable.size() - 3, portable.size()));
        }

        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "plan", 900, false, false, List.of(), "file"));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, false, false, List.of(), "m3db"));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, false, false, List.of(), "embedded"));
        for (String backend : List.of("derby", "ext-", "ext-Bad", "ext-path/escape",
                "ext-" + "x".repeat(49), "jdbc:sqlite:remote", "jdbc:hsqldb:mem:escape")) {
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), backend));
        }
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "plan", 900, false, false, List.of(), "ext-a"));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, false, false, List.of(), "h2"));
        for (String backend : List.of("embedded", "native", "sqlite", "h2", "hsqldb", "ext-local", "ext-1", "ext-a-b")) {
            List<String> portable = M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), backend);
            assertEquals(List.of("--incremental", "--state-backend", backend),
                    portable.subList(portable.size() - 3, portable.size()));
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "plan", 900, false, false, List.of(), backend));
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "run", 900, false, false, List.of(), backend));
        }
        for (String invalid : List.of("ext-", "ext-Upper", "ext-a_b", "ext-../../local",
                "ext-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "http://server", "SQLite", "HSQLDB")) {
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", jar, root, manifest, "all", "run", 900, true, false, List.of(), "M3DB"));
    }

    @Test
    void m3JdkDatabaseModuleExportsAreExplicitAndNeverAFreeFormJvmOption() {
        File engine = temp.resolve("engine.jar").toFile();
        File workspace = temp.toFile();
        File manifest = temp.resolve("m3-build.tsv").toFile();
        List<String> old = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "run", 900,
                true, false, List.of(), "m3db", null);
        List<String> unchanged = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "run", 900,
                true, false, List.of(), "m3db", null, "none");
        assertEquals(old, unchanged, "unspecified M3JDK access must preserve original CLI ABI");

        Map<String, List<String>> expected = Map.of(
                "current-java-base", List.of("--add-exports=java.base/jdk.m3.db=ALL-UNNAMED"),
                "current-named", List.of("--add-modules=jdk.m3.db",
                        "--add-exports=jdk.m3.db/jdk.m3.db=ALL-UNNAMED"),
                "legacy-java-base", List.of("--add-exports=java.base/jdk.m3.indexdb=ALL-UNNAMED"),
                "legacy-named", List.of("--add-modules=jdk.m3.indexdb",
                        "--add-exports=jdk.m3.indexdb/jdk.m3.indexdb=ALL-UNNAMED"));
        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            List<String> run = M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "verify", "run", 900,
                    true, false, List.of(), "m3db", null, entry.getKey());
            assertEquals("java", run.get(0));
            assertEquals(entry.getValue(), run.subList(1, 1 + entry.getValue().size()));
            assertEquals(old.subList(1, old.size()), run.subList(1 + entry.getValue().size(), run.size()),
                    "Maven's original CLI argv must not be modified after JVM module admission");
        }

        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "plan", 900,
                false, false, List.of(), "legacy", null, "current-java-base"));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "run", 900,
                false, false, List.of(), "m3db", null, "current-java-base"));
        for (String backend : List.of("legacy", "embedded", "file", "sqlite", "h2", "hsqldb", "ext-local")) {
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "verify", "run", 900,
                    true, false, List.of(), backend, null, "current-java-base"));
        }
        for (String invalid : List.of("", "auto", "CURRENT-JAVA-BASE", "current java base",
                "--javaagent=/tmp/untrusted.jar", "current-named --add-opens=java.base/java.lang")) {
            assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "verify", "run", 900,
                    true, false, List.of(), "native", null, invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "run", 900,
                true, false, List.of(), "native", null, null));
        List<String> alias = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "verify", "run", 900,
                true, false, List.of(), "native", null, "current-java-base");
        assertEquals("--add-exports=java.base/jdk.m3.db=ALL-UNNAMED", alias.get(1));
        assertEquals(List.of("--state-backend", "native"), alias.subList(alias.size() - 2, alias.size()));
    }

    @Test
    void mavenLocalCacheIsExplicitRunOnlyAndDoesNotChangeOldArgv() {
        File engine = temp.resolve("engine.jar").toFile();
        File workspace = temp.resolve("workspace with spaces").toFile();
        File manifest = temp.resolve("workspace with spaces/m3-build.tsv").toFile();
        File cache = temp.resolve("shared cache with spaces").toFile();
        List<String> old = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "all", "run", 900, true, false,
                List.of(), "legacy");
        assertFalse(old.contains("--build-cache"));

        List<String> selected = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "all", "run", 900, true, false,
                List.of(), "file", cache);
        assertEquals(List.of("--state-backend", "file", "--build-cache", cache.getAbsolutePath()),
                selected.subList(selected.size() - 4, selected.size()));
        assertFalse(selected.contains("bash"));
        assertFalse(selected.contains("sh"));

        List<String> cacheOnly = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "all", "run", 900, false, false,
                List.of(), "legacy", cache);
        assertEquals(List.of("--build-cache", cache.getAbsolutePath()),
                cacheOnly.subList(cacheOnly.size() - 2, cacheOnly.size()));

        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "all", "plan", 900, false, false,
                List.of(), "legacy", cache));
    }

    @Test
    void providerReuseIsExplicitAndPreservesEveryExistingCommandForm() {
        File engine = temp.resolve("engine.jar").toFile();
        File workspace = temp.resolve("project with spaces").toFile();
        File manifest = new File(workspace, "m3-build.tsv");
        File cache = temp.resolve("external cache with spaces").toFile();
        for (boolean incremental : List.of(false, true)) {
            for (File selectedCache : new File[] {null, cache}) {
                List<String> original = M3PolyglotCommands.command(
                        "java", engine, workspace, manifest, "compile", "run", 37,
                        incremental, false, List.of(), "legacy", selectedCache);
                List<String> disabled = M3PolyglotCommands.command(
                        "java", engine, workspace, manifest, "compile", "run", 37,
                        incremental, false, List.of(), "legacy", selectedCache, false);
                assertEquals(original, disabled);
                assertFalse(original.contains("--provider-reuse"));
                if (incremental || selectedCache != null) {
                    List<String> enabled = M3PolyglotCommands.command(
                            "java", engine, workspace, manifest, "compile", "run", 37,
                            incremental, false, List.of(), "legacy", selectedCache, true);
                    assertEquals(original, enabled.subList(0, enabled.size() - 1));
                    assertEquals("--provider-reuse", enabled.get(enabled.size() - 1));
                    assertThrows(UnsupportedOperationException.class, () -> enabled.add("--unsafe"));
                }
            }
        }
    }

    @Test
    void providerReuseRequiresRunWithIncrementalStateOrAnArtifactCache() {
        File engine = temp.resolve("engine.jar").toFile();
        File workspace = temp.toFile();
        File manifest = temp.resolve("m3-build.tsv").toFile();
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "plan", 37,
                false, false, List.of(), "legacy", null, true));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "run", 37,
                false, false, List.of(), "legacy", null, true));
    }

    @Test
    void providerReuseAndM3JdkModuleAccessRemainIndependentSelections() {
        File engine = temp.resolve("engine.jar").toFile();
        File workspace = temp.toFile();
        File manifest = temp.resolve("m3-build.tsv").toFile();
        File cache = temp.resolve("explicit cache").toFile();
        for (String selection : List.of("current-java-base", "current-named",
                "legacy-java-base", "legacy-named")) {
            List<String> moduleOnly = M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "compile", "run", 37,
                    true, false, List.of(), "m3db", cache, selection);
            assertEquals(moduleOnly, M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "compile", "run", 37,
                    true, false, List.of(), "m3db", cache, selection, false));
            List<String> both = M3PolyglotCommands.command(
                    "java", engine, workspace, manifest, "compile", "run", 37,
                    true, false, List.of(), "m3db", cache, selection, true);
            assertEquals(moduleOnly, both.subList(0, both.size() - 1));
            assertEquals("--provider-reuse", both.get(both.size() - 1));
        }
        List<String> providerOnly = M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "run", 37,
                false, false, List.of(), "legacy", cache, true);
        assertEquals(providerOnly, M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "run", 37,
                false, false, List.of(), "legacy", cache, "none", true));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "run", 37,
                false, false, List.of(), "legacy", cache, "current-java-base", true));
        assertThrows(IllegalArgumentException.class, () -> M3PolyglotCommands.command(
                "java", engine, workspace, manifest, "compile", "plan", 37,
                false, false, List.of(), "legacy", null, "none", true));
    }

    @Test
    void binaryPinRejectsChangesAndMalformedHashes() throws Exception {
        Path jar = temp.resolve("polyglot.jar");
        Files.writeString(jar, "reviewed engine bytes", StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar)));
        M3PolyglotCommands.verifyEngine(jar, digest);
        assertThrows(IOException.class, () -> M3PolyglotCommands.verifyEngine(jar, digest.toUpperCase()));
        Files.writeString(jar, "changed engine bytes", StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> M3PolyglotCommands.verifyEngine(jar, digest));
        assertThrows(IOException.class, () -> M3PolyglotCommands.verifyEngine(temp.resolve("missing.jar"), digest));
        Path symlink = temp.resolve("symlink.jar");
        try {
            Files.createSymbolicLink(symlink, jar);
            assertThrows(IOException.class, () -> M3PolyglotCommands.verifyEngine(symlink, digest));
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            // Symlink creation is not supported on every Windows test host.
            assertTrue(Files.isRegularFile(jar));
        }
    }

    @Test
    void thirdPartyJarsRequireOneDigestEachAndHaveDeterministicClasspaths() throws Exception {
        Path first = temp.resolve("a-provider.jar");
        Path second = temp.resolve("z-provider.jar");
        Files.writeString(first, "a reviewed provider", StandardCharsets.UTF_8);
        Files.writeString(second, "another reviewed provider", StandardCharsets.UTF_8);
        String firstHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(first)));
        String secondHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(second)));
        var pins = Map.of("a-provider.jar", firstHash, "z-provider.jar", secondHash);
        List<File> installed = M3ProviderClasspath.verify(Set.of(second.toFile(), first.toFile()), pins);
        assertEquals(List.of(first.toFile(), second.toFile()), installed);
        var cli = M3PolyglotCommands.providers("java", temp.resolve("engine.jar").toFile(), installed);
        assertEquals("providers", cli.get(cli.size() - 1));
        assertEquals(temp.resolve("engine.jar") + File.pathSeparator + first + File.pathSeparator + second,
                cli.get(3));
        var run = M3PolyglotCommands.command("java", temp.resolve("engine.jar").toFile(),
                temp.toFile(), temp.resolve("m3-build.tsv").toFile(), "all", "plan", 900,
                false, false, installed);
        assertEquals(cli.get(3), run.get(2));
        assertThrows(IOException.class, () -> M3ProviderClasspath.verify(
                List.of(first.toFile(), second.toFile()), Map.of("a-provider.jar", firstHash)));
        assertThrows(IOException.class, () -> M3ProviderClasspath.verify(
                List.of(first.toFile()), Map.of("a-provider.jar", secondHash)));
        assertThrows(IOException.class, () -> M3ProviderClasspath.verify(
                List.of(first.toFile()), Map.of("a-provider.jar", firstHash, "extra.jar", secondHash)));
    }
}
