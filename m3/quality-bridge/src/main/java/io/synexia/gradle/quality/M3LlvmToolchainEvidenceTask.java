/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * Explicit read-only/atomic-write proxy for Maven #210's original LLVM
 * evidence generator. Native binaries and the manifest format are not Gradle
 * build-cache inputs or evidence of complete ABI/compiler dependencies.
 */
@UntrackedTask(because = "Native LLVM toolchain bytes and aliases remain source-owned, non-cacheable facts")
public abstract class M3LlvmToolchainEvidenceTask extends DefaultTask {
    @Input
    public abstract Property<String> getJavaExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getOriginalProviderJar();

    @Input
    public abstract Property<String> getOriginalProviderSha256();

    @Internal
    public abstract DirectoryProperty getLlvmHome();

    @Internal
    public abstract RegularFileProperty getEvidenceManifest();

    @Input
    public abstract Property<String> getMode();

    @Input
    public abstract Property<String> getSelectedTools();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void inspectToolchain() {
        Path project = getProject().getLayout().getProjectDirectory().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path build = getProject().getLayout().getBuildDirectory().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path evidence = getEvidenceManifest().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        Path home = getLlvmHome().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path provider = getOriginalProviderJar().get().getAsFile()
                .toPath().toAbsolutePath().normalize();
        String mode = getMode().get();
        if (!build.startsWith(project) || !evidence.startsWith(build)
                || !evidence.toString().endsWith(".tsv") || !Files.isDirectory(home)) {
            throw new GradleException("M3 LLVM evidence requires an installed toolchain and build-local TSV");
        }
        try {
            rejectSymlinkAncestors(project, evidence);
            M3PolyglotCommands.verifyEngine(provider, getOriginalProviderSha256().get());
            List<String> argv = M3LlvmToolchainEvidenceCommands.command(
                    getJavaExecutable().get(), provider.toFile(), home.toFile(), evidence.toFile(),
                    mode, getSelectedTools().get());
            if ("scan".equals(mode)) {
                Files.createDirectories(evidence.getParent());
                rejectSymlinkAncestors(project, evidence);
            }
            getExecOperations().exec(spec -> {
                spec.setExecutable(argv.get(0));
                spec.setArgs(argv.subList(1, argv.size()));
                spec.setWorkingDir(project.toFile());
            });
        } catch (IOException invalid) {
            throw new GradleException("M3 LLVM original toolchain evidence refused", invalid);
        }
    }

    private static void rejectSymlinkAncestors(Path project, Path evidence) throws IOException {
        for (Path path = evidence; path != null && path.startsWith(project); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) {
                throw new IOException("M3 LLVM evidence path traverses a symlink");
            }
        }
    }
}
