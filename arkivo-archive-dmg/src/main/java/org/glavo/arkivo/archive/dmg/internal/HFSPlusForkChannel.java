// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Reads one HFS Plus fork through its allocation extents.
@NotNullByDefault
class HFSPlusForkChannel implements SeekableByteChannel {
    /// The partition channel owned by this fork channel.
    private final SeekableByteChannel partition;

    /// The immutable fork layout.
    private final HFSPlusFork fork;

    /// The HFS Plus allocation-block size.
    private final int blockSize;

    /// The current logical fork position.
    private long position;

    /// Creates a fork channel over one owned partition channel while preserving interruptibility.
    ///
    /// @param partition the partition channel whose ownership is transferred
    /// @param fork the validated complete fork layout
    /// @param blockSize the positive allocation-block size
    /// @return a new owning fork channel
    static SeekableByteChannel open(
            SeekableByteChannel partition,
            HFSPlusFork fork,
            int blockSize
    ) {
        return partition instanceof InterruptibleChannel
                ? new InterruptibleForkChannel(partition, fork, blockSize)
                : new HFSPlusForkChannel(partition, fork, blockSize);
    }

    /// Creates a fork channel over one owned partition channel.
    private HFSPlusForkChannel(SeekableByteChannel partition, HFSPlusFork fork, int blockSize) {
        this.partition = Objects.requireNonNull(partition, "partition");
        this.fork = Objects.requireNonNull(fork, "fork");
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        this.blockSize = blockSize;
    }

    /// Reads bytes from the current logical fork position.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (target.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (!target.hasRemaining()) {
            return 0;
        }
        if (position >= fork.logicalSize()) {
            return -1;
        }
        int initialRemaining = target.remaining();
        while (target.hasRemaining() && position < fork.logicalSize()) {
            ExtentLocation location = locate(position);
            int count = (int) Math.min(
                    Math.min(location.remaining(), fork.logicalSize() - position),
                    target.remaining()
            );
            partition.position(location.physicalOffset());
            ByteBuffer slice = target.slice();
            slice.limit(count);
            int read = partition.read(slice);
            if (read < 0) {
                throw new IOException("Unexpected end of HFS Plus allocation extent");
            }
            if (read == 0) {
                throw new IOException("HFS Plus extent read made no progress");
            }
            target.position(target.position() + read);
            position += read;
        }
        return initialRemaining - target.remaining();
    }

    /// Rejects writes because HFS Plus support is read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the current logical fork position.
    @Override
    public long position() throws ClosedChannelException {
        ensureOpen();
        return position;
    }

    /// Sets the current logical fork position.
    @Override
    public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the logical fork size.
    @Override
    public long size() throws ClosedChannelException {
        ensureOpen();
        return fork.logicalSize();
    }

    /// Validates the requested size and rejects truncation because HFS Plus support is read-only.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether the partition channel remains open.
    @Override
    public boolean isOpen() {
        return partition.isOpen();
    }

    /// Closes the owned partition channel.
    @Override
    public void close() throws IOException {
        partition.close();
    }

    /// Locates a logical fork offset in the ordered extent sequence.
    private ExtentLocation locate(long logicalOffset) throws IOException {
        long extentLogicalOffset = 0L;
        for (HFSPlusExtent extent : fork.extents()) {
            long extentLength = extent.blockCount() * (long) blockSize;
            if (logicalOffset < extentLogicalOffset + extentLength) {
                long offsetInExtent = logicalOffset - extentLogicalOffset;
                return new ExtentLocation(
                        extent.startBlock() * (long) blockSize + offsetInExtent,
                        extentLength - offsetInExtent
                );
            }
            extentLogicalOffset += extentLength;
        }
        throw new IOException("HFS Plus fork extents do not cover the declared logical size");
    }

    /// Rejects operations after close.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Stores a mapped physical location and its contiguous remaining length.
    ///
    /// @param physicalOffset the partition-relative physical byte offset
    /// @param remaining the contiguous byte count in this extent
    private record ExtentLocation(long physicalOffset, long remaining) {
    }

    /// Marks a fork view as interruptible when its partition channel has that capability.
    @NotNullByDefault
    private static final class InterruptibleForkChannel
            extends HFSPlusForkChannel implements InterruptibleChannel {
        /// Creates an interruptible fork view.
        private InterruptibleForkChannel(
                SeekableByteChannel partition,
                HFSPlusFork fork,
                int blockSize
        ) {
            super(partition, fork, blockSize);
        }
    }
}
