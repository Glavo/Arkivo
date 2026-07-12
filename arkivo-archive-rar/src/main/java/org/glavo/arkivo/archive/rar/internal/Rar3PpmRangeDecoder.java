// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Objects;

/// Decodes the unsigned 32-bit arithmetic ranges used by RAR3 PPMd blocks.
@NotNullByDefault
final class Rar3PpmRangeDecoder {
    /// The normalization threshold for stable high-order bytes.
    private static final int RANGE_TOP = 1 << 24;
    /// The minimum range retained when low and high share a byte.
    private static final int RANGE_BOTTOM = 1 << 15;
    /// The packed input supplying complete bytes.
    private @Nullable Rar4BitInput input;
    /// The current coded value.
    private int code;
    /// The inclusive low endpoint.
    private int low;
    /// The current interval width.
    private int range;

    /// Creates an uninitialized range decoder.
    Rar3PpmRangeDecoder() {
    }

    /// Starts a new arithmetic stream and consumes its four-byte initial code.
    void initialize(Rar4BitInput input) throws IOException {
        this.input = Objects.requireNonNull(input, "input");
        code = 0;
        low = 0;
        range = -1;
        for (int index = 0; index < Integer.BYTES; index++) code = code << 8 | input.readBits(8);
    }

    /// Returns the cumulative-frequency count and scales the current range.
    int currentCount(int scale) throws IOException {
        if (scale <= 0) throw new IOException("Invalid RAR3 PPM arithmetic scale");
        long divided = Integer.toUnsignedLong(range) / scale;
        if (divided == 0L) throw new IOException("RAR3 PPM arithmetic range collapsed");
        range = (int) divided;
        long count = Integer.toUnsignedLong(code - low) / divided;
        if (count >= scale) throw new IOException("RAR3 PPM arithmetic count exceeds its scale");
        return (int) count;
    }

    /// Selects one cumulative-frequency interval and normalizes the decoder.
    void decode(int lowCount, int highCount) throws IOException {
        if (lowCount < 0 || highCount <= lowCount) {
            throw new IOException("Invalid RAR3 PPM arithmetic interval");
        }
        low += (int) (Integer.toUnsignedLong(range) * lowCount);
        range = (int) (Integer.toUnsignedLong(range) * (highCount - lowCount));
        normalize();
    }

    /// Shifts stable bytes out until the interval is wide enough for another symbol.
    private void normalize() throws IOException {
        Rar4BitInput source = Objects.requireNonNull(input, "RAR3 PPM range decoder is not initialized");
        while (true) {
            int changedHighBits = low ^ low + range;
            if (Integer.compareUnsigned(changedHighBits, RANGE_TOP) >= 0) {
                if (Integer.compareUnsigned(range, RANGE_BOTTOM) >= 0) return;
                range = -low & RANGE_BOTTOM - 1;
            }
            code = code << 8 | source.readBits(8);
            range <<= 8;
            low <<= 8;
        }
    }
}
