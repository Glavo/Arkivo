// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Stores one validated immutable Zstandard seek table.
@NotNullByDefault
public final class ZstdSeekableIndex implements CompressionCodec.Seekable.Index {
    /// The bounded buffer used to parse batches of table entries.
    private static final int PARSE_BUFFER_SIZE = 64 * 1024;

    /// The codec configuration used by channels created from this index.
    private final ZstdCodec codec;

    /// The complete encoded byte count, including the seek table.
    private final long compressedSize;

    /// Cumulative compressed frame offsets, including the data-frame end offset.
    private final long @Unmodifiable [] compressedOffsets;

    /// Cumulative uncompressed frame offsets, including the logical end offset.
    private final long @Unmodifiable [] uncompressedOffsets;

    /// Per-frame seek-table checksums, or an empty array when the table omits them.
    private final int @Unmodifiable [] checksums;

    /// Whether the seek table carries per-frame checksums.
    private final boolean hasChecksums;

    /// The primitive index storage retained for logical channels.
    private final long retainedMemorySize;

    /// Parses a terminal seek table while preserving the caller's source position.
    ///
    /// @param codec  the immutable standard-frame Zstandard configuration
    /// @param source the seekable encoding at its current logical origin
    /// @return the validated index, or `null` when the terminal seekable magic is absent
    /// @throws IOException if source I/O fails or a recognized table is malformed or exceeds a configured limit
    public static @Nullable ZstdSeekableIndex read(
            ZstdCodec codec,
            SeekableByteChannel source
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");

        long origin = source.position();
        try {
            long sourceSize = source.size();
            if (origin < 0L || origin > sourceSize) {
                throw new IOException("Zstandard seekable source origin is outside its size");
            }
            long compressedSize = sourceSize - origin;
            if (compressedSize < ZstdSeekableFormat.FOOTER_SIZE) {
                return null;
            }

            byte[] footer = new byte[ZstdSeekableFormat.FOOTER_SIZE];
            readFullyAt(
                    source,
                    origin + compressedSize - ZstdSeekableFormat.FOOTER_SIZE,
                    footer
            );
            if (ByteArrayAccess.readIntLittleEndian(footer, 5) != ZstdSeekableFormat.SEEKABLE_MAGIC) {
                return null;
            }

            long frameCountValue = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(footer, 0));
            if (frameCountValue > ZstdSeekableFormat.MAXIMUM_FRAME_COUNT) {
                throw malformed("frame count exceeds the supported maximum");
            }
            int frameCount = (int) frameCountValue;
            int descriptor = Byte.toUnsignedInt(footer[4]);
            if ((descriptor & ZstdSeekableFormat.RESERVED_DESCRIPTOR_MASK) != 0) {
                throw malformed("descriptor contains nonzero reserved bits");
            }
            boolean hasChecksums = (descriptor & ZstdSeekableFormat.CHECKSUM_FLAG) != 0;
            int entrySize = hasChecksums
                    ? ZstdSeekableFormat.CHECKSUM_ENTRY_SIZE
                    : ZstdSeekableFormat.ENTRY_SIZE;
            long entriesSize = Math.multiplyExact(frameCountValue, entrySize);
            long payloadSize = Math.addExact(entriesSize, ZstdSeekableFormat.FOOTER_SIZE);
            long tableSize = Math.addExact(payloadSize, ZstdSeekableFormat.SKIPPABLE_HEADER_SIZE);
            if (tableSize > compressedSize || payloadSize > ZstdSeekableFormat.UNSIGNED_INT_MAX) {
                throw malformed("declared table extent is outside the encoded source");
            }
            long tableOffset = compressedSize - tableSize;

            byte[] header = new byte[ZstdSeekableFormat.SKIPPABLE_HEADER_SIZE];
            readFullyAt(source, origin + tableOffset, header);
            if (ByteArrayAccess.readIntLittleEndian(header, 0)
                    != ZstdSeekableFormat.SEEK_TABLE_SKIPPABLE_MAGIC) {
                throw malformed("terminal table uses the wrong skippable-frame magic");
            }
            long declaredPayloadSize = Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(header, 4));
            if (declaredPayloadSize != payloadSize) {
                throw malformed("skippable-frame size does not match the seek-table footer");
            }

            long retainedMemorySize = retainedMemorySize(frameCount, hasChecksums);
            CompressionDecoderSupport.requireMemorySize(
                    codec.maximumMemorySize(),
                    Math.addExact(retainedMemorySize, PARSE_BUFFER_SIZE)
            );
            long[] compressedOffsets = new long[frameCount + 1];
            long[] uncompressedOffsets = new long[frameCount + 1];
            int[] checksums = hasChecksums ? new int[frameCount] : new int[0];
            parseEntries(
                    source,
                    origin + tableOffset + ZstdSeekableFormat.SKIPPABLE_HEADER_SIZE,
                    frameCount,
                    entrySize,
                    hasChecksums,
                    compressedOffsets,
                    uncompressedOffsets,
                    checksums
            );
            if (compressedOffsets[frameCount] != tableOffset) {
                throw malformed("frame compressed sizes do not end at the seek table");
            }

            long uncompressedSize = uncompressedOffsets[frameCount];
            long maximumOutputSize = codec.maximumOutputSize();
            if (maximumOutputSize != CompressionCodec.UNLIMITED_SIZE
                    && uncompressedSize > maximumOutputSize) {
                throw new DecompressionOutputLimitException(maximumOutputSize, uncompressedSize);
            }
            return new ZstdSeekableIndex(
                    codec,
                    compressedSize,
                    compressedOffsets,
                    uncompressedOffsets,
                    checksums,
                    hasChecksums,
                    retainedMemorySize
            );
        } catch (ArithmeticException exception) {
            throw malformed("table size arithmetic overflow", exception);
        } finally {
            source.position(origin);
        }
    }

    /// Creates an immutable index from validated primitive arrays.
    private ZstdSeekableIndex(
            ZstdCodec codec,
            long compressedSize,
            long[] compressedOffsets,
            long[] uncompressedOffsets,
            int[] checksums,
            boolean hasChecksums,
            long retainedMemorySize
    ) {
        this.codec = codec;
        this.compressedSize = compressedSize;
        this.compressedOffsets = compressedOffsets;
        this.uncompressedOffsets = uncompressedOffsets;
        this.checksums = checksums;
        this.hasChecksums = hasChecksums;
        this.retainedMemorySize = retainedMemorySize;
    }

    /// Returns the complete indexed encoding size.
    @Override
    public long compressedSize() {
        return compressedSize;
    }

    /// Returns the complete logical uncompressed size.
    @Override
    public long uncompressedSize() {
        return uncompressedOffsets[uncompressedOffsets.length - 1];
    }

    /// Returns the number of indexed frames.
    @Override
    public int frameCount() {
        return compressedOffsets.length - 1;
    }

    /// Returns one frame's compressed offset.
    @Override
    public long frameCompressedOffset(int frameIndex) {
        Objects.checkIndex(frameIndex, frameCount());
        return compressedOffsets[frameIndex];
    }

    /// Returns one frame's compressed size.
    @Override
    public long frameCompressedSize(int frameIndex) {
        Objects.checkIndex(frameIndex, frameCount());
        return compressedOffsets[frameIndex + 1] - compressedOffsets[frameIndex];
    }

    /// Returns one frame's logical uncompressed offset.
    @Override
    public long frameUncompressedOffset(int frameIndex) {
        Objects.checkIndex(frameIndex, frameCount());
        return uncompressedOffsets[frameIndex];
    }

    /// Returns one frame's uncompressed size.
    @Override
    public long frameUncompressedSize(int frameIndex) {
        Objects.checkIndex(frameIndex, frameCount());
        return uncompressedOffsets[frameIndex + 1] - uncompressedOffsets[frameIndex];
    }

    /// Creates a logical random-access channel over another view of the same encoded source.
    @Override
    public SeekableByteChannel newReadableByteChannel(
            SeekableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return ZstdSeekableByteChannel.open(this, source, ownership);
    }

    /// Returns the immutable codec used to decode indexed frames.
    ZstdCodec codec() {
        return codec;
    }

    /// Returns the primitive index storage retained while a logical channel is open.
    long retainedMemorySize() {
        return retainedMemorySize;
    }

    /// Returns whether the table carries checksums that this codec is configured to verify.
    boolean verifiesTableChecksums() {
        return hasChecksums && codec.verifiesChecksums();
    }

    /// Returns one stored frame checksum.
    int frameChecksum(int frameIndex) {
        Objects.checkIndex(frameIndex, frameCount());
        return checksums[frameIndex];
    }

    /// Finds the frame containing a logical position strictly before the logical end.
    int frameContaining(long position) {
        int low = 0;
        int high = frameCount();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (uncompressedOffsets[middle + 1] <= position) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }

    /// Parses frame entries in bounded batches into cumulative offset arrays.
    private static void parseEntries(
            SeekableByteChannel source,
            long entriesOffset,
            int frameCount,
            int entrySize,
            boolean hasChecksums,
            long[] compressedOffsets,
            long[] uncompressedOffsets,
            int[] checksums
    ) throws IOException {
        int framesPerBatch = Math.max(1, PARSE_BUFFER_SIZE / entrySize);
        byte[] buffer = new byte[framesPerBatch * entrySize];
        source.position(entriesOffset);
        int frameIndex = 0;
        while (frameIndex < frameCount) {
            int batchFrames = Math.min(frameCount - frameIndex, framesPerBatch);
            int batchSize = batchFrames * entrySize;
            readFully(source, buffer, batchSize);
            for (int batchIndex = 0; batchIndex < batchFrames; batchIndex++) {
                int offset = batchIndex * entrySize;
                long compressedFrameSize =
                        Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(buffer, offset));
                long uncompressedFrameSize =
                        Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(buffer, offset + 4));
                if (compressedFrameSize == 0L) {
                    throw malformed("frame entry has a zero compressed size");
                }
                if (uncompressedFrameSize > ZstdSeekableFormat.MAXIMUM_FRAME_SIZE) {
                    throw malformed("frame uncompressed size exceeds the supported maximum");
                }
                int index = frameIndex + batchIndex;
                try {
                    compressedOffsets[index + 1] =
                            Math.addExact(compressedOffsets[index], compressedFrameSize);
                    uncompressedOffsets[index + 1] =
                            Math.addExact(uncompressedOffsets[index], uncompressedFrameSize);
                } catch (ArithmeticException exception) {
                    throw malformed("frame offsets overflow signed 64-bit range", exception);
                }
                if (hasChecksums) {
                    checksums[index] = ByteArrayAccess.readIntLittleEndian(buffer, offset + 8);
                }
            }
            frameIndex += batchFrames;
        }
    }

    /// Returns the retained primitive-index memory charged to decoder limits.
    private static long retainedMemorySize(int frameCount, boolean hasChecksums) {
        long offsetBytes = Math.multiplyExact(Math.addExact(frameCount, 1L), 2L * Long.BYTES);
        long checksumBytes = hasChecksums ? Math.multiplyExact((long) frameCount, Integer.BYTES) : 0L;
        return Math.addExact(offsetBytes, checksumBytes);
    }

    /// Reads an exact byte range after positioning the source.
    private static void readFullyAt(
            SeekableByteChannel source,
            long position,
            byte[] target
    ) throws IOException {
        source.position(position);
        readFully(source, target, target.length);
    }

    /// Reads an exact byte count and rejects premature EOF or zero progress.
    private static void readFully(
            SeekableByteChannel source,
            byte[] target,
            int length
    ) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(target, 0, length);
        while (bytes.hasRemaining()) {
            int read = source.read(bytes);
            if (read < 0) {
                throw new EOFException("Unexpected end of Zstandard seek table");
            }
            if (read == 0) {
                throw new IOException("Zstandard seek-table source made no progress");
            }
        }
    }

    /// Creates a malformed-table exception.
    private static IOException malformed(String message) {
        return new IOException("Malformed Zstandard seek table: " + message);
    }

    /// Creates a malformed-table exception with an arithmetic cause.
    private static IOException malformed(String message, Throwable cause) {
        return new IOException("Malformed Zstandard seek table: " + message, cause);
    }
}
