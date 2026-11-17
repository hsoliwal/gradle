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
 * Opt-in Gradle proxy for Maven's original AST/atom fingerprint producer.
 * <p>
 * Only the pinned Java 21 producer validates canonical preparation and
 * atom/pattern receipts, owns MIndexDB persistence and publishes the facts.
 * Gradle does not promise cacheability, Java source transformation or proof.
 */
@UntrackedTask(because = "Original AST producer validates source, receipts and native database generations")
public abstract class M3MachineFingerprintTask extends DefaultTask {
    @Input public abstract Property<String> getJavaExecutable();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEngineJar();
    @Input public abstract Property<String> getEngineSha256();
    @InputFiles @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getProviderJars();
    @Input public abstract MapProperty<String, String> getProviderJarSha256();
    @Internal public abstract DirectoryProperty getProjectDirectory();
    @Internal public abstract DirectoryProperty getSourceRoot();
    @Internal public abstract DirectoryProperty getBuildDirectory();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPreparationReport();
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAtomPatternManifest();
    @Input public abstract Property<Integer> getWorkers();
    @Input public abstract Property<Boolean> getAttributeTypes();
    @Input public abstract Property<Boolean> getAnalyzeFlow();
    @Input public abstract Property<String> getModuleDb();

    @Inject protected abstract ExecOperations getExecOperations();

    /** Validates only the process admission boundary; Maven revalidates facts. */
    @TaskAction
    public void produceFingerprints() {
        Path root = absolute(getProjectDirectory().get().getAsFile());
        Path build = absolute(getBuildDirectory().get().getAsFile());
        Path source = absolute(getSourceRoot().get().getAsFile());
        Path preparation = absolute(getPreparationReport().get().getAsFile());
        Path pattern = absolute(getAtomPatternManifest().get().getAsFile());
        Path engine = absolute(getEngineJar().get().getAsFile());
        try {
            requirePaths(root, build, source, preparation, pattern);
            M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
            List<File> providers = M3ProviderClasspath.verify(
                    getProviderJars().getFiles(), getProviderJarSha256().get());
            List<String> command = M3MachineFingerprintCommands.command(
                    getJavaExecutable().get(), engine.toFile(), root.toFile(), build.toFile(),
                    source.toFile(), preparation.toFile(), pattern.toFile(), getWorkers().get(),
                    getAttributeTypes().get(), getAnalyzeFlow().get(), getModuleDb().get(), providers);
            getExecOperations().exec(spec -> {
                spec.setExecutable(command.get(0));
                spec.setArgs(command.subList(1, command.size()));
                spec.setWorkingDir(root.toFile());
            });
        } catch (IOException refused) {
            throw new GradleException("Invalid M3 AST producer or canonical evidence paths", refused);
        }
    }

    private static Path absolute(File value) {
        return value.toPath().toAbsolutePath().normalize();
    }

    private static void requirePaths(Path root, Path build, Path source, Path preparation, Path pattern)
            throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !source.startsWith(root) || source.equals(root)
                || !build.startsWith(root) || build.equals(root) || build.startsWith(source)
                || source.startsWith(build)
                || !regularReceipt(root, source, preparation, ".json")
                || !regularReceipt(root, source, pattern, ".tsv")) {
            throw new IOException("M3 fingerprint project, source, build or receipt scope refused");
        }
        for (Path scoped : List.of(source, build, preparation, pattern)) {
            for (Path part = scoped; part != null && part.startsWith(root); part = part.getParent()) {
                if (Files.isSymbolicLink(part)) throw new IOException("symlinked M3 fingerprint path");
            }
        }
    }

    private static boolean regularReceipt(Path root, Path source, Path receipt, String suffix)
            throws IOException {
        return receipt.startsWith(root) && !receipt.startsWith(source)
                && receipt.getFileName() != null && receipt.getFileName().toString().endsWith(suffix)
                && Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                && Files.size(receipt) <= 1_048_576;
    }
}
