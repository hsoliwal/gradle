/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The original Maven owner controls syntax facts and the MIndexDB format. */
final class M3MachineFingerprintCommandsTest {
    @TempDir Path root;

    @Test
    void originalMavenCliReceivesAllExactOptionsAndImmutablePaths() {
        File jar = root.resolve("smart builder.jar").toFile();
        File source = root.resolve("src/main/java").toFile();
        File build = root.resolve("build").toFile();
        File prep = root.resolve("receipts/preparation.json").toFile();
        File pattern = root.resolve("receipts/atom-pattern.tsv").toFile();
        List<String> command = invoke(jar, build, source, prep, pattern, 3, true, true, "required");
        assertEquals("java", command.get(0));
        assertEquals(M3MachineFingerprintCommands.MAIN_CLASS, command.get(4));
        assertTrue(command.contains(source.getAbsolutePath()));
        assertEquals(List.of("--workers", "3", "--attribute-types", "true",
                "--analyze-flow", "true", "--module-db", "required"),
                command.subList(command.size() - 8, command.size()));
        assertThrows(UnsupportedOperationException.class, () -> command.add("--unsafe"));
    }

    @Test
    void refusesInvalidModesAndUnprovenCompilerFlow() {
        File jar = root.resolve("engine.jar").toFile();
        File source = root.resolve("src/main/java").toFile();
        File build = root.resolve("build").toFile();
        File prep = root.resolve("preparation.json").toFile();
        File pattern = root.resolve("atom-pattern.tsv").toFile();
        for (String mode : List.of("m3db", "", "sqlite", "AUTO")) {
            assertThrows(IllegalArgumentException.class,
                    () -> invoke(jar, build, source, prep, pattern, 1, false, false, mode));
        }
        assertThrows(IllegalArgumentException.class,
                () -> invoke(jar, build, source, prep, pattern, 1, false, true, "auto"));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(jar, build, source, prep, pattern, 0, true, false, "off"));
        assertThrows(IllegalArgumentException.class,
                () -> invoke(jar, build, source, prep, pattern, 9, true, false, "off"));
        List<String> off = invoke(jar, build, source, prep, pattern, 1, false, false, "off");
        assertEquals("off", off.get(off.size() - 1));
    }

    private List<String> invoke(File jar, File build, File source, File prep, File pattern,
            int workers, boolean attributed, boolean flow, String mode) {
        return M3MachineFingerprintCommands.command("java", jar, root.toFile(), build,
                source, prep, pattern, workers, attributed, flow, mode, List.of());
    }
}
