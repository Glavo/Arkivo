// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

/// Captures one committed multi-volume archive entirely in memory for deterministic fuzz seeds.
@NotNullByDefault
final class InMemoryVolumeTarget implements ArkivoVolumeTarget {
    /// The single transaction opened for this target, or null before use.
    private @Nullable Output output;

    /// Creates an unused in-memory target.
    InMemoryVolumeTarget() {
    }

    /// Opens the target's only output transaction.
    ///
    /// @return a fresh in-memory output
    /// @throws IllegalStateException if a transaction was already opened
    @Override
    public ArkivoVolumeOutput openOutput() {
        if (output != null) {
            throw new IllegalStateException("In-memory volume target was already opened");
        }
        Output created = new Output();
        output = created;
        return created;
    }

    /// Returns defensive copies of every committed physical volume.
    ///
    /// @return the committed volumes in logical order
    /// @throws IllegalStateException if the transaction has not committed
    @Unmodifiable List<byte @Unmodifiable []> volumes() {
        @Nullable Output current = output;
        if (current == null || !current.committed) {
            throw new IllegalStateException("In-memory volume output was not committed");
        }
        ArrayList<byte @Unmodifiable []> copies = new ArrayList<>(current.volumes.size());
        for (byte[] volume : current.volumes) {
            copies.add(volume.clone());
        }
        return List.copyOf(copies);
    }

    /// Implements one sequential transactional output.
    @NotNullByDefault
    private static final class Output implements ArkivoVolumeOutput {
        /// Completed physical volumes in logical order.
        private final List<byte[]> volumes = new ArrayList<>();

        /// The currently open physical volume, or null between volumes.
        private @Nullable VolumeChannel current;

        /// Whether publication completed successfully.
        private boolean committed;

        /// Whether rollback or close abandoned this output.
        private boolean abandoned;

        /// Creates an empty output transaction.
        private Output() {
        }

        /// Opens the next sequential physical volume.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (committed || abandoned) {
                throw new ClosedChannelException();
            }
            if (current != null && current.isOpen()) {
                throw new IOException("Previous in-memory volume remains open");
            }
            if (index != volumes.size()) {
                throw new IllegalArgumentException("Unexpected in-memory volume index: " + index);
            }
            VolumeChannel channel = new VolumeChannel(this);
            current = channel;
            return channel;
        }

        /// Commits the completed in-memory volumes.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            if (committed) {
                return;
            }
            if (abandoned) {
                throw new ClosedChannelException();
            }
            if (current != null && current.isOpen()) {
                throw new IOException("Final in-memory volume remains open");
            }
            if (volumes.isEmpty() || finalVolumeIndex != volumes.size() - 1L) {
                throw new IllegalArgumentException("Unexpected final in-memory volume index: " + finalVolumeIndex);
            }
            committed = true;
        }

        /// Abandons every captured volume.
        @Override
        public void rollback() {
            if (committed || abandoned) {
                return;
            }
            volumes.clear();
            abandoned = true;
        }

        /// Rolls back an uncommitted output.
        @Override
        public void close() {
            rollback();
        }

        /// Records one successfully closed physical volume.
        private void complete(VolumeChannel channel, byte @Unmodifiable [] content) {
            if (channel != current) {
                throw new IllegalStateException("In-memory volume is no longer current");
            }
            volumes.add(content);
        }
    }

    /// Buffers one physical volume until successful close.
    @NotNullByDefault
    private static final class VolumeChannel implements WritableByteChannel {
        /// Transaction that receives this channel's completed bytes.
        private final Output owner;

        /// Bytes written to this physical volume.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Whether this channel still accepts writes.
        private boolean open = true;

        /// Creates one open channel for the current volume.
        private VolumeChannel(Output owner) {
            this.owner = owner;
        }

        /// Appends all remaining source bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
            int count = source.remaining();
            while (source.hasRemaining()) {
                bytes.write(source.get());
            }
            return count;
        }

        /// Returns whether this physical volume remains writable.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes and records this physical volume exactly once.
        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            owner.complete(this, bytes.toByteArray());
        }
    }
}
