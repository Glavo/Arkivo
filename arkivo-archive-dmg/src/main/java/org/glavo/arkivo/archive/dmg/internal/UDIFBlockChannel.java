// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.glavo.arkivo.codec.deflate.ZlibCodec;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Objects;

/// Exposes a UDIF block layout as a read-only decoded seekable channel.
///
/// The channel owns its encoded source channel. At most one decoded compressed run is cached, and the cache is discarded
/// when another compressed run is accessed. Instances are not safe for concurrent use.
@NotNullByDefault
class UDIFBlockChannel implements SeekableByteChannel {
    /// The encoded source owned by this channel.
    private final SeekableByteChannel source;

    /// The immutable decoded block layout.
    private final UDIFLayout layout;

    /// The operation-wide decoding limits.
    private final ArchiveReadLimits limits;

    /// The current decoded position.
    private long position;

    /// Whether this channel remains open.
    private boolean open = true;

    /// The run whose decoded bytes are cached, or `null`.
    private @Nullable UDIFRun cachedRun;

    /// The decoded bytes for [#cachedRun], or `null`.
    private byte @Nullable [] cachedBytes;

    /// Creates a decoded channel over one independently opened encoded source while preserving interruptibility.
    ///
    /// @param source the encoded source whose ownership is transferred
    /// @param layout the immutable decoded layout
    /// @param limits the operation-wide decoding limits
    /// @return a new owning decoded channel
    static SeekableByteChannel open(
            SeekableByteChannel source,
            UDIFLayout layout,
            ArchiveReadLimits limits
    ) {
        return source instanceof InterruptibleChannel
                ? new InterruptibleUDIFBlockChannel(source, layout, limits)
                : new UDIFBlockChannel(source, layout, limits);
    }

    /// Creates a decoded channel over one independently opened encoded source.
    private UDIFBlockChannel(SeekableByteChannel source, UDIFLayout layout, ArchiveReadLimits limits) {
        this.source = Objects.requireNonNull(source, "source");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /// Reads decoded bytes at the current logical position.
    ///
    /// Bytes delivered before a failure, including a partial raw-run read, advance both the target and logical
    /// positions. The target limit is unchanged. A later read resumes after those bytes if the source remains usable.
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
        if (position >= layout.size()) {
            return -1;
        }

        int initialRemaining = target.remaining();
        while (target.hasRemaining() && position < layout.size()) {
            int runIndex = runIndex(position);
            if (runIndex < 0) {
                long boundary = nextRunOffset(position);
                putZeroes(target, Math.min(boundary - position, target.remaining()));
                continue;
            }

            UDIFRun run = layout.runs().get(runIndex);
            long offsetInRun = position - run.logicalOffset();
            int count = (int) Math.min(Math.min(run.logicalLength() - offsetInRun, target.remaining()), Integer.MAX_VALUE);
            if (run.isSparse()) {
                putZeroes(target, count);
            } else if (run.type() == UDIFConstants.BLOCK_RAW) {
                source.position(run.physicalOffset() + offsetInRun);
                ByteBuffer slice = target.slice();
                slice.limit(count);
                int read;
                try {
                    read = source.read(slice);
                } finally {
                    // Include raw bytes delivered before the physical source reports a failure.
                    int transferred = slice.position();
                    target.position(target.position() + transferred);
                    position += transferred;
                }
                if (read < 0) {
                    throw new IOException("Unexpected end of raw UDIF run");
                }
                if (read == 0) {
                    throw new IOException("Raw UDIF run read made no progress");
                }
            } else {
                byte[] decoded = decodedRun(run);
                target.put(decoded, Math.toIntExact(offsetInRun), count);
                position += count;
            }
        }
        return initialRemaining - target.remaining();
    }

    /// Rejects writes because decoded DMG images are read-only.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        throw new NonWritableChannelException();
    }

    /// Returns the current decoded position.
    @Override
    public long position() throws ClosedChannelException {
        ensureOpen();
        return position;
    }

    /// Sets the current decoded position.
    @Override
    public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
        ensureOpen();
        if (newPosition < 0L) {
            throw new IllegalArgumentException("newPosition must not be negative");
        }
        position = newPosition;
        return this;
    }

    /// Returns the complete decoded disk size.
    @Override
    public long size() throws ClosedChannelException {
        ensureOpen();
        return layout.size();
    }

    /// Validates the requested size and rejects truncation because decoded DMG images are read-only.
    @Override
    public SeekableByteChannel truncate(long size) throws IOException {
        ensureOpen();
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        throw new NonWritableChannelException();
    }

    /// Returns whether the channel and its encoded source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes the encoded source and discards cached decoded bytes.
    ///
    /// Reads are rejected as soon as closing starts. If closing the encoded source fails while leaving it open, a
    /// later call retries that cleanup.
    @Override
    public void close() throws IOException {
        if (!open && !source.isOpen()) {
            return;
        }
        open = false;
        cachedRun = null;
        cachedBytes = null;
        source.close();
    }

    /// Returns the index of the run containing one logical position, or `-1` for a gap.
    private int runIndex(long logicalPosition) {
        List<UDIFRun> runs = layout.runs();
        int low = 0;
        int high = runs.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            UDIFRun run = runs.get(middle);
            if (logicalPosition < run.logicalOffset()) {
                high = middle - 1;
            } else if (logicalPosition >= run.logicalEnd()) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        return -1;
    }

    /// Returns the next run start after a gap, or the decoded disk end.
    private long nextRunOffset(long logicalPosition) {
        for (UDIFRun run : layout.runs()) {
            if (run.logicalOffset() > logicalPosition) {
                return run.logicalOffset();
            }
        }
        return layout.size();
    }

    /// Writes zero-filled bytes and advances the decoded position.
    private void putZeroes(ByteBuffer target, long count) {
        int remaining = Math.toIntExact(count);
        while (remaining >= Long.BYTES) {
            target.putLong(0L);
            remaining -= Long.BYTES;
        }
        while (remaining-- > 0) {
            target.put((byte) 0);
        }
        position += count;
    }

    /// Returns a decoded compressed run, populating the single-run cache when needed.
    private byte[] decodedRun(UDIFRun run) throws IOException {
        if (run.equals(cachedRun)) {
            return Objects.requireNonNull(cachedBytes, "cachedBytes");
        }
        if (run.type() == UDIFConstants.BLOCK_LZFSE) {
            throw new IOException("LZFSE-compressed UDIF runs are not supported");
        }
        if (run.logicalLength() > Integer.MAX_VALUE || run.physicalLength() > Integer.MAX_VALUE) {
            throw new IOException("UDIF compressed run exceeds the supported in-memory block size");
        }
        requireMemory(run.logicalLength(), run.physicalLength());
        byte[] encodedBytes = ChannelIO.readBytes(
                source,
                run.physicalOffset(),
                Math.toIntExact(run.physicalLength())
        );
        byte[] decodedBytes = new byte[Math.toIntExact(run.logicalLength())];
        if (run.type() == UDIFConstants.BLOCK_ADC) {
            decodeADC(encodedBytes, decodedBytes);
        } else {
            CompressionCodec<?> codec = codec(run.type(), run.logicalLength());
            ByteBuffer encoded = ByteBuffer.wrap(encodedBytes);
            ByteBuffer decoded = ByteBuffer.wrap(decodedBytes);
            codec.decompress(encoded, decoded);
            if (encoded.hasRemaining() || decoded.hasRemaining()) {
                throw new IOException("UDIF compressed run did not decode to its declared size");
            }
        }
        cachedRun = run;
        cachedBytes = decodedBytes;
        return decodedBytes;
    }

    /// Creates a compression codec configured for one declared run size and operation limits.
    private CompressionCodec<?> codec(int type, long outputSize) throws IOException {
        long windowSize = limits.maximumCompressionWindowSize();
        long memorySize = limits.maximumDecoderMemorySize();
        if (type == UDIFConstants.BLOCK_ZLIB) {
            return ZlibCodec.DEFAULT
                    .withMaximumOutputSize(outputSize)
                    .withMaximumWindowSize(windowSize)
                    .withMaximumMemorySize(memorySize);
        }
        if (type == UDIFConstants.BLOCK_BZIP2) {
            return BZip2Codec.DEFAULT
                    .withMaximumOutputSize(outputSize)
                    .withMaximumWindowSize(windowSize)
                    .withMaximumMemorySize(memorySize);
        }
        if (type == UDIFConstants.BLOCK_LZMA) {
            return XZCodec.DEFAULT
                    .withMaximumOutputSize(outputSize)
                    .withMaximumWindowSize(windowSize)
                    .withMaximumMemorySize(memorySize);
        }
        throw new IOException("Unsupported compressed UDIF run type 0x" + Integer.toHexString(type));
    }

    /// Enforces the caller's decoder-memory limit before allocating a compressed-run cache.
    private void requireMemory(long decodedSize, long encodedSize) throws IOException {
        long maximum = limits.maximumDecoderMemorySize();
        long required = decodedSize > Long.MAX_VALUE - encodedSize ? Long.MAX_VALUE : decodedSize + encodedSize;
        if (maximum != ArchiveReadLimits.UNLIMITED_SIZE && required > maximum) {
            throw new IOException(
                    "UDIF compressed run requires " + required + " bytes of buffering, exceeding the limit of " + maximum
            );
        }
    }

    /// Decodes one Apple Data Compression block into an exact caller-provided target.
    private static void decodeADC(byte[] source, byte[] target) throws IOException {
        int sourcePosition = 0;
        int targetPosition = 0;
        while (sourcePosition < source.length && targetPosition < target.length) {
            int control = Byte.toUnsignedInt(source[sourcePosition++]);
            if ((control & 0x80) != 0) {
                int length = (control & 0x7f) + 1;
                if (sourcePosition > source.length - length || targetPosition > target.length - length) {
                    throw new IOException("Invalid ADC literal range");
                }
                System.arraycopy(source, sourcePosition, target, targetPosition, length);
                sourcePosition += length;
                targetPosition += length;
                continue;
            }

            final int length;
            final int distance;
            if ((control & 0x40) != 0) {
                if (sourcePosition > source.length - 2) {
                    throw new IOException("Truncated ADC long back-reference");
                }
                length = (control & 0x3f) + 4;
                distance = (Byte.toUnsignedInt(source[sourcePosition]) << 8)
                        | Byte.toUnsignedInt(source[sourcePosition + 1]);
                sourcePosition += 2;
            } else {
                if (sourcePosition >= source.length) {
                    throw new IOException("Truncated ADC short back-reference");
                }
                length = ((control & 0x3f) >>> 2) + 3;
                distance = ((control & 0x03) << 8) | Byte.toUnsignedInt(source[sourcePosition++]);
            }
            int back = distance + 1;
            if (back > targetPosition || targetPosition > target.length - length) {
                throw new IOException("Invalid ADC back-reference");
            }
            for (int index = 0; index < length; index++) {
                target[targetPosition] = target[targetPosition - back];
                targetPosition++;
            }
        }
        if (sourcePosition != source.length || targetPosition != target.length) {
            throw new IOException("ADC run did not decode to its declared size");
        }
    }

    /// Rejects operations after close.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }

    /// Marks a decoded view as interruptible when its encoded source has that capability.
    @NotNullByDefault
    private static final class InterruptibleUDIFBlockChannel
            extends UDIFBlockChannel implements InterruptibleChannel {
        /// Creates an interruptible decoded view.
        private InterruptibleUDIFBlockChannel(
                SeekableByteChannel source,
                UDIFLayout layout,
                ArchiveReadLimits limits
        ) {
            super(source, layout, limits);
        }
    }
}
