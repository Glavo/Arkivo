// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.checksum.Checksums;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.codec.internal.InterruptibleChannelSupport;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Decodes indexed Zstandard frames on demand behind a logical seekable channel.
@NotNullByDefault
final class ZstdSeekableByteChannel implements SeekableByteChannel {
    /// The immutable frame index.
    private final ZstdSeekableIndex index;

    /// The encoded seekable source.
    private final SeekableByteChannel source;

    /// The source ownership tracker.
    private final OwnedChannelCloser sourceCloser;

    /// The physical source offset corresponding to compressed index offset zero.
    private final long sourceOrigin;

    /// The current logical uncompressed position.
    private long position;

    /// The currently cached frame index, or negative when no frame is cached.
    private int cachedFrameIndex = -1;

    /// The decoded bytes of the cached frame.
    private byte[] cachedFrame = new byte[0];

    /// Whether this logical channel remains open.
    private boolean open = true;

    /// Creates and validates a logical view, applying source ownership if setup fails.
    ///
    /// @param index     the immutable index parsed from the same complete encoding
    /// @param source    the encoded source at its logical origin
    /// @param ownership whether this channel owns `source`
    /// @return a logical channel preserving interruptible-channel capability
    /// @throws IOException if the source extent does not match the index or owned-source cleanup fails
    static SeekableByteChannel open(
            ZstdSeekableIndex index,
            SeekableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        OwnedChannelCloser sourceCloser = new OwnedChannelCloser(source, ownership);
        try {
            long origin = source.position();
            long size = source.size();
            if (origin < 0L || origin > size || size - origin != index.compressedSize()) {
                throw new IOException("Zstandard seekable source extent does not match the parsed index");
            }
            ZstdSeekableByteChannel channel = new ZstdSeekableByteChannel(
                    index,
                    source,
                    sourceCloser,
                    origin
            );
            return source instanceof InterruptibleChannel
                    ? new InterruptibleView(source, channel)
                    : channel;
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Creates a validated logical channel.
    private ZstdSeekableByteChannel(
            ZstdSeekableIndex index,
            SeekableByteChannel source,
            OwnedChannelCloser sourceCloser,
            long sourceOrigin
    ) {
        this.index = index;
        this.source = source;
        this.sourceCloser = sourceCloser;
        this.sourceOrigin = sourceOrigin;
    }

    /// Reads logical bytes, decoding and caching only frames crossed by this request.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (position >= index.uncompressedSize()) {
            return -1;
        }

        int initialPosition = target.position();
        while (target.hasRemaining() && position < index.uncompressedSize()) {
            int frameIndex = index.frameContaining(position);
            if (cachedFrameIndex != frameIndex) {
                loadFrame(frameIndex);
            }
            long frameOffset = index.frameUncompressedOffset(frameIndex);
            int offset = Math.toIntExact(position - frameOffset);
            int count = Math.min(target.remaining(), cachedFrame.length - offset);
            target.put(cachedFrame, offset, count);
            position += count;
        }
        return target.position() - initialPosition;
    }

    /// Rejects writes because the decoded logical view is read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the current logical decoded position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return position;
    }

    /// Changes the current logical decoded position without decoding intervening frames.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        ensureOpen();
        position = newPosition;
        return this;
    }

    /// Returns the indexed logical decoded size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return index.uncompressedSize();
    }

    /// Rejects truncation because the decoded logical view is read-only.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns whether this view and its encoded source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Releases cached data and applies ordinary source ownership.
    @Override
    public void close() throws IOException {
        if (open) {
            open = false;
            cachedFrame = new byte[0];
            cachedFrameIndex = -1;
        }
        sourceCloser.close();
    }

    /// Applies terminal cancellation cleanup after the interruptible endpoint has been closed.
    private void abort() throws IOException {
        close();
    }

    /// Decodes and verifies one complete frame into the cache.
    private void loadFrame(int frameIndex) throws IOException {
        long uncompressedSize = index.frameUncompressedSize(frameIndex);
        if (uncompressedSize > Integer.MAX_VALUE) {
            throw new IOException("Indexed Zstandard frame is too large for a Java byte buffer");
        }
        cachedFrame = new byte[0];
        cachedFrameIndex = -1;
        ZstdCodec codec = index.codec();
        long retainedMemorySize = Math.addExact(index.retainedMemorySize(), uncompressedSize);
        CompressionDecoderSupport.requireMemorySize(codec.maximumMemorySize(), retainedMemorySize);
        byte[] decoded = new byte[(int) uncompressedSize];

        source.position(sourceOrigin + index.frameCompressedOffset(frameIndex));
        BoundedReadableChannel bounded = new BoundedReadableChannel(
                source,
                index.frameCompressedSize(frameIndex)
        );
        ZstdCodec frameCodec = codec.withMaximumOutputSize(uncompressedSize);
        if (codec.maximumMemorySize() != CompressionCodec.UNLIMITED_SIZE) {
            frameCodec = frameCodec.withMaximumMemorySize(
                    codec.maximumMemorySize() - retainedMemorySize
            );
        }
        try (DecompressingReadableByteChannel decoder = frameCodec.newReadableByteChannel(
                bounded,
                ResourceOwnership.BORROWED
        )) {
            ByteBuffer output = ByteBuffer.wrap(decoded);
            while (output.hasRemaining()) {
                int read = decoder.read(output);
                if (read < 0) {
                    throw new EOFException("Indexed Zstandard frame ended before its declared output size");
                }
                if (read == 0) {
                    throw new IOException("Indexed Zstandard decoder made no progress");
                }
            }
            ByteBuffer extra = ByteBuffer.allocate(1);
            int trailing = decoder.read(extra);
            if (trailing >= 0) {
                throw new IOException("Indexed Zstandard frame exceeds its declared output size");
            }
            if (decoder.inputBytes() != index.frameCompressedSize(frameIndex)) {
                throw new IOException("Indexed Zstandard frame compressed size does not match the seek table");
            }
        }
        if (index.verifiesTableChecksums()) {
            if ((int) Checksums.XXH64.computeLong(decoded) != index.frameChecksum(frameIndex)) {
                throw new IOException("Indexed Zstandard frame checksum does not match the seek table");
            }
        }
        cachedFrame = decoded;
        cachedFrameIndex = frameIndex;
    }

    /// Requires this logical channel and its encoded source to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Restricts sequential reads to one indexed compressed frame without owning the source.
    @NotNullByDefault
    private static final class BoundedReadableChannel implements java.nio.channels.ReadableByteChannel {
        /// The borrowed encoded source.
        private final SeekableByteChannel source;

        /// The unread compressed byte count.
        private long remaining;

        /// Creates a bounded view at the source's current position.
        private BoundedReadableChannel(SeekableByteChannel source, long size) {
            this.source = source;
            this.remaining = size;
        }

        /// Reads no more than the remaining indexed frame extent.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!target.hasRemaining()) {
                return 0;
            }
            if (remaining == 0L) {
                return -1;
            }
            int count = (int) Math.min(target.remaining(), remaining);
            ByteBuffer boundedTarget = target.duplicate();
            boundedTarget.limit(boundedTarget.position() + count);
            int read = source.read(boundedTarget);
            if (read > 0) {
                target.position(target.position() + read);
                remaining -= read;
            }
            return read;
        }

        /// Returns whether the borrowed source remains open.
        @Override
        public boolean isOpen() {
            return source.isOpen();
        }

        /// Leaves the borrowed encoded source open.
        @Override
        public void close() {
        }
    }

    /// Adds interruptible-channel lifecycle semantics to one logical view.
    @NotNullByDefault
    private static final class InterruptibleView implements SeekableByteChannel, InterruptibleChannel {
        /// The logical decoded channel.
        private final ZstdSeekableByteChannel delegate;

        /// The interrupt and concurrent-close state.
        private final InterruptibleChannelSupport state;

        /// Creates an interruptible wrapper around the logical view and encoded endpoint.
        private InterruptibleView(SeekableByteChannel source, ZstdSeekableByteChannel delegate) {
            this.delegate = delegate;
            this.state = new InterruptibleChannelSupport(source);
        }

        /// Reads logical data as one interruptible operation.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return state.execute(() -> delegate.read(target), delegate::abort);
        }

        /// Delegates the read-only write rejection under interruptible lifecycle control.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return state.execute(() -> delegate.write(source), delegate::abort);
        }

        /// Returns the delegate's logical position.
        @Override
        public long position() throws IOException {
            return state.execute(
                    (InterruptibleChannelSupport.IOOperation<Long>) delegate::position,
                    delegate::abort
            );
        }

        /// Changes the delegate's logical position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            state.execute(() -> {
                delegate.position(newPosition);
            }, delegate::abort);
            return this;
        }

        /// Returns the delegate's logical size.
        @Override
        public long size() throws IOException {
            return state.execute(delegate::size, delegate::abort);
        }

        /// Delegates the read-only truncate rejection under interruptible lifecycle control.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            state.execute(() -> {
                delegate.truncate(size);
            }, delegate::abort);
            return this;
        }

        /// Returns whether another interruptible operation may begin.
        @Override
        public boolean isOpen() {
            return state.isOpen();
        }

        /// Gracefully closes an idle view or aborts an active read.
        @Override
        public void close() throws IOException {
            state.close(delegate::close, delegate::abort);
        }
    }
}
