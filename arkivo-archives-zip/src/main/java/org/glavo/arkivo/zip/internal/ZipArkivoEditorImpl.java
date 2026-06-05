// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoCommitTarget;
import org.glavo.arkivo.ArkivoEditStorage;
import org.glavo.arkivo.ArkivoEditor;
import org.glavo.arkivo.ArkivoSourceMutationPolicy;
import org.glavo.arkivo.zip.ZipArkivoEditor;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/// Implements ZIP archive editor configuration and lifecycle.
@NotNullByDefault
public final class ZipArkivoEditorImpl extends ZipArkivoEditor {
    /// The default memory threshold used by hybrid entry storage.
    private static final long DEFAULT_MEMORY_THRESHOLD = 8L * 1024L * 1024L;

    /// The source archive path edited by this editor.
    private final Path sourcePath;

    /// The storage strategy used for new and replacement entry data.
    private final ArkivoEditStorage storage;

    /// The target strategy used when committing assembled archive bytes.
    private final ArkivoCommitTarget commitTarget;

    /// The policy that decides whether source-file mutation is allowed.
    private final ArkivoSourceMutationPolicy sourceMutationPolicy;

    /// Whether this editor is open.
    private boolean open = true;

    /// Creates a ZIP archive editor implementation.
    private ZipArkivoEditorImpl(
            Path sourcePath,
            ArkivoEditStorage storage,
            ArkivoCommitTarget commitTarget,
            ArkivoSourceMutationPolicy sourceMutationPolicy
    ) {
        this.sourcePath = Objects.requireNonNull(sourcePath, "sourcePath");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.commitTarget = Objects.requireNonNull(commitTarget, "commitTarget");
        this.sourceMutationPolicy = Objects.requireNonNull(sourceMutationPolicy, "sourceMutationPolicy");
    }

    /// Opens a ZIP archive editor implementation.
    public static ZipArkivoEditorImpl open(Path path, Map<String, ?> environment) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(environment, "environment");
        Path sourcePath = path.toAbsolutePath().normalize();
        Path workingDirectory = workingDirectory(sourcePath);
        ArkivoEditStorage storage = ArkivoEditor.STORAGE.readOrDefault(
                environment,
                ArkivoEditStorage.hybrid(DEFAULT_MEMORY_THRESHOLD, workingDirectory)
        );
        ArkivoCommitTarget commitTarget = ArkivoEditor.COMMIT_TARGET.readOrDefault(
                environment,
                ArkivoCommitTarget.atomicReplace(workingDirectory)
        );
        ArkivoSourceMutationPolicy sourceMutationPolicy = ArkivoEditor.SOURCE_MUTATION_POLICY.readOrDefault(
                environment,
                ArkivoSourceMutationPolicy.never()
        );
        return new ZipArkivoEditorImpl(sourcePath, storage, commitTarget, sourceMutationPolicy);
    }

    /// Returns the source archive path edited by this editor.
    @Override
    public Path sourcePath() {
        return sourcePath;
    }

    /// Returns the storage strategy used for new and replacement entry data.
    @Override
    public ArkivoEditStorage storage() {
        return storage;
    }

    /// Returns the target strategy used when committing assembled archive bytes.
    @Override
    public ArkivoCommitTarget commitTarget() {
        return commitTarget;
    }

    /// Returns the policy that decides whether source-file mutation is allowed.
    @Override
    public ArkivoSourceMutationPolicy sourceMutationPolicy() {
        return sourceMutationPolicy;
    }

    /// Closes this editor and its configured storage.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        storage.close();
    }

    /// Returns the default working directory for editor temporary files.
    private static Path workingDirectory(Path sourcePath) {
        Path parent = sourcePath.getParent();
        return parent != null ? parent : Path.of(".").toAbsolutePath().normalize();
    }
}
