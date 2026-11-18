/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;

/** Standalone Java 17 contract for the Maven #203 provider inventory schema. */
public final class M3ProviderInventoryContractMain {
    private static final String HEADER = "M3-POLYGLOT-PROVIDERS-V1\n";
    private static final String ORIGINAL = "provider\tjdk\tcompiler\tjava\n";
    private static final String LLVM = "provider\tllvm-tools\t"
            + "assembler,interpreter,linker,optimizer,transpiler\t"
            + "coff,elf,filecheck,lit,llvm-ir,macho,mlir,wasm\n";

    private M3ProviderInventoryContractMain() {}

    public static void main(String[] args) throws Exception {
        accept(HEADER + ORIGINAL);
        accept(HEADER + ORIGINAL + LLVM);
        accept(HEADER + "provider\tllvm-tools\t"
                + "assembler,atomizer,compiler,interpreter,linker,optimizer,patternizer,transpiler"
                + "\tjava,llvm-ir\n");

        reject(HEADER + "provider\tllvm-tools\tlinker,assembler\tllvm-ir\n");
        reject(HEADER + "provider\tllvm-tools\tlinker,linker\tllvm-ir\n");
        reject(HEADER + "provider\tllvm-tools\tlinker,,optimizer\tllvm-ir\n");
        reject(HEADER + "provider\tllvm-tools\tGPU\tllvm-ir\n");
        reject(HEADER + "provider\tllvm-tools\tlinkers\tllvm-ir\n");
        reject(HEADER + "provider\tllvm-tools\tlinker\tllvm-ir,elf\n");
        reject(HEADER + "provider\tllvm-tools\tlinker\tllvm-ir,llvm-ir\n");
        reject(HEADER + "provider\tLLVM-tools\tlinker\tllvm-ir\n");
        reject(HEADER + LLVM + ORIGINAL);
        reject(HEADER + LLVM + LLVM);
        reject(HEADER + "provider\tllvm-tools\tassembler\tllvm-ir\r\n");
        reject(HEADER + "provider\tllvm-tools\tassembler\tllvm-ir\u0000\n");
        reject(HEADER + "provider\tllvm-tools\tassembler\tllvm-ir");
        reject("M3-POLYGLOT-PROVIDERS-V2\n" + LLVM);
        reject(HEADER + "provider\tllvm-tools\tassembler\n");
        reject(HEADER + "provider\tllvm-tools\tassembler\t\n");

        StringBuilder oversized = new StringBuilder(HEADER);
        for (int i = 0; i < 513; i++) {
            oversized.append("provider\tp").append(String.format("%03d", i))
                    .append("\tlinker\tllvm-ir\n");
        }
        reject(oversized.toString());
        System.out.println("PASS: Maven #203 LLVM/MLIR/LLD capabilities and bounded v1 schema");
    }

    private static void accept(String report) throws IOException {
        M3ProviderInventoryV1.validate(report);
    }

    private static void reject(String report) throws Exception {
        try {
            M3ProviderInventoryV1.validate(report);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("invalid Maven inventory accepted");
    }
}
