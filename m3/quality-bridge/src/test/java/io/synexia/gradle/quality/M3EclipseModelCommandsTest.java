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
    void builderOwnershipUsesOneCliWithAnExplicitProviderClasspath() {
        File engine = workspace.resolve("engine.jar").toFile();
        File provider = workspace.resolve("adapter.jar").toFile();
        File eclipse = workspace.resolve("project").toFile();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", engine.getAbsolutePath(),
                "io.synexia.maven.polyglot.EclipseModelCli", "owners", eclipse.getAbsolutePath()),
                M3EclipseModelCommands.owners("java", engine, eclipse));
        String classpath = engine.getAbsolutePath() + File.pathSeparator + provider.getAbsolutePath();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", classpath,
                "io.synexia.maven.polyglot.EclipseModelCli", "owners", eclipse.getAbsolutePath()),
                M3EclipseModelCommands.owners("java", engine, eclipse, List.of(provider)));
        assertThrows(NullPointerException.class,
                () -> M3EclipseModelCommands.owners("java", engine, eclipse, List.of((File) null)));
    }

    @Test
    void cdtScannerFactsKeepExactOriginalOwnerPathsAndActiveCdtSeparate() {
        File engine = workspace.resolve("engine.jar").toFile();
        File cdt = workspace.resolve("cdt scanner.ide").toFile();
        File handoff = workspace.resolve("eclipse invocation.ide").toFile();
        File adapter = workspace.resolve("cdt-adapter.jar").toFile();
        String classpath = engine.getAbsolutePath() + File.pathSeparator + adapter.getAbsolutePath();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", classpath,
                "io.synexia.maven.polyglot.EclipseModelCli", "cdt-facts",
                cdt.getAbsolutePath(), handoff.getAbsolutePath()),
                M3EclipseModelCommands.cdt("java", engine, cdt, handoff, List.of(adapter)));
        assertThrows(NullPointerException.class,
                () -> M3EclipseModelCommands.cdt("java", engine, cdt, null, List.of()));
    }

    @Test
    void jdtCompilerFactsRequireBothOriginalOwnerPathsAndPinnedEngine() {
        File engine = workspace.resolve("engine.jar").toFile();
        File jdt = workspace.resolve("jdt compiler.ide").toFile();
        File eclipse = workspace.resolve("eclipse invocation.ide").toFile();
        File adapter = workspace.resolve("jdt-adapter.jar").toFile();
        String classpath = engine.getAbsolutePath() + File.pathSeparator + adapter.getAbsolutePath();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", classpath,
                "io.synexia.maven.polyglot.EclipseModelCli", "jdt-facts",
                jdt.getAbsolutePath(), eclipse.getAbsolutePath()),
                M3EclipseModelCommands.jdt("java", engine, jdt, eclipse, List.of(adapter)));
        assertThrows(NullPointerException.class,
                () -> M3EclipseModelCommands.jdt("java", engine, null, eclipse, List.of()));
    }

    @Test
    void modelReconciliationPreservesBothOriginalPathArguments() {
        File engine = workspace.resolve("engine.jar").toFile();
        File project = workspace.resolve("Eclipse project").toFile();
        File packet = workspace.resolve("build-handoff.ide").toFile();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", engine.getAbsolutePath(),
                "io.synexia.maven.polyglot.EclipseModelCli", "reconcile",
                project.getAbsolutePath(), packet.getAbsolutePath()),
                M3EclipseModelCommands.reconcile("java", engine, project, packet, List.of()));
    }

    @Test
    void invocationHandoffUsesOnePinnedMavenCliWithOriginalPacketPath() {
        File engine = workspace.resolve("engine.jar").toFile();
        File packet = workspace.resolve("eclipse invocation.ide").toFile();
        File provider = workspace.resolve("language-extension.jar").toFile();
        String classpath = engine.getAbsolutePath() + File.pathSeparator + provider.getAbsolutePath();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", classpath,
                "io.synexia.maven.polyglot.EclipseModelCli", "handoff", packet.getAbsolutePath()),
                M3EclipseModelCommands.handoff("java", engine, packet, List.of(provider)));
    }

    @Test
    void sourceOwnerContextIsASeparateAndExplicitMavenCliCommand() {
        File engine = workspace.resolve("engine.jar").toFile();
        File packet = workspace.resolve("build context.ide").toFile();
        assertEquals(List.of("java", "-Dfile.encoding=UTF-8", "-cp", engine.getAbsolutePath(),
                "io.synexia.maven.polyglot.EclipseModelCli", "context", packet.getAbsolutePath()),
                M3EclipseModelCommands.context("java", engine, packet, List.of()));
    }

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
