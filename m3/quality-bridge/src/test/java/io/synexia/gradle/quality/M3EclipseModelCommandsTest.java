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

/** The Maven Eclipse reader remains the sole owner of descriptor interpretation. */
final class M3EclipseModelCommandsTest {
    @TempDir
    Path workspace;

    @Test
    void usesOnlyPinnedMavenCliWithoutShellOrInferredCommands() {
        File engine = workspace.resolve("source owned engine.jar").toFile();
        File project = workspace.resolve("Eclipse work space").toFile();
        List<String> command = M3EclipseModelCommands.inspect("java", engine, project);
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", engine.getAbsolutePath(),
                "io.synexia.maven.polyglot.EclipseModelCli", project.getAbsolutePath()), command);
        assertThrows(UnsupportedOperationException.class, () -> command.add("--apply"));
        assertThrows(IllegalArgumentException.class,
                () -> M3EclipseModelCommands.inspect("java\nsh", engine, project));
        assertThrows(NullPointerException.class,
                () -> M3EclipseModelCommands.inspect("java", null, project));
    }
}
