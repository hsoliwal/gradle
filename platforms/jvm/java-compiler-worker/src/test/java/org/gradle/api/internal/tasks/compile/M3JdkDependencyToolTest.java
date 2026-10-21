/*
 * Copyright 2026 Hitesh Soliwal and contributors.
 * Licensed under the Apache License, Version 2.0.
 */
package org.gradle.api.internal.tasks.compile;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.spi.ToolProvider;
import org.junit.Test;
import static org.junit.Assert.*;

/** Original Gradle worker's JUnit4 fixture; no compiler or source AST reconstructed. */
public class M3JdkDependencyToolTest {
    private static final class NativeProvider implements ToolProvider {
        int called;
        boolean badSchema;
        @Override public String name() { return "m3-class-deps"; }
        @Override public int run(PrintWriter out, PrintWriter err, String... args) {
            called++;
            try {
                Path file = Path.of(args[args.length - 1]);
                String digest = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(file)));
                out.println((badSchema ? "FORGED" : "M3JDK21_CLASS_DEPS_V1") + "\t"
                        + (args.length == 2 ? "PUBLIC_API" : "CLASS") + "\t" + digest + "\tcC5Qcm9iZQ\t0");
                return 0;
            } catch (Exception failure) { err.println("native failure"); return 1; }
        }
    }

    @Test public void originalNativeProviderIsUsedOnlyUnderMatchingToolchain() throws Exception {
        Path fixture = Files.createTempDirectory("m3-gradle-native-");
        Path classFile = fixture.resolve("Probe.class"); Files.write(classFile, new byte[] {1, 2, 3, 4});
        Path current = Path.of(System.getProperty("java.home"));
        NativeProvider sdk = new NativeProvider();
        String result = M3JdkDependencyTool.inspect(current, classFile, false,
                () -> Optional.of(sdk), current).orElseThrow();
        assertTrue(result.startsWith("M3JDK21_CLASS_DEPS_V1\tCLASS\t"));
        assertEquals(1, sdk.called);
        String publicApi = M3JdkDependencyTool.inspect(current, classFile, true,
                () -> Optional.of(sdk), current).orElseThrow();
        assertTrue(publicApi.contains("\tPUBLIC_API\t"));
        assertEquals(2, sdk.called);
        Path external = fixture.resolve("external-java"); Files.createDirectory(external);
        assertTrue(M3JdkDependencyTool.inspect(external, classFile, true,
                () -> Optional.of(sdk), current).isEmpty());
        assertTrue(M3JdkDependencyTool.inspect(current, classFile, true,
                Optional::<ToolProvider>empty, current).isEmpty());
        assertEquals(2, sdk.called);
    }

    @Test public void invalidReceiptAndChangedInputFailClosed() throws Exception {
        Path fixture = Files.createTempDirectory("m3-gradle-refuse-");
        Path classFile = fixture.resolve("Probe.class"); Files.write(classFile, new byte[] {5, 6, 7});
        Path current = Path.of(System.getProperty("java.home"));
        NativeProvider sdk = new NativeProvider(); sdk.badSchema = true;
        assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(current, classFile, false,
                () -> Optional.of(sdk), current));
        sdk.badSchema = false;
        Path link = fixture.resolve("Alias.class"); Files.createSymbolicLink(link, classFile);
        assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(current, link, false,
                () -> Optional.of(sdk), current));
        assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(current, classFile, false,
                () -> Optional.of(new ToolProvider() {
                    @Override public String name() { return "wrong-tool"; }
                    @Override public int run(PrintWriter out, PrintWriter err, String... args) { return 0; }
                }), current));
        assertEquals(1, sdk.called);
        ToolProvider mutator = new ToolProvider() {
            @Override public String name() { return "m3-class-deps"; }
            @Override public int run(PrintWriter out, PrintWriter err, String... args) {
                try {
                    String old = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(Files.readAllBytes(classFile)));
                    out.println("M3JDK21_CLASS_DEPS_V1\tCLASS\t" + old + "\tcC5Qcm9iZQ\t0");
                    Files.write(classFile, new byte[] {8, 9, 0});
                    return 0;
                } catch (Exception e) { return 1; }
            }
        };
        assertThrows(IOException.class, () -> M3JdkDependencyTool.inspect(current, classFile, false,
                () -> Optional.of(mutator), current));
    }

    public static void main(String[] args) throws Exception {
        M3JdkDependencyToolTest test = new M3JdkDependencyToolTest();
        test.originalNativeProviderIsUsedOnlyUnderMatchingToolchain();
        test.invalidReceiptAndChangedInputFailClosed();
        System.out.println("GRADLE_M3_JDK_BUILD_TOOLS_PASS tests=2");
    }
}
