/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Validates each explicitly supplied third-party SPI JAR by SHA-256 before it
 * can enter the Maven CLI classpath. The engine JAR has its own digest pin.
 */
public final class M3ProviderClasspath {
    private M3ProviderClasspath() {}

    /**
     * Digest keys use bare JAR filenames. Names must be unique; the returned
     * order is deterministic regardless of Gradle file collection iteration.
     */
    public static List<File> verify(Collection<File> jars, Map<String, String> expected) throws IOException {
        Objects.requireNonNull(jars, "jars");
        Objects.requireNonNull(expected, "expected");
        var ordered = new TreeMap<String, File>();
        for (File declared : jars) {
            if (declared == null || !declared.getName().matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\\.jar")) {
                throw new IOException("M3 SPI provider must be a named JAR");
            }
            String name = declared.getName();
            Path path = declared.toPath().toAbsolutePath().normalize();
            if (path.toString().contains(File.pathSeparator)) {
                throw new IOException("M3 SPI provider path cannot contain classpath delimiters");
            }
            if (ordered.putIfAbsent(name, path.toFile()) != null) {
                throw new IOException("ambiguous duplicate M3 provider JAR filename: " + name);
            }
        }
        if (!ordered.keySet().equals(expected.keySet())) {
            throw new IOException("M3 SPI provider JARs need an exact SHA-256 pin for each filename");
        }
        for (Map.Entry<String, File> entry : ordered.entrySet()) {
            M3PolyglotCommands.verifyEngine(entry.getValue().toPath(), expected.get(entry.getKey()));
        }
        return List.copyOf(ordered.values());
    }
}
