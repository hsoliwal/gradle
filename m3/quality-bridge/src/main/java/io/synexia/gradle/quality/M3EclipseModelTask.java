/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/**
 * Explicit read-only Eclipse project-model proxy to Maven's source-owned CLI.
 * Gradle supplies task scheduling and a report destination; Maven owns parsing.
 */
@UntrackedTask(because = "Eclipse descriptors, source roots and external CLI inputs are not a complete cache key")
public abstract class M3EclipseModelTask extends DefaultTask {
    private static final int MAX_REPORT_BYTES = 16 * 1024 * 1024;
    private static final byte[] MODEL_PREFIX = "{\"schema\":\"m3-eclipse-project-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] OWNERS_PREFIX = "{\"schema\":\"m3-eclipse-builder-owners-v1\""
            .getBytes(StandardCharsets.UTF_8);

    @Input
    public abstract Property<String> getJavaExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getEngineJar();

    @Input
    public abstract Property<String> getEngineSha256();

    /** Explicitly installed Eclipse provider JARs; no downloads or OSGi activation. */
    @InputFiles
    @PathSensitive(PathSensitivity.NONE)
    public abstract ConfigurableFileCollection getProviderJars();

    @Input
    public abstract MapProperty<String, String> getProviderJarSha256();

    @Internal
    public abstract DirectoryProperty getEclipseProject();

    @OutputFile
    public abstract RegularFileProperty getModelReport();

    /** "model" retains the original v1 schema; "owners" selects the generic builder-owner report. */
    @Input
    public abstract Property<String> getReportMode();

    @Inject
    protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void inspectEclipseProject() {
        Path root = getEclipseProject().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path checkout = getProject().getLayout().getProjectDirectory().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path build = getProject().getLayout().getBuildDirectory().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path engine = getEngineJar().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path report = getModelReport().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.startsWith(checkout)
                || !Files.isRegularFile(root.resolve(".project"), LinkOption.NOFOLLOW_LINKS)
                || !build.startsWith(checkout) || !report.startsWith(build)
                || !report.toString().endsWith(".json")) {
            throw new GradleException("M3 Eclipse model requires a project-local Eclipse descriptor and build JSON report");
        }
        try {
            rejectLinkedPath(checkout, root);
            rejectLinkedPath(checkout, root.resolve(".project"));
            rejectLinkedPath(checkout, report);
            M3PolyglotCommands.verifyEngine(engine, getEngineSha256().get());
            List<File> installedProviders = M3ProviderClasspath.verify(
                    getProviderJars().getFiles(), getProviderJarSha256().get());

            String mode = getReportMode().get();
            if (!"model".equals(mode) && !"owners".equals(mode)) {
                throw new IOException("unsupported Eclipse report mode: " + mode);
            }
            List<String> command = "owners".equals(mode)
                    ? M3EclipseModelCommands.owners(
                            getJavaExecutable().get(), engine.toFile(), root.toFile(), installedProviders)
                    : M3EclipseModelCommands.inspect(
                            getJavaExecutable().get(), engine.toFile(), root.toFile(), installedProviders);
            M3BoundedOutput output = new M3BoundedOutput(MAX_REPORT_BYTES, "Eclipse model");
            getExecOperations().exec(spec -> {
                spec.setExecutable(command.get(0));
                spec.setArgs(command.subList(1, command.size()));
                spec.setWorkingDir(root.toFile());
                spec.setStandardOutput(output);
            });
            byte[] json = output.bytes();
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(json));
            // Java's println uses CRLF on Windows; normalize only that terminal
            // delimiter so the Maven descriptor JSON has one portable digest.
            if (json.length >= 3 && json[json.length - 2] == '\r' && json[json.length - 1] == '\n') {
                json = Arrays.copyOf(json, json.length - 1);
                json[json.length - 1] = '\n';
            }
            byte[] expectedPrefix = "owners".equals(mode) ? OWNERS_PREFIX : MODEL_PREFIX;
            if (json.length < expectedPrefix.length + 2
                    || !Arrays.equals(Arrays.copyOf(json, expectedPrefix.length), expectedPrefix)
                    || json[json.length - 2] != '}' || json[json.length - 1] != '\n') {
                throw new IOException("Maven Eclipse model schema or encoding mismatch");
            }

            Path parent = report.getParent();
            Files.createDirectories(parent);
            rejectLinkedPath(checkout, report);
            Path temporary = Files.createTempFile(parent, ".m3-eclipse-", ".tmp");
            try {
                Files.write(temporary, json);
                Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException invalid) {
            throw new GradleException("M3 Eclipse model inspection refused", invalid);
        }
    }

    private static void rejectLinkedPath(Path checkout, Path destination) throws IOException {
        for (Path path = destination; path != null && path.startsWith(checkout); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("symlinked Eclipse model path");
        }
    }

}
