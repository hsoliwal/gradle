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
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Read-only Semgrep with explicitly supplied local rules; no registry downloads. */
@UntrackedTask(because = "Semgrep CLI and its external rule engine are outside this plugin's cache contract")
public abstract class M3SemgrepTask extends DefaultTask {
    @Input
    public abstract Property<String> getSemgrepExecutable();

    @InputFile
    public abstract RegularFileProperty getLocalRulesFile();

    @Internal
    public abstract DirectoryProperty getSourceDirectory();

    @OutputFile
    public abstract RegularFileProperty getJsonReport();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void scan() {
        Path source = getSourceDirectory().get().getAsFile().toPath();
        Path rules = getLocalRulesFile().get().getAsFile().toPath();
        Path output = getJsonReport().get().getAsFile().toPath();
        if (!Files.isDirectory(source) || !Files.isRegularFile(rules)) {
            throw new GradleException("Existing source root and local Semgrep rules are required");
        }
        try {
            Files.createDirectories(output.getParent());
        } catch (IOException failure) {
            throw new GradleException("Cannot prepare Semgrep report directory", failure);
        }
        List<String> command = M3QualityCommands.semgrep(
                getSemgrepExecutable().get(), rules.toFile(), source.toFile(), output.toFile());
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
        });
    }
}
