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
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Delegate original M3 Java inventory, policy and BQRS exports to its JDK 21 runner. */
@UntrackedTask(because = "The original external CodeQL runner owns its mutable database and stage evidence")
public abstract class M3CodeqlEvidenceTask extends DefaultTask {
    @Input
    public abstract Property<String> getJava21Executable();

    @InputFile
    public abstract RegularFileProperty getM3CodeqlRunnerJar();

    @Internal
    public abstract DirectoryProperty getCodeqlRepository();

    @Internal
    public abstract DirectoryProperty getDatabaseDirectory();

    @Input
    public abstract Property<String> getCodeqlExecutable();

    @Input
    public abstract Property<Integer> getThreads();

    @Input
    public abstract Property<Integer> getRamMb();

    @Input
    public abstract Property<Integer> getTimeoutSeconds();

    @OutputDirectory
    public abstract DirectoryProperty getEvidenceDirectory();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void analyze() {
        Path root = getCodeqlRepository().get().getAsFile().toPath();
        Path db = getDatabaseDirectory().get().getAsFile().toPath();
        Path jar = getM3CodeqlRunnerJar().get().getAsFile().toPath();
        Path output = getEvidenceDirectory().get().getAsFile().toPath();
        if (!Files.isDirectory(root) || !Files.isDirectory(db) || !Files.isRegularFile(jar)) {
            throw new GradleException("M3 CodeQL runner, checkout and existing database are required");
        }
        try {
            if (Files.exists(output)) {
                try (var children = Files.list(output)) {
                    if (children.findAny().isPresent()) {
                        throw new GradleException("M3 CodeQL requires a fresh evidence directory: " + output);
                    }
                }
            }
            Files.createDirectories(output.getParent());
        } catch (IOException failure) {
            throw new GradleException("Cannot prepare M3 CodeQL evidence directory", failure);
        }
        List<String> command = M3QualityCommands.m3Codeql(getJava21Executable().get(), jar.toFile(),
                root.toFile(), db.toFile(), output.toFile(), getCodeqlExecutable().get(),
                getThreads().get(), getRamMb().get(), getTimeoutSeconds().get());
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
        });
    }
}
