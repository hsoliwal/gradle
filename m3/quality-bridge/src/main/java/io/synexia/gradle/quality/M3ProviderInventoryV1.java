/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;

/**
 * Bounded, read-only admission of the original Maven provider inventory wire.
 * The Maven CLI and its ServiceLoader remain the only providers of inventory
 * facts. Capability slugs match the additive Maven #203 SPI v1 vocabulary.
 */
final class M3ProviderInventoryV1 {
    private static final String CAPABILITY =
            "(compiler|interpreter|transpiler|atomizer|patternizer|linker|optimizer|assembler)";

    private M3ProviderInventoryV1() {}

    static void validate(String report) throws IOException {
        if (report.indexOf('\r') >= 0 || report.indexOf('\0') >= 0 || !report.endsWith("\n")) {
            throw new IOException("M3 provider inventory requires canonical LF UTF-8");
        }
        String[] lines = report.split("\n", -1);
        if (lines.length < 3 || lines.length > 514 || !lines[0].equals("M3-POLYGLOT-PROVIDERS-V1")) {
            throw new IOException("unknown or oversized M3 provider inventory schema");
        }
        String previous = "";
        for (int row = 1; row < lines.length - 1; row++) {
            String[] fields = lines[row].split("\t", -1);
            if (fields.length != 4 || !"provider".equals(fields[0])
                    || !fields[1].matches("[a-z][a-z0-9.-]{0,63}")
                    || fields[1].compareTo(previous) <= 0) {
                throw new IOException("invalid or unsorted M3 provider inventory row");
            }
            tokens(fields[2], CAPABILITY);
            tokens(fields[3], "[a-z][a-z0-9+._-]{0,63}");
            previous = fields[1];
        }
    }

    private static void tokens(String value, String pattern) throws IOException {
        String previous = "";
        for (String token : value.split(",", -1)) {
            if (!token.matches(pattern) || token.compareTo(previous) <= 0) {
                throw new IOException("invalid or unsorted M3 provider capability/language token");
            }
            previous = token;
        }
    }
}
