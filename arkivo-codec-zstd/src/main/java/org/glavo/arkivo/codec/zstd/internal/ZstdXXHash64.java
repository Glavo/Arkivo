// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Computes the XXH64 checksum carried by optional Zstandard frame trailers.
@NotNullByDefault
final class ZstdXXHash64 {
    /// XXH64 prime 1.
    private static final long PRIME1 = 0x9e37_79b1_85eb_ca87L;

    /// XXH64 prime 2.
    private static final long PRIME2 = 0xc2b2_ae3d_27d4_eb4fL;

    /// XXH64 prime 3.
    private static final long PRIME3 = 0x1656_67b1_9e37_79f9L;

    /// XXH64 prime 4.
    private static final long PRIME4 = 0x85eb_ca77_c2b2_ae63L;

    /// XXH64 prime 5.
    private static final long PRIME5 = 0x27d4_eb2f_1656_67c5L;

    /// Pending bytes that do not yet fill a 32-byte stripe.
    private final byte[] pending = new byte[32];

    /// First stripe accumulator.
    private long accumulator1 = 0x60ea_27ee_adc0_b5d6L;

    /// Second stripe accumulator.
    private long accumulator2 = PRIME2;

    /// Third stripe accumulator.
    private long accumulator3;

    /// Fourth stripe accumulator.
    private long accumulator4 = 0x61c8_864e_7a14_3579L;

    /// Number of pending bytes.
    private int pendingSize;

    /// Total number of bytes supplied.
    private long length;

    /// Adds bytes to this checksum.
    void update(byte[] source, int offset, int count) {
        java.util.Objects.checkFromIndexSize(offset, count, source.length);
        length += count;
        int input = offset;
        int remaining = count;
        if (pendingSize + remaining < 32) {
            System.arraycopy(source, input, pending, pendingSize, remaining);
            pendingSize += remaining;
            return;
        }
        if (pendingSize != 0) {
            int copied = 32 - pendingSize;
            System.arraycopy(source, input, pending, pendingSize, copied);
            processStripe(pending, 0);
            input += copied;
            remaining -= copied;
            pendingSize = 0;
        }
        while (remaining >= 32) {
            processStripe(source, input);
            input += 32;
            remaining -= 32;
        }
        if (remaining != 0) {
            System.arraycopy(source, input, pending, 0, remaining);
            pendingSize = remaining;
        }
    }

    /// Returns the current 64-bit checksum without changing this instance.
    long digest() {
        long hash;
        if (length >= 32) {
            hash = Long.rotateLeft(accumulator1, 1)
                    + Long.rotateLeft(accumulator2, 7)
                    + Long.rotateLeft(accumulator3, 12)
                    + Long.rotateLeft(accumulator4, 18);
            hash = mergeRound(hash, accumulator1);
            hash = mergeRound(hash, accumulator2);
            hash = mergeRound(hash, accumulator3);
            hash = mergeRound(hash, accumulator4);
        } else {
            hash = PRIME5;
        }
        hash += length;

        int offset = 0;
        int remaining = pendingSize;
        while (remaining >= 8) {
            long lane = round(0L, readLong(pending, offset));
            hash ^= lane;
            hash = Long.rotateLeft(hash, 27) * PRIME1 + PRIME4;
            offset += 8;
            remaining -= 8;
        }
        if (remaining >= 4) {
            hash ^= Integer.toUnsignedLong(readInt(pending, offset)) * PRIME1;
            hash = Long.rotateLeft(hash, 23) * PRIME2 + PRIME3;
            offset += 4;
            remaining -= 4;
        }
        while (remaining-- > 0) {
            hash ^= (long) Byte.toUnsignedInt(pending[offset++]) * PRIME5;
            hash = Long.rotateLeft(hash, 11) * PRIME1;
        }
        hash ^= hash >>> 33;
        hash *= PRIME2;
        hash ^= hash >>> 29;
        hash *= PRIME3;
        hash ^= hash >>> 32;
        return hash;
    }

    /// Processes one complete 32-byte stripe.
    private void processStripe(byte[] source, int offset) {
        accumulator1 = round(accumulator1, readLong(source, offset));
        accumulator2 = round(accumulator2, readLong(source, offset + 8));
        accumulator3 = round(accumulator3, readLong(source, offset + 16));
        accumulator4 = round(accumulator4, readLong(source, offset + 24));
    }

    /// Mixes one input lane into an accumulator.
    private static long round(long accumulator, long lane) {
        accumulator += lane * PRIME2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * PRIME1;
    }

    /// Merges one stripe accumulator into the final hash.
    private static long mergeRound(long hash, long accumulator) {
        hash ^= round(0L, accumulator);
        return hash * PRIME1 + PRIME4;
    }

    /// Reads a little-endian 64-bit value.
    private static long readLong(byte[] source, int offset) {
        return Integer.toUnsignedLong(readInt(source, offset))
                | Integer.toUnsignedLong(readInt(source, offset + 4)) << 32;
    }

    /// Reads a little-endian 32-bit value.
    private static int readInt(byte[] source, int offset) {
        return Byte.toUnsignedInt(source[offset])
                | Byte.toUnsignedInt(source[offset + 1]) << 8
                | Byte.toUnsignedInt(source[offset + 2]) << 16
                | source[offset + 3] << 24;
    }
}
