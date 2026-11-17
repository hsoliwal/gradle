/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The exact no-shell Java argv used to consume Maven's original polyglot
 * engine. There is no embedded Maven Core, reimplemented DAG or second parser.
 */
public final class M3PolyglotCommands {
    public static final long MAX_ENGINE_BYTES = 64L * 1024 * 1024;
    public static final long MAX_MANIFEST_BYTES = 1024L * 1024;
    public static final String MAIN_CLASS = "io.synexia.maven.polyglot.PolyglotCli";

    private M3PolyglotCommands() {}

    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, List.of(), "legacy");
    }

    /** Opt-in persistent state; the default signature retains the original CLI argv. */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, String stateBackend) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, List.of(), stateBackend);
    }

    /** Includes only separately verified, explicit third-party SPI provider JARs. */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, providerJars, "legacy");
    }

    /** Delegates Maven's sealed module-state backend; never enables Gradle task caching. */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars, String stateBackend) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(manifest, "manifest");
        if (!("plan".equals(mode) || "run".equals(mode))
                || target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
                || timeoutSeconds < 1 || timeoutSeconds > 43_200
                || !("legacy".equals(stateBackend) || "file".equals(stateBackend)
                        || "m3db".equals(stateBackend))
                || (!"legacy".equals(stateBackend) && !incremental)
                || (!"run".equals(mode) && (incremental || approveRewriteApply))) {
            throw new IllegalArgumentException("invalid M3 polyglot CLI mode, target or option");
        }
        List<String> argv = new ArrayList<>(List.of(java, "-cp", classpath(engineJar, providerJars),
                MAIN_CLASS, mode, workspace.getAbsolutePath(), manifest.getAbsolutePath(), target,
                "--timeout-seconds", Integer.toString(timeoutSeconds)));
        if (incremental) argv.add("--incremental");
        if (!"legacy".equals(stateBackend)) {
            argv.add("--state-backend");
            argv.add(stateBackend);
        }
        if (approveRewriteApply) argv.add("--approve-rewrite-apply");
        return List.copyOf(argv);
    }

    /** Read-only, versioned capability inventory from the same pinned Maven CLI. */
    public static List<String> providers(String javaExecutable, File engineJar) {
        return providers(javaExecutable, engineJar, List.of());
    }

    /** Inventories exactly the additional, digest-pinned providers selected by the caller. */
    public static List<String> providers(String javaExecutable, File engineJar, List<File> providerJars) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        return List.of(java, "-Dfile.encoding=UTF-8", "-cp", classpath(engineJar, providerJars),
                MAIN_CLASS, "providers");
    }

    private static String classpath(File engineJar, List<File> providerJars) {
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(providerJars, "providerJars");
        StringBuilder result = new StringBuilder(engineJar.getAbsolutePath());
        if (result.toString().contains(File.pathSeparator)) {
            throw new IllegalArgumentException("M3 engine JAR path contains a classpath delimiter");
        }
        for (File provider : providerJars) {
            Objects.requireNonNull(provider, "provider");
            String path = provider.getAbsolutePath();
            if (path.contains(File.pathSeparator)) {
                throw new IllegalArgumentException("M3 provider JAR path contains a classpath delimiter");
            }
            result.append(File.pathSeparator).append(path);
        }
        return result.toString();
    }

    /** Reject missing, malformed, oversized or symlinked engine artifacts. */
    public static void verifyEngine(Path engine, String expectedSha256) throws IOException {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(engine, LinkOption.NOFOLLOW_LINKS)
                || Files.size(engine) > MAX_ENGINE_BYTES) {
            throw new IOException("M3 polyglot engine requires a regular, bounded, SHA-256-pinned JAR");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        try (InputStream stream = Files.newInputStream(engine, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = new byte[64 * 1024];
            for (int length = stream.read(bytes); length != -1; length = stream.read(bytes)) {
                if (length > 0) digest.update(bytes, 0, length);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expectedSha256)) {
            throw new IOException("M3 polyglot engine SHA-256 mismatch");
        }
    }
}
