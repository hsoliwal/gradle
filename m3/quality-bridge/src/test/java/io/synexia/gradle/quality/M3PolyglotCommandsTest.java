/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

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
}
