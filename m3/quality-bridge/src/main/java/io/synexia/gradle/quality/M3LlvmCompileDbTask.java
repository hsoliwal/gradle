/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
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
 * Explicit Gradle adapter to Maven #255's original Clang compilation database.
 * Neither Gradle's cache nor its native C/C++ scheduler owns the output.
 */
@UntrackedTask(because = "Native toolchain and external Maven manifest inputs are not a qualified Gradle cache key")
public abstract class M3LlvmCompileDbTask extends DefaultTask {
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

    @Internal
    public abstract DirectoryProperty getWorkspace();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    @Input
    public abstract Property<String> getTarget();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @Inject
    protected abstract ProjectLayout getM3ProjectLayout();

    @TaskAction
    public void publishCompilationDatabase() {
        Path checkout = getM3ProjectLayout().getProjectDirectory().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path workspace = getWorkspace().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path manifest = getManifestFile().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path engine = getEngineJar().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path database = M3LlvmCompileDbCommands.expectedDatabase(workspace);
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
                || !workspace.startsWith(checkout)
                || !manifest.startsWith(workspace) || manifest.equals(workspace)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new GradleException("M3 LLVM compile DB needs a project-local module and manifest");
        }
        try {
            rejectLinks(workspace);
            rejectLinks(manifest);
            rejectLinks(database);
            if (Files.size(manifest) > M3PolyglotCommands.MAX_MANIFEST_BYTES
                    || Files.exists(database, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("invalid M3 LLVM compile DB manifest or output");
            }
            M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
            List<File> jars = M3ProviderClasspath.verify(getProviderJars().getFiles(),
                    getProviderJarSha256().get());
            List<String> command = M3LlvmCompileDbCommands.command(getJavaExecutable().get(),
                    engine.toFile(), jars, workspace.toFile(), manifest.toFile(), getTarget().get());
            var receipt = new M3BoundedOutput(M3LlvmCompileDbCommands.MAX_RECEIPT_BYTES,
                    "Maven LLVM compilation database receipt");
            getExecOperations().exec(spec -> {
                spec.setExecutable(command.get(0));
                spec.setArgs(command.subList(1, command.size()));
                spec.setWorkingDir(workspace.toFile());
                spec.setStandardOutput(receipt);
            });
            M3LlvmCompileDbCommands.verifyReceipt(receipt.bytes(), database);
            rejectLinks(database);
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Maven LLVM compile DB did not publish a bounded regular file");
            }
            long bytes = Files.size(database);
            // The Maven exporter owns JSON syntax. Gradle checks only the
            // minimal nonempty handoff: [] + LF is three bytes even in a
            // synthetic producer; an empty/partial file is never success.
            if (bytes < 3 || bytes > M3LlvmCompileDbCommands.MAX_DATABASE_BYTES) {
                throw new IOException("Maven LLVM compile DB output is empty, truncated or oversized");
            }
            getLogger().lifecycle("Maven-owned Clang compilation database: {}", database);
        } catch (IOException rejected) {
            throw new GradleException("M3 LLVM compile DB source-owner handoff refused", rejected);
        }
    }

    /** Reject any existing symlink component, including the output's parents. */
    private static void rejectLinks(Path absolute) throws IOException {
        Path path = absolute.toAbsolutePath().normalize();
        Path cursor = path.getRoot();
        for (Path component : path) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new IOException("symlinked M3 LLVM compilation database path");
            }
        }
    }
}
