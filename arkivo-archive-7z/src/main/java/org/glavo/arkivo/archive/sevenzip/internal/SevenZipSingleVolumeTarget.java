// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Writes one archive directly to an owned channel through the volume-output interface.
///
/// Bytes reach the channel before commit. Opening volume zero transfers channel cleanup to the caller;
/// rollback closes the channel only if no volume was opened and cannot undo earlier writes.
@NotNullByDefault
final class SevenZipSingleVolumeTarget implements ArkivoVolumeTarget, AutoCloseable {
    /// Owns the destination even before archive assembly reaches publication.
    private final SingleVolumeOutput output;

    /// Whether an output transaction has already been opened.
    private boolean opened;

    /// Whether target cleanup has started, including an incomplete close attempt.
    private boolean closing;

    /// Creates a single-volume target over the given channel.
    SevenZipSingleVolumeTarget(WritableByteChannel channel) {
        this.output = new SingleVolumeOutput(Objects.requireNonNull(channel, "channel"));
    }

    /// Opens the only output transaction supported by this target.
    @Override
    public ArkivoVolumeOutput openOutput() throws IOException {
        if (opened || closing) {
            throw new IOException("7z output channel transaction is no longer available");
        }
        opened = true;
        return output;
    }

    /// Releases an unpublished destination or retries unfinished transaction cleanup.
    @Override
    public void close() throws IOException {
        closing = true;
        output.close();
    }

    /// Publishes one archive to the supplied writable channel.
    @NotNullByDefault
    private static final class SingleVolumeOutput implements ArkivoVolumeOutput {
        /// The destination channel.
        private final WritableByteChannel channel;

        /// Whether volume zero has been opened.
        private boolean volumeOpened;

        /// Whether this transaction has finished.
        private boolean finished;

        /// Creates one single-volume output transaction.
        private SingleVolumeOutput(WritableByteChannel channel) {
            this.channel = Objects.requireNonNull(channel, "channel");
        }

        /// Opens volume zero exactly once.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new IOException("7z output channel transaction is finished");
            }
            long expectedIndex = volumeOpened ? 1L : 0L;
            if (index != expectedIndex) {
                throw new IllegalArgumentException("Volume indexes must be opened once in ascending order");
            }
            if (volumeOpened) {
                throw new IOException("7z output channel supports exactly one volume");
            }
            volumeOpened = true;
            return channel;
        }

        /// Commits the single closed output volume.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            if (finished) {
                throw new IOException("7z output channel transaction is finished");
            }
            if (!volumeOpened || finalVolumeIndex != 0L) {
                throw new IllegalArgumentException("finalVolumeIndex must identify volume zero");
            }
            if (channel.isOpen()) {
                throw new IOException("7z output channel volume is still open");
            }
            finished = true;
        }

        /// Abandons uncommitted output, closing the destination only if no volume was opened.
        ///
        /// The caller must finish closing an opened volume first. Before volume creation, a failed destination
        /// close is retried even if the channel already reports itself closed.
        @Override
        public void rollback() throws IOException {
            if (finished) {
                return;
            }
            if (!volumeOpened) {
                channel.close();
            } else if (channel.isOpen()) {
                throw new IOException("7z output channel volume is still open");
            }
            finished = true;
        }

        /// Rolls back this output when it has not committed.
        @Override
        public void close() throws IOException {
            rollback();
        }
    }
}
