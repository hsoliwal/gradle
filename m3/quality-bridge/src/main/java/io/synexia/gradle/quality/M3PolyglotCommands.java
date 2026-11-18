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
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, providerJars, stateBackend, null);
    }

    /**
     * Explicit Maven #149 local artifact cache; not Gradle's own build cache.
     * The old argv signatures remain byte-for-byte unchanged without a cache.
     */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars, String stateBackend, File buildCache) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, providerJars,
                stateBackend, buildCache, "none");
    }

    /** Requests Maven-owned provider qualification; existing callers omit this opt-in. */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars, String stateBackend, File buildCache,
            boolean providerReuse) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, providerJars,
                stateBackend, buildCache, "none", providerReuse);
    }

    /**
     * A narrowly selected M3JDK module export for original Maven #220.
     * No free-form JVM options or Gradle-side M3DB implementation are accepted.
     * Existing overloads retain their exact argument order without selection.
     */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars, String stateBackend,
            File buildCache, String m3DbModuleAccess) {
        return command(javaExecutable, engineJar, workspace, manifest, target, mode,
                timeoutSeconds, incremental, approveRewriteApply, providerJars,
                stateBackend, buildCache, m3DbModuleAccess, false);
    }

    /** Combines independently selected provider reuse and source-owned M3JDK module access. */
    public static List<String> command(String javaExecutable, File engineJar, File workspace,
            File manifest, String target, String mode, int timeoutSeconds, boolean incremental,
            boolean approveRewriteApply, List<File> providerJars, String stateBackend,
            File buildCache, String m3DbModuleAccess, boolean providerReuse) {
        String java = M3QualityCommands.executable(javaExecutable);
        Objects.requireNonNull(engineJar, "engineJar");
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(manifest, "manifest");
        List<String> moduleAccessArgs = m3DbModuleAccessArgs(m3DbModuleAccess);
        if (!("plan".equals(mode) || "run".equals(mode))
                || target == null || !target.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}")
                || timeoutSeconds < 1 || timeoutSeconds > 43_200
                || !validStateBackend(stateBackend)
                || (!"legacy".equals(stateBackend) && !incremental)
                || (providerReuse && (!"run".equals(mode) || (!incremental && buildCache == null)))
                || (!"run".equals(mode) && (incremental || approveRewriteApply || buildCache != null))
                || (!moduleAccessArgs.isEmpty() && (!"run".equals(mode) || !incremental
                        || !("m3db".equals(stateBackend) || "native".equals(stateBackend))))) {
            throw new IllegalArgumentException("invalid M3 polyglot CLI mode, target or option");
        }
        List<String> argv = new ArrayList<>();
        argv.add(java);
        argv.addAll(moduleAccessArgs);
        argv.addAll(List.of("-cp", classpath(engineJar, providerJars), MAIN_CLASS, mode,
                workspace.getAbsolutePath(), manifest.getAbsolutePath(), target,
                "--timeout-seconds", Integer.toString(timeoutSeconds)));
        if (incremental) argv.add("--incremental");
        if (!"legacy".equals(stateBackend)) {
            argv.add("--state-backend");
            argv.add(stateBackend);
        }
        if (approveRewriteApply) argv.add("--approve-rewrite-apply");
        if (buildCache != null) {
            String location = buildCache.getAbsolutePath();
            if (location.isBlank() || location.startsWith("--")) {
                throw new IllegalArgumentException("invalid M3 polyglot local cache directory");
            }
            argv.add("--build-cache");
            argv.add(location);
        }
        if (providerReuse) argv.add("--provider-reuse");
        return List.copyOf(argv);
    }

    /** Only these source-owned M3JDK database module shapes can be exposed. */
    private static List<String> m3DbModuleAccessArgs(String selection) {
        if (selection == null) throw new IllegalArgumentException("missing M3DB module selection");
        return switch (selection) {
            case "none" -> List.of();
            case "current-java-base" -> List.of("--add-exports=java.base/jdk.m3.db=ALL-UNNAMED");
            case "current-named" -> List.of("--add-modules=jdk.m3.db",
                    "--add-exports=jdk.m3.db/jdk.m3.db=ALL-UNNAMED");
            case "legacy-java-base" -> List.of("--add-exports=java.base/jdk.m3.indexdb=ALL-UNNAMED");
            case "legacy-named" -> List.of("--add-modules=jdk.m3.indexdb",
                    "--add-exports=jdk.m3.indexdb/jdk.m3.indexdb=ALL-UNNAMED");
            default -> throw new IllegalArgumentException("unsupported M3DB module selection: " + selection);
        };
    }

    /** Match Maven #184+ backend spellings; aliases preserve Maven-owned storage. */
    private static boolean validStateBackend(String backend) {
        return "legacy".equals(backend) || "embedded".equals(backend)
                || "file".equals(backend) || "m3db".equals(backend)
                || "native".equals(backend) || "sqlite".equals(backend)
                || "h2".equals(backend)
                || (backend != null && backend.matches("ext-[a-z0-9][a-z0-9-]{0,47}"));
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

    /** Shared sealed Java classpath for the Maven polyglot and AST fact CLIs. */
    static String classpath(File engineJar, List<File> providerJars) {
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
