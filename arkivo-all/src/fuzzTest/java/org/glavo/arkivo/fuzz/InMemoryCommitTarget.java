// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.fuzz;

import org.glavo.arkivo.archive.ArkivoCommitOutput;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Set;

/// Captures one path-style archive commit transaction entirely in memory.
@NotNullByDefault
final class InMemoryCommitTarget implements ArkivoCommitTarget {
    /// The descriptive assembly path exposed by the in-memory output.
    private static final Path ASSEMBLY_PATH = Path.of("in-memory-archive");

    /// The single output transaction opened for this target, or null before use.
    private @Nullable Output output;

    /// Creates an unused in-memory commit target.
    InMemoryCommitTarget() {
    }

    /// Opens the target's only output transaction.
    ///
    /// @param sourcePath the ignored source path, which may be null
    /// @return a fresh in-memory output transaction
    /// @throws IllegalStateException if a transaction was already opened
    @Override
    public ArkivoCommitOutput openOutput(@Nullable Path sourcePath) {
        if (output != null) {
            throw new IllegalStateException("In-memory commit target was already opened");
        }
        Output created = new Output();
        output = created;
        return created;
    }

    /// Returns a defensive copy of the committed archive bytes.
    ///
    /// @return the committed archive content
    /// @throws IllegalStateException if the transaction has not committed
    byte @Unmodifiable [] bytes() {
        @Nullable Output current = output;
        if (current == null || !current.committed) {
            throw new IllegalStateException("In-memory commit output was not committed");
        }
        return current.snapshot();
    }

    /// Implements one seekable transactional output.
    @NotNullByDefault
    private static final class Output implements ArkivoCommitOutput {
        /// The initial assembly-buffer capacity.
        private static final int INITIAL_CAPACITY = 64;

        /// The mutable assembly buffer.
        private byte[] content = new byte[INITIAL_CAPACITY];

        /// The logical assembly size.
        private int size;

        /// The currently open assembly channel, or null between channel sessions.
        private @Nullable Channel currentChannel;

        /// Whether publication completed successfully.
        private boolean committed;

        /// Whether rollback or close abandoned this output.
        private boolean abandoned;

        /// Creates an empty output transaction.
        private Output() {
        }

        /// Returns the descriptive in-memory assembly path.
        @Override
        public Path path() {
            return ASSEMBLY_PATH;
        }

        /// Opens a writable seekable view over the assembly buffer.
        ///
        /// This test endpoint accepts the create, truncate, and write options used by complete-rewrite archive
        /// implementations. It rejects read and append modes so accidental changes in the publication protocol fail
        /// visibly.
        @Override
        public SeekableByteChannel openChannel(Set<? extends OpenOption> options) throws IOException {
            if (committed || abandoned) {
                throw new ClosedChannelException();
            }
            if (currentChannel != null && currentChannel.isOpen()) {
                throw new IOException("Previous in-memory commit channel remains open");
            }
            for (OpenOption option : options) {
                if (option != StandardOpenOption.CREATE
                        && option != StandardOpenOption.CREATE_NEW
                        && option != StandardOpenOption.TRUNCATE_EXISTING
                        && option != StandardOpenOption.WRITE) {
                    throw new UnsupportedOperationException("Unsupported in-memory commit option: " + option);
                }
            }
            if (!options.contains(StandardOpenOption.WRITE)) {
                throw new IllegalArgumentException("In-memory commit channel requires WRITE");
            }
            if (options.contains(StandardOpenOption.TRUNCATE_EXISTING)) {
                size = 0;
            }
            Channel channel = new Channel(this);
            currentChannel = channel;
            return channel;
        }

        /// Publishes the assembled bytes exactly once.
        @Override
        public void commit() throws IOException {
            if (committed) {
                return;
            }
            ensureCompletable();
            committed = true;
        }

        /// Abandons uncommitted assembly bytes.
        @Override
        public void rollback() throws IOException {
            if (committed || abandoned) {
                return;
            }
            ensureNoOpenChannel();
            size = 0;
            abandoned = true;
        }

        /// Rolls back an uncommitted output.
        @Override
        public void close() throws IOException {
            rollback();
        }

        /// Returns a defensive copy of the logical assembly content.
        private byte @Unmodifiable [] snapshot() {
            return Arrays.copyOf(content, size);
        }

        /// Ensures capacity for a write ending at the requested exclusive offset.
        private void ensureCapacity(int requiredSize) {
            if (requiredSize <= content.length) {
                return;
            }
            int doubled = content.length <= Integer.MAX_VALUE / 2
                    ? content.length * 2
                    : Integer.MAX_VALUE;
            int grown = Math.max(requiredSize, Math.max(INITIAL_CAPACITY, doubled));
            content = Arrays.copyOf(content, grown);
        }

        /// Requires this output to remain active and have no open assembly channel.
        private void ensureCompletable() throws IOException {
            if (abandoned) {
                throw new ClosedChannelException();
            }
            ensureNoOpenChannel();
        }

        /// Requires every opened assembly channel to have closed.
        private void ensureNoOpenChannel() throws IOException {
            if (currentChannel != null && currentChannel.isOpen()) {
                throw new IOException("In-memory commit channel remains open");
            }
        }

        /// Records successful closure of the current assembly channel.
        private void complete(Channel channel) {
            if (channel != currentChannel) {
                throw new IllegalStateException("In-memory commit channel is no longer current");
            }
        }
    }

    /// Provides one writable seekable view over an output's assembly buffer.
    @NotNullByDefault
    private static final class Channel implements SeekableByteChannel {
        /// The output whose buffer receives writes.
        private final Output owner;

        /// The current logical write position.
        private int position;

        /// Whether this channel remains open.
        private boolean open = true;

        /// Creates a writable channel at assembly offset zero.
        private Channel(Output owner) {
            this.owner = owner;
        }

        /// Rejects reads from the write-only assembly channel.
        @Override
        public int read(ByteBuffer target) throws IOException {
            requireOpen();
            throw new NonReadableChannelException();
        }

        /// Writes all remaining source bytes at the current position.
        @Override
        public int write(ByteBuffer source) throws IOException {
            requireOpen();
            int count = source.remaining();
            if (count == 0) {
                return 0;
            }
            int end;
            try {
                end = Math.addExact(position, count);
            } catch (ArithmeticException exception) {
                throw new IOException("In-memory commit output exceeds supported size", exception);
            }
            owner.ensureCapacity(end);
            if (position > owner.size) {
                Arrays.fill(owner.content, owner.size, position, (byte) 0);
            }
            source.get(owner.content, position, count);
            position = end;
            owner.size = Math.max(owner.size, end);
            return count;
        }

        /// Returns the current logical position.
        @Override
        public long position() throws IOException {
            requireOpen();
            return position;
        }

        /// Changes the current logical position within the integer-sized in-memory address space.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            requireOpen();
            if (newPosition < 0L || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is outside the in-memory address space");
            }
            position = Math.toIntExact(newPosition);
            return this;
        }

        /// Returns the current logical assembly size.
        @Override
        public long size() throws IOException {
            requireOpen();
            return owner.size;
        }

        /// Shrinks the assembly buffer when the requested size is smaller.
        @Override
        public SeekableByteChannel truncate(long newSize) throws IOException {
            requireOpen();
            if (newSize < 0L || newSize > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newSize is outside the in-memory address space");
            }
            int checkedSize = Math.toIntExact(newSize);
            if (checkedSize < owner.size) {
                owner.size = checkedSize;
                position = Math.min(position, checkedSize);
            }
            return this;
        }

        /// Returns whether this assembly channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes and completes this assembly channel exactly once.
        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            owner.complete(this);
        }

        /// Requires this channel to remain open.
        private void requireOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
