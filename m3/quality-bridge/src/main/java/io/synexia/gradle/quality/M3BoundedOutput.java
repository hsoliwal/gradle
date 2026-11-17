/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Bounded byte capture shared by Maven-backed Gradle proxy tasks. */
final class M3BoundedOutput extends OutputStream {
    private final int limit;
    private final String label;
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();

    M3BoundedOutput(int limit, String label) {
        if (limit <= 0) throw new IllegalArgumentException("invalid output budget");
        this.limit = limit;
        this.label = label;
    }

    @Override
    public void write(int next) throws IOException {
        if (data.size() >= limit) throw new IOException(label + " exceeds output budget");
        data.write(next);
    }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        if (length > limit - data.size()) throw new IOException(label + " exceeds output budget");
        data.write(source, offset, length);
    }

    byte[] bytes() { return data.toByteArray(); }
}
