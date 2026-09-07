// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Reads a fixed archive range through an owned seekable channel.
///
/// Positions are relative to the range. Each non-empty read within the range repositions the source; its initial
/// position is ignored. The declared size does not change when the source grows or shrinks. Reaching physical EOF
/// before the range ends throws [EOFException]. Creating a slice does not read or query the source.
///
/// The caller must not otherwise use the source after transferring ownership. Reads and position changes must be
/// externally serialized. Closing the slice may run concurrently with a read; source closure governs cancellation
/// of any blocked source operation.
@NotNullByDefault
public class ArchiveSliceChannel implements SeekableByteChannel {
    /// The channel owned by this slice.
    private final SeekableByteChannel source;

    /// The absolute start of the archive range.
    private final long offset;

    /// The fixed range length in bytes.
    private final long size;

    /// The current range-relative position.
    private long position;

    /// Whether close has not yet been requested.
    private volatile boolean open = true;

    /// Whether source cleanup completed successfully.
    private boolean sourceClosed;

    /// Creates an owning read-only view over an archive range.
    ///
    /// Ownership transfers only on normal return. If the source implements [InterruptibleChannel], the returned
    /// channel also implements that interface. A read whose absolute source position would overflow a `long` throws
    /// [IOException] without reading or changing either channel position.
    ///
    /// @param source the channel to own
    /// @param offset the non-negative absolute range offset, in bytes
    /// @param size the non-negative range length, in bytes
    /// @return a new channel positioned at zero
    /// @throws IllegalArgumentException if offset or size is negative
    public static SeekableByteChannel open(SeekableByteChannel source, long offset, long size) {
        return source instanceof InterruptibleChannel
                ? new InterruptibleSlice(source, offset, size)
                : new ArchiveSliceChannel(source, offset, size);
    }

    /// Records a range without accessing its source.
    private ArchiveSliceChannel(SeekableByteChannel source, long offset, long size) {
        this.source = Objects.requireNonNull(source, "source");
        if (offset < 0L || size < 0L) {
            throw new IllegalArgumentException("offset and size must not be negative");
        }
        this.offset = offset;
        this.size = size;
    }

    /// Reads no farther than the range end, preserving progress even when the source throws.
    ///
    /// The destination limit and mark are unchanged. Bytes delivered before a failure advance both the destination
    /// and slice positions, so another read resumes after those bytes if the source remains usable. An empty writable
    /// destination returns zero, including at EOF; otherwise a position at or beyond the range end returns `-1`.
    /// Source reads that make no progress return zero.
    ///
    /// @throws ReadOnlyBufferException if the destination is read-only
    /// @throws EOFException if the source ends before the declared range end
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (destination.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (!destination.hasRemaining()) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }
        long absolutePosition;
        try {
            absolutePosition = Math.addExact(offset, position);
        } catch (ArithmeticException exception) {
            throw new IOException("Archive slice offset is too large", exception);
        }
        source.position(absolutePosition);
        ByteBuffer target = destination.slice();
        target.limit((int) Math.min(destination.remaining(), size - position));
        try {
            int count = source.read(target);
            if (count < 0) {
                throw new EOFException("Archive source ended inside a declared range");
            }
            return count;
        } finally {
            // The temporary view records progress even when the source throws after delivering bytes.
            int transferred = target.position();
            destination.position(destination.position() + transferred);
            position += transferred;
        }
    }

    /// Rejects writes without consuming the source buffer.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the range-relative position.
    @Override
    public long position() throws ClosedChannelException {
        ensureOpen();
        return position;
    }

    /// Sets the range-relative position, which may exceed the range length.
    @Override
    public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the declared range length, without querying the source size.
    @Override
    public long size() throws ClosedChannelException {
        ensureOpen();
        return size;
    }

    /// Validates the requested size and rejects truncation.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether neither slice closure nor source closure has occurred.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes this slice and its source.
    ///
    /// This slice rejects further operations even if source cleanup fails. A later close retries failed cleanup;
    /// after successful cleanup, further calls have no effect.
    @Override
    public synchronized void close() throws IOException {
        open = false;
        if (!sourceClosed) {
            source.close();
            sourceClosed = true;
        }
    }

    /// Rejects operations after this slice or its source has closed.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Preserves the interruptible source capability without adding a second cancellation layer.
    @NotNullByDefault
    private static final class InterruptibleSlice extends ArchiveSliceChannel implements InterruptibleChannel {
        /// Creates a slice over an interruptible source.
        private InterruptibleSlice(SeekableByteChannel source, long offset, long size) {
            super(source, offset, size);
        }
    }
}
