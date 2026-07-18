// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/// Computes streaming xxHash-32 values using the seed required by the LZ4 frame format.
@NotNullByDefault
final class XXHash32 {
    /// First xxHash-32 prime.
    private static final int PRIME_1 = 0x9e37_79b1;

    /// Second xxHash-32 prime.
    private static final int PRIME_2 = 0x85eb_ca77;

    /// Third xxHash-32 prime.
    private static final int PRIME_3 = 0xc2b2_ae3d;

    /// Fourth xxHash-32 prime.
    private static final int PRIME_4 = 0x27d4_eb2f;

    /// Fifth xxHash-32 prime.
    private static final int PRIME_5 = 0x1656_67b1;

    /// Fixed seed used by LZ4 framing.
    private final int seed;

    /// Incomplete stripe bytes.
    private final byte[] memory = new byte[16];

    /// First large-input accumulator.
    private int accumulator1;

    /// Second large-input accumulator.
    private int accumulator2;

    /// Third large-input accumulator.
    private int accumulator3;

    /// Fourth large-input accumulator.
    private int accumulator4;

    /// Total number of bytes supplied since the last reset.
    private long totalLength;

    /// Number of meaningful bytes in the incomplete stripe.
    private int memorySize;

    /// Creates a hash using the zero seed required by LZ4.
    XXHash32() {
        this(0);
    }

    /// Creates a hash using an explicit seed.
    XXHash32(int seed) {
        this.seed = seed;
        reset();
    }

    /// Updates this hash with one complete byte array.
    void update(byte[] bytes) {
        update(bytes, 0, bytes.length);
    }

    /// Updates this hash with one byte-array range.
    void update(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (length == 0) {
            return;
        }
        totalLength += length;
        int end = offset + length;

        if (memorySize + length < memory.length) {
            System.arraycopy(bytes, offset, memory, memorySize, length);
            memorySize += length;
            return;
        }

        if (memorySize != 0) {
            int required = memory.length - memorySize;
            System.arraycopy(bytes, offset, memory, memorySize, required);
            processStripe(memory, 0);
            offset += required;
            memorySize = 0;
        }

        int stripeLimit = end - memory.length;
        while (offset <= stripeLimit) {
            processStripe(bytes, offset);
            offset += memory.length;
        }
        if (offset < end) {
            memorySize = end - offset;
            System.arraycopy(bytes, offset, memory, 0, memorySize);
        }
    }

    /// Returns the current unsigned 32-bit digest without changing this hash.
    long value() {
        int hash;
        if (totalLength >= memory.length) {
            hash = Integer.rotateLeft(accumulator1, 1)
                    + Integer.rotateLeft(accumulator2, 7)
                    + Integer.rotateLeft(accumulator3, 12)
                    + Integer.rotateLeft(accumulator4, 18);
        } else {
            hash = seed + PRIME_5;
        }
        hash += (int) totalLength;

        int position = 0;
        while (position + Integer.BYTES <= memorySize) {
            hash += readInt(memory, position) * PRIME_3;
            hash = Integer.rotateLeft(hash, 17) * PRIME_4;
            position += Integer.BYTES;
        }
        while (position < memorySize) {
            hash += Byte.toUnsignedInt(memory[position++]) * PRIME_5;
            hash = Integer.rotateLeft(hash, 11) * PRIME_1;
        }

        hash ^= hash >>> 15;
        hash *= PRIME_2;
        hash ^= hash >>> 13;
        hash *= PRIME_3;
        hash ^= hash >>> 16;
        return Integer.toUnsignedLong(hash);
    }

    /// Restores the configured initial seed state.
    void reset() {
        accumulator1 = seed + PRIME_1 + PRIME_2;
        accumulator2 = seed + PRIME_2;
        accumulator3 = seed;
        accumulator4 = seed - PRIME_1;
        totalLength = 0L;
        memorySize = 0;
        Arrays.fill(memory, (byte) 0);
    }

    /// Computes a complete zero-seeded byte-array hash.
    static long hash(byte[] bytes) {
        XXHash32 hash = new XXHash32();
        hash.update(bytes);
        return hash.value();
    }

    /// Processes one complete 16-byte stripe.
    private void processStripe(byte[] bytes, int offset) {
        accumulator1 = round(accumulator1, readInt(bytes, offset));
        accumulator2 = round(accumulator2, readInt(bytes, offset + 4));
        accumulator3 = round(accumulator3, readInt(bytes, offset + 8));
        accumulator4 = round(accumulator4, readInt(bytes, offset + 12));
    }

    /// Mixes one four-byte word into a large-input accumulator.
    private static int round(int accumulator, int input) {
        accumulator += input * PRIME_2;
        accumulator = Integer.rotateLeft(accumulator, 13);
        return accumulator * PRIME_1;
    }

    /// Reads one little-endian 32-bit word.
    private static int readInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }
}
