/*
 * SPDX-FileCopyrightText: 2026 Contributors to the Synexia Project
 * SPDX-License-Identifier: Apache-2.0
 */
package io.synexia.gradle.quality;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Incremental;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.options.Option;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.work.FileChange;
import org.gradle.work.FileType;
import org.gradle.work.InputChanges;

/**
 * Gradle-owned change events emitted as Maven M3-IDE-DELTA-V1.
 * Completeness is conservatively false; this never authorizes task skipping.
 */
@DisableCachingByDefault(because = "A host delta depends on local execution history, not just current sources")
public abstract class M3IdeDeltaExportTask extends DefaultTask {
    @Incremental
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getSourceFiles();

    @Input
    public abstract Property<String> getProjectIdentifier();

    @OutputFile
    public abstract RegularFileProperty getDeltaReport();

    @OutputFile
    public abstract RegularFileProperty getDeltaSha256();

    @Internal
    public abstract DirectoryProperty getWorkspace();

    @Internal
    public abstract DirectoryProperty getOutputRoot();

    @TaskAction
    public void exportDelta(InputChanges inputChanges) {
        Path root = getWorkspace().get().getAsFile().toPath().toAbsolutePath().normalize();
        Path outputRoot = getOutputRoot().get().getAsFile().toPath().toAbsolutePath().normalize();
        try {
            ArrayList<M3IdeDeltaWire.Change> changes = collect(inputChanges, root, outputRoot);
            changes.sort(Comparator.comparing(M3IdeDeltaWire.Change::path));
            M3IdeDeltaWire.Packet packet = new M3IdeDeltaWire.Packet("gradle",
                    getProjectIdentifier().get(), inputChanges.isIncremental()
                            ? M3IdeDeltaWire.BuildKind.INCREMENTAL : M3IdeDeltaWire.BuildKind.FULL,
                    false, changes);
            byte[] bytes = M3IdeDeltaWire.encode(packet);
            Path report = getDeltaReport().get().getAsFile().toPath().toAbsolutePath().normalize();
            Path seal = getDeltaSha256().get().getAsFile().toPath().toAbsolutePath().normalize();
            validateOutput(report, root, outputRoot);
            validateOutput(seal, root, outputRoot);
            if (report.equals(seal)) throw new IOException("IDE delta and digest share a destination");
            publish(report, bytes);
            publish(seal, (sha256(bytes) + "\n").getBytes(StandardCharsets.UTF_8));
            getLogger().lifecycle("M3 Gradle source delta: {} changes, kind={}, complete=false",
                    changes.size(), packet.kind());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new GradleException("Invalid M3 Gradle IDE delta", invalid);
        }
    }

    private ArrayList<M3IdeDeltaWire.Change> collect(InputChanges input, Path root, Path outputRoot)
            throws IOException {
        ArrayList<M3IdeDeltaWire.Change> changes = new ArrayList<>();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            throw new IOException("workspace is not an ordinary directory");
        }
        if (!input.isIncremental()) return changes;
        for (FileChange change : input.getFileChanges(getSourceFiles())) {
            if (change.getFileType() == FileType.DIRECTORY) continue;
            Path file = change.getFile().toPath().toAbsolutePath().normalize();
            if (!file.startsWith(root) || file.startsWith(outputRoot)) {
                throw new IOException("delta input outside workspace or within report root");
            }
            rejectSymlinks(root, file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            changes.add(new M3IdeDeltaWire.Change(
                    M3IdeDeltaWire.ChangeKind.valueOf(change.getChangeType().name()), relative));
        }
        return changes;
    }

    private static void validateOutput(Path file, Path workspace, Path outputRoot) throws IOException {
        if (!outputRoot.startsWith(workspace) || !file.startsWith(outputRoot)
                || file.equals(outputRoot) || Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("IDE delta destination must be inside project build directory");
        }
        rejectSymlinks(workspace, file);
    }

    private static void rejectSymlinks(Path root, Path file) throws IOException {
        if (Files.isSymbolicLink(root)) throw new IOException("linked delta workspace");
        Path parent = root;
        for (Path segment : root.relativize(file)) {
            parent = parent.resolve(segment);
            if (Files.isSymbolicLink(parent)) throw new IOException("linked delta path");
        }
    }

    private static void publish(Path path, byte[] bytes) throws IOException {
        Path parent = path.getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".m3-ide-delta-", ".tmp");
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }
}
