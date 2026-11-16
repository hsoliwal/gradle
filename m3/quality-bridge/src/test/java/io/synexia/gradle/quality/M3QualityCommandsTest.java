/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M3QualityCommandsTest {
    @Test
    void codeqlAlwaysUsesArgumentVectorAndStableSarifSchema() {
        var cmd = M3QualityCommands.codeql("codeql", new File("db"), "codeql/java-queries",
                new File("out/results.sarif"), 2);
        assertEquals("codeql", cmd.get(0));
        assertEquals("database", cmd.get(1));
        assertTrue(cmd.contains("--format=sarifv2.1.0"));
        assertTrue(cmd.contains("--threads=2"));
        assertTrue(cmd.contains("codeql/java-queries"));
        assertThrows(UnsupportedOperationException.class, () -> cmd.add("--overwrite"));
        assertThrows(IllegalArgumentException.class, () -> M3QualityCommands.codeql("codeql",
                new File("db"), "--unsafe", new File("out.sarif"), 1));
        assertThrows(IllegalArgumentException.class, () -> M3QualityCommands.codeql("codeql",
                new File("db"), null, new File("out.txt"), 1));
    }

    @Test
    void evidenceCoordinatorRemainsTheOriginalM3Runner() {
        var cmd = M3QualityCommands.m3Codeql("java", new File("runner.jar"), new File("ql"),
                new File("db"), new File("receipt"), "codeql", 2, 3072, 600);
        assertEquals("java", cmd.get(0));
        assertTrue(cmd.contains("analyze"));
        assertTrue(cmd.contains("--execute"));
        assertFalse(cmd.contains("--overwrite"));
        assertThrows(IllegalArgumentException.class, () -> M3QualityCommands.m3Codeql("java",
                new File("runner.jar"), new File("ql"), new File("db"), new File("receipt"),
                "codeql", 2, 128, 60));
    }

    @Test
    void semgrepUsesExplicitLocalRuleAndDoesNotRequestAutofix() {
        var cmd = M3QualityCommands.semgrep("semgrep", new File("rules.yml"),
                new File("src"), new File("report.json"));
        assertEquals("semgrep", cmd.get(0));
        assertTrue(cmd.contains("--error"));
        assertTrue(cmd.contains("--metrics=off"));
        assertFalse(cmd.contains("--autofix"));
        assertFalse(cmd.contains("--config=auto"));
        assertThrows(IllegalArgumentException.class, () -> M3QualityCommands.semgrep("semgrep",
                new File("rules.yml"), new File("src"), new File("report.xml")));
    }
}
