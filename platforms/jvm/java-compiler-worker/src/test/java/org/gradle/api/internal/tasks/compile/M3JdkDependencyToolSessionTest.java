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
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.spi.ToolProvider;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Caller-owned receipt reuse through named ToolProvider fixtures. These tests do not
 * qualify the original semantic parser or authorize native Gradle task-cache decisions.
 */
public class M3JdkDependencyToolSessionTest {
    private static final String SCHEMA = "M3JDK21_CLASS_DEPS_V1";
    private static final String CLASS_NAME = "cC5Qcm9iZQ";
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_RECEIPT_CHARS = 4 * 1024 * 1024;

    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Path runtimeHome;
    private Path classFile;

    @Before
    public void createInput() throws IOException {
        runtimeHome = temporary.newFolder("selected-jdk").toPath();
        classFile = newClass("Probe.class", 1);
    }

    @Test
    public void publicFactoryAndSessionSignaturesAreExplicit() throws Exception {
        Method factory = M3JdkDependencyTool.class.getMethod("newSession");
        assertTrue(Modifier.isPublic(factory.getModifiers()));
        assertTrue(Modifier.isStatic(factory.getModifiers()));
        assertEquals(M3JdkDependencyTool.Session.class, factory.getReturnType());
        assertTrue(AutoCloseable.class.isAssignableFrom(M3JdkDependencyTool.Session.class));

        Method inspect = M3JdkDependencyTool.Session.class.getMethod("inspect", Path.class, Path.class, boolean.class);
        assertTrue(Modifier.isSynchronized(inspect.getModifiers()));
        assertEquals(Optional.class, inspect.getReturnType());
        assertArrayEquals(new Class<?>[] {IOException.class}, inspect.getExceptionTypes());

        Method injected = M3JdkDependencyTool.class.getDeclaredMethod("newSession", Supplier.class, Path.class);
        assertFalse(Modifier.isPublic(injected.getModifiers()));
        assertEquals("java.util.function.Supplier<? extends java.util.Optional<?>>",
            injected.getGenericParameterTypes()[0].getTypeName());
    }

    @Test
    public void constructionIsDeferredAndHitsStillSelectAndNameTheProvider() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            assertEquals(0, lookup.calls.get());
            assertEquals(0, provider.nameCalls.get());
            assertEquals(0, provider.calls.get());

            String first = inspect(session, classFile, false);
            assertEquals(first, inspect(session, classFile, false));
            assertEquals(2, lookup.calls.get());
            assertEquals(2, provider.nameCalls.get());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test
    public void oneSelectedObjectSuppliesIdentityNameAndAnalysis() throws Exception {
        FixtureProvider first = new PrimaryProvider();
        FixtureProvider second = new SecondaryProvider();
        AtomicInteger selections = new AtomicInteger();
        Supplier<Optional<?>> lookup = () -> Optional.of(selections.getAndIncrement() == 0 ? first : second);
        try (M3JdkDependencyTool.Session session = M3JdkDependencyTool.newSession(lookup, runtimeHome)) {
            assertEquals(first.expected(classFile, false), inspect(session, classFile, false));
            assertEquals(1, selections.get());
            assertEquals(1, first.calls.get());
            assertEquals(0, second.calls.get());

            assertEquals(second.expected(classFile, false), inspect(session, classFile, false));
            assertEquals(2, selections.get());
            assertEquals(1, second.calls.get());
        }
    }

    @Test
    public void deterministicInsertionAndLookupOrdersPreserveEveryIdentity() throws Exception {
        Path[] files = new Path[] {
            newClass("Zulu.class", 2), newClass("Alpha.class", 3), newClass("Middle.class", 4),
            newClass("Beta.class", 5), newClass("Yankee.class", 6)
        };
        int[][] orders = new int[][] {{0, 1, 2, 3, 4}, {4, 3, 2, 1, 0}, {2, 0, 4, 1, 3}};
        for (int[] insertion : orders) {
            FixtureProvider provider = new PrimaryProvider();
            try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
                for (int index : insertion) {
                    assertEquals(provider.expected(files[index], false), inspect(session, files[index], false));
                }
                for (int[] lookup : orders) {
                    for (int index : lookup) {
                        assertEquals(provider.expected(files[index], false), inspect(session, files[index], false));
                    }
                }
                assertEquals(files.length, provider.calls.get());
            }
        }
    }

    @Test
    public void classAndPublicApiModesOwnSeparateEntries() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            String all = inspect(session, classFile, false);
            String api = inspect(session, classFile, true);
            assertNotEquals(all, api);
            assertEquals(all, inspect(session, classFile, false));
            assertEquals(api, inspect(session, classFile, true));
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    public void equalBytesAtDifferentPathsDoNotShareAnEntry() throws Exception {
        Path another = newClass("Another.class", 1);
        FixtureProvider provider = new PrimaryProvider();
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            assertEquals(inspect(session, classFile, false), inspect(session, another, false));
            assertEquals(2, provider.calls.get());
            inspect(session, classFile, false);
            inspect(session, another, false);
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    public void separateSessionsAndStaticCallsDoNotShareRetainedFacts() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        try (M3JdkDependencyTool.Session first = newSession(lookup);
             M3JdkDependencyTool.Session second = newSession(lookup)) {
            String expected = inspect(first, classFile, false);
            assertEquals(expected, inspect(second, classFile, false));
            assertEquals(2, provider.calls.get());
            assertEquals(expected, M3JdkDependencyTool.inspect(runtimeHome, classFile, false, lookup, runtimeHome).get());
            assertEquals(expected, M3JdkDependencyTool.inspect(runtimeHome, classFile, false, lookup, runtimeHome).get());
            assertEquals(4, provider.calls.get());
            assertEquals(expected, inspect(first, classFile, false));
            assertEquals(expected, inspect(second, classFile, false));
            assertEquals(4, provider.calls.get());
        }
    }

    @Test
    public void hitsRehashSameSizeBytesEvenWhenTimestampsAreRestored() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            String before = inspect(session, classFile, false);
            changeBytesWithSameTimestamp(classFile, 31);
            String after = inspect(session, classFile, false);
            assertNotEquals(before, after);
            assertEquals(provider.expected(classFile, false), after);
            assertEquals(after, inspect(session, classFile, false));
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    public void providerCallbacksCannotMutateBytesThenAuthorizeAnOldHit() throws Exception {
        for (CallbackStage stage : new CallbackStage[] {CallbackStage.LOOKUP, CallbackStage.NAME}) {
            FixtureProvider provider = new PrimaryProvider();
            Lookup lookup = new Lookup(provider);
            try (M3JdkDependencyTool.Session session = newSession(lookup)) {
                String before = inspect(session, classFile, false);
                setCallback(stage, lookup, provider, () -> {
                    try {
                        changeBytesWithSameTimestamp(classFile, stage == CallbackStage.LOOKUP ? 41 : 42);
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                });
                String after = inspect(session, classFile, false);
                assertNotEquals(before, after);
                assertEquals(provider.expected(classFile, false), after);
                assertEquals(2, provider.calls.get());
            }
        }
    }

    @Test
    public void cachedInputsAreStillRejectedWhenReplacedBySymlinks() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            inspect(session, classFile, false);
            Path backing = newClass("Backing.class", 1);
            Files.delete(classFile);
            Files.createSymbolicLink(classFile, backing);

            assertThrows(IOException.class, () -> session.inspect(runtimeHome, classFile, false));
            assertEquals(1, lookup.calls.get());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test
    public void selectedToolchainMismatchCannotReturnAnExistingHit() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        Path otherJdk = temporary.newFolder("different-jdk").toPath();
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            inspect(session, classFile, false);
            assertFalse(session.inspect(otherJdk, classFile, false).isPresent());
            assertEquals(1, lookup.calls.get());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test
    public void providerAbsenceRemainsAMissEvenWhenFactsWereRetained() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(null);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            assertFalse(session.inspect(runtimeHome, classFile, false).isPresent());
            assertFalse(session.inspect(runtimeHome, classFile, false).isPresent());
            assertEquals(2, lookup.calls.get());
            lookup.selected.set(provider);
            String expected = inspect(session, classFile, false);
            assertEquals(1, provider.calls.get());
            lookup.selected.set(null);
            assertFalse(session.inspect(runtimeHome, classFile, false).isPresent());
            assertEquals(4, lookup.calls.get());
            assertEquals(1, provider.calls.get());
            lookup.selected.set(provider);
            // Temporary absence has no specified eviction policy, but cannot authorize a stale or foreign receipt.
            assertEquals(expected, inspect(session, classFile, false));
        }
    }

    @Test
    public void wrongProviderNameCannotAuthorizeAHit() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            inspect(session, classFile, false);
            provider.toolName = "jdeps";
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, classFile, false));
            assertEquals(2, lookup.calls.get());
            assertEquals(2, provider.nameCalls.get());
            assertEquals(1, provider.calls.get());
        }
    }

    @Test
    public void aNewInstanceOfTheSameProviderClassCanReuseVerifiedFacts() throws Exception {
        FixtureProvider original = new PrimaryProvider();
        FixtureProvider replacement = new PrimaryProvider();
        Lookup lookup = new Lookup(original);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            String expected = inspect(session, classFile, false);
            lookup.selected.set(replacement);
            assertEquals(expected, inspect(session, classFile, false));
            assertEquals(1, original.calls.get());
            assertEquals(1, replacement.nameCalls.get());
            assertEquals(0, replacement.calls.get());
        }
    }

    @Test
    public void aChangedProviderClassInvalidatesTheWholeEpoch() throws Exception {
        Path another = newClass("EpochOther.class", 2);
        FixtureProvider original = new PrimaryProvider();
        FixtureProvider changed = new SecondaryProvider();
        Lookup lookup = new Lookup(original);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            inspect(session, classFile, false);
            inspect(session, another, false);
            assertEquals(2, original.calls.get());
            lookup.selected.set(changed);
            assertEquals(changed.expected(classFile, false), inspect(session, classFile, false));
            assertEquals(1, changed.calls.get());
            lookup.selected.set(original);
            assertEquals(original.expected(another, false), inspect(session, another, false));
            assertEquals("The untouched entry from the old epoch must also be invalidated", 3, original.calls.get());
        }
    }

    @Test
    public void aFailedNewProviderEpochStillInvalidatesUnrequestedOldEntries() throws Exception {
        Path another = newClass("FailedEpochOther.class", 2);
        FixtureProvider original = new PrimaryProvider();
        FixtureProvider changed = new SecondaryProvider();
        changed.fault = Fault.STATUS;
        Lookup lookup = new Lookup(original);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            inspect(session, classFile, false);
            inspect(session, another, false);
            lookup.selected.set(changed);
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, classFile, false));
            assertEquals(1, changed.calls.get());
            lookup.selected.set(original);
            assertEquals(original.expected(another, false), inspect(session, another, false));
            assertEquals(3, original.calls.get());
        }
    }

    @Test
    public void identicalBinaryNamesFromDifferentClassLoadersAreDifferentProviderIdentities() throws Exception {
        String name = ReloadableProvider.class.getName();
        String resource = name.replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input);
            bytes = input.readAllBytes();
        }
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger secondCalls = new AtomicInteger();
        ToolProvider first = new ProviderLoader(getClass().getClassLoader()).create(name, bytes, firstCalls,
            facts(classFile, false, "QQ", "\n", 0));
        ToolProvider second = new ProviderLoader(getClass().getClassLoader()).create(name, bytes, secondCalls,
            facts(classFile, false, "Qg", "\n", 0));
        assertEquals(first.getClass().getName(), second.getClass().getName());
        assertNotSame(first.getClass(), second.getClass());
        Lookup lookup = new Lookup(first);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            String original = inspect(session, classFile, false);
            lookup.selected.set(second);
            assertNotEquals(original, inspect(session, classFile, false));
            assertEquals(1, firstCalls.get());
            assertEquals(1, secondCalls.get());
            lookup.selected.set(first);
            assertEquals(original, inspect(session, classFile, false));
            assertEquals(2, firstCalls.get());
        }
    }

    @Test
    public void malformedDiagnosticsStatusAndRuntimeFailuresAreNeverRetained() throws Exception {
        for (Fault fault : new Fault[] {Fault.BAD_HEADER, Fault.STDERR, Fault.STATUS, Fault.RUNTIME}) {
            FixtureProvider provider = new PrimaryProvider();
            provider.fault = fault;
            try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
                if (fault == Fault.RUNTIME) {
                    assertSame(provider.runtimeFailure,
                        assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false)));
                } else {
                    assertThrows(IOException.class, () -> session.inspect(runtimeHome, classFile, false));
                }
                assertEquals(1, provider.calls.get());
                provider.fault = Fault.NONE;
                String accepted = inspect(session, classFile, false);
                assertEquals(accepted, inspect(session, classFile, false));
                assertEquals(2, provider.calls.get());
            }
        }
    }

    @Test
    public void retainedReceiptsKeepTheirOriginalCrLfBytes() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        provider.ending = "\r\n";
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            String expected = provider.expected(classFile, false);
            assertEquals(expected, inspect(session, classFile, false));
            assertEquals(expected, inspect(session, classFile, false));
            assertEquals(1, provider.calls.get());
        }
    }

    @Test
    public void fullCountRefusesNewAnalysisButPermitsAnExistingReplacement() throws Exception {
        Path[] files = capacityInputs();
        FixtureProvider provider = new PrimaryProvider();
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            fill(session, files);
            assertEquals(MAX_ENTRIES, provider.calls.get());
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, files[MAX_ENTRIES], false));
            assertEquals(MAX_ENTRIES, provider.calls.get());
            inspect(session, files[0], false);
            assertEquals(MAX_ENTRIES, provider.calls.get());
            changeBytesWithSameTimestamp(files[0], 91);
            assertEquals(provider.expected(files[0], false), inspect(session, files[0], false));
            assertEquals(MAX_ENTRIES + 1, provider.calls.get());
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, files[MAX_ENTRIES], false));
            assertEquals(MAX_ENTRIES + 1, provider.calls.get());
        }
    }

    @Test
    public void aFailedReplacementReleasesTheStaleSlotBeforeAnotherAdmission() throws Exception {
        Path[] files = capacityInputs();
        FixtureProvider provider = new PrimaryProvider();
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            fill(session, files);
            changeBytesWithSameTimestamp(files[0], 92);
            provider.fault = Fault.STATUS;
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, files[0], false));
            assertEquals(MAX_ENTRIES + 1, provider.calls.get());
            provider.fault = Fault.NONE;
            inspect(session, files[MAX_ENTRIES], false);
            assertEquals(MAX_ENTRIES + 2, provider.calls.get());
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, files[0], false));
            assertEquals(MAX_ENTRIES + 2, provider.calls.get());
        }
    }

    @Test
    public void textCapacityIncludesMetadataAndRejectedResultsAreNotRetained() throws Exception {
        Path another = newClass("TextOther.class", 2);
        FixtureProvider provider = new PrimaryProvider();
        provider.outputSizes.put(classFile, MAX_RECEIPT_CHARS);
        provider.outputSizes.put(another, MAX_RECEIPT_CHARS);
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            String first = inspect(session, classFile, false);
            assertEquals(MAX_RECEIPT_CHARS, first.length());
            // Two 4 Mi receipts alone fill 8 Mi; their mandatory nonempty keys and digests must exceed the bound.
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, another, false));
            assertEquals(2, provider.calls.get());
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, another, false));
            assertEquals(3, provider.calls.get());
            assertEquals(first, inspect(session, classFile, false));
            assertEquals(3, provider.calls.get());
        }
    }

    @Test
    public void textAccountingRemovesTheOldReceiptBeforeReplacingIt() throws Exception {
        Path small = newClass("Small.class", 2);
        FixtureProvider provider = new PrimaryProvider();
        provider.outputSizes.put(classFile, MAX_RECEIPT_CHARS);
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            String before = inspect(session, classFile, false);
            inspect(session, small, false);
            changeBytesWithSameTimestamp(classFile, 61);
            String after = inspect(session, classFile, false);
            assertNotEquals(before, after);
            assertEquals(MAX_RECEIPT_CHARS, after.length());
            assertEquals(after, inspect(session, classFile, false));
            inspect(session, small, false);
            assertEquals(3, provider.calls.get());
        }
    }

    @Test
    public void failedReplacementAlsoReleasesRetainedTextCapacity() throws Exception {
        Path small = newClass("Small.class", 2);
        Path replacement = newClass("LargeReplacement.class", 3);
        FixtureProvider provider = new PrimaryProvider();
        provider.outputSizes.put(classFile, MAX_RECEIPT_CHARS);
        provider.outputSizes.put(replacement, MAX_RECEIPT_CHARS);
        try (M3JdkDependencyTool.Session session = newSession(new Lookup(provider))) {
            inspect(session, classFile, false);
            inspect(session, small, false);
            changeBytesWithSameTimestamp(classFile, 62);
            provider.fault = Fault.STATUS;
            assertThrows(IOException.class, () -> session.inspect(runtimeHome, classFile, false));
            provider.fault = Fault.NONE;
            assertEquals(MAX_RECEIPT_CHARS, inspect(session, replacement, false).length());
            inspect(session, small, false);
            assertEquals(4, provider.calls.get());
        }
    }

    @Test
    public void concurrentSameKeyConsumersPerformOneNativeAnalysis() throws Exception {
        int consumers = 8;
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        CountDownLatch ready = new CountDownLatch(consumers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch attempting = new CountDownLatch(consumers);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        provider.beforeRun = () -> {
            entered.countDown();
            await(release);
        };
        ExecutorService workers = Executors.newFixedThreadPool(consumers);
        M3JdkDependencyTool.Session session = newSession(lookup);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < consumers; index++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    await(start);
                    attempting.countDown();
                    return inspect(session, classFile, false);
                }));
            }
            await(ready);
            start.countDown();
            await(entered);
            await(attempting);
            release.countDown();
            String expected = provider.expected(classFile, false);
            for (Future<String> result : results) {
                assertEquals(expected, result.get(30, TimeUnit.SECONDS));
            }
            assertEquals(consumers, lookup.calls.get());
            assertEquals(consumers, provider.nameCalls.get());
            assertEquals(1, provider.calls.get());
        } finally {
            start.countDown();
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            session.close();
        }
    }

    @Test
    public void closeIsIdempotentAndFurtherInspectionCannotInvokeCallbacks() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        M3JdkDependencyTool.Session session = newSession(lookup);
        inspect(session, classFile, false);
        session.close();
        session.close();
        assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
        assertEquals(1, lookup.calls.get());
        assertEquals(1, provider.calls.get());
    }

    @Test
    public void closeFromAnyProviderCallbackCannotReturnOrRetainASuccessfulReceipt() throws Exception {
        for (CallbackStage stage : CallbackStage.values()) {
            FixtureProvider provider = new PrimaryProvider();
            Lookup lookup = new Lookup(provider);
            M3JdkDependencyTool.Session session = newSession(lookup);
            try {
                setCallback(stage, lookup, provider, session::close);
                assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
                int selections = lookup.calls.get();
                int analyses = provider.calls.get();
                session.close();
                assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
                assertEquals(selections, lookup.calls.get());
                assertEquals(analyses, provider.calls.get());
            } finally {
                session.close();
            }
        }
    }

    @Test
    public void closeFromLookupOrNameAlsoPreventsReturningAnExistingHit() throws Exception {
        for (CallbackStage stage : new CallbackStage[] {CallbackStage.LOOKUP, CallbackStage.NAME}) {
            FixtureProvider provider = new PrimaryProvider();
            Lookup lookup = new Lookup(provider);
            try (M3JdkDependencyTool.Session session = newSession(lookup)) {
                inspect(session, classFile, false);
                setCallback(stage, lookup, provider, session::close);
                assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
                assertEquals(1, provider.calls.get());
            }
        }
    }

    @Test
    public void reentrantInspectionIsRejectedBeforeAnyNestedDiscovery() throws Exception {
        for (CallbackStage stage : CallbackStage.values()) {
            FixtureProvider provider = new PrimaryProvider();
            Lookup lookup = new Lookup(provider);
            AtomicInteger refusals = new AtomicInteger();
            try (M3JdkDependencyTool.Session session = newSession(lookup)) {
                setCallback(stage, lookup, provider, () -> {
                    assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
                    refusals.incrementAndGet();
                });
                String accepted = inspect(session, classFile, false);
                assertEquals(1, refusals.get());
                assertEquals(1, lookup.calls.get());
                assertEquals(1, provider.calls.get());
                setCallback(stage, lookup, provider, null);
                assertEquals(accepted, inspect(session, classFile, false));
                assertEquals(1, provider.calls.get());
            }
        }
    }

    @Test
    public void uncaughtReentrantFailureDoesNotRetainFactsOrPoisonTheSession() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        Lookup lookup = new Lookup(provider);
        try (M3JdkDependencyTool.Session session = newSession(lookup)) {
            provider.afterWrite = () -> {
                try {
                    session.inspect(runtimeHome, classFile, false);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            };
            assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
            assertEquals(1, provider.calls.get());
            provider.afterWrite = null;
            String accepted = inspect(session, classFile, false);
            assertEquals(accepted, inspect(session, classFile, false));
            assertEquals(2, provider.calls.get());
        }
    }

    @Test
    public void crossThreadCloseWaitsForTheInFlightInspection() throws Exception {
        FixtureProvider provider = new PrimaryProvider();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        provider.beforeRun = () -> {
            entered.countDown();
            await(release);
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        M3JdkDependencyTool.Session session = newSession(new Lookup(provider));
        try {
            Future<String> inFlight = workers.submit(() -> inspect(session, classFile, false));
            await(entered);
            Future<?> close = workers.submit(() -> {
                closing.countDown();
                session.close();
            });
            await(closing);
            assertThrows(TimeoutException.class, () -> close.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(provider.expected(classFile, false), inFlight.get(30, TimeUnit.SECONDS));
            close.get(30, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> session.inspect(runtimeHome, classFile, false));
            assertEquals(1, provider.calls.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS));
            session.close();
        }
    }

    private M3JdkDependencyTool.Session newSession(Lookup lookup) {
        return M3JdkDependencyTool.newSession(lookup, runtimeHome);
    }

    private String inspect(M3JdkDependencyTool.Session session, Path input, boolean apiOnly) throws IOException {
        Optional<String> result = session.inspect(runtimeHome, input, apiOnly);
        assertTrue("The explicitly selected fixture must produce a receipt", result.isPresent());
        return result.get();
    }

    private Path newClass(String name, int value) throws IOException {
        Path file = temporary.getRoot().toPath().resolve(name);
        return Files.write(file, new byte[] {(byte) value, (byte) (value >>> 8), 3, 4});
    }

    private Path[] capacityInputs() throws IOException {
        Path[] files = new Path[MAX_ENTRIES + 1];
        for (int index = 0; index < files.length; index++) {
            files[index] = newClass("Capacity" + index + ".class", index);
        }
        return files;
    }

    private void fill(M3JdkDependencyTool.Session session, Path[] files) throws IOException {
        for (int index = MAX_ENTRIES - 1; index >= 0; index--) {
            inspect(session, files[index], false);
        }
    }

    private static void changeBytesWithSameTimestamp(Path file, int value) throws IOException {
        FileTime time = Files.getLastModifiedTime(file);
        long length = Files.size(file);
        Files.write(file, new byte[] {(byte) value, (byte) (value >>> 8), 3, 4});
        Files.setLastModifiedTime(file, time);
        assertEquals(length, Files.size(file));
        assertEquals(time, Files.getLastModifiedTime(file));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue("Fixture coordination did not complete", latch.await(30, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Fixture coordination was interrupted", failure);
        }
    }

    private static void setCallback(CallbackStage stage, Lookup lookup, FixtureProvider provider, Runnable callback) {
        switch (stage) {
            case LOOKUP:
                lookup.callback = callback;
                break;
            case NAME:
                provider.onName = callback;
                break;
            case RUN:
                provider.afterWrite = callback;
                break;
            default:
                throw new AssertionError(stage);
        }
    }

    private static String facts(Path file, boolean apiOnly, String tag, String ending, int targetLength) throws IOException {
        String header = SCHEMA + "\t" + (apiOnly ? "PUBLIC_API" : "CLASS") + "\t" + sha256(file)
            + "\t" + CLASS_NAME + "\t1" + ending;
        int padding = targetLength == 0 ? 0 : targetLength - header.length() - 2 - tag.length() - ending.length();
        if (padding < 0) {
            throw new IllegalArgumentException("Fixture receipt length cannot contain its header and row");
        }
        return header + "D\t" + tag + "A".repeat(padding) + ending;
    }

    private static String sha256(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new AssertionError(failure);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] bytes = new byte[32 * 1024];
            for (int count; (count = input.read(bytes)) != -1;) {
                digest.update(bytes, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private enum CallbackStage {
        LOOKUP, NAME, RUN
    }

    private enum Fault {
        NONE, BAD_HEADER, STDERR, STATUS, RUNTIME
    }

    private static final class Lookup implements Supplier<Optional<?>> {
        private final AtomicReference<ToolProvider> selected;
        private final AtomicInteger calls = new AtomicInteger();
        private Runnable callback;

        private Lookup(ToolProvider provider) {
            selected = new AtomicReference<>(provider);
        }

        @Override
        public Optional<?> get() {
            calls.incrementAndGet();
            if (callback != null) {
                callback.run();
            }
            return Optional.ofNullable(selected.get());
        }
    }

    private abstract static class FixtureProvider implements ToolProvider {
        private final String tag;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger nameCalls = new AtomicInteger();
        private final Map<Path, Integer> outputSizes = new HashMap<>();
        private final IllegalStateException runtimeFailure = new IllegalStateException("selected provider failed");
        private String toolName = "m3-class-deps";
        private String ending = "\n";
        private Fault fault = Fault.NONE;
        private Runnable onName;
        private Runnable beforeRun;
        private Runnable afterWrite;

        private FixtureProvider(String tag) {
            this.tag = tag;
        }

        private String expected(Path file, boolean apiOnly) throws IOException {
            return facts(file, apiOnly, tag, ending, outputSizes.getOrDefault(file, 0));
        }

        @Override
        public String name() {
            nameCalls.incrementAndGet();
            if (onName != null) {
                onName.run();
            }
            return toolName;
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... args) {
            calls.incrementAndGet();
            if (beforeRun != null) {
                beforeRun.run();
            }
            if (fault == Fault.RUNTIME) {
                throw runtimeFailure;
            }
            try {
                Path input = Path.of(args[args.length - 1]);
                String receipt = expected(input, args.length == 2);
                out.write(fault == Fault.BAD_HEADER ? receipt.replace(CLASS_NAME, "invalid.name") : receipt);
                if (fault == Fault.STDERR) {
                    err.write("native diagnostic");
                }
                if (afterWrite != null) {
                    afterWrite.run();
                }
                return fault == Fault.STATUS ? 7 : 0;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }
    }

    private static final class PrimaryProvider extends FixtureProvider {
        private PrimaryProvider() {
            super("QQ");
        }
    }

    private static final class SecondaryProvider extends FixtureProvider {
        private SecondaryProvider() {
            super("Qg");
        }
    }

    /** Identical compiled bytes are loaded twice; no JDK compiler or parser is replaced. */
    public static final class ReloadableProvider implements ToolProvider {
        private final AtomicInteger calls;
        private final String receipt;

        public ReloadableProvider(AtomicInteger calls, String receipt) {
            this.calls = calls;
            this.receipt = receipt;
        }

        @Override
        public String name() {
            return "m3-class-deps";
        }

        @Override
        public int run(PrintWriter out, PrintWriter err, String... args) {
            calls.incrementAndGet();
            out.write(receipt);
            return 0;
        }
    }

    private static final class ProviderLoader extends ClassLoader {
        private ProviderLoader(ClassLoader parent) {
            super(parent);
        }

        private ToolProvider create(String name, byte[] bytes, AtomicInteger calls, String receipt) throws ReflectiveOperationException {
            Class<?> type = defineClass(name, bytes, 0, bytes.length);
            return (ToolProvider) type.getConstructor(AtomicInteger.class, String.class).newInstance(calls, receipt);
        }
    }
}
