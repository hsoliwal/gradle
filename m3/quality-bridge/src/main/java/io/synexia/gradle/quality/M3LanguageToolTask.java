/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.file.Files;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Native Gradle task for an explicit compiler/build tool; no shell or invented semantics. */
@UntrackedTask(because = "Arbitrary language tools need tool-specific completeness proofs before cache reuse")
public abstract class M3LanguageToolTask extends DefaultTask {
    @Input
    public abstract Property<String> getExecutable();

    @Input
    public abstract ListProperty<String> getArguments();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Internal
    public abstract DirectoryProperty getWorkingDirectory();

    @OutputDirectory
    public abstract DirectoryProperty getArtifactDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void compile() {
        String executable = M3QualityCommands.executable(getExecutable().get());
        var cwd = getWorkingDirectory().get().getAsFile();
        if (!Files.isDirectory(cwd.toPath())) {
            throw new GradleException("Language-tool working directory is missing: " + cwd);
        }
        for (String arg : getArguments().get()) {
            if (arg == null || arg.indexOf('\0') >= 0) {
                throw new GradleException("Invalid language tool argument");
            }
        }
        try {
            Files.createDirectories(getArtifactDirectory().get().getAsFile().toPath());
        } catch (IOException failure) {
            throw new GradleException("Cannot prepare language-tool output directory", failure);
        }
        getExecOperations().exec(spec -> {
            spec.setExecutable(executable);
            spec.setArgs(getArguments().get());
            spec.setWorkingDir(cwd);
        });
    }
}
