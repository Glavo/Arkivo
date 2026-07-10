// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.ArkivoVolumeOutput;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Adapts one owned writable channel to a single-volume transactional target.
@NotNullByDefault
final class SevenZipSingleVolumeTarget implements ArkivoVolumeTarget {
    /// The channel that receives the completed archive.
    private final WritableByteChannel channel;

    /// Whether an output transaction has already been opened.
    private boolean opened;

    /// Creates a single-volume target over the given channel.
    SevenZipSingleVolumeTarget(WritableByteChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel");
    }

    /// Opens the only output transaction supported by this target.
    @Override
    public ArkivoVolumeOutput openOutput() throws IOException {
        if (opened) {
            throw new IOException("7z output channel transaction is already open");
        }
        opened = true;
        return new SingleVolumeOutput(channel);
    }

    /// Publishes one archive to the supplied writable channel.
    @NotNullByDefault
    private static final class SingleVolumeOutput implements ArkivoVolumeOutput {
        /// The destination channel.
        private final WritableByteChannel channel;

        /// Whether volume zero has been opened.
        private boolean volumeOpened;

        /// Whether the archive has been committed.
        private boolean committed;

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
            if (index != 0L) {
                throw new IOException("7z output channel supports exactly one volume");
            }
            if (volumeOpened) {
                throw new IOException("7z output channel volume is already open");
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
            committed = true;
            finished = true;
        }

        /// Closes the destination channel and abandons uncommitted output.
        @Override
        public void rollback() throws IOException {
            if (finished) {
                return;
            }
            channel.close();
            finished = true;
        }

        /// Rolls back this output when it has not committed.
        @Override
        public void close() throws IOException {
            if (!committed) {
                rollback();
            }
        }
    }
}
