/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
 * Opt-in bridge to the same Maven-authored polyglot manifest and Java engine.
 * Native language tools remain the owners of their outputs and exit statuses.
 */
@UntrackedTask(because = "Arbitrary language toolchains and generated outputs have unproven complete inputs")
public abstract class M3PolyglotManifestTask extends DefaultTask {
    @Input
    public abstract Property<String> getJavaExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEngineJar();

    @Input
    public abstract Property<String> getEngineSha256();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getManifestFile();

    @Internal
    public abstract DirectoryProperty getWorkspace();

    @Input
    public abstract Property<String> getTarget();

    @Input
    public abstract Property<String> getMode();

    @Input
    public abstract Property<Integer> getTimeoutSeconds();

    @Input
    public abstract Property<Boolean> getIncremental();

    @Input
    public abstract Property<Boolean> getApproveRewriteApply();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void executeManifest() {
        Path root = getWorkspace().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path manifest = getManifestFile().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path engine = getEngineJar().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !manifest.startsWith(root)) {
            throw new GradleException("M3 polyglot requires an existing manifest inside its workspace");
        }
        try {
            if (Files.size(manifest) > M3PolyglotCommands.MAX_MANIFEST_BYTES) {
                throw new IOException("M3 polyglot manifest exceeds the 1 MiB contract");
            }
            rejectSymlinkAncestors(root, manifest);
            M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
        } catch (IOException invalid) {
            throw new GradleException("Invalid M3 polyglot engine or manifest", invalid);
        }
        List<String> command = M3PolyglotCommands.command(
                getJavaExecutable().get(), engine.toFile(), root.toFile(), manifest.toFile(),
                getTarget().get(), getMode().get(), getTimeoutSeconds().get(),
                getIncremental().get(), getApproveRewriteApply().get());
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
            spec.setWorkingDir(root.toFile());
        });
    }

    private static void rejectSymlinkAncestors(Path root, Path manifest) throws IOException {
        for (Path path = manifest; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("symlinked M3 polyglot manifest path");
        }
    }
}
