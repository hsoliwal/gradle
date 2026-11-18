/*
 * Copyright 2026 Gradle and contributors.
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.process.ExecOperations;

/** Managed process and evidence inputs shared by the two original Maven review owners. */
public abstract class M3SmartReviewTask extends DefaultTask {
    @Input public abstract Property<String> getJavaExecutable();
    @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getEngineJar();
    @Input public abstract Property<String> getEngineSha256();
    @InputFiles @PathSensitive(PathSensitivity.NONE) public abstract ConfigurableFileCollection getProviderJars();
    @Input public abstract MapProperty<String, String> getProviderJarSha256();
    @Internal public abstract DirectoryProperty getProjectDirectory();
    @Internal public abstract DirectoryProperty getBuildDirectory();
    @Internal public abstract DirectoryProperty getSourceRoot();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getPreparationReport();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getAtomPatternManifest();
    @Input public abstract Property<Boolean> getOptionLlm();
    @Input public abstract Property<String> getBackend();
    @Input public abstract Property<Integer> getTimeoutSeconds();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getChromeLlmJar();
    @Optional @Input public abstract Property<String> getChromeLlmJarSha256();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getLlamaExecutable();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getJniLibrary();
    @Optional @InputFile @PathSensitive(PathSensitivity.NONE) public abstract RegularFileProperty getModelFile();

    @Inject protected abstract ExecOperations getExecOperations();

    /** Collects only declared inputs and validates their local scope before process admission. */
    protected final Map<String, String> reviewOptions() throws IOException {
        Path root = absolute(getProjectDirectory().get().getAsFile());
        Path build = absolute(getBuildDirectory().get().getAsFile());
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || !build.startsWith(root) || build.equals(root)) {
            throw new IOException("M3 review output must remain inside the selected project");
        }
        noLinks(root, build);
        Map<String, String> options = new LinkedHashMap<>();
        options.put("--project-dir", root.toString());
        options.put("--build-dir", build.toString());
        if (getSourceRoot().isPresent()) {
            Path source = absolute(getSourceRoot().get().getAsFile());
            if (!source.startsWith(root) || source.equals(root) || source.startsWith(build)
                    || build.startsWith(source) || !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("M3 review source and output scopes must be separate project directories");
            }
            noLinks(root, source);
            options.put("--source-root", source.toString());
        }
        localInput(options, "--preparation-report", getPreparationReport(), 1_048_576);
        localInput(options, "--atom-pattern-manifest", getAtomPatternManifest(), 128L * 1024 * 1024);
        options.put("--backend", getBackend().get());
        options.put("--timeout-seconds", Integer.toString(getTimeoutSeconds().get()));
        nativeInput(options, "--chrome-llm-jar", getChromeLlmJar());
        nativeInput(options, "--llama-bin", getLlamaExecutable());
        nativeInput(options, "--jni-lib", getJniLibrary());
        nativeInput(options, "--model", getModelFile());
        return options;
    }

    /** Adds a bounded project-local evidence file, without interpreting producer claims. */
    protected final void localInput(Map<String, String> options, String name,
            RegularFileProperty property, long maximumBytes) throws IOException {
        if (!property.isPresent()) return;
        Path root = absolute(getProjectDirectory().get().getAsFile());
        Path path = absolute(property.get().getAsFile());
        if (!path.startsWith(root) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) > maximumBytes
                || getSourceRoot().isPresent() && path.startsWith(absolute(getSourceRoot().get().getAsFile()))) {
            throw new IOException("M3 review evidence file is outside its declared scope: " + name);
        }
        noLinks(root, path);
        options.put(name, path.toString());
    }

    /** Executes the original Java owner through Gradle's injected process service. */
    protected final void runReview(String mainClass, Map<String, String> options) throws IOException {
        Path engine = absolute(getEngineJar().get().getAsFile());
        M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
        List<File> providers = M3ProviderClasspath.verify(getProviderJars().getFiles(), getProviderJarSha256().get());
        List<String> command = M3SmartReviewCommands.command(getJavaExecutable().get(), engine.toFile(),
                providers, mainClass, options, getOptionLlm().get());
        if (getChromeLlmJar().isPresent()) {
            M3PolyglotCommands.verifyEngine(absolute(getChromeLlmJar().get().getAsFile()),
                    getChromeLlmJarSha256().getOrNull());
        } else if (getChromeLlmJarSha256().isPresent()) {
            throw new IOException("ChromeLLM digest requires its explicitly selected JAR");
        }
        File root = getProjectDirectory().get().getAsFile();
        getExecOperations().exec(spec -> {
            spec.setExecutable(command.get(0));
            spec.setArgs(command.subList(1, command.size()));
            spec.setWorkingDir(root);
        });
    }

    /** Provides a consistent diagnostic while leaving the original owner's nonzero exit authoritative. */
    protected final GradleException refused(Exception failure) {
        return new GradleException("M3 review invocation refused: " + failure.getMessage(), failure);
    }

    private static void nativeInput(Map<String, String> options, String name, RegularFileProperty property)
            throws IOException {
        if (!property.isPresent()) return;
        Path path = absolute(property.get().getAsFile());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("M3 native review input must be an existing regular file: " + name);
        }
        options.put(name, path.toString());
    }

    private static Path absolute(File file) {
        return file.toPath().toAbsolutePath().normalize();
    }

    private static void noLinks(Path root, Path path) throws IOException {
        for (Path current = path; current != null && current.startsWith(root); current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("symlinked M3 review project path");
        }
    }
}
