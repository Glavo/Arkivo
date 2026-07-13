// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Decodes the 7z arithmetic range representation used with PPMd7 models.
@NotNullByDefault
final class PPMd7RangeDecoder implements PPMdRangeDecoder {
    /// The unsigned 32-bit mask.
    private static final long UINT_MASK = 0xffff_ffffL;

    /// The normalization threshold.
    private static final long RANGE_TOP = 1L << 24;

    /// The compressed source.
    private final ReadableByteChannel source;

    /// Reusable single-byte source buffer.
    private final ByteBuffer byteBuffer = ByteBuffer.allocate(1);

    /// Current unsigned arithmetic code.
    private long code;

    /// Current unsigned arithmetic range.
    private long range = UINT_MASK;

    /// Number of compressed bytes consumed.
    private long inputBytes;

    /// Creates and initializes a 7z PPMd range decoder.
    PPMd7RangeDecoder(ReadableByteChannel source) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        if (readByte() != 0) {
            throw new IOException("Malformed PPMd7 range-code prefix");
        }
        for (int index = 0; index < Integer.BYTES; index++) {
            code = (code << Byte.SIZE | readByte()) & UINT_MASK;
        }
        if (code == UINT_MASK) {
            throw new IOException("Malformed PPMd7 initial range code");
        }
    }

    /// Returns the current cumulative-frequency position for the given scale.
    @Override
    public int currentCount(int scale) throws IOException {
        if (scale <= 0) throw new IOException("PPMd range scale must be positive");
        range /= scale;
        long count = code / range;
        if (count >= scale) throw new IOException("Corrupt PPMd7 arithmetic interval");
        return (int) count;
    }

    /// Commits the selected half-open cumulative-frequency interval.
    @Override
    public void decode(int lowCount, int highCount) throws IOException {
        if (lowCount < 0 || highCount <= lowCount) {
            throw new IOException("Invalid PPMd arithmetic interval");
        }
        code = (code - range * lowCount) & UINT_MASK;
        range = range * (highCount - lowCount) & UINT_MASK;
        while (range < RANGE_TOP) {
            code = (code << Byte.SIZE | readByte()) & UINT_MASK;
            range = range << Byte.SIZE & UINT_MASK;
        }
    }

    /// Decodes one binary context with the 7z range coder's format-defined rounding.
    @Override
    public boolean decodeBit(int zeroSize, int scale) throws IOException {
        if (scale != 1 << 14 || zeroSize <= 0 || zeroSize >= scale) {
            throw new IOException("Invalid PPMd7 binary arithmetic interval");
        }
        long zeroRange = (range >>> 14) * zeroSize & UINT_MASK;
        boolean one = code >= zeroRange;
        if (one) {
            code = (code - zeroRange) & UINT_MASK;
            range = (range - zeroRange) & UINT_MASK;
        } else {
            range = zeroRange;
        }
        while (range < RANGE_TOP) {
            code = (code << Byte.SIZE | readByte()) & UINT_MASK;
            range = range << Byte.SIZE & UINT_MASK;
        }
        return one;
    }

    /// Returns the number of compressed bytes consumed by arithmetic decoding.
    long inputBytes() {
        return inputBytes;
    }

    /// Reads one required compressed byte.
    private int readByte() throws IOException {
        byteBuffer.clear();
        while (byteBuffer.hasRemaining()) {
            int read = source.read(byteBuffer);
            if (read < 0) throw new EOFException("Truncated PPMd7 range-coded stream");
        }
        inputBytes++;
        return byteBuffer.array()[0] & 0xff;
    }
}
