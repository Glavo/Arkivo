// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum.xxhash;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Computes XXH64 checksums and creates independent incremental computations.
///
/// The default seed is zero, as required by Zstandard frame checksums. Each accumulator retains its seed across resets,
/// buffers at most one partial 32-byte stripe, and never retains caller buffers.
@NotNullByDefault
public final class XXHash64 {
    /// First XXH64 prime.
    private static final long PRIME_1 = 0x9e37_79b1_85eb_ca87L;

    /// Second XXH64 prime.
    private static final long PRIME_2 = 0xc2b2_ae3d_27d4_eb4fL;

    /// Third XXH64 prime.
    private static final long PRIME_3 = 0x1656_67b1_9e37_79f9L;

    /// Fourth XXH64 prime.
    private static final long PRIME_4 = 0x85eb_ca77_c2b2_ae63L;

    /// Fifth XXH64 prime.
    private static final long PRIME_5 = 0x27d4_eb2f_1656_67c5L;

    /// Prevents construction.
    private XXHash64() {
    }

    /// Creates an active zero-seeded computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width64 newAccumulator() {
        return newAccumulator(0);
    }

    /// Creates an active seeded computation.
    ///
    /// @param seed the seed bit pattern, restored by every reset
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width64 newAccumulator(long seed) {
        return new Accumulator(seed);
    }

    /// Computes the 64-bit checksum using a zero seed.
    ///
    /// @param source the complete input
    /// @return the raw 64-bit result
    public static long computeLong(byte[] source) {
        return computeLong(source, 0);
    }

    /// Computes the 64-bit checksum using the supplied seed.
    ///
    /// @param source the complete input
    /// @param seed the seed bit pattern
    /// @return the raw 64-bit result
    public static long computeLong(byte[] source, long seed) {
        ChecksumAccumulator.Width64 accumulator = newAccumulator(seed);
        accumulator.update(source);
        return accumulator.finishLong();
    }

    /// Computes the 64-bit checksum using a zero seed.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the raw 64-bit result
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static long computeLong(byte[] source, int offset, int length) {
        return computeLong(source, offset, length, 0);
    }

    /// Computes the 64-bit checksum using the supplied seed.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @param seed the seed bit pattern
    /// @return the raw 64-bit result
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static long computeLong(byte[] source, int offset, int length, long seed) {
        ChecksumAccumulator.Width64 accumulator = newAccumulator(seed);
        accumulator.update(source, offset, length);
        return accumulator.finishLong();
    }

    /// Computes the 64-bit checksum using a zero seed.
    ///
    /// @param source the input buffer, consumed through its limit without changing its limit or byte order
    /// @return the raw 64-bit result
    public static long computeLong(ByteBuffer source) {
        return computeLong(source, 0);
    }

    /// Computes the 64-bit checksum using the supplied seed.
    ///
    /// @param source the input buffer, consumed through its limit without changing its limit or byte order
    /// @param seed the seed bit pattern
    /// @return the raw 64-bit result
    public static long computeLong(ByteBuffer source, long seed) {
        ChecksumAccumulator.Width64 accumulator = newAccumulator(seed);
        accumulator.update(source);
        return accumulator.finishLong();
    }

    /// Mixes one eight-byte lane into a large-input accumulator.
    ///
    /// @param accumulator the current stripe accumulator
    /// @param lane        the next little-endian lane
    /// @return the updated accumulator
    private static long round(long accumulator, long lane) {
        accumulator += lane * PRIME_2;
        accumulator = Long.rotateLeft(accumulator, 31);
        return accumulator * PRIME_1;
    }

    /// Merges one large-input accumulator into the finalized hash.
    ///
    /// @param hash        the current hash
    /// @param accumulator the stripe accumulator
    /// @return the merged hash
    private static long mergeRound(long hash, long accumulator) {
        hash ^= round(0L, accumulator);
        return hash * PRIME_1 + PRIME_4;
    }

    /// Holds one incremental XXH64 computation.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator.Width64 {
        /// The configured seed bit pattern.
        private final long seed;

        /// Incomplete stripe bytes.
        private final byte[] memory = new byte[32];

        /// First large-input accumulator.
        private long accumulator1;

        /// Second large-input accumulator.
        private long accumulator2;

        /// Third large-input accumulator.
        private long accumulator3;

        /// Fourth large-input accumulator.
        private long accumulator4;

        /// Total input length modulo 2^64.
        private long totalLength;

        /// Number of meaningful bytes in [#memory].
        private int memorySize;

        /// The cached completed result.
        private long result;

        /// Whether this computation has finished.
        private boolean finished;

        /// Creates active state retaining the supplied seed.
        ///
        /// @param seed the seed bit pattern
        private Accumulator(long seed) {
            this.seed = seed;
            reset();
        }

        @Override
        public String name() {
            return "XXH64";
        }

        /// Adds one byte.
        ///
        /// @param value the byte to add
        @Override
        public void update(byte value) {
            requireActive();
            totalLength++;
            memory[memorySize++] = value;
            if (memorySize == memory.length) {
                processStripe(memory, 0);
                memorySize = 0;
            }
        }

        /// Adds one array range.
        ///
        /// @param source the source array
        /// @param offset the first byte index
        /// @param length the number of bytes
        @Override
        public void update(byte[] source, int offset, int length) {
            Objects.requireNonNull(source, "source");
            Objects.checkFromIndexSize(offset, length, source.length);
            requireActive();
            if (length == 0) {
                return;
            }
            totalLength += length;
            int end = offset + length;

            if (memorySize + length < memory.length) {
                System.arraycopy(source, offset, memory, memorySize, length);
                memorySize += length;
                return;
            }
            if (memorySize != 0) {
                int required = memory.length - memorySize;
                System.arraycopy(source, offset, memory, memorySize, required);
                processStripe(memory, 0);
                offset += required;
                memorySize = 0;
            }
            int stripeLimit = end - memory.length;
            while (offset <= stripeLimit) {
                processStripe(source, offset);
                offset += memory.length;
            }
            if (offset < end) {
                memorySize = end - offset;
                System.arraycopy(source, offset, memory, 0, memorySize);
            }
        }

        /// Adds all remaining buffer bytes.
        ///
        /// @param source the source buffer
        @Override
        public void update(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            requireActive();
            int length = source.remaining();
            if (length == 0) {
                return;
            }
            totalLength += length;
            if (memorySize + length < memory.length) {
                source.get(memory, memorySize, length);
                memorySize += length;
                return;
            }
            if (memorySize != 0) {
                int required = memory.length - memorySize;
                source.get(memory, memorySize, required);
                processStripe(memory, 0);
                memorySize = 0;
            }

            ByteBuffer view = source.slice().order(ByteOrder.LITTLE_ENDIAN);
            while (view.remaining() >= memory.length) {
                processStripe(view);
            }
            memorySize = view.remaining();
            view.get(memory, 0, memorySize);
            source.position(source.limit());
        }

        /// Completes and returns the raw XXH64 result.
        ///
        /// @return the raw 64-bit pattern
        @Override
        public long finishLong() {
            if (!finished) {
                result = calculateResult();
                finished = true;
            }
            return result;
        }

        /// Restores the configured seeded initial state.
        @Override
        public void reset() {
            accumulator1 = seed + PRIME_1 + PRIME_2;
            accumulator2 = seed + PRIME_2;
            accumulator3 = seed;
            accumulator4 = seed - PRIME_1;
            totalLength = 0L;
            memorySize = 0;
            result = 0L;
            finished = false;
            Arrays.fill(memory, (byte) 0);
        }

        /// Calculates the current finalized result without changing stripe state.
        ///
        /// @return the raw XXH64 result
        private long calculateResult() {
            long hash;
            if (totalLength >= memory.length) {
                hash = Long.rotateLeft(accumulator1, 1)
                        + Long.rotateLeft(accumulator2, 7)
                        + Long.rotateLeft(accumulator3, 12)
                        + Long.rotateLeft(accumulator4, 18);
                hash = mergeRound(hash, accumulator1);
                hash = mergeRound(hash, accumulator2);
                hash = mergeRound(hash, accumulator3);
                hash = mergeRound(hash, accumulator4);
            } else {
                hash = seed + PRIME_5;
            }
            hash += totalLength;

            int offset = 0;
            int remaining = memorySize;
            while (remaining >= Long.BYTES) {
                long lane = round(0L, ByteArrayAccess.readLongLittleEndian(memory, offset));
                hash ^= lane;
                hash = Long.rotateLeft(hash, 27) * PRIME_1 + PRIME_4;
                offset += Long.BYTES;
                remaining -= Long.BYTES;
            }
            if (remaining >= Integer.BYTES) {
                hash ^= Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(memory, offset)) * PRIME_1;
                hash = Long.rotateLeft(hash, 23) * PRIME_2 + PRIME_3;
                offset += Integer.BYTES;
                remaining -= Integer.BYTES;
            }
            while (remaining-- > 0) {
                hash ^= (long) Byte.toUnsignedInt(memory[offset++]) * PRIME_5;
                hash = Long.rotateLeft(hash, 11) * PRIME_1;
            }
            hash ^= hash >>> 33;
            hash *= PRIME_2;
            hash ^= hash >>> 29;
            hash *= PRIME_3;
            hash ^= hash >>> 32;
            return hash;
        }

        /// Processes one array-backed 32-byte stripe.
        ///
        /// @param source the source array
        /// @param offset the stripe offset
        private void processStripe(byte[] source, int offset) {
            accumulator1 = round(accumulator1, ByteArrayAccess.readLongLittleEndian(source, offset));
            accumulator2 = round(accumulator2, ByteArrayAccess.readLongLittleEndian(source, offset + 8));
            accumulator3 = round(accumulator3, ByteArrayAccess.readLongLittleEndian(source, offset + 16));
            accumulator4 = round(accumulator4, ByteArrayAccess.readLongLittleEndian(source, offset + 24));
        }

        /// Processes one 32-byte stripe from a little-endian buffer view.
        ///
        /// @param source the source view
        private void processStripe(ByteBuffer source) {
            accumulator1 = round(accumulator1, source.getLong());
            accumulator2 = round(accumulator2, source.getLong());
            accumulator3 = round(accumulator3, source.getLong());
            accumulator4 = round(accumulator4, source.getLong());
        }

        /// Requires this computation to remain active.
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
