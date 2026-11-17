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

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises real Gradle InputChanges, never reinterpreting deltas as cache authority. */
final class M3IdeDeltaExportTaskTest {
    @TempDir
    Path root;

    @Test
    void emitsFullFallbackAndThenNativeModifiedAndDeletedPathChanges() throws Exception {
        fixture();
        Path source = root.resolve("src/main/java/A.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class A {}", StandardCharsets.UTF_8);

        run("m3IdeDeltaExport");
        M3IdeDeltaWire.Packet first = readPacket();
        assertEquals("gradle", first.provider());
        assertEquals("fixture::", first.project());
        assertEquals(M3IdeDeltaWire.BuildKind.FULL, first.kind());
        assertFalse(first.complete());
        assertTrue(first.changes().isEmpty());
        assertShaMatches();

        Files.writeString(source, "class A { int value; }", StandardCharsets.UTF_8);
        run("m3IdeDeltaExport");
        M3IdeDeltaWire.Packet modified = readPacket();
        assertEquals(M3IdeDeltaWire.BuildKind.INCREMENTAL, modified.kind());
        assertFalse(modified.complete());
        assertEquals(1, modified.changes().size());
        assertEquals(new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED,
                "src/main/java/A.java"), modified.changes().get(0));
        assertShaMatches();

        Files.delete(source);
        run("m3IdeDeltaExport");
        M3IdeDeltaWire.Packet deleted = readPacket();
        assertEquals(M3IdeDeltaWire.BuildKind.INCREMENTAL, deleted.kind());
        assertEquals(new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.DELETED,
                "src/main/java/A.java"), deleted.changes().get(0));
        assertShaMatches();
    }

    @Test
    void pluginApplicationAndHelpNeverExportOrStartAnyTool() throws IOException {
        fixture();
        run("help");
        assertFalse(Files.exists(report()));
        assertFalse(Files.exists(digest()));
    }

    @Test
    void refusesForeignSourceBeforePublishingEvenOnTheFirstFullBuild() throws Exception {
        fixture();
        Path outside = Files.createTempFile("m3-foreign-ide-delta-", ".java");
        try {
            Files.writeString(outside, "class Foreign {}", StandardCharsets.UTF_8);
            Files.writeString(root.resolve("build.gradle"),
                    Files.readString(root.resolve("build.gradle"))
                    + "tasks.named('m3IdeDeltaExport') { sourceFiles.from(files('"
                    + outside.toString().replace('\\', '/') + "')) }\n");
            String output = GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                    .withArguments("m3IdeDeltaExport", "--stacktrace").buildAndFail().getOutput();
            assertTrue(output.contains("source collection leaves project"), output);
            assertFalse(Files.exists(report()));
            assertFalse(Files.exists(digest()));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    private void fixture() throws IOException {
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n");
    }

    private M3IdeDeltaWire.Packet readPacket() throws Exception {
        return M3IdeDeltaWire.decode(Files.readAllBytes(report()));
    }

    private void assertShaMatches() throws Exception {
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(report())));
        assertEquals(expected + "\n", Files.readString(digest(), StandardCharsets.UTF_8));
    }

    private Path report() {
        return root.resolve("build/reports/m3/ide-delta.tsv");
    }

    private Path digest() {
        return root.resolve("build/reports/m3/ide-delta.sha256");
    }

    private void run(String task) {
        GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath()
                .withArguments(task, "--stacktrace").build();
    }
}
