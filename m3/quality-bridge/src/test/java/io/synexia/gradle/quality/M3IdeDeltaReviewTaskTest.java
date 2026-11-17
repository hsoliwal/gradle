/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins canonical bytes and refuses untrusted owner identities before reporting. */
final class M3IdeDeltaReviewTaskTest {
    @TempDir
    Path project;

    @Test
    void acceptsExactPinnedBytesButNeverGrantsGradleBuildAuthority() throws Exception {
        fixture();
        byte[] bytes = packet("gradle", "fixture::");
        Files.write(report(), bytes);
        String output = run(sha(bytes), false);
        assertTrue(output.contains("Reviewed M3 IDE delta"), output);
        assertTrue(output.contains("not build authority"), output);
    }

    @Test
    void failsOnCorruptBytesAndForeignOwnerEvenWhenHashMatches() throws Exception {
        fixture();
        byte[] expected = packet("gradle", "fixture::");
        Files.write(report(), expected);
        Files.write(report(), packet("gradle", "different-project"));
        assertTrue(run(sha(expected), true).contains("M3 IDE delta SHA-256 mismatch"));

        byte[] foreign = packet("eclipse.m2e", "fixture::");
        Files.write(report(), foreign);
        assertTrue(run(sha(foreign), true).contains("foreign M3 IDE delta"));

        byte[] wrongProject = packet("gradle", "foreign::");
        Files.write(report(), wrongProject);
        assertTrue(run(sha(wrongProject), true).contains("foreign M3 IDE delta"));
    }

    private void fixture() throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name=\"fixture\"\n");
        Files.writeString(project.resolve("build.gradle"), "plugins { id 'io.synexia.m3.quality' }\n");
        Files.createDirectories(report().getParent());
    }

    private byte[] packet(String provider, String id) {
        return M3IdeDeltaWire.encode(new M3IdeDeltaWire.Packet(provider, id,
                M3IdeDeltaWire.BuildKind.INCREMENTAL, false,
                List.of(new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED,
                        "src/main/java/A.java"))));
    }

    private String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Path report() {
        return project.resolve("build/reports/m3/ide-delta.tsv");
    }

    private String run(String checksum, boolean expectFailure) {
        GradleRunner runner = GradleRunner.create().withProjectDir(project.toFile())
                .withPluginClasspath().withArguments("m3IdeDeltaReview",
                        "-Pm3.ide.delta.sha256=" + checksum, "--stacktrace");
        return expectFailure ? runner.buildAndFail().getOutput() : runner.build().getOutput();
    }
}
