/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import javax.inject.Inject;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.UntrackedTask;
import org.gradle.process.ExecOperations;

/** Read-only proxy for Maven's provider inventory; Gradle owns no provider registry. */
@UntrackedTask(because = "The pinned CLI and provider classloader own the actual installed capability inventory")
public abstract class M3PolyglotProvidersTask extends DefaultTask {
    private static final int MAX_REPORT_BYTES = 256 * 1024;

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

    @OutputFile
    public abstract RegularFileProperty getProviderReport();

    @Inject
    protected abstract ExecOperations getExecOperations();

    /** Gradle supplies a configuration-cache-compatible layout service for this task. */
    @Inject
    protected ProjectLayout getM3ProjectLayout() {
        throw new UnsupportedOperationException("Gradle injects the project layout service");
    }

    @TaskAction
    public void inspectProviders() {
        Path checkout = getM3ProjectLayout().getProjectDirectory().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path build = getM3ProjectLayout().getBuildDirectory().get().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path report = getProviderReport().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path jar = getEngineJar().get().getAsFile().toPath().toAbsolutePath().normalize();
        if (!build.startsWith(checkout) || !report.startsWith(build)
                || !report.toString().endsWith(".tsv")) {
            throw new GradleException("M3 provider inventory must be written under the project build directory");
        }
        try {
            rejectSymlinks(checkout, report);
            M3PolyglotCommands.verifyEngine(jar, getEngineSha256().get());
            List<File> providerJars = M3ProviderClasspath.verify(
                    getProviderJars().getFiles(), getProviderJarSha256().get());
            List<String> args = M3PolyglotCommands.providers(getJavaExecutable().get(),
                    jar.toFile(), providerJars);
            M3BoundedOutput output = new M3BoundedOutput(MAX_REPORT_BYTES, "M3 providers");
            getExecOperations().exec(spec -> {
                spec.setExecutable(args.get(0));
                spec.setArgs(args.subList(1, args.size()));
                spec.setWorkingDir(checkout.toFile());
                spec.setStandardOutput(output);
            });
            byte[] bytes = output.bytes();
            String canonical = strictUtf8(bytes).replace("\r\n", "\n");
            M3ProviderInventoryV1.validate(canonical);
            Files.createDirectories(report.getParent());
            rejectSymlinks(checkout, report);
            Path temporary = Files.createTempFile(report.getParent(), ".m3-providers-", ".tmp");
            try {
                Files.writeString(temporary, canonical, StandardCharsets.UTF_8);
                Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new GradleException("M3 Maven provider inventory refused", failure);
        }
    }

    private static String strictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IOException("M3 provider inventory must be valid UTF-8", invalid);
        }
    }

    private static void rejectSymlinks(Path checkout, Path destination) throws IOException {
        for (Path path = destination; path != null && path.startsWith(checkout); path = path.getParent()) {
            if (Files.isSymbolicLink(path)) throw new IOException("M3 provider report traverses symlink");
        }
    }
}
