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
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Read-only SARIF analysis of an existing CodeQL database with the official CLI. */
@UntrackedTask(because = "CodeQL may update its database; results depend on external packs and CLI bytes")
public abstract class M3CodeqlSarifTask extends DefaultTask {
    @Input
    public abstract Property<String> getCodeqlExecutable();

    @Internal
    public abstract DirectoryProperty getDatabaseDirectory();

    @Optional
    @Input
    public abstract Property<String> getQuerySuite();

    @Input
    public abstract Property<Integer> getThreads();

    @OutputFile
    public abstract RegularFileProperty getSarifReport();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void analyze() {
        Path database = getDatabaseDirectory().get().getAsFile().toPath();
        if (!Files.isDirectory(database)) {
            throw new GradleException("CodeQL database is missing: " + database);
        }
        Path report = getSarifReport().get().getAsFile().toPath();
        try {
            Files.createDirectories(report.getParent());
        } catch (IOException failure) {
            throw new GradleException("Cannot prepare CodeQL report directory", failure);
        }
        List<String> command = M3QualityCommands.codeql(getCodeqlExecutable().get(),
                database.toFile(), getQuerySuite().getOrNull(), report.toFile(), getThreads().get());
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
        });
    }
}
