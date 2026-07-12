// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Set;

/// Provides built-in archive editor commit targets.
@NotNullByDefault
public final class ArkivoCommitTargetSupport {
    /// The commit target that writes directly to the original archive path.
    private static final ArkivoCommitTarget REPLACE_ORIGINAL = DirectOriginalTarget.INSTANCE;

    /// Creates built-in archive editor commit targets.
    private ArkivoCommitTargetSupport() {
    }

    /// Returns a target that writes directly to the original archive path.
    public static ArkivoCommitTarget replaceOriginal() {
        return REPLACE_ORIGINAL;
    }

    /// Returns a target that writes to a temporary file and atomically replaces the original archive path on commit.
    public static ArkivoCommitTarget atomicReplace(Path directory) {
        return new AtomicReplaceTarget(directory);
    }

    /// Returns a target that writes the assembled archive to the given path.
    public static ArkivoCommitTarget writeTo(Path path) {
        return new FixedPathTarget(path);
    }

    /// Implements direct writes to the original archive path.
    @NotNullByDefault
    private enum DirectOriginalTarget implements ArkivoCommitTarget {
        /// The shared direct target instance.
        INSTANCE;

        /// Opens output over the source archive path.
        @Override
        public ArkivoCommitOutput openOutput(Path sourcePath) {
            return new PathCommitOutput(Objects.requireNonNull(sourcePath, "sourcePath"), null);
        }
    }

    /// Implements atomic replacement through a temporary output file.
    @NotNullByDefault
    private static final class AtomicReplaceTarget implements ArkivoCommitTarget {
        /// The directory that receives temporary archive output.
        private final Path directory;

        /// Creates an atomic replacement target.
        private AtomicReplaceTarget(Path directory) {
            this.directory = Objects.requireNonNull(directory, "directory");
        }

        /// Opens temporary output for the given source archive path.
        @Override
        public ArkivoCommitOutput openOutput(Path sourcePath) throws IOException {
            Objects.requireNonNull(sourcePath, "sourcePath");
            Files.createDirectories(directory);
            Path temporaryPath = Files.createTempFile(directory, "arkivo-zip-archive-", ".tmp");
            return new PathCommitOutput(temporaryPath, sourcePath);
        }
    }

    /// Implements output to a fixed target path.
    @NotNullByDefault
    private static final class FixedPathTarget implements ArkivoCommitTarget {
        /// The output path.
        private final Path path;

        /// Creates a fixed path target.
        private FixedPathTarget(Path path) {
            this.path = Objects.requireNonNull(path, "path");
        }

        /// Opens output over the fixed target path.
        @Override
        public ArkivoCommitOutput openOutput(Path sourcePath) {
            Objects.requireNonNull(sourcePath, "sourcePath");
            return new PathCommitOutput(path, null);
        }
    }

    /// Implements path-backed commit output.
    @NotNullByDefault
    private static final class PathCommitOutput implements ArkivoCommitOutput {
        /// The path that receives assembled archive bytes.
        private final Path path;

        /// The path replaced during commit, or `null` when no move is required.
        private final Path replacementTarget;

        /// Whether this output has been committed or rolled back.
        private boolean finished;

        /// Whether rollback cleanup has completed.
        private boolean cleanupComplete;

        /// Creates path-backed commit output.
        private PathCommitOutput(Path path, Path replacementTarget) {
            this.path = Objects.requireNonNull(path, "path");
            this.replacementTarget = replacementTarget;
        }

        /// Returns the output path.
        @Override
        public Path path() {
            return path;
        }

        /// Opens a channel over the output path.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            Objects.requireNonNull(options, "options");
            ensureOpen();
            return Files.newByteChannel(path, options);
        }

        /// Commits this output.
        @Override
        public void commit() throws IOException {
            ensureOpen();
            Path target = replacementTarget;
            if (target != null) {
                Files.move(path, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            finished = true;
            cleanupComplete = true;
        }

        /// Rolls this output back.
        @Override
        public void rollback() throws IOException {
            if (cleanupComplete) {
                return;
            }
            finished = true;
            if (replacementTarget != null) {
                Files.deleteIfExists(path);
            }
            cleanupComplete = true;
        }

        /// Closes this output without committing it.
        @Override
        public void close() throws IOException {
            rollback();
        }

        /// Requires this output to be open.
        private void ensureOpen() throws IOException {
            if (finished) {
                throw new IOException("Commit output is closed");
            }
        }
    }
}
