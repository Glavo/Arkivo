// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Set;

/// Provides built-in archive editor commit targets.
@NotNullByDefault
public final class ArkivoCommitTargetSupport {
    /// The maximum number of attempts made for an atomic replacement rejected by a transient file lock.
    private static final int ATOMIC_MOVE_ATTEMPTS = 5;

    /// The base delay in milliseconds between atomic replacement attempts.
    private static final long ATOMIC_MOVE_RETRY_MILLISECONDS = 25L;

    /// Creates built-in archive editor commit targets.
    private ArkivoCommitTargetSupport() {
    }

    /// Opens an editor-owned transaction that retains channels whose closure has not completed.
    ///
    /// Commit requires all returned channels to have closed successfully. Rollback and close retry outstanding
    /// channel closures before invoking the underlying transaction; a failed closure prevents that invocation.
    /// The returned output is not safe for concurrent use.
    ///
    /// @param target the target that supplies the underlying transaction
    /// @param sourcePath the source archive path, or `null` for a non-path source
    /// @return an output owned by the editor until transaction cleanup succeeds
    /// @throws IOException if the target cannot open its output
    public static ArkivoCommitOutput openManagedOutput(ArkivoCommitTarget target, @Nullable Path sourcePath)
            throws IOException {
        return new ManagedOutput(Objects.requireNonNull(target.openOutput(sourcePath), "output"));
    }

    /// Retains caller-owned channels until successful closure before completing their transaction.
    @NotNullByDefault
    private static final class ManagedOutput implements ArkivoCommitOutput {
        /// The transaction completed after its channels are released.
        private final ArkivoCommitOutput delegate;

        /// Channels whose close has not yet returned successfully.
        private final ArrayList<ManagedChannel> channels = new ArrayList<>();

        /// Creates an owner for the opened transaction.
        private ManagedOutput(ArkivoCommitOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public Path path() {
            return delegate.path();
        }

        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            ManagedChannel channel = new ManagedChannel(delegate.openChannel(options));
            channels.add(channel);
            return channel;
        }

        @Override
        public void commit() throws IOException {
            if (!channels.isEmpty()) {
                throw new IOException("Commit output channels have not closed successfully");
            }
            delegate.commit();
        }

        @Override
        public void rollback() throws IOException {
            closeChannels();
            delegate.rollback();
        }

        @Override
        public void close() throws IOException {
            closeChannels();
            delegate.close();
        }

        /// Attempts every pending channel without completing the transaction after a failure.
        private void closeChannels() throws IOException {
            @Nullable Throwable failure = null;
            // A successful close removes its channel, so traverse a snapshot.
            for (ManagedChannel channel : channels.toArray(ManagedChannel[]::new)) {
                try {
                    channel.close();
                } catch (IOException | RuntimeException | Error exception) {
                    if (failure == null) failure = exception;
                    else if (failure != exception) failure.addSuppressed(exception);
                }
            }
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            if (failure instanceof Error exception) throw exception;
        }

        /// Removes itself from the owner only after its physical channel closes successfully.
        @NotNullByDefault
        private final class ManagedChannel implements SeekableByteChannel {
            /// The physical channel whose operations are forwarded.
            private final SeekableByteChannel delegate;

            /// Whether closure has returned successfully.
            private boolean closed;

            /// Takes ownership of one newly opened channel.
            private ManagedChannel(SeekableByteChannel delegate) {
                this.delegate = delegate;
            }

            @Override
            public int read(ByteBuffer destination) throws IOException {
                return delegate.read(destination);
            }

            @Override
            public int write(ByteBuffer source) throws IOException {
                return delegate.write(source);
            }

            @Override
            public long position() throws IOException {
                return delegate.position();
            }

            @Override
            public SeekableByteChannel position(long position) throws IOException {
                delegate.position(position);
                return this;
            }

            @Override
            public long size() throws IOException {
                return delegate.size();
            }

            @Override
            public SeekableByteChannel truncate(long size) throws IOException {
                delegate.truncate(size);
                return this;
            }

            @Override
            public boolean isOpen() {
                return delegate.isOpen();
            }

            @Override
            public void close() throws IOException {
                if (!closed) {
                    delegate.close();
                    closed = true;
                    channels.remove(this);
                }
            }
        }
    }

    /// Returns a target that writes directly to the original archive path.
    ///
    /// @return the shared direct-replacement target
    public static ArkivoCommitTarget replaceOriginal() {
        return DirectOriginalTarget.INSTANCE;
    }

    /// Returns a target that writes to a temporary file and atomically replaces the original archive path on commit.
    ///
    /// @param directory the temporary-output directory
    /// @return an atomic replacement target using {@code directory}
    public static ArkivoCommitTarget atomicReplace(Path directory) {
        return new AtomicReplaceTarget(directory);
    }

    /// Returns a target that writes the assembled archive to the given path.
    ///
    /// @param path the fixed output path
    /// @return a direct target for {@code path}
    public static ArkivoCommitTarget writeTo(Path path) {
        return new FixedPathTarget(path);
    }

    /// Atomically replaces a target while tolerating short-lived platform file locks.
    private static void replaceAtomically(Path source, Path target) throws IOException {
        for (int attempt = 1; attempt <= ATOMIC_MOVE_ATTEMPTS; attempt++) {
            try {
                Files.move(
                        source,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
                return;
            } catch (AccessDeniedException exception) {
                if (attempt == ATOMIC_MOVE_ATTEMPTS) {
                    throw exception;
                }
                try {
                    Thread.sleep(ATOMIC_MOVE_RETRY_MILLISECONDS * attempt);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    exception.addSuppressed(interruptedException);
                    throw exception;
                }
            }
        }
    }

    /// Implements direct writes to the original archive path.
    @NotNullByDefault
    private enum DirectOriginalTarget implements ArkivoCommitTarget {
        /// The shared direct target instance.
        INSTANCE;

        /// Opens output over the source archive path.
        @Override
        public ArkivoCommitOutput openOutput(@Nullable Path sourcePath) {
            if (sourcePath == null) {
                throw new IllegalArgumentException("Replacing the original archive requires a path-backed source");
            }
            return new PathCommitOutput(sourcePath, null);
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
        public ArkivoCommitOutput openOutput(@Nullable Path sourcePath) throws IOException {
            if (sourcePath == null) {
                throw new IllegalArgumentException(
                        "Atomically replacing the original archive requires a path-backed source"
                );
            }
            Files.createDirectories(directory);
            Path temporaryPath = Files.createTempFile(directory.toRealPath(), "arkivo-archive-", ".tmp");
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
        public ArkivoCommitOutput openOutput(@Nullable Path sourcePath) {
            return new PathCommitOutput(path, null);
        }
    }

    /// Implements path-backed commit output.
    @NotNullByDefault
    private static final class PathCommitOutput implements ArkivoCommitOutput {
        /// The path that receives assembled archive bytes.
        private final Path path;

        /// The path replaced during commit, or `null` when no move is required.
        private final @Nullable Path replacementTarget;

        /// Whether this output has been committed or rolled back.
        private boolean finished;

        /// Whether rollback cleanup has completed.
        private boolean cleanupComplete;

        /// Creates path-backed commit output.
        private PathCommitOutput(Path path, @Nullable Path replacementTarget) {
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
            @Nullable Path target = replacementTarget;
            if (target != null) {
                replaceAtomically(path, target);
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
