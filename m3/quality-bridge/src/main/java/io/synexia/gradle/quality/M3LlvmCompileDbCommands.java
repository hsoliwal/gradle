/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Thin argv and two-line receipt contract for Maven #255's original
 * LLVM compilation database exporter. Maven owns the JSON and compiler facts.
 */
public final class M3LlvmCompileDbCommands {
    private static final String HEADER = "M3-LLVM-COMPILE-DB-V1";
    static final int MAX_RECEIPT_BYTES = 4096;
    static final long MAX_DATABASE_BYTES = 4L * 1024 * 1024;

    private M3LlvmCompileDbCommands() { }

    public static List<String> command(String javaExecutable, File originalEngine,
            List<File> providerJars, File workspace, File manifest, String target) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(originalEngine, "originalEngine");
        Objects.requireNonNull(providerJars, "providerJars");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(manifest, "manifest");
        if (target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")) {
            throw new IllegalArgumentException("invalid M3 LLVM compilation database target");
        }
        Path root = workspace.toPath().toAbsolutePath().normalize();
        Path manifestPath = manifest.toPath().toAbsolutePath().normalize();
        if (manifestPath.equals(root) || !manifestPath.startsWith(root)) {
            throw new IllegalArgumentException("M3 LLVM compilation database manifest must be project-local");
        }
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp",
                M3PolyglotCommands.classpath(originalEngine, providerJars),
                M3PolyglotCommands.MAIN_CLASS, "llvm-compile-db",
                root.toString(), manifestPath.toString(), target);
    }

    /** The Maven owner deliberately writes under target/, not Gradle's build/. */
    public static Path expectedDatabase(Path workspace) {
        return workspace.toAbsolutePath().normalize()
                .resolve("target/m3-llvm/compile_commands.json");
    }

    /** Validate only Maven's versioned handoff; never reparse compilation JSON. */
    public static void verifyReceipt(byte[] receipt, Path expectedDatabase) throws IOException {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(expectedDatabase, "expectedDatabase");
        if (receipt.length < HEADER.length() || receipt.length > MAX_RECEIPT_BYTES) {
            throw new IOException("invalid M3 LLVM compilation database receipt length");
        }
        String decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(receipt)).toString();
        String body = HEADER + "\nfile\t" + expectedDatabase.toAbsolutePath().normalize() + "\n";
        if (!decoded.equals(body) && !decoded.equals(body.replace("\n", "\r\n"))) {
            throw new IOException("Maven LLVM compilation database returned a foreign or malformed receipt");
        }
    }
}
