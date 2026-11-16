/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.tasks.compile;

import org.gradle.api.JavaVersion;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

/**
 * Optional integration of the M3JDK21-owned original jdeps class dependency
 * service into Gradle's existing selected Java compiler worker. No source AST,
 * independent classfile scanner or alternate task/toolchain is created.
 * <p>
 * A compiler worker running under another JDK must not silently use that
 * runtime's tools to analyze classes produced by its selected toolchain.
 * External/forked selected toolchains need a separately qualified process
 * protocol; the in-process path deliberately reports unavailable.
 */
public final class M3JdkDependencyTool {
    private static final String TOOL = "m3-class-deps";
    private static final String SCHEMA = "M3JDK21_CLASS_DEPS_V1";
    private static final int MAX_CHARS = 4 * 1024 * 1024;
    private static final String HEX = "0123456789abcdef";

    private M3JdkDependencyTool() {
    }

    /**
     * Inspects one class through the selected runtime's optional native provider, without retaining its receipt.
     *
     * @param selectedJdkHome the JDK selected by the existing compiler worker
     * @param classFile the original regular, non-symlink class file
     * @param apiOnly whether to request public-API dependencies
     * @return the exact validated native receipt, or empty when the selected native provider is unavailable
     * @throws IOException when input admission, native analysis or receipt validation fails
     */
    public static Optional<String> inspect(Path selectedJdkHome, Path classFile, boolean apiOnly) throws IOException {
        return inspect(selectedJdkHome, classFile, apiOnly,
            M3JdkDependencyTool::findNativeTool, Paths.get(System.getProperty("java.home")));
    }

    private static Optional<?> findNativeTool() {
        return NativeTool.find();
    }

    /** Explicit provider injection permits compiler-worker tests without a stock-JDK fallback. */
    static Optional<String> inspect(Path selectedJdkHome, Path classFile, boolean apiOnly,
                                   Supplier<? extends Optional<?>> provider, Path runtimeHome) throws IOException {
        Optional<?> selected = selectProvider(selectedJdkHome, classFile, provider, runtimeHome);
        if (!selected.isPresent()) {
            return Optional.empty();
        }
        requireProvider(selected.get());
        return Optional.of(invoke(selected.get(), classFile, apiOnly));
    }

    private static Optional<?> selectProvider(Path selectedJdkHome, Path classFile,
                                             Supplier<? extends Optional<?>> provider, Path runtimeHome) throws IOException {
        Objects.requireNonNull(selectedJdkHome, "selectedJdkHome");
        Objects.requireNonNull(classFile, "classFile");
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(runtimeHome, "runtimeHome");
        if (!Files.isSameFile(selectedJdkHome, runtimeHome)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(classFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3JDK21 class input must be a regular non-symlink file");
        }
        // Keep all executable and reflective SPI signatures behind the original Java-version owner.
        if (!JavaVersion.current().isJava9Compatible()) {
            return Optional.empty();
        }
        return provider.get();
    }

    private static void requireProvider(Object selected) throws IOException {
        if (!TOOL.equals(NativeTool.name(selected))) {
            throw new IOException("non-M3JDK build tool provider");
        }
    }

    /**
     * Creates an explicitly owned scope for reuse of validated native dependency receipts.
     * Construction does not select or invoke a provider. The caller must close the scope.
     *
     * @return a fresh session with no retained receipts
     */
    public static Session newSession() {
        return newSession(M3JdkDependencyTool::findNativeTool, Paths.get(System.getProperty("java.home")));
    }

    /** Explicit provider injection is confined to compiler-worker tests. */
    static Session newSession(Supplier<? extends Optional<?>> provider, Path runtimeHome) {
        return new Session(Objects.requireNonNull(provider, "provider"), Objects.requireNonNull(runtimeHome, "runtimeHome"));
    }

    /**
     * Bounded caller-owned reuse of exact native receipts. This scope has no authority to
     * skip a task, restore a build-cache entry or decide incremental compilation.
     * <p>
     * The sorted parallel-array storage adapts Apache Maven's DefaultM3BuildToolsService
     * (Apache Maven, Copyright 2001-2026 The Apache Software Foundation, Apache-2.0).
     * Modified for Gradle's worker compatibility, stricter validator, aggregate text
     * limit, provider-class epochs and explicit close/reentrancy contract.
     */
    public static final class Session implements AutoCloseable {
        private static final int MAX_ENTRIES = 256;
        private static final int MAX_RETAINED_CHARS = 8 * 1024 * 1024;

        private final Supplier<? extends Optional<?>> provider;
        private final Path runtimeHome;
        private String[] keys = new String[0];
        private String[] digests = new String[0];
        private String[] receipts = new String[0];
        private @Nullable Class<?> providerClass;
        private int size;
        private int retainedChars;
        private boolean inspecting;
        private boolean closed;

        private Session(Supplier<? extends Optional<?>> provider, Path runtimeHome) {
            this.provider = provider;
            this.runtimeHome = runtimeHome;
        }

        /**
         * Returns exact validated native facts, reusing a receipt only after current
         * provider identity and current class bytes have been reauthenticated.
         *
         * @param selectedJdkHome the JDK selected by the existing compiler worker
         * @param classFile the original regular, non-symlink class file
         * @param apiOnly whether to request public-API dependencies
         * @return the exact native receipt, or empty when the selected provider is unavailable
         * @throws IOException when admission, analysis, validation or a session budget fails
         * @throws IllegalStateException when closed or entered recursively by a provider callback
         */
        public synchronized Optional<String> inspect(Path selectedJdkHome, Path classFile, boolean apiOnly) throws IOException {
            requireOpen();
            if (inspecting) {
                throw new IllegalStateException("reentrant M3 build receipt inspection");
            }
            inspecting = true;
            try {
                Optional<?> selected = selectProvider(selectedJdkHome, classFile, provider, runtimeHome);
                requireOpen();
                if (!selected.isPresent()) {
                    return Optional.empty();
                }
                Object tool = selected.get();
                if (providerClass != tool.getClass()) {
                    clearReceipts();
                    providerClass = tool.getClass();
                }
                requireProvider(tool);
                requireOpen();
                // Provider callbacks run before this check and before authenticating any cached bytes.
                if (!Files.isRegularFile(classFile, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("M3JDK21 class input must be a regular non-symlink file");
                }
                String key = classFile.toAbsolutePath().normalize().toString() + '\0' + (apiOnly ? 'A' : 'C');
                int at = Arrays.binarySearch(keys, 0, size, key);
                if (at >= 0) {
                    if (sha256(classFile).equals(digests[at])) {
                        return Optional.of(receipts[at]);
                    }
                    remove(at);
                }
                // A changed identity has already released its stale slot before any replacement analysis.
                if (size == MAX_ENTRIES) {
                    throw new IOException("M3 build receipt session entry budget exceeded");
                }
                String receipt = invoke(tool, classFile, apiOnly);
                requireOpen();
                String prefix = SCHEMA + "\t" + (apiOnly ? "PUBLIC_API" : "CLASS") + "\t";
                String digest = receipt.substring(prefix.length(), prefix.length() + 64);
                long added = (long) key.length() + digest.length() + receipt.length();
                if (added > MAX_RETAINED_CHARS - retainedChars) {
                    throw new IOException("M3 build receipt session text budget exceeded");
                }
                int slot = Arrays.binarySearch(keys, 0, size, key);
                if (slot >= 0) {
                    throw new IllegalStateException("duplicate M3 build receipt identity");
                }
                slot = -slot - 1;
                if (size == keys.length) {
                    int capacity = Math.min(MAX_ENTRIES, Math.max(8, keys.length * 2));
                    keys = Arrays.copyOf(keys, capacity);
                    digests = Arrays.copyOf(digests, capacity);
                    receipts = Arrays.copyOf(receipts, capacity);
                }
                System.arraycopy(keys, slot, keys, slot + 1, size - slot);
                System.arraycopy(digests, slot, digests, slot + 1, size - slot);
                System.arraycopy(receipts, slot, receipts, slot + 1, size - slot);
                keys[slot] = key;
                digests[slot] = digest;
                receipts[slot] = receipt;
                size++;
                retainedChars += (int) added;
                return Optional.of(receipt);
            } finally {
                inspecting = false;
            }
        }

        /** Releases retained facts and makes subsequent inspection fail; repeated close is harmless. */
        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                clearReceipts();
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("M3 build receipt session is closed");
            }
        }

        private void clearReceipts() {
            keys = new String[0];
            digests = new String[0];
            receipts = new String[0];
            providerClass = null;
            size = 0;
            retainedChars = 0;
        }

        private void remove(int index) {
            retainedChars -= keys[index].length() + digests[index].length() + receipts[index].length();
            int remaining = size - index - 1;
            System.arraycopy(keys, index + 1, keys, index, remaining);
            System.arraycopy(digests, index + 1, digests, index, remaining);
            System.arraycopy(receipts, index + 1, receipts, index, remaining);
            size--;
            keys[size] = null;
            digests[size] = null;
            receipts[size] = null;
        }
    }

    private static String invoke(Object provider, Path classFile, boolean apiOnly) throws IOException {
        LimitedWriter output = new LimitedWriter(MAX_CHARS);
        LimitedWriter diagnostics = new LimitedWriter(4096);
        PrintWriter out = new PrintWriter(output);
        PrintWriter err = new PrintWriter(diagnostics);
        int status = NativeTool.run(provider, out, err, classFile, apiOnly);
        out.flush();
        err.flush();
        if (status != 0 || output.overflow || diagnostics.overflow
            || out.checkError() || err.checkError() || !diagnostics.toString().isEmpty()) {
            throw new IOException("native M3JDK21 class dependency tool failed: " + status);
        }
        String facts = output.toString();
        String expectedHeader = SCHEMA + "\t" + (apiOnly ? "PUBLIC_API" : "CLASS") + "\t";
        if (!facts.startsWith(expectedHeader)) {
            throw new IOException("foreign M3JDK21 dependency fact schema");
        }
        int end = facts.indexOf('\n');
        if (end < 0) {
            throw new IOException("missing SDK dependency receipt header");
        }
        // The original native producer uses platform line endings. Preserve receipt bytes and reject mixtures.
        boolean crlf = end > 0 && facts.charAt(end - 1) == '\r';
        String[] fields = facts.substring(0, crlf ? end - 1 : end).split("\t", -1);
        if (fields.length != 5 || !fields[2].matches("[0-9a-f]{64}")
            || !fields[3].matches("[A-Za-z0-9_-]+")) {
            throw new IOException("invalid SDK dependency receipt identity");
        }
        validateRows(facts, end + 1, fields[4], crlf);
        if (!sha256(classFile).equals(fields[2])) {
            throw new IOException("class changed during native SDK dependency analysis");
        }
        return facts;
    }

    /** Verify bounded provider rows before giving plugins the claimed SDK facts. */
    private static void validateRows(String facts, int start, String declared, boolean crlf) throws IOException {
        final int expected;
        try {
            expected = Integer.parseInt(declared);
        } catch (NumberFormatException invalid) {
            throw new IOException("invalid class dependency count", invalid);
        }
        if (expected < 0 || expected > 131_072) {
            throw new IOException("class dependency row budget exceeded");
        }
        int found = 0;
        while (start < facts.length()) {
            int end = facts.indexOf('\n', start);
            boolean rowCrlf = end > start && facts.charAt(end - 1) == '\r';
            int contentEnd = rowCrlf ? end - 1 : end;
            if (end < 0 || rowCrlf != crlf || contentEnd < start + 3 || facts.charAt(start) != 'D'
                || facts.charAt(start + 1) != '\t') {
                throw new IOException("malformed native class dependency row");
            }
            for (int pos = start + 2; pos < contentEnd; pos++) {
                char ch = facts.charAt(pos);
                if (!(ch >= 'A' && ch <= 'Z') && !(ch >= 'a' && ch <= 'z')
                    && !(ch >= '0' && ch <= '9') && ch != '_' && ch != '-') {
                    throw new IOException("malformed native class dependency name");
                }
            }
            if (++found > expected) {
                throw new IOException("excess native dependency rows");
            }
            start = end + 1;
        }
        if (found != expected) {
            throw new IOException("incomplete native dependency receipt");
        }
    }

    private static String sha256(Path classFile) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        long size = 0;
        try (InputStream input = Files.newInputStream(classFile)) {
            byte[] buffer = new byte[32 * 1024];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read == 0) {
                    throw new IOException("stalled class input");
                }
                size += read;
                if (size > 32L * 1024 * 1024) {
                    throw new IOException("class byte budget exceeded");
                }
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        char[] hex = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            hex[index * 2] = HEX.charAt(value >>> 4);
            hex[index * 2 + 1] = HEX.charAt(value & 0xf);
        }
        return new String(hex);
    }

    /** The worker's Java 8 path never loads this newer-JDK SPI holder. */
    private static final class NativeTool {
        private static Optional<?> find() {
            return ToolProvider.findFirst(TOOL);
        }

        private static String name(Object provider) {
            return ((ToolProvider) provider).name();
        }

        private static int run(Object provider, PrintWriter out, PrintWriter err, Path classFile, boolean apiOnly) {
            ToolProvider selected = (ToolProvider) provider;
            return apiOnly ? selected.run(out, err, "--api", classFile.toString())
                : selected.run(out, err, classFile.toString());
        }
    }

    private static final class LimitedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private final int budget;
        private boolean overflow;

        private LimitedWriter(int budget) {
            this.budget = budget;
        }

        @Override
        public void write(char[] data, int offset, int length) throws IOException {
            int arrayLength = data.length;
            if (offset < 0 || length < 0 || offset > arrayLength - length) {
                throw new IndexOutOfBoundsException("invalid tool output range");
            }
            if (length > budget - content.length()) {
                overflow = true;
                throw new IOException("tool receipt budget exceeded");
            }
            content.append(data, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }

        @Override
        public String toString() {
            return content.toString();
        }
    }
}
