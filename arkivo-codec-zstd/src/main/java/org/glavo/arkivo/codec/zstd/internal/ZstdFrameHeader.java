// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;

/// Parses the bounded Zstandard frame-header fields needed before streaming decompression starts.
@NotNullByDefault
final class ZstdFrameHeader {
    /// Indicates that more header bytes are required.
    static final long NEED_MORE_INPUT = -1L;

    /// The standard Zstandard frame magic in little-endian integer form.
    private static final long FRAME_MAGIC = 0xfd2f_b528L;

    /// The first skippable-frame magic in little-endian integer form.
    private static final long SKIPPABLE_MAGIC = 0x184d_2a50L;

    /// The mask shared by all sixteen skippable-frame magic values.
    private static final long SKIPPABLE_MAGIC_MASK = 0xffff_fff0L;

    /// Creates no instances.
    private ZstdFrameHeader() {
    }

    /// Returns the required window size, zero for a nonstandard or skippable frame, or `NEED_MORE_INPUT`.
    static long requiredWindowSize(ByteBuffer source) {
        int start = source.position();
        if (source.remaining() < Integer.BYTES) {
            return NEED_MORE_INPUT;
        }
        long magic = readLittleEndian(source, start, Integer.BYTES);
        if ((magic & SKIPPABLE_MAGIC_MASK) == SKIPPABLE_MAGIC) {
            return 0L;
        }
        if (magic != FRAME_MAGIC) {
            return 0L;
        }
        if (source.remaining() < Integer.BYTES + 1) {
            return NEED_MORE_INPUT;
        }

        int descriptor = Byte.toUnsignedInt(source.get(start + Integer.BYTES));
        if ((descriptor & 0x18) != 0) {
            return 0L;
        }
        boolean singleSegment = (descriptor & 0x20) != 0;
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
            return NEED_MORE_INPUT;
        }

        int fieldOffset = start + Integer.BYTES + 1;
        if (!singleSegment) {
            int descriptorValue = Byte.toUnsignedInt(source.get(fieldOffset));
            int windowLog = (descriptorValue >>> 3) + 10;
            long windowBase = 1L << windowLog;
            return windowBase + (windowBase >>> 3) * (descriptorValue & 7);
        }

        fieldOffset += dictionaryIdSize;
        long contentSize = readLittleEndianSaturated(source, fieldOffset, contentSizeFieldSize);
        return contentSizeFieldSize == 2 ? contentSize + 256L : contentSize;
    }

    /// Reads an unsigned little-endian field that is known to fit in a signed long.
    private static long readLittleEndian(ByteBuffer source, int offset, int length) {
        long value = 0L;
        for (int index = 0; index < length; index++) {
            value |= (long) Byte.toUnsignedInt(source.get(offset + index)) << (index * 8);
        }
        return value;
    }

    /// Reads an unsigned field and saturates values beyond signed-long range.
    private static long readLittleEndianSaturated(ByteBuffer source, int offset, int length) {
        if (length == Long.BYTES && source.get(offset + Long.BYTES - 1) < 0) {
            return Long.MAX_VALUE;
        }
        return readLittleEndian(source, offset, length);
    }
}
