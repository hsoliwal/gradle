/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Explicit Maven project-db ingestion or CPU-only query, never Gradle build authority. */
@UntrackedTask(because = "Maven project DB owns evolving per-project sources, indexes and build generations")
public abstract class M3ProjectDbTask extends DefaultTask {
    private static final int MAX_REPORT_BYTES = 1024 * 1024;

    @Input public abstract Property<String> getJavaExecutable();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getProjectDbJar();
    @Input public abstract Property<String> getProjectDbSha256();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPolyglotJar();
    @Input public abstract Property<String> getPolyglotSha256();
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getAdditionalJars();
    @Input public abstract MapProperty<String, String> getAdditionalJarSha256();
    @Internal public abstract DirectoryProperty getSourceProject();
    @Internal public abstract DirectoryProperty getStorageDirectory();
    @Input public abstract Property<String> getMode();
    @Input public abstract Property<String> getQuestion();
    @Input public abstract Property<Integer> getTopK();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBuildLog();
    @OutputFile public abstract RegularFileProperty getResultReport();

    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void runProjectDb() {
        Path root = absolute(getSourceProject().get().getAsFile());
        Path store = absolute(getStorageDirectory().get().getAsFile());
        Path build = absolute(getProject().getLayout().getBuildDirectory().get().getAsFile());
        Path report = absolute(getResultReport().get().getAsFile());
        Path plugin = absolute(getProjectDbJar().get().getAsFile());
        Path engine = absolute(getPolyglotJar().get().getAsFile());
        Path log = getBuildLog().isPresent() ? absolute(getBuildLog().get().getAsFile()) : null;
        try {
            requirePaths(root, build, store, report, log);
            if ("query".equals(getMode().get())) requireExistingStore(store);
            if (plugin.equals(engine)) throw new IOException("M3 project DB and storage JARs must be distinct");
            M3PolyglotCommands.verifyEngine(plugin, getProjectDbSha256().get());
            M3PolyglotCommands.verifyEngine(engine, getPolyglotSha256().get());
            List<File> jars = M3ProviderClasspath.verify(
                    getAdditionalJars().getFiles(), getAdditionalJarSha256().get());
            List<String> command = M3ProjectDbCommands.command(getJavaExecutable().get(),
                    plugin.toFile(), engine.toFile(), jars, root.toFile(), store.toFile(),
                    getMode().get(), getQuestion().get(), getTopK().get(),
                    log == null ? null : log.toFile());
            M3BoundedOutput output = new M3BoundedOutput(MAX_REPORT_BYTES, "M3 project DB result");
            getExecOperations().exec(spec -> {
                spec.setExecutable(command.get(0));
                spec.setArgs(command.subList(1, command.size()));
                spec.setWorkingDir(root.toFile());
                spec.setStandardOutput(output);
            });
            String response = validateResult(output.bytes(), getMode().get());
            publish(root, report, response);
            getLogger().lifecycle("M3 project DB {}: source-pinned Maven report created",
                    getMode().get());
        } catch (IOException refused) {
            throw new GradleException("M3 project database source or report refused", refused);
        }
    }

    private static Path absolute(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    private static void requirePaths(Path root, Path build, Path store, Path report, Path log)
            throws IOException {
        boolean approvedStore = store.startsWith(build)
                || store.startsWith(root.resolve(".mvn/project-db"))
                || store.startsWith(root.resolve(".m3/project-db"))
                || store.startsWith(root.resolve("target/project-db"));
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !build.startsWith(root) || build.equals(root)
                || !approvedStore || store.equals(root) || !report.startsWith(build)
                || !report.toString().endsWith(".json") || report.startsWith(store)
                || (log != null && (!log.startsWith(root)
                        || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)))) {
            throw new IOException("project DB requires project-local store, log and build JSON report");
        }
        for (Path path : List.of(root, build, store, report)) rejectLinks(root, path);
        if (log != null) rejectLinks(root, log);
    }

    /** A query must not silently create an empty Maven project database. */
    private static void requireExistingStore(Path store) throws IOException {
        if (!Files.isDirectory(store, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(store.resolve("db"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(store.resolve(".gitignore"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3 project DB query requires an existing ingested database");
        }
    }

    private static void rejectLinks(Path root, Path file) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("linked M3 project root");
        Path cursor = root;
        for (Path part : root.relativize(file)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("linked project database path");
        }
    }

    private static String validateResult(byte[] bytes, String mode) throws IOException {
        String value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (value.endsWith("\r\n")) value = value.substring(0, value.length() - 2);
        else if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0
                || !value.endsWith("}")
                || ("ingest".equals(mode) && (!value.startsWith("{\"build\":")
                        || !value.contains("\"status\":")))
                || ("query".equals(mode) && (!value.startsWith("{\"question\":")
                        || !value.contains("\"build\":") || !value.contains("\"answers\":[")))) {
            throw new IOException("Maven project database returned an invalid one-line JSON result");
        }
        return value + "\n";
    }

    private static void publish(Path root, Path report, String content) throws IOException {
        Files.createDirectories(report.getParent());
        rejectLinks(root, report);
        Path temporary = Files.createTempFile(report.getParent(), ".m3-project-db-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
