/*
 * Copyright 2026 the original author or authors.
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
// SPDX-License-Identifier: Apache-2.0
package org.gradle.api.internal.tasks.compile;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Receipt and callback contracts of the injected native tool, independent of its classfile parser. */
public class M3JdkDependencyToolCompatibilityTest {
    private static final String SCHEMA = "M3JDK21_CLASS_DEPS_V1";
    private static final String CLASS_NAME = "cC5Qcm9iZQ";
    private static final int MAX_RECEIPT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_ROWS = 131_072;
    private static final long MAX_CLASS_BYTES = 32L * 1024 * 1024;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Path selectedJdk;
    private Path classFile;
    private String inputSha256;

    @Before
    public void createInput() throws Exception {
        selectedJdk = temporary.newFolder("selected-jdk").toPath();
        classFile = temporary.newFile("Probe.class").toPath();
        Files.write(classFile, new byte[] {1, 2, 3, 4});
        inputSha256 = sha256(classFile);
    }

    @Test
    public void publicSignatureAndErasedInjectionSignatureRemainAvailable() throws Exception {
        Method publicInspect = M3JdkDependencyTool.class.getMethod("inspect", Path.class, Path.class, boolean.class);
        assertTrue(Modifier.isPublic(publicInspect.getModifiers()));
        assertTrue(Modifier.isStatic(publicInspect.getModifiers()));
        assertEquals(Optional.class, publicInspect.getReturnType());
        assertArrayEquals(new Class<?>[] {IOException.class}, publicInspect.getExceptionTypes());

        Method injectedInspect = M3JdkDependencyTool.class.getDeclaredMethod(
            "inspect", Path.class, Path.class, boolean.class, Supplier.class, Path.class);
        assertFalse(Modifier.isPublic(injectedInspect.getModifiers()));
        assertEquals("java.util.function.Supplier<? extends java.util.Optional<?>>",
            injectedInspect.getGenericParameterTypes()[3].getTypeName());
    }

    @Test
    public void lfReceiptsRemainExactAndStaticInspectionRemainsUncached() throws Exception {
        for (boolean apiOnly : new boolean[] {false, true}) {
            String expected = receipt(apiOnly, "\n", "amF2YS5sYW5nLk9iamVjdA", "cC5EZXBlbmRlbmN5");
            FixtureProvider provider = new FixtureProvider(expected);

            assertEquals(expected, inspectReceipt(provider, apiOnly));
            assertEquals(expected, inspectReceipt(provider, apiOnly));
            assertEquals(2, provider.calls);
            assertEquals(Arrays.asList("lookup", "name", "run", "lookup", "name", "run"), provider.events);
            assertArrayEquals(apiOnly
                ? new String[] {"--api", classFile.toString()}
                : new String[] {classFile.toString()}, provider.arguments);
        }
    }

    @Test
    public void uniformCrLfHeadersAndRowsRemainExact() throws Exception {
        for (boolean apiOnly : new boolean[] {false, true}) {
            String expected = receipt(apiOnly, "\r\n", "amF2YS5sYW5nLk9iamVjdA", "cC5EZXBlbmRlbmN5");
            FixtureProvider provider = new FixtureProvider(expected);

            assertEquals(expected, inspectReceipt(provider, apiOnly));
            assertEquals(1, provider.calls);
            assertArrayEquals(apiOnly
                ? new String[] {"--api", classFile.toString()}
                : new String[] {classFile.toString()}, provider.arguments);
        }
    }

    @Test
    public void zeroRowReceiptsAcceptBothUniformLineEndings() throws Exception {
        for (String ending : new String[] {"\n", "\r\n"}) {
            String expected = receipt(false, ending);
            assertEquals(expected, inspectReceipt(new FixtureProvider(expected), false));
        }
    }

    @Test
    public void mixedLineEndingsAndBareCarriageReturnsAreRefused() throws Exception {
        List<String> malformed = Arrays.asList(
            header("CLASS", inputSha256, CLASS_NAME, "1", "\n") + "D\tQQ\r\n",
            header("CLASS", inputSha256, CLASS_NAME, "1", "\r\n") + "D\tQQ\n",
            header("CLASS", inputSha256, CLASS_NAME, "2", "\n") + "D\tQQ\nD\tQg\r\n",
            header("CLASS", inputSha256, CLASS_NAME, "2", "\r\n") + "D\tQQ\r\nD\tQg\n",
            header("CLASS", inputSha256, CLASS_NAME, "0", "\r"),
            header("CLASS", inputSha256, CLASS_NAME, "1", "\r\r\n") + "D\tQQ\r\n",
            header("CLASS", inputSha256, CLASS_NAME, "1", "\n") + "D\tQ\rQ\n"
        );
        for (String facts : malformed) {
            assertReceiptRefused(facts);
        }
    }

    @Test
    public void schemaModeHeaderShapeAndStrongClassNameValidationAreRetained() throws Exception {
        String valid = receipt(false, "\n");
        for (String facts : Arrays.asList(
            valid.replace(SCHEMA, "FOREIGN"),
            valid.replace("\tCLASS\t", "\tPUBLIC_API\t"),
            header("CLASS", inputSha256, CLASS_NAME, "0", ""),
            header("CLASS", inputSha256, CLASS_NAME, "0\textra", "\n"),
            SCHEMA + "\tCLASS\t" + inputSha256 + "\t0\n"
        )) {
            assertReceiptRefused(facts);
        }
        for (String hash : Arrays.asList("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64))) {
            assertReceiptRefused(header("CLASS", hash, CLASS_NAME, "0", "\n"));
        }
        for (String name : Arrays.asList("", "p.Probe", "cC5Qcm9iZQ=", "bad+name", "bad/name", "bad name", "bad\tname", "\u00e9")) {
            assertReceiptRefused(header("CLASS", inputSha256, name, "0", "\n"));
        }
    }

    @Test
    public void rowPrefixesNamesAndTerminationRemainStrict() throws Exception {
        String prefix = header("CLASS", inputSha256, CLASS_NAME, "1", "\n");
        for (String row : Arrays.asList(
            "X\tQQ\n", "D QQ\n", "D\t\n", "D\tQQ", "\n", "D\tQ Q\n",
            "D\tQQ=\n", "D\tQ+Q\n", "D\tQ/Q\n", "D\tQ\tQ\n", "D\t\u00e9\n"
        )) {
            assertReceiptRefused(prefix + row);
        }
    }

    @Test
    public void declaredCountsRejectMalformedIncompleteAndExcessiveRows() throws Exception {
        for (String declared : Arrays.asList("", "one", "1.0", "2147483648", "-1", "131073")) {
            assertReceiptRefused(header("CLASS", inputSha256, CLASS_NAME, declared, "\n"));
        }
        assertReceiptRefused(header("CLASS", inputSha256, CLASS_NAME, "1", "\n"));
        assertReceiptRefused(header("CLASS", inputSha256, CLASS_NAME, "0", "\n") + "D\tQQ\n");
        assertReceiptRefused(header("CLASS", inputSha256, CLASS_NAME, "1", "\n") + "D\tQQ\nD\tQg\n");
    }

    @Test
    public void maximumDeclaredRowsAreAcceptedWithoutChangingTheReceipt() throws Exception {
        String facts = header("CLASS", inputSha256, CLASS_NAME, Integer.toString(MAX_ROWS), "\n")
            + "D\tQQ\n".repeat(MAX_ROWS);
        FixtureProvider provider = new FixtureProvider(facts);

        assertEquals(facts, inspectReceipt(provider, false));
        assertEquals(1, provider.calls);
    }

    @Test
    public void outputBudgetAcceptsItsBoundaryAndRejectsOneMoreCharacter() throws Exception {
        String prefix = header("CLASS", inputSha256, CLASS_NAME, "1", "\n");
        int nameLength = MAX_RECEIPT_CHARS - prefix.length() - 3;
        String exact = prefix + "D\t" + "A".repeat(nameLength) + "\n";
        assertEquals(MAX_RECEIPT_CHARS, exact.length());
        FixtureProvider accepted = new FixtureProvider(exact);

        assertEquals(exact, inspectReceipt(accepted, false));
        assertFalse(accepted.outputWriteError);

        FixtureProvider exceeded = new FixtureProvider(prefix + "D\t" + "A".repeat(nameLength + 1) + "\n");
        assertThrows(IOException.class, () -> inspect(exceeded, false));
        assertEquals(1, exceeded.calls);
        assertTrue(exceeded.outputWriteError);
    }

    @Test
    public void nonemptyDiagnosticsAreRefusedEvenWithSuccessfulStatusAndValidFacts() throws Exception {
        for (String diagnostic : Arrays.asList(" ", "\n", "native warning")) {
            FixtureProvider provider = new FixtureProvider(receipt(false, "\n"));
            provider.diagnostics = diagnostic;

            assertThrows(IOException.class, () -> inspect(provider, false));
            assertEquals(1, provider.calls);
            assertFalse(provider.diagnosticWriteError);
        }
    }

    @Test
    public void diagnosticWriterHasItsOriginalBoundaryAndOverflowFailure() throws Exception {
        FixtureProvider exact = new FixtureProvider(receipt(false, "\n"));
        exact.diagnostics = "x".repeat(4096);
        assertThrows(IOException.class, () -> inspect(exact, false));
        assertFalse(exact.diagnosticWriteError);

        FixtureProvider exceeded = new FixtureProvider(receipt(false, "\n"));
        exceeded.diagnostics = "x".repeat(4097);
        assertThrows(IOException.class, () -> inspect(exceeded, false));
        assertTrue(exceeded.diagnosticWriteError);
    }

    @Test
    public void nonzeroStatusIsRefusedAndProviderExceptionsRemainVisible() throws Exception {
        FixtureProvider failed = new FixtureProvider(receipt(false, "\n"));
        failed.status = 7;
        assertThrows(IOException.class, () -> inspect(failed, false));
        assertEquals(1, failed.calls);

        FixtureProvider throwing = new FixtureProvider(receipt(false, "\n"));
        IllegalStateException original = new IllegalStateException("selected provider failed");
        throwing.failure = original;
        assertSame(original, assertThrows(IllegalStateException.class, () -> inspect(throwing, false)));
        assertEquals(Arrays.asList("lookup", "name", "run"), throwing.events);
    }

    @Test
    public void differentToolchainReturnsUnavailableBeforeInputInspectionOrDiscovery() throws Exception {
        Path otherJdk = temporary.newFolder("other-jdk").toPath();
        Path absentInput = temporary.getRoot().toPath().resolve("absent.class");
        List<String> events = new ArrayList<>();
        Supplier<Optional<?>> lookup = () -> {
            events.add("lookup");
            throw new AssertionError("foreign toolchain must not discover a provider");
        };

        assertFalse(M3JdkDependencyTool.inspect(otherJdk, absentInput, false, lookup, selectedJdk).isPresent());
        assertTrue(events.isEmpty());
    }

    @Test
    public void missingDirectoryAndSymlinkInputsFailBeforeProviderDiscovery() throws Exception {
        Path absent = temporary.getRoot().toPath().resolve("absent.class");
        Path directory = temporary.newFolder("input-directory").toPath();
        Path symlink = temporary.getRoot().toPath().resolve("Alias.class");
        Files.createSymbolicLink(symlink, classFile);
        List<String> events = new ArrayList<>();
        Supplier<Optional<?>> lookup = () -> {
            events.add("lookup");
            throw new AssertionError("invalid input must not discover a provider");
        };

        for (Path input : Arrays.asList(absent, directory, symlink)) {
            assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(selectedJdk, input, false, lookup, selectedJdk));
        }
        assertTrue(events.isEmpty());
    }

    @Test
    public void providerAbsenceIsARealMissAfterInputValidation() throws Exception {
        List<String> events = new ArrayList<>();
        Supplier<Optional<?>> lookup = () -> {
            events.add("lookup");
            return Optional.empty();
        };

        assertFalse(M3JdkDependencyTool.inspect(selectedJdk, classFile, false, lookup, selectedJdk).isPresent());
        assertEquals(Arrays.asList("lookup"), events);
        events.clear();
        Path absent = temporary.getRoot().toPath().resolve("absent.class");
        assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(selectedJdk, absent, false, lookup, selectedJdk));
        assertTrue(events.isEmpty());
    }

    @Test
    public void wrongNamedProviderIsRefusedWithoutExecution() throws Exception {
        FixtureProvider provider = new FixtureProvider(receipt(false, "\n"));
        provider.toolName = "jdeps";

        assertThrows(IOException.class, () -> inspect(provider, false));
        assertEquals(0, provider.calls);
        assertEquals(Arrays.asList("lookup", "name"), provider.events);
    }

    @Test
    public void nullArgumentValidationPrecedesProviderDiscovery() throws Exception {
        List<String> events = new ArrayList<>();
        Supplier<Optional<?>> lookup = () -> {
            events.add("lookup");
            throw new AssertionError("null arguments must not discover a provider");
        };

        assertThrows(NullPointerException.class, () -> M3JdkDependencyTool.inspect(null, classFile, false, lookup, selectedJdk));
        assertThrows(NullPointerException.class, () -> M3JdkDependencyTool.inspect(selectedJdk, null, false, lookup, selectedJdk));
        assertThrows(NullPointerException.class, () -> M3JdkDependencyTool.inspect(selectedJdk, classFile, false, null, selectedJdk));
        assertThrows(NullPointerException.class, () -> M3JdkDependencyTool.inspect(selectedJdk, classFile, false, lookup, null));
        assertTrue(events.isEmpty());
    }

    @Test
    public void sameSizeMutationWithRestoredTimestampIsRefusedAfterNativeExecution() throws Exception {
        FileTime originalTime = Files.getLastModifiedTime(classFile);
        FixtureProvider provider = new FixtureProvider(receipt(false, "\n"));
        provider.afterWrite = () -> {
            try {
                Files.write(classFile, new byte[] {4, 3, 2, 1});
                Files.setLastModifiedTime(classFile, originalTime);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        };

        assertThrows(IOException.class, () -> inspect(provider, false));
        assertEquals(4L, Files.size(classFile));
        assertEquals(originalTime, Files.getLastModifiedTime(classFile));
        assertEquals(1, provider.calls);
    }

    @Test
    public void foreignContentDigestIsRefusedEvenWithValidReceiptFraming() throws Exception {
        String facts = header("CLASS", "0".repeat(64), CLASS_NAME, "0", "\n");
        FixtureProvider provider = new FixtureProvider(facts);

        assertThrows(IOException.class, () -> inspect(provider, false));
        assertEquals(1, provider.calls);
    }

    @Test
    public void classByteBudgetAcceptsItsBoundaryAndRejectsOneMoreByte() throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(classFile.toFile(), "rw")) {
            input.setLength(MAX_CLASS_BYTES);
        }
        inputSha256 = sha256(classFile);
        String facts = receipt(false, "\n");
        FixtureProvider accepted = new FixtureProvider(facts);
        assertEquals(facts, inspectReceipt(accepted, false));

        try (RandomAccessFile input = new RandomAccessFile(classFile.toFile(), "rw")) {
            input.setLength(MAX_CLASS_BYTES + 1);
        }
        FixtureProvider exceeded = new FixtureProvider(facts);
        IOException failure = assertThrows(IOException.class, () -> inspect(exceeded, false));
        assertTrue(failure.getMessage().contains("class byte budget exceeded"));
        assertEquals(1, exceeded.calls);
    }

    private Optional<String> inspect(FixtureProvider provider, boolean apiOnly) throws IOException {
        Supplier<Optional<?>> lookup = () -> {
            provider.events.add("lookup");
            return Optional.of(provider);
        };
        return M3JdkDependencyTool.inspect(selectedJdk, classFile, apiOnly, lookup, selectedJdk);
    }

    private String inspectReceipt(FixtureProvider provider, boolean apiOnly) throws IOException {
        Optional<String> result = inspect(provider, apiOnly);
        assertTrue("The explicitly selected native fixture must produce its receipt", result.isPresent());
        return result.get();
    }

    private void assertReceiptRefused(String facts) {
        FixtureProvider provider = new FixtureProvider(facts);
        assertThrows(IOException.class, () -> inspect(provider, false));
        assertEquals(1, provider.calls);
    }

    private String receipt(boolean apiOnly, String ending, String... names) {
        StringBuilder facts = new StringBuilder(header(apiOnly ? "PUBLIC_API" : "CLASS", inputSha256,
            CLASS_NAME, Integer.toString(names.length), ending));
        for (String name : names) {
            facts.append("D\t").append(name).append(ending);
        }
        return facts.toString();
    }

    private static String header(String mode, String hash, String name, String count, String ending) {
        return SCHEMA + "\t" + mode + "\t" + hash + "\t" + name + "\t" + count + ending;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[32 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** A named ToolProvider exercises the existing injection seam; it does not parse classes. */
    private static final class FixtureProvider implements ToolProvider {
        private final String facts;
        private final List<String> events = new ArrayList<>();
        private String toolName = "m3-class-deps";
        private String diagnostics = "";
        private String[] arguments;
        private int status;
        private int calls;
        private boolean outputWriteError;
        private boolean diagnosticWriteError;
        private RuntimeException failure;
        private Runnable afterWrite;

        private FixtureProvider(String facts) {
            this.facts = facts;
        }

        @Override
        public String name() {
            events.add("name");
            return toolName;
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... args) {
            events.add("run");
            calls++;
            arguments = args.clone();
            if (failure != null) {
                throw failure;
            }
            out.write(facts);
            err.write(diagnostics);
            outputWriteError = out.checkError();
            diagnosticWriteError = err.checkError();
            if (afterWrite != null) {
                afterWrite.run();
            }
            return status;
        }
    }
}
