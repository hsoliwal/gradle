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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

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

    @Test
    void nativeBuildTreeIdentitySurvivesConfigurationCacheReplay() throws Exception {
        compositeFixture();
        var runner = compositeRunner(":m3IdeDeltaExport", ":child:m3IdeDeltaExport",
                ":left:m3IdeDeltaExport", ":right:m3IdeDeltaExport");
        String first = runner.build().getOutput();
        assertTrue(first.contains("Configuration cache entry stored"), first);
        assertEquals("fixture::", packet(root).project());
        assertEquals("fixture::child", packet(root.resolve("child")).project());
        assertEquals("fixture::left", packet(root.resolve("left")).project());
        assertEquals("fixture::right", packet(root.resolve("right")).project());

        Files.writeString(root.resolve("left/src/main/java/Identity.java"),
                "class Identity { int generation; }", StandardCharsets.UTF_8);
        String replay = runner.build().getOutput();
        assertTrue(replay.contains("Reusing configuration cache"), replay);
        M3IdeDeltaWire.Packet changed = packet(root.resolve("left"));
        assertEquals("fixture::left", changed.project());
        assertEquals(M3IdeDeltaWire.BuildKind.INCREMENTAL, changed.kind());
        assertFalse(changed.complete());
        assertEquals(List.of(new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED,
                "src/main/java/Identity.java")), changed.changes());
        assertEquals("fixture::right", packet(root.resolve("right")).project());
    }

    @Test
    void reviewRefusesAnotherIncludedBuildEvenWhenItsDigestIsPinned() throws Exception {
        compositeFixture();
        compositeRunner(":left:m3IdeDeltaExport", ":right:m3IdeDeltaExport").build();
        Path leftBuild = root.resolve("left/build.gradle");
        String plugin = Files.readString(leftBuild);
        String ownSha = Files.readString(root.resolve("left/build/reports/m3/ide-delta.sha256")).trim();
        Files.writeString(leftBuild, plugin + "tasks.named('m3IdeDeltaReview') { expectedSha256.set('"
                + ownSha + "') }\n");
        String ownReview = compositeRunner(":left:m3IdeDeltaReview").build().getOutput();
        assertTrue(ownReview.contains("Reviewed M3 IDE delta"), ownReview);

        String foreignSha = Files.readString(root.resolve("right/build/reports/m3/ide-delta.sha256")).trim();
        Files.writeString(leftBuild, plugin + "tasks.named('m3IdeDeltaReview') {\n"
                + "  reportFile.set(layout.projectDirectory.file('../right/build/reports/m3/ide-delta.tsv'))\n"
                + "  expectedSha256.set('" + foreignSha + "')\n}\n");
        String refused = compositeRunner(":left:m3IdeDeltaReview").buildAndFail().getOutput();
        assertTrue(refused.contains("foreign M3 IDE delta provider or project"), refused);
    }

    @Test
    void explicitCompositeIdentityOverridesRemainExact() throws Exception {
        compositeFixture();
        Path leftBuild = root.resolve("left/build.gradle");
        Files.writeString(leftBuild, Files.readString(leftBuild)
                + "tasks.named('m3IdeDeltaExport') { projectIdentifier.set('legacy-workspace:chosen') }\n"
                + "tasks.named('m3IdeDeltaReview') { expectedProjectIdentifier.set('legacy-workspace:chosen') }\n");
        compositeRunner(":left:m3IdeDeltaExport").build();
        assertEquals("legacy-workspace:chosen", packet(root.resolve("left")).project());
        String sha = Files.readString(root.resolve("left/build/reports/m3/ide-delta.sha256")).trim();
        String review = compositeRunner(":left:m3IdeDeltaReview", "-Pm3.ide.delta.sha256=" + sha)
                .build().getOutput();
        assertTrue(review.contains("Reviewed M3 IDE delta"), review);
    }

    private void compositeFixture() throws IOException {
        fixture();
        Files.writeString(root.resolve("settings.gradle.kts"),
                "rootProject.name = \"fixture\"\ninclude(\"child\")\n"
                + "includeBuild(\"left\") { name = \"left\" }\n"
                + "includeBuild(\"right\") { name = \"right\" }\n");
        for (String name : List.of("child", "left", "right")) {
            Path project = Files.createDirectories(root.resolve(name));
            Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n");
            if (!"child".equals(name)) {
                Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"fixture\"\n");
            }
            Path source = project.resolve("src/main/java/Identity.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class Identity {}", StandardCharsets.UTF_8);
        }
    }

    private GradleRunner compositeRunner(String... tasks) {
        List<String> arguments = new ArrayList<>(List.of(tasks));
        arguments.addAll(List.of("--configuration-cache", "--configuration-cache-problems=fail", "--stacktrace"));
        return GradleRunner.create().withProjectDir(root.toFile()).withPluginClasspath().withArguments(arguments);
    }

    private static M3IdeDeltaWire.Packet packet(Path project) throws Exception {
        Path report = project.resolve("build/reports/m3/ide-delta.tsv");
        byte[] bytes = Files.readAllBytes(report);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(sha + "\n", Files.readString(project.resolve("build/reports/m3/ide-delta.sha256")));
        return M3IdeDeltaWire.decode(bytes);
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
