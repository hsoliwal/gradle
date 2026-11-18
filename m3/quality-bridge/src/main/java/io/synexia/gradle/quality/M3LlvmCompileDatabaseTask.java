/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * Untracked Gradle proxy for the original Maven-owned llvm-compile-db export.
 * This task does not render or parse C/Clang compilation arguments and never
 * compiles native sources. The exact Maven CLI binary and its ServiceLoader
 * providers remain mandatory, independently SHA-256-qualified inputs.
 *
 * <p>Only a bounded, versioned path receipt is parsed here; the actual
 * compile_commands.json format, native ownership and atomic publication
 * remain in the Maven fork's LlvmCompilationDatabase implementation.</p>
 */
@UntrackedTask(because = "LLVM native inputs and original Maven IDE evidence are not Gradle cacheable")
public abstract class M3LlvmCompileDatabaseTask extends DefaultTask {
    private static final int MAX_RESPONSE_BYTES = 16 * 1024;
    private static final long MAX_DATABASE_BYTES = 4L * 1024 * 1024;

    @Input
    public abstract Property<String> getJavaExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEngineJar();

    @Input
    public abstract Property<String> getEngineSha256();

    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getProviderJars();

    @Input
    public abstract MapProperty<String, String> getProviderJarSha256();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    @Internal
    public abstract DirectoryProperty getWorkspace();

    @Input
    public abstract Property<String> getTarget();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected ProjectLayout getM3ProjectLayout() {
        throw new UnsupportedOperationException("Gradle injects the project layout service");
    }

    @TaskAction
    public void exportCompilationDatabase() {
        Path checkout = getM3ProjectLayout().getProjectDirectory().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path workspace = getWorkspace().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path manifest = getManifestFile().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path engine = getEngineJar().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path output = workspace.resolve("target/m3-llvm/compile_commands.json");
        if (!workspace.startsWith(checkout) || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
                || !manifest.startsWith(workspace)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new GradleException("M3 LLVM compilation database requires a project-local M3 task manifest");
        }

        try {
            if (Files.size(manifest) > M3PolyglotCommands.MAX_MANIFEST_BYTES) {
                throw new IOException("M3 LLVM manifest exceeds the 1 MiB limit");
            }
            rejectSymlinks(checkout, manifest);
            rejectSymlinks(checkout, output);
            M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
            List<File> providerJars = M3ProviderClasspath.verify(
                    getProviderJars().getFiles(), getProviderJarSha256().get());
            List<String> argv = M3PolyglotCommands.compilationDatabase(
                    getJavaExecutable().get(), engine.toFile(), workspace.toFile(),
                    manifest.toFile(), getTarget().get(), providerJars);
            M3BoundedOutput captured = new M3BoundedOutput(MAX_RESPONSE_BYTES, "M3 LLVM compilation database");
            getExecOperations().exec(spec -> {
                spec.setExecutable(argv.get(0));
                spec.setArgs(argv.subList(1, argv.size()));
                spec.setWorkingDir(workspace.toFile());
                spec.setStandardOutput(captured);
            });
            String response = utf8(captured.bytes()).replace("\r\n", "\n");
            String expected = "M3-LLVM-COMPILE-DB-V1\nfile\t" + output + "\n";
            if (!expected.equals(response)) {
                throw new IOException("M3 Maven CLI returned an unexpected LLVM database receipt");
            }
            rejectSymlinks(checkout, output);
            if (!Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(output) == 0 || Files.size(output) > MAX_DATABASE_BYTES) {
                throw new IOException("M3 Maven LLVM compilation database is missing or oversized");
            }
            getLogger().lifecycle("Original M3 LLVM compilation database: {}", output);
        } catch (IOException invalid) {
            throw new GradleException("M3 LLVM compilation database refused", invalid);
        }
    }

    private static String utf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("M3 Maven compilation database response must be UTF-8", invalid);
        }
    }

    private static void rejectSymlinks(Path project, Path path) throws IOException {
        for (Path candidate = path; candidate != null && candidate.startsWith(project);
                candidate = candidate.getParent()) {
            if (Files.isSymbolicLink(candidate)) {
                throw new IOException("M3 LLVM compilation database path traverses symlink: " + path);
            }
        }
    }
}
