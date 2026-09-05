// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;

/// Records transactional split-volume output and exposes publication and cleanup state to tests.
@NotNullByDefault
final class RecordingVolumeTarget implements ArkivoVolumeTarget {
    /// The volume index whose open should fail, or a negative value when opens succeed.
    private final long failVolumeIndex;

    /// Whether commit should fail.
    private final boolean failCommit;

    /// Whether opened volume channels should make no write progress.
    private final boolean zeroProgress;

    /// The number of output transactions opened by this target.
    private int openOutputCount;

    /// The latest opened output transaction, or `null` before first use.
    private @Nullable Output output;

    /// Creates an in-memory target with the requested failures.
    ///
    /// @param failVolumeIndex the volume index whose open should fail, or a negative value to allow every open
    /// @param failCommit whether commit should fail
    RecordingVolumeTarget(long failVolumeIndex, boolean failCommit) {
        this(failVolumeIndex, failCommit, false);
    }

    /// Creates an in-memory target with the requested failures and progress behavior.
    ///
    /// @param failVolumeIndex the volume index whose open should fail, or a negative value to allow every open
    /// @param failCommit whether commit should fail
    /// @param zeroProgress whether opened channels should reject progress by repeatedly returning zero
    RecordingVolumeTarget(long failVolumeIndex, boolean failCommit, boolean zeroProgress) {
        this.failVolumeIndex = failVolumeIndex;
        this.failCommit = failCommit;
        this.zeroProgress = zeroProgress;
    }

    /// Opens one new in-memory output transaction.
    @Override
    public ArkivoVolumeOutput openOutput() {
        openOutputCount++;
        output = new Output(failVolumeIndex, failCommit, zeroProgress);
        return output;
    }

    /// Returns the number of output transactions opened by this target.
    int openOutputCount() {
        return openOutputCount;
    }

    /// Returns committed volume snapshots, or an empty array when publication failed.
    byte[][] committedVolumes() {
        @Nullable Output currentOutput = output;
        return currentOutput != null ? currentOutput.committedVolumes() : new byte[0][];
    }

    /// Returns the number of effective rollback operations.
    int rollbackCount() {
        @Nullable Output currentOutput = output;
        return currentOutput != null ? currentOutput.rollbackCount() : 0;
    }

    /// Returns whether every volume channel opened by the target has been closed.
    boolean allOpenedChannelsClosed() {
        @Nullable Output currentOutput = output;
        return currentOutput == null || currentOutput.allOpenedChannelsClosed();
    }

    /// Records one in-memory multi-volume output transaction.
    @NotNullByDefault
    private static final class Output implements ArkivoVolumeOutput {
        /// The volume index whose open should fail, or a negative value when opens succeed.
        private final long failVolumeIndex;

        /// Whether commit should fail.
        private final boolean failCommit;

        /// Whether opened volume channels should make no write progress.
        private final boolean zeroProgress;

        /// Bytes written to each opened volume.
        private final ArrayList<ByteArrayOutputStream> volumeBytes = new ArrayList<>();

        /// Channels opened for each volume.
        private final ArrayList<WritableByteChannel> channels = new ArrayList<>();

        /// The number of effective rollback operations.
        private int rollbackCount;

        /// Whether all written volumes were committed.
        private boolean committed;

        /// Whether this transaction has committed or rolled back.
        private boolean finished;

        /// Creates one recording output transaction.
        ///
        /// @param failVolumeIndex the volume index whose open should fail, or a negative value to allow every open
        /// @param failCommit whether commit should fail
        /// @param zeroProgress whether opened channels should repeatedly return zero
        private Output(long failVolumeIndex, boolean failCommit, boolean zeroProgress) {
            this.failVolumeIndex = failVolumeIndex;
            this.failCommit = failCommit;
            this.zeroProgress = zeroProgress;
        }

        /// Opens the next in-memory volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (index != channels.size()) {
                throw new IllegalArgumentException("volume indexes must be contiguous");
            }
            if (!channels.isEmpty() && channels.get(channels.size() - 1).isOpen()) {
                throw new IOException("previous volume is still open");
            }
            if (index == failVolumeIndex) {
                throw new IOException("volume open failed");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WritableByteChannel channel = zeroProgress
                    ? new ZeroProgressChannel()
                    : Channels.newChannel(bytes);
            volumeBytes.add(bytes);
            channels.add(channel);
            return channel;
        }

        /// Commits all opened in-memory volumes.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (finalVolumeIndex != channels.size() - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex does not identify the last volume");
            }
            if (!allOpenedChannelsClosed()) {
                throw new IOException("volume channel is still open");
            }
            if (failCommit) {
                throw new IOException("volume commit failed");
            }
            committed = true;
            finished = true;
        }

        /// Rolls back this transaction once.
        @Override
        public void rollback() {
            if (finished) {
                return;
            }
            rollbackCount++;
            finished = true;
        }

        /// Closes this transaction and rolls it back when uncommitted.
        @Override
        public void close() {
            rollback();
        }

        /// Returns committed volume snapshots, or an empty array when publication failed.
        private byte[][] committedVolumes() {
            if (!committed) {
                return new byte[0][];
            }
            byte[][] result = new byte[volumeBytes.size()][];
            for (int index = 0; index < volumeBytes.size(); index++) {
                result[index] = volumeBytes.get(index).toByteArray();
            }
            return result;
        }

        /// Returns the number of effective rollback operations.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns whether every opened volume channel is closed.
        private boolean allOpenedChannelsClosed() {
            for (WritableByteChannel channel : channels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Writable channel that remains open but never accepts bytes.
    @NotNullByDefault
    private static final class ZeroProgressChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Reports zero bytes written without consuming the source buffer.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }
}
