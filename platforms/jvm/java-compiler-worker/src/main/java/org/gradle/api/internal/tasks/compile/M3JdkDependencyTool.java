/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package org.gradle.api.internal.tasks.compile;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.io.InputStream;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.HexFormat;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

/**
 * Optional integration of the M3JDK21-owned original jdeps class dependency
 * service into Gradle's existing selected Java compiler worker. No source AST,
 * independent classfile scanner or alternate task/toolchain is created.
 *
 * <p>A compiler worker running under another JDK must not silently use that
 * runtime's tools to analyze classes produced by its selected toolchain.
 * External/forked selected toolchains need a separately qualified process
 * protocol; the in-process path deliberately reports unavailable.</p>
 */
public final class M3JdkDependencyTool {
    private static final String TOOL = "m3-class-deps";
    private static final String SCHEMA = "M3JDK21_CLASS_DEPS_V1";
    private static final int MAX_CHARS = 4 * 1024 * 1024;

    private M3JdkDependencyTool() { }

    public static Optional<String> inspect(Path selectedJdkHome, Path classFile, boolean apiOnly)
            throws IOException {
        return inspect(selectedJdkHome, classFile, apiOnly,
                () -> ToolProvider.findFirst(TOOL), Path.of(System.getProperty("java.home")));
    }

    /** Explicit provider injection permits compiler-worker tests without a stock-JDK fallback. */
    static Optional<String> inspect(Path selectedJdkHome, Path classFile, boolean apiOnly,
            Supplier<Optional<ToolProvider>> provider, Path runtimeHome) throws IOException {
        Objects.requireNonNull(selectedJdkHome, "selectedJdkHome");
        Objects.requireNonNull(classFile, "classFile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(runtimeHome, "runtimeHome");
        if (!Files.isSameFile(selectedJdkHome, runtimeHome)) return Optional.empty();
        if (!Files.isRegularFile(classFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3JDK21 class input must be a regular non-symlink file");
        }
        Optional<ToolProvider> selected = provider.get();
        if (selected.isEmpty()) return Optional.empty();
        if (!TOOL.equals(selected.get().name())) throw new IOException("non-M3JDK build tool provider");
        LimitedWriter output = new LimitedWriter(MAX_CHARS);
        LimitedWriter diagnostics = new LimitedWriter(4096);
        PrintWriter out = new PrintWriter(output), err = new PrintWriter(diagnostics);
        int status = apiOnly
                ? selected.get().run(out, err, "--api", classFile.toString())
                : selected.get().run(out, err, classFile.toString());
        out.flush(); err.flush();
        if (status != 0 || output.overflow || diagnostics.overflow
                || out.checkError() || err.checkError() || !diagnostics.toString().isEmpty()) {
            throw new IOException("native M3JDK21 class dependency tool failed: " + status);
        }
        String facts = output.toString();
        String expectedHeader = SCHEMA + "\t" + (apiOnly ? "PUBLIC_API" : "CLASS") + "\t";
        if (!facts.startsWith(expectedHeader)) throw new IOException("foreign M3JDK21 dependency fact schema");
        int end = facts.indexOf('\n');
        if (end < 0) throw new IOException("missing SDK dependency receipt header");
        String[] fields = facts.substring(0, end).split("\t", -1);
        if (fields.length != 5 || !fields[2].matches("[0-9a-f]{64}")) {
            throw new IOException("invalid SDK dependency receipt identity");
        }
        if (!sha256(classFile).equals(fields[2])) {
            throw new IOException("class changed during native SDK dependency analysis");
        }
        return Optional.of(facts);
    }

    private static String sha256(Path classFile) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        long size = 0;
        try (InputStream input = Files.newInputStream(classFile)) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) throw new IOException("stalled class input");
                size += read;
                if (size > 32L * 1024 * 1024) throw new IOException("class byte budget exceeded");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private final int budget;
        private boolean overflow;
        LimitedWriter(int budget) { this.budget = budget; }
        @Override public void write(char[] data, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, data.length);
            if (length > budget - content.length()) {
                overflow = true;
                throw new IOException("tool receipt budget exceeded");
            }
            content.append(data, offset, length);
        }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return content.toString(); }
    }
}
