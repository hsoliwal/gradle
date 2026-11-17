/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Dependency-free Java 17 codec and cross-host input-safety acceptance gate. */
public final class M3IdeDeltaWireContractMain {
    private M3IdeDeltaWireContractMain() {}

    public static void main(String[] ignored) throws Exception {
        var added = new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.ADDED, "src/main/java/A.java");
        var modified = new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED, "src/main/java/B.java");
        var packet = new M3IdeDeltaWire.Packet("eclipse.m2e", "example",
                M3IdeDeltaWire.BuildKind.INCREMENTAL, true, List.of(added, modified));
        byte[] bytes = M3IdeDeltaWire.encode(packet);
        String canonical = "M3-IDE-DELTA-V1\n"
                + "provider\teclipse.m2e\n"
                + "project64\tZXhhbXBsZQ\n"
                + "kind\tINCREMENTAL\n"
                + "complete\ttrue\n"
                + "change\tADDED\tsrc/main/java/A.java\n"
                + "change\tMODIFIED\tsrc/main/java/B.java\n";
        require(Arrays.equals(bytes, canonical.getBytes(StandardCharsets.UTF_8)), "Maven sample bytes");
        require(M3IdeDeltaWire.decode(bytes).equals(packet), "Maven sample round trip");

        var gradle = new M3IdeDeltaWire.Packet("gradle", "root::module",
                M3IdeDeltaWire.BuildKind.FULL, false, List.of());
        require(M3IdeDeltaWire.decode(M3IdeDeltaWire.encode(gradle)).equals(gradle),
                "full fallback round trip");
        var utf = new M3IdeDeltaWire.Packet("gradle", "项目",
                M3IdeDeltaWire.BuildKind.AUTO, false,
                List.of(new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED, "src/नमूना.java")));
        require(M3IdeDeltaWire.decode(M3IdeDeltaWire.encode(utf)).equals(utf),
                "multilingual UTF-8 identity");

        refuse(() -> M3IdeDeltaWire.decode(new byte[] {(byte) 0xc3, 0x28}));
        refuse(() -> M3IdeDeltaWire.decode(new byte[8 * 1024 * 1024 + 1]));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.substring(0, canonical.length() - 1)
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(("M3-IDE-DELTA-V2\n" + canonical.substring(16))
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("ZXhhbXBsZQ", "ZXhhbXBsZQ==")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("complete\ttrue", "complete\tTRUE")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("ADDED", "UNKNOWN")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("src/main/java/B.java", "../escape.java")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("src/main/java/B.java", "C:\\windows\\bad")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("src/main/java/B.java", "src//Bad.java")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("src/main/java/B.java", "src/main/java/A.java")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("src/main/java/B.java", "src/main/java/0.java")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> M3IdeDeltaWire.decode(canonical.replace("kind\tINCREMENTAL", "kind\tFULL")
                .getBytes(StandardCharsets.UTF_8)));
        refuse(() -> new M3IdeDeltaWire.Packet("gradle", "root",
                M3IdeDeltaWire.BuildKind.CLEAN, true, List.of()));
        refuse(() -> new M3IdeDeltaWire.Packet("gradle", "",
                M3IdeDeltaWire.BuildKind.INCREMENTAL, false, List.of()));
        refuse(() -> new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED, "src/\nnew.java"));
        refuse(() -> new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED, "/absolute"));
        refuse(() -> new M3IdeDeltaWire.Change(M3IdeDeltaWire.ChangeKind.MODIFIED, "src/../escape"));
        System.out.println("PASS: canonical Maven IDE-delta v1 bytes, round trips and malformed input refusals");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static void refuse(Checked operation) throws Exception {
        try {
            operation.run();
        } catch (IOException | IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("invalid IDE-delta envelope was accepted");
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
