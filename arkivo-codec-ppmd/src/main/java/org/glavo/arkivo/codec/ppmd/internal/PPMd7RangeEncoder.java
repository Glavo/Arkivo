// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes the 7z arithmetic range representation used with PPMd7 models.
@NotNullByDefault
final class PPMd7RangeEncoder implements PPMdRangeEncoder {
    /// The unsigned 32-bit mask.
    private static final long UINT_MASK = 0xffff_ffffL;

    /// The normalization threshold.
    private static final long RANGE_TOP = 1L << 24;

    /// Buffered output capacity.
    private static final int OUTPUT_BUFFER_SIZE = 8 * 1_024;

    /// The compressed target.
    private final WritableByteChannel target;

    /// Buffered compressed output.
    private final ByteBuffer outputBuffer = ByteBuffer.allocate(OUTPUT_BUFFER_SIZE);

    /// Current arithmetic low endpoint including a carry bit.
    private long low;

    /// Current unsigned arithmetic range.
    private long range = UINT_MASK;

    /// Delayed high byte awaiting carry resolution.
    private int cache;

    /// Number of delayed bytes represented by the cache.
    private long cacheSize = 1L;

    /// Number of compressed bytes produced.
    private long outputBytes;

    /// Whether the arithmetic representation was finished.
    private boolean finished;

    /// Creates an uninitialized-output 7z range encoder.
    PPMd7RangeEncoder(WritableByteChannel target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /// Encodes one selected cumulative-frequency interval.
    @Override
    public void encode(int lowCount, int highCount, int scale) throws IOException {
        if (finished) {
            throw new IOException("PPMd7 range encoder is already finished");
        }
        if (scale <= 0 || lowCount < 0 || highCount <= lowCount || highCount > scale) {
            throw new IOException("Invalid PPMd arithmetic interval");
        }
        range /= scale;
        low += range * lowCount;
        range *= highCount - lowCount;
        normalize();
    }

    /// Encodes one binary context with the 7z range coder's format-defined rounding.
    @Override
    public void encodeBit(boolean one, int zeroSize, int scale) throws IOException {
        if (finished) {
            throw new IOException("PPMd7 range encoder is already finished");
        }
        if (scale != 1 << 14 || zeroSize <= 0 || zeroSize >= scale) {
            throw new IOException("Invalid PPMd7 binary arithmetic interval");
        }
        long zeroRange = (range >>> 14) * zeroSize;
        if (one) {
            low += zeroRange;
            range -= zeroRange;
        } else {
            range = zeroRange;
        }
        normalize();
    }

    /// Finishes the arithmetic representation and flushes all bytes.
    @Override
    public void finish() throws IOException {
        if (finished) {
            flushOutput();
            return;
        }
        finished = true;
        for (int index = 0; index < 5; index++) {
            shiftLow();
        }
        flushOutput();
    }

    /// Flushes complete staged bytes without ending the arithmetic representation.
    void flushOutput() throws IOException {
        outputBuffer.flip();
        while (outputBuffer.hasRemaining()) {
            if (target.write(outputBuffer) == 0) {
                outputBuffer.compact();
                throw new IOException("PPMd7 target channel made no progress");
            }
        }
        outputBuffer.clear();
    }

    /// Returns the number of compressed bytes produced.
    long outputBytes() {
        return outputBytes;
    }

    /// Abandons the current arithmetic representation and restores its initial state.
    void reset() {
        outputBuffer.clear();
        low = 0L;
        range = UINT_MASK;
        cache = 0;
        cacheSize = 1L;
        outputBytes = 0L;
        finished = false;
    }

    /// Renormalizes the arithmetic range.
    private void normalize() throws IOException {
        while (range < RANGE_TOP) {
            range = range << Byte.SIZE & UINT_MASK;
            shiftLow();
        }
    }

    /// Resolves carries and emits one delayed high-order byte.
    private void shiftLow() throws IOException {
        long lowBits = low & UINT_MASK;
        int carry = (int) (low >>> Integer.SIZE);
        if (lowBits < 0xff00_0000L || carry != 0) {
            int value = cache;
            do {
                writeByte(value + carry);
                value = 0xff;
            } while (--cacheSize != 0L);
            cache = (int) (lowBits >>> 24);
        }
        cacheSize++;
        low = lowBits << Byte.SIZE & UINT_MASK;
    }

    /// Stages one compressed byte.
    private void writeByte(int value) throws IOException {
        if (!outputBuffer.hasRemaining()) {
            flushOutput();
        }
        outputBuffer.put((byte) value);
        outputBytes++;
    }
}
