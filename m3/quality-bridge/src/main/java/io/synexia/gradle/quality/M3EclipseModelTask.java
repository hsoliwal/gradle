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
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
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
    private static final byte[] CONTEXT_PREFIX = "{\"schema\":\"m3-eclipse-build-context-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] HANDOFF_PREFIX = "{\"schema\":\"m3-eclipse-build-handoff-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] RECONCILE_PREFIX = "{\"schema\":\"m3-eclipse-reconciliation-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] JDT_PREFIX = "{\"schema\":\"m3-jdt-compiler-facts-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CDT_PREFIX = "{\"schema\":\"m3-cdt-compiler-facts-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] CARGO_PREFIX = "{\"schema\":\"m3-cargo-build-facts-v1\""
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] LANGUAGE_PREFIX = "{\"schema\":\"m3-eclipse-language-facts-v1\""
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

    /** Optional, Eclipse-owned packet. Required only when reportMode is context. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getContextPacket();

    /** Original Eclipse m2e invocation envelope, never a Gradle build-cache input. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getHandoffPacket();

    /** Exact compiler model receipt produced by the original m2e JDT project. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJdtPacket();

    /** Exact selected native CDT scanner-facts packet from the original CDT core. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCdtPacket();

    /** Source-owned Corrosion/Cargo offline/locked metadata evidence. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getCargoPacket();

    /** Arbitrary original Eclipse language plugin compact source-owner facts. */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLanguagePacket();

    /** Exact installed M3EclipseLanguageEvidenceProvider Java 17 service ID. */
    @Optional
    @Input
    public abstract Property<String> getLanguageProviderId();

    /** "model" retains the original v1 schema; "owners" selects the generic builder-owner report. */
    @Input
    public abstract Property<String> getReportMode();

    @Inject
    protected abstract ExecOperations getExecOperations();

    /** Gradle supplies a configuration-cache-compatible layout service for this task. */
    @Inject
    protected ProjectLayout getM3ProjectLayout() {
        throw new UnsupportedOperationException("Gradle injects the project layout service");
    }

    @TaskAction
    public void inspectEclipseProject() {
        Path root = getEclipseProject().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path checkout = getM3ProjectLayout().getProjectDirectory().getAsFile().toPath()
                .toAbsolutePath().normalize();
        Path build = getM3ProjectLayout().getBuildDirectory().get().getAsFile().toPath()
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
            if (!"model".equals(mode) && !"owners".equals(mode)
                    && !"context".equals(mode) && !"handoff".equals(mode)
                    && !"reconcile".equals(mode) && !"jdt".equals(mode)
                    && !"cdt".equals(mode) && !"cargo".equals(mode)
                    && !"language".equals(mode)) {
                throw new IOException("unsupported Eclipse report mode: " + mode);
            }
            Path packet = null;
            if ("context".equals(mode) || "handoff".equals(mode)
                    || "reconcile".equals(mode) || "jdt".equals(mode)
                    || "cdt".equals(mode) || "cargo".equals(mode)
                    || "language".equals(mode)) {
                var declared = ("handoff".equals(mode) || "reconcile".equals(mode)
                        || "jdt".equals(mode) || "cdt".equals(mode)
                        || "cargo".equals(mode) || "language".equals(mode))
                        ? getHandoffPacket() : getContextPacket();
                if (!declared.isPresent()) {
                    throw new IOException("Eclipse source-owner packet was not supplied");
                }
                packet = declared.get().getAsFile().toPath().toAbsolutePath().normalize();
                long maxBytes = ("handoff".equals(mode) || "reconcile".equals(mode)
                        || "jdt".equals(mode) || "cdt".equals(mode)
                        || "cargo".equals(mode) || "language".equals(mode))
                        ? 12L * 1024 * 1024 : 1024L * 1024;
                if (!packet.startsWith(root) || !Files.isRegularFile(packet, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(packet) > maxBytes) {
                    throw new IOException("Eclipse source-owner packet must be project-local and bounded");
                }
                rejectLinkedPath(checkout, packet);
            }
            Path jdtPacket = null;
            if ("jdt".equals(mode)) {
                if (!getJdtPacket().isPresent()) {
                    throw new IOException("original JDT compiler facts packet was not supplied");
                }
                jdtPacket = getJdtPacket().get().getAsFile().toPath().toAbsolutePath().normalize();
                if (!jdtPacket.startsWith(root)
                        || !Files.isRegularFile(jdtPacket, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(jdtPacket) > 16 * 1024) {
                    throw new IOException("JDT compiler facts must be a project-local file under 16 KiB");
                }
                rejectLinkedPath(checkout, jdtPacket);
            }
            Path cdtPacket = null;
            if ("cdt".equals(mode)) {
                if (!getCdtPacket().isPresent()) {
                    throw new IOException("original CDT scanner facts packet was not supplied");
                }
                cdtPacket = getCdtPacket().get().getAsFile().toPath().toAbsolutePath().normalize();
                if (!cdtPacket.startsWith(root)
                        || !Files.isRegularFile(cdtPacket, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(cdtPacket) > 64 * 1024) {
                    throw new IOException("CDT facts must be a project-local file under 64 KiB");
                }
                rejectLinkedPath(checkout, cdtPacket);
            }
            Path cargoPacket = null;
            if ("cargo".equals(mode)) {
                if (!getCargoPacket().isPresent()) {
                    throw new IOException("original Corrosion/Cargo facts packet was not supplied");
                }
                cargoPacket = getCargoPacket().get().getAsFile().toPath().toAbsolutePath().normalize();
                if (!cargoPacket.startsWith(root)
                        || !Files.isRegularFile(cargoPacket, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(cargoPacket) > 64 * 1024) {
                    throw new IOException("Cargo facts must be a project-local file under 64 KiB");
                }
                rejectLinkedPath(checkout, cargoPacket);
            }
            Path languagePacket = null;
            String languageProviderId = null;
            if ("language".equals(mode)) {
                if (!getLanguagePacket().isPresent() || !getLanguageProviderId().isPresent()) {
                    throw new IOException("original language packet and installed provider ID are required");
                }
                languageProviderId = getLanguageProviderId().get();
                if (!languageProviderId.matches("[a-z][a-z0-9._-]{0,63}")) {
                    throw new IOException("invalid installed language evidence provider ID");
                }
                languagePacket = getLanguagePacket().get().getAsFile().toPath().toAbsolutePath().normalize();
                if (!languagePacket.startsWith(root)
                        || !Files.isRegularFile(languagePacket, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(languagePacket) > 1024 * 1024) {
                    throw new IOException("language owner packet must be a project-local file under 1 MiB");
                }
                rejectLinkedPath(checkout, languagePacket);
            }
            List<String> command = switch (mode) {
                case "owners" -> M3EclipseModelCommands.owners(
                        getJavaExecutable().get(), engine.toFile(), root.toFile(), installedProviders);
                case "context" -> M3EclipseModelCommands.context(
                        getJavaExecutable().get(), engine.toFile(), packet.toFile(), installedProviders);
                case "handoff" -> M3EclipseModelCommands.handoff(
                        getJavaExecutable().get(), engine.toFile(), packet.toFile(), installedProviders);
                case "reconcile" -> M3EclipseModelCommands.reconcile(
                        getJavaExecutable().get(), engine.toFile(), root.toFile(), packet.toFile(), installedProviders);
                case "jdt" -> M3EclipseModelCommands.jdt(
                        getJavaExecutable().get(), engine.toFile(), jdtPacket.toFile(), packet.toFile(),
                        installedProviders);
                case "cdt" -> M3EclipseModelCommands.cdt(
                        getJavaExecutable().get(), engine.toFile(), cdtPacket.toFile(), packet.toFile(),
                        installedProviders);
                case "cargo" -> M3EclipseModelCommands.cargo(
                        getJavaExecutable().get(), engine.toFile(), cargoPacket.toFile(), packet.toFile(),
                        installedProviders);
                case "language" -> M3EclipseModelCommands.languageFacts(
                        getJavaExecutable().get(), engine.toFile(), languageProviderId,
                        languagePacket.toFile(), packet.toFile(), installedProviders);
                default -> M3EclipseModelCommands.inspect(
                        getJavaExecutable().get(), engine.toFile(), root.toFile(), installedProviders);
            };
            M3BoundedOutput output = new M3BoundedOutput(
                    "handoff".equals(mode) ? 24 * 1024 * 1024 : MAX_REPORT_BYTES, "Eclipse model");
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
            byte[] expectedPrefix = switch (mode) {
                case "owners" -> OWNERS_PREFIX;
                case "context" -> CONTEXT_PREFIX;
                case "handoff" -> HANDOFF_PREFIX;
                case "reconcile" -> RECONCILE_PREFIX;
                case "jdt" -> JDT_PREFIX;
                case "cdt" -> CDT_PREFIX;
                case "cargo" -> CARGO_PREFIX;
                case "language" -> LANGUAGE_PREFIX;
                default -> MODEL_PREFIX;
            };
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
