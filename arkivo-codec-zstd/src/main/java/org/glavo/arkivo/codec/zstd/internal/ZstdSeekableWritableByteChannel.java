// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.xxhash.XXHash64;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.SeekableEncodingOptions;
import org.glavo.arkivo.codec.internal.InterruptibleChannelSupport;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Writes independent Zstandard frames followed by the standard seek table.
@NotNullByDefault
public final class ZstdSeekableWritableByteChannel implements CompressingWritableByteChannel.FlushableFramed {
    /// The initial number of frame records retained without resizing.
    private static final int INITIAL_FRAME_CAPACITY = 16;

    /// The staging size used while serializing the seek table.
    private static final int TABLE_BUFFER_SIZE = 64 * 1024;

    /// The staging size used to hash non-array-backed input buffers.
    private static final int HASH_BUFFER_SIZE = 8192;

    /// The compressed target.
    private final WritableByteChannel target;

    /// The target ownership tracker.
    private final OwnedChannelCloser targetCloser;

    /// The framed encoder writing the data frames.
    private final CompressingWritableByteChannel.FlushableFramed encoder;

    /// The exact complete logical size, or the unknown-size sentinel.
    private final long expectedSourceSize;

    /// The maximum uncompressed bytes accepted into one frame.
    private final int maximumFrameSize;

    /// Whether the seek table carries per-frame XXH64 checksums.
    private final boolean tableChecksums;

    /// Scratch storage for hashing direct or otherwise non-array-backed input.
    private final byte[] hashBuffer = new byte[HASH_BUFFER_SIZE];

    /// Compressed sizes of completed frames.
    private long[] frameCompressedSizes = new long[INITIAL_FRAME_CAPACITY];

    /// Uncompressed sizes of completed frames.
    private int[] frameUncompressedSizes = new int[INITIAL_FRAME_CAPACITY];

    /// Checksums of completed frames, or an empty array when checksums are disabled.
    private int[] frameChecksums;

    /// Hash state for the active frame, or `null` when table checksums are disabled.
    private @Nullable ChecksumAccumulator.Width64 activeChecksum;

    /// The number of completed frame records.
    private int frameCount;

    /// Uncompressed bytes accepted into the active frame.
    private int activeFrameSize;

    /// Compressed output count at the beginning of the active frame.
    private long activeFrameCompressedOffset;

    /// The cumulative accepted uncompressed byte count.
    private long inputBytes;

    /// The cumulative emitted compressed byte count, including the seek table.
    private long outputBytes;

    /// Whether this channel accepts more source bytes.
    private boolean open = true;

    /// Whether a data frame is active and must be recorded when finalized.
    private boolean frameActive = true;

    /// Whether terminal processing has already been attempted.
    private boolean finished;

    /// Creates a seekable writer and applies endpoint ownership if setup fails.
    ///
    /// @param codec     the immutable standard-frame Zstandard configuration
    /// @param target    the channel receiving frames and the terminal seek table
    /// @param options   the complete source metadata and frame-size policy
    /// @param ownership whether this writer owns `target`
    /// @return a writer preserving interruptible-channel capability
    /// @throws IOException if the framed encoder cannot be initialized or owned-target cleanup fails
    public static CompressingWritableByteChannel.FlushableFramed open(
            ZstdCodec codec,
            WritableByteChannel target,
            SeekableEncodingOptions options,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        if (options.maximumFrameSize() > ZstdSeekableFormat.MAXIMUM_FRAME_SIZE) {
            throw new IllegalArgumentException(
                    "Zstandard seekable maximumFrameSize exceeds " + ZstdSeekableFormat.MAXIMUM_FRAME_SIZE
            );
        }

        OwnedChannelCloser targetCloser = new OwnedChannelCloser(target, ownership);
        try {
            CompressingWritableByteChannel.FlushableFramed encoder = codec.newWritableByteChannel(
                    target,
                    EncodingOptions.DEFAULT,
                    ResourceOwnership.BORROWED
            );
            ZstdSeekableWritableByteChannel writer = new ZstdSeekableWritableByteChannel(
                    target,
                    targetCloser,
                    encoder,
                    options,
                    codec.emitsFrameChecksum()
            );
            return target instanceof InterruptibleChannel
                    ? new InterruptibleWriter(target, writer)
                    : writer;
        } catch (IOException | RuntimeException | Error exception) {
            targetCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Creates a writer after all public arguments and the framed encoder have been validated.
    private ZstdSeekableWritableByteChannel(
            WritableByteChannel target,
            OwnedChannelCloser targetCloser,
            CompressingWritableByteChannel.FlushableFramed encoder,
            SeekableEncodingOptions options,
            boolean tableChecksums
    ) {
        this.target = target;
        this.targetCloser = targetCloser;
        this.encoder = encoder;
        this.expectedSourceSize = options.sourceSize();
        this.maximumFrameSize = options.maximumFrameSize();
        this.tableChecksums = tableChecksums;
        this.frameChecksums = tableChecksums ? new int[INITIAL_FRAME_CAPACITY] : new int[0];
        this.activeChecksum = tableChecksums ? XXHash64.DEFAULT.newAccumulator() : null;
    }

    /// Encodes all remaining source bytes, ending frames at the configured uncompressed size.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }
        if (expectedSourceSize >= 0L && source.remaining() > expectedSourceSize - inputBytes) {
            throw new IOException("Seekable encoding input exceeds the pledged source size");
        }
        int initialPosition = source.position();
        while (source.hasRemaining()) {
            if (!frameActive) {
                frameActive = true;
            }
            int capacity = maximumFrameSize - activeFrameSize;
            int requested = Math.min(source.remaining(), capacity);
            int sourceStart = source.position();
            ByteBuffer frameSource = source.duplicate();
            frameSource.limit(sourceStart + requested);
            int consumed;
            try {
                int written = encoder.write(frameSource);
                if (written != requested || frameSource.position() != sourceStart + requested) {
                    throw new IOException("Zstandard framed encoder did not consume the requested input");
                }
            } finally {
                consumed = frameSource.position() - sourceStart;
                if (consumed > 0) {
                    updateActiveChecksum(source, sourceStart, consumed);
                    source.position(sourceStart + consumed);
                    activeFrameSize += consumed;
                    inputBytes += consumed;
                }
                outputBytes = encoder.outputBytes();
            }
            if (activeFrameSize == maximumFrameSize) {
                finishActiveFrame(false);
            }
        }
        return source.position() - initialPosition;
    }

    /// Flushes the active frame to a decodable block boundary without recording a frame boundary.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        try {
            encoder.flush();
        } finally {
            outputBytes = encoder.outputBytes();
        }
    }

    /// Explicitly starts another seekable data frame with frame-scoped options.
    @Override
    public void startFrame(EncodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        ensureOpen();
        if (frameActive) {
            throw new IllegalStateException("A seekable Zstandard data frame is already active");
        }
        encoder.startFrame(options);
        frameActive = true;
    }

    /// Finishes and records the active frame before it reaches the automatic size boundary.
    @Override
    public void finishFrame() throws IOException {
        ensureOpen();
        finishActiveFrame(false);
    }

    /// Finishes the final data frame, validates the pledged size, writes the seek table, and applies target ownership.
    @Override
    public void finish() throws IOException {
        if (finished) {
            targetCloser.close();
            return;
        }
        finished = true;
        open = false;

        @Nullable Throwable failure = null;
        try {
            finishActiveFrame(true);
            if (expectedSourceSize >= 0L && inputBytes != expectedSourceSize) {
                throw new IOException(
                        "Seekable encoding accepted " + inputBytes
                                + " bytes but the pledged source size is " + expectedSourceSize
                );
            }
            writeSeekTable();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        targetCloser.closeAfter(failure);
    }

    /// Returns the complete accepted logical byte count.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the complete emitted compressed byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this writer and its target remain available for source writes.
    @Override
    public boolean isOpen() {
        return open && target.isOpen() && encoder.isOpen();
    }

    /// Finishes this seekable encoding.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Releases codec state after terminal cancellation without writing a seek table.
    private void abort() throws IOException {
        if (finished) {
            targetCloser.close();
            return;
        }
        finished = true;
        open = false;
        @Nullable Throwable failure = null;
        try {
            encoder.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        outputBytes = encoder.outputBytes();
        targetCloser.closeAfter(failure);
    }

    /// Finishes and records the active frame, or only releases the framed encoder when no frame is active.
    private void finishActiveFrame(boolean terminal) throws IOException {
        if (!frameActive) {
            if (terminal) {
                try {
                    encoder.finish();
                } finally {
                    outputBytes = encoder.outputBytes();
                }
            }
            return;
        }

        try {
            if (terminal) {
                encoder.finish();
            } else {
                encoder.finishFrame();
            }
        } finally {
            outputBytes = encoder.outputBytes();
        }
        long compressedSize = outputBytes - activeFrameCompressedOffset;
        recordFrame(compressedSize, activeFrameSize, activeChecksumValue());
        activeFrameCompressedOffset = outputBytes;
        activeFrameSize = 0;
        frameActive = false;
        activeChecksum = tableChecksums ? XXHash64.DEFAULT.newAccumulator() : null;
    }

    /// Records one completed frame in the in-memory seek-table builder.
    private void recordFrame(long compressedSize, int uncompressedSize, int checksum) throws IOException {
        if (compressedSize <= 0L || compressedSize > ZstdSeekableFormat.UNSIGNED_INT_MAX) {
            throw new IOException("Zstandard seekable frame compressed size is outside unsigned 32-bit range");
        }
        if (frameCount == ZstdSeekableFormat.MAXIMUM_FRAME_COUNT) {
            throw new IOException("Zstandard seekable frame count exceeds the supported maximum");
        }
        ensureFrameCapacity(frameCount + 1);
        frameCompressedSizes[frameCount] = compressedSize;
        frameUncompressedSizes[frameCount] = uncompressedSize;
        if (tableChecksums) {
            frameChecksums[frameCount] = checksum;
        }
        frameCount++;
    }

    /// Expands the primitive frame-record arrays when necessary.
    private void ensureFrameCapacity(int requiredCapacity) {
        if (requiredCapacity <= frameCompressedSizes.length) {
            return;
        }
        int newCapacity = Math.max(requiredCapacity, frameCompressedSizes.length << 1);
        frameCompressedSizes = Arrays.copyOf(frameCompressedSizes, newCapacity);
        frameUncompressedSizes = Arrays.copyOf(frameUncompressedSizes, newCapacity);
        if (tableChecksums) {
            frameChecksums = Arrays.copyOf(frameChecksums, newCapacity);
        }
    }

    /// Adds one accepted source range to the active frame checksum.
    private void updateActiveChecksum(ByteBuffer source, int offset, int count) {
        ChecksumAccumulator.Width64 checksum = activeChecksum;
        if (checksum == null) {
            return;
        }
        if (source.hasArray()) {
            checksum.update(source.array(), source.arrayOffset() + offset, count);
            return;
        }
        ByteBuffer bytes = source.duplicate();
        bytes.position(offset);
        bytes.limit(offset + count);
        while (bytes.hasRemaining()) {
            int chunkSize = Math.min(bytes.remaining(), hashBuffer.length);
            bytes.get(hashBuffer, 0, chunkSize);
            checksum.update(hashBuffer, 0, chunkSize);
        }
    }

    /// Returns the low 32 bits of the active frame checksum, or zero when checksums are disabled.
    private int activeChecksumValue() {
        ChecksumAccumulator.Width64 checksum = activeChecksum;
        return checksum != null ? (int) checksum.finishLong() : 0;
    }

    /// Serializes the terminal seek table as the assigned Zstandard skippable frame.
    private void writeSeekTable() throws IOException {
        int entrySize = tableChecksums
                ? ZstdSeekableFormat.CHECKSUM_ENTRY_SIZE
                : ZstdSeekableFormat.ENTRY_SIZE;
        long payloadSize = Math.addExact(
                Math.multiplyExact((long) frameCount, entrySize),
                ZstdSeekableFormat.FOOTER_SIZE
        );
        if (payloadSize > ZstdSeekableFormat.UNSIGNED_INT_MAX) {
            throw new IOException("Zstandard seek table exceeds the unsigned 32-bit skippable-frame limit");
        }

        TableOutput table = new TableOutput();
        table.writeInt(ZstdSeekableFormat.SEEK_TABLE_SKIPPABLE_MAGIC);
        table.writeInt((int) payloadSize);
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            table.writeInt((int) frameCompressedSizes[frameIndex]);
            table.writeInt(frameUncompressedSizes[frameIndex]);
            if (tableChecksums) {
                table.writeInt(frameChecksums[frameIndex]);
            }
        }
        table.writeInt(frameCount);
        table.writeByte(tableChecksums ? ZstdSeekableFormat.CHECKSUM_FLAG : 0);
        table.writeInt(ZstdSeekableFormat.SEEKABLE_MAGIC);
        table.flush();
    }

    /// Requires this writer to accept another source operation.
    private void ensureOpen() throws IOException {
        if (!isOpen()) {
            throw new IOException("Zstandard seekable writer is closed");
        }
    }

    /// Buffers little-endian seek-table fields and writes full batches to the target.
    @NotNullByDefault
    private final class TableOutput {
        /// The reusable table serialization buffer.
        private final byte[] buffer = new byte[TABLE_BUFFER_SIZE];

        /// The number of staged bytes.
        private int size;

        /// Writes one little-endian 32-bit field.
        private void writeInt(int value) throws IOException {
            ensureCapacity(Integer.BYTES);
            ByteArrayAccess.writeIntLittleEndian(buffer, size, value);
            size += Integer.BYTES;
        }

        /// Writes one descriptor byte.
        private void writeByte(int value) throws IOException {
            ensureCapacity(1);
            buffer[size++] = (byte) value;
        }

        /// Flushes staged bytes when the next field would not fit.
        private void ensureCapacity(int required) throws IOException {
            if (size + required > buffer.length) {
                flush();
            }
        }

        /// Writes all staged table bytes and rejects a zero-progress target.
        private void flush() throws IOException {
            ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, size);
            while (bytes.hasRemaining()) {
                int start = bytes.position();
                int written;
                try {
                    written = target.write(bytes);
                } finally {
                    outputBytes += bytes.position() - start;
                }
                if (written == 0) {
                    throw new IOException("Zstandard seek-table target made no progress");
                }
            }
            size = 0;
        }
    }

    /// Adds interruptible-channel lifecycle semantics to a seekable writer.
    @NotNullByDefault
    private static final class InterruptibleWriter
            implements CompressingWritableByteChannel.FlushableFramed, InterruptibleChannel {
        /// The transport-independent seekable writer.
        private final ZstdSeekableWritableByteChannel delegate;

        /// The interrupt and concurrent-close state.
        private final InterruptibleChannelSupport state;

        /// Creates an interruptible wrapper around the writer and its endpoint.
        private InterruptibleWriter(WritableByteChannel target, ZstdSeekableWritableByteChannel delegate) {
            this.delegate = delegate;
            this.state = new InterruptibleChannelSupport(target);
        }

        /// Starts another seekable data frame as one interruptible operation.
        @Override
        public void startFrame(EncodingOptions options) throws IOException {
            state.execute(
                    () -> delegate.startFrame(options),
                    delegate::abort
            );
        }

        /// Encodes source bytes as one interruptible operation.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return state.execute(() -> delegate.write(source), delegate::abort);
        }

        /// Flushes the active frame without ending it.
        @Override
        public void flush() throws IOException {
            state.execute(
                    (InterruptibleChannelSupport.IOAction) delegate::flush,
                    delegate::abort
            );
        }

        /// Finishes and records the active frame while retaining the complete encoding session.
        @Override
        public void finishFrame() throws IOException {
            state.execute(
                    (InterruptibleChannelSupport.IOAction) delegate::finishFrame,
                    delegate::abort
            );
        }

        /// Gracefully finishes an idle writer or aborts an active operation.
        @Override
        public void finish() throws IOException {
            close();
        }

        /// Returns the delegate's accepted source count.
        @Override
        public long inputBytes() {
            return delegate.inputBytes();
        }

        /// Returns the delegate's emitted output count.
        @Override
        public long outputBytes() {
            return delegate.outputBytes();
        }

        /// Returns whether another interruptible operation may begin.
        @Override
        public boolean isOpen() {
            return state.isOpen();
        }

        /// Gracefully finishes an idle writer or aborts an active operation.
        @Override
        public void close() throws IOException {
            state.close(delegate::finish, delegate::abort);
        }
    }
}
