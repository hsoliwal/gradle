/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;

/** Explicit read-only consumer of a separately SHA-256-pinned M3 IDE-delta packet. */
@UntrackedTask(because = "External change notifications cannot authorize native Gradle task/cache hits")
public abstract class M3IdeDeltaReviewTask extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getReportFile();

    @Input
    public abstract Property<String> getExpectedSha256();

    @Input
    public abstract Property<String> getExpectedProvider();

    @Input
    public abstract Property<String> getExpectedProjectIdentifier();

    @TaskAction
    public void review() {
        Path report = getReportFile().get().getAsFile().toPath().toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(report) > M3IdeDeltaWire.MAX_BYTES) {
                throw new IOException("IDE delta packet must be ordinary and at most 8 MiB");
            }
            byte[] bytes = Files.readAllBytes(report);
            if (bytes.length > M3IdeDeltaWire.MAX_BYTES) throw new IOException("oversized IDE delta packet");
            String expected = getExpectedSha256().get();
            if (!expected.matches("[0-9a-f]{64}") || !expected.equals(sha256(bytes))) {
                throw new IOException("M3 IDE delta SHA-256 mismatch");
            }
            M3IdeDeltaWire.Packet packet = M3IdeDeltaWire.decode(bytes);
            if (!packet.provider().equals(getExpectedProvider().get())
                    || !packet.project().equals(getExpectedProjectIdentifier().get())) {
                throw new IOException("foreign M3 IDE delta provider or project");
            }
            getLogger().lifecycle("Reviewed M3 IDE delta: provider={}, kind={}, changed={}, "
                            + "claimedComplete={} (not build authority)",
                    packet.provider(), packet.kind(), packet.changes().size(), packet.complete());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new GradleException("Invalid or unauthenticated M3 IDE delta packet", invalid);
        }
    }

    private static String sha256(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
