// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.zstd.ZstdFrameInfo;
import org.glavo.arkivo.codec.zstd.ZstdSkippableFrameInfo;
import org.glavo.arkivo.codec.zstd.ZstdStandardFrameInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

/// Parses standard and skippable Zstandard frame headers without changing source buffer state.
@NotNullByDefault
public final class ZstdFrameHeader {
    /// Indicates that more header bytes are required.
    public static final long NEED_MORE_INPUT = -1L;

    /// The standard Zstandard frame magic in little-endian integer form.
    private static final long FRAME_MAGIC = 0xfd2f_b528L;

    /// The first skippable-frame magic in little-endian integer form.
    private static final long SKIPPABLE_MAGIC = 0x184d_2a50L;

    /// The mask shared by all sixteen skippable-frame magic values.
    private static final long SKIPPABLE_MAGIC_MASK = 0xffff_fff0L;

    /// Creates no instances.
    private ZstdFrameHeader() {
    }

    /// Parses one complete frame header.
    public static ZstdFrameInfo parse(ByteBuffer source) throws IOException {
        @Nullable ZstdFrameInfo info = tryParse(source);
        if (info == null) {
            throw new EOFException("Incomplete Zstandard frame header");
        }
        return info;
    }

    /// Returns the complete compressed size of one framed item.
    public static long frameCompressedSize(ByteBuffer source) throws IOException {
        ZstdFrameInfo info = parse(source);
        int start = source.position();
        if (info instanceof ZstdSkippableFrameInfo skippable) {
            long size = (long) skippable.headerSize() + skippable.payloadSize();
            requireAvailable(source, start, size);
            return size;
        }

        ZstdStandardFrameInfo standard = (ZstdStandardFrameInfo) info;
        long offset = (long) start + standard.headerSize();
        while (true) {
            requireAvailable(source, offset, 3L);
            int blockHeader = (int) readLittleEndian(source, Math.toIntExact(offset), 3);
            boolean lastBlock = (blockHeader & 1) != 0;
            int blockType = (blockHeader >>> 1) & 3;
            int blockSize = blockHeader >>> 3;
            int payloadSize = switch (blockType) {
                case 0, 2 -> blockSize;
                case 1 -> 1;
                case 3 -> throw new IOException("Reserved Zstandard block type");
                default -> throw new AssertionError(blockType);
            };
            offset += 3L;
            requireAvailable(source, offset, payloadSize);
            offset += payloadSize;
            if (lastBlock) {
                break;
            }
        }
        if (standard.checksum()) {
            requireAvailable(source, offset, Integer.BYTES);
            offset += Integer.BYTES;
        }
        return offset - start;
    }

    /// Returns the required window size, zero for invalid or skippable input, or NEED_MORE_INPUT.
    public static long requiredWindowSize(ByteBuffer source) {
        int start = source.position();
        if (source.remaining() >= Integer.BYTES) {
            long magic = readLittleEndian(source, start, Integer.BYTES);
            if ((magic & SKIPPABLE_MAGIC_MASK) == SKIPPABLE_MAGIC) {
                return 0L;
            }
        }
        try {
            @Nullable ZstdFrameInfo info = tryParse(source);
            if (info == null) {
                return NEED_MORE_INPUT;
            }
            return info instanceof ZstdStandardFrameInfo standard ? standard.windowSize() : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    /// Parses an available header or returns null when more input is required.
    private static @Nullable ZstdFrameInfo tryParse(ByteBuffer source) throws IOException {
        int start = source.position();
        if (source.remaining() < Integer.BYTES) {
            return null;
        }
        long magic = readLittleEndian(source, start, Integer.BYTES);
        if ((magic & SKIPPABLE_MAGIC_MASK) == SKIPPABLE_MAGIC) {
            if (source.remaining() < ZstdSkippableFrameInfo.HEADER_SIZE) {
                return null;
            }
            int id = (int) (magic - SKIPPABLE_MAGIC);
            long payloadSize = readLittleEndian(source, start + Integer.BYTES, Integer.BYTES);
            return new ZstdSkippableFrameInfo(id, payloadSize);
        }
        if (magic != FRAME_MAGIC) {
            throw new IOException("Invalid Zstandard frame magic");
        }
        if (source.remaining() < Integer.BYTES + 1) {
            return null;
        }

        int descriptor = Byte.toUnsignedInt(source.get(start + Integer.BYTES));
        if ((descriptor & 0x18) != 0) {
            throw new IOException("Reserved Zstandard frame descriptor bits are set");
        }
        boolean singleSegment = (descriptor & 0x20) != 0;
        boolean checksum = (descriptor & 0x04) != 0;
        int dictionaryIdFlag = descriptor & 3;
        int dictionaryIdSize = dictionaryIdFlag == 0 ? 0 : 1 << (dictionaryIdFlag - 1);
        int contentSizeFlag = descriptor >>> 6;
        int contentSizeFieldSize = switch (contentSizeFlag) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new AssertionError(contentSizeFlag);
        };
        int windowDescriptorSize = singleSegment ? 0 : 1;
        int headerSize = Integer.BYTES + 1 + windowDescriptorSize + dictionaryIdSize + contentSizeFieldSize;
        if (source.remaining() < headerSize) {
            return null;
        }

        int fieldOffset = start + Integer.BYTES + 1;
        long windowSize;
        if (singleSegment) {
            windowSize = 0L;
        } else {
            int windowDescriptor = Byte.toUnsignedInt(source.get(fieldOffset++));
            int windowLog = (windowDescriptor >>> 3) + 10;
            long windowBase = 1L << windowLog;
            windowSize = windowBase + (windowBase >>> 3) * (windowDescriptor & 7);
        }

        long dictionaryId = dictionaryIdSize == 0
                ? CompressionDictionary.UNKNOWN_ID
                : readLittleEndian(source, fieldOffset, dictionaryIdSize);
        fieldOffset += dictionaryIdSize;

        long contentSize;
        if (contentSizeFieldSize == 0) {
            contentSize = CompressionCodec.UNKNOWN_SIZE;
        } else if (contentSizeFieldSize == Long.BYTES
                && source.get(fieldOffset + Long.BYTES - 1) < 0) {
            contentSize = ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW;
        } else {
            contentSize = readLittleEndian(source, fieldOffset, contentSizeFieldSize);
            if (contentSizeFieldSize == Short.BYTES) {
                contentSize += 256L;
            }
        }
        if (singleSegment) {
            windowSize = contentSize == ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW
                    ? Long.MAX_VALUE
                    : contentSize;
        }
        return new ZstdStandardFrameInfo(
                headerSize,
                contentSize,
                windowSize,
                dictionaryId,
                checksum
        );
    }

    /// Requires one source range to be available.
    private static void requireAvailable(ByteBuffer source, long offset, long length) throws EOFException {
        long end = offset + length;
        if (length < 0L || end < offset || end > source.limit()) {
            throw new EOFException("Incomplete Zstandard frame");
        }
    }

    /// Reads an unsigned little-endian field that fits in a signed long.
    private static long readLittleEndian(ByteBuffer source, int offset, int length) {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) Byte.toUnsignedInt(source.get(offset + index)) << (index * 8);
        }
        return value;
    }
}