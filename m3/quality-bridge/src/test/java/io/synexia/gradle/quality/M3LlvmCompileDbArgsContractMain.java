/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Offline Java17 proof for exact argv, receipt and refusal behavior. */
public final class M3LlvmCompileDbArgsContractMain {
    private M3LlvmCompileDbArgsContractMain() { }

    public static void main(String[] unused) throws Exception {
        File root = Path.of("workspace with spaces").toAbsolutePath().toFile();
        File jar = Path.of("engine.jar").toAbsolutePath().toFile();
        File manifest = new File(root, "m3-build.tsv");
        File provider = Path.of("provider.jar").toAbsolutePath().toFile();
        List<String> expected = List.of("java", "-Dfile.encoding=UTF-8", "-cp",
                jar + File.pathSeparator + provider,
                "io.synexia.maven.polyglot.PolyglotCli", "llvm-compile-db",
                root.toString(), manifest.toString(), "all");
        require(M3LlvmCompileDbCommands.command("java", jar, List.of(provider), root, manifest, "all")
                .equals(expected), "original Maven argv and provider order");
        require(M3LlvmCompileDbCommands.command("java", jar, List.of(), root, manifest, "all")
                .get(3).equals(jar.toString()), "providerless original classpath");
        Path output = M3LlvmCompileDbCommands.expectedDatabase(root.toPath());
        require(output.endsWith("target/m3-llvm/compile_commands.json"), "original Maven path");
        String protocol = "M3-LLVM-COMPILE-DB-V1\nfile\t" + output + "\n";
        M3LlvmCompileDbCommands.verifyReceipt(protocol.getBytes(StandardCharsets.UTF_8), output);
        M3LlvmCompileDbCommands.verifyReceipt(protocol.replace("\n", "\r\n")
                .getBytes(StandardCharsets.UTF_8), output);
        for (String invalid : List.of("../escape", "bad/name", "", "-all", " all", "all\nspoof")) {
            refuse(() -> M3LlvmCompileDbCommands.command("java", jar, List.of(), root, manifest, invalid));
        }
        refuse(() -> M3LlvmCompileDbCommands.command("java", jar, List.of(), root,
                Path.of("elsewhere/m3-build.tsv").toFile(), "all"));
        for (String invalid : List.of("", "file\t" + output + "\n", "M3-LLVM-COMPILE-DB-V2\nfile\t" + output + "\n",
                protocol.replace("file\t", "path\t"), protocol + "extra\n",
                protocol.replace(output.toString(), "/tmp/foreign/compile_commands.json"),
                protocol.replace("\n", "\r"), protocol.replace("\n", "\n\n"))) {
            refuse(() -> M3LlvmCompileDbCommands.verifyReceipt(invalid.getBytes(StandardCharsets.UTF_8), output));
        }
        refuse(() -> M3LlvmCompileDbCommands.verifyReceipt(new byte[] {(byte) 0xc3, (byte) 0x28}, output));
        refuse(() -> M3LlvmCompileDbCommands.verifyReceipt(new byte[4097], output));
        System.out.println("PASS: Maven #255 Gradle CLI argv and strict two-row receipt contract");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static void refuse(ThrowingAction action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException | java.io.IOException expected) {
            return;
        }
        throw new AssertionError("invalid invocation or receipt was accepted");
    }
    private interface ThrowingAction { void run() throws Exception; }
}
