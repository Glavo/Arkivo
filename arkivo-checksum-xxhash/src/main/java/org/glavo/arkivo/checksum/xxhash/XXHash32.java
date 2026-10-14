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

/// Computes XXH32 checksums and creates independent incremental computations.
///
/// The default seed is zero, as required by LZ4 frame checksums. Each accumulator retains its seed across resets,
/// buffers at most one partial 16-byte stripe, and never retains caller buffers.
@NotNullByDefault
public final class XXHash32 {
    /// First XXH32 prime.
    private static final int PRIME_1 = 0x9e37_79b1;

    /// Second XXH32 prime.
    private static final int PRIME_2 = 0x85eb_ca77;

    /// Third XXH32 prime.
    private static final int PRIME_3 = 0xc2b2_ae3d;

    /// Fourth XXH32 prime.
    private static final int PRIME_4 = 0x27d4_eb2f;

    /// Fifth XXH32 prime.
    private static final int PRIME_5 = 0x1656_67b1;

    /// Prevents construction.
    private XXHash32() {
    }

    /// Creates an active zero-seeded computation.
    ///
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 newAccumulator() {
        return newAccumulator(0);
    }

    /// Creates an active seeded computation.
    ///
    /// @param seed the seed bit pattern, restored by every reset
    /// @return an independent accumulator
    public static ChecksumAccumulator.Width32 newAccumulator(int seed) {
        return new Accumulator(seed);
    }

    /// Computes the 32-bit checksum using a zero seed.
    ///
    /// @param source the complete input
    /// @return the raw 32-bit result
    public static int computeInt(byte[] source) {
        return computeInt(source, 0);
    }

    /// Computes the 32-bit checksum using the supplied seed.
    ///
    /// @param source the complete input
    /// @param seed the seed bit pattern
    /// @return the raw 32-bit result
    public static int computeInt(byte[] source, int seed) {
        ChecksumAccumulator.Width32 accumulator = newAccumulator(seed);
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Computes the 32-bit checksum using a zero seed.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @return the raw 32-bit result
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static int computeInt(byte[] source, int offset, int length) {
        return computeInt(source, offset, length, 0);
    }

    /// Computes the 32-bit checksum using the supplied seed.
    ///
    /// @param source the source array
    /// @param offset the first byte index
    /// @param length the number of bytes
    /// @param seed the seed bit pattern
    /// @return the raw 32-bit result
    /// @throws IndexOutOfBoundsException if the range is outside the array
    public static int computeInt(byte[] source, int offset, int length, int seed) {
        ChecksumAccumulator.Width32 accumulator = newAccumulator(seed);
        accumulator.update(source, offset, length);
        return accumulator.finishInt();
    }

    /// Computes the 32-bit checksum using a zero seed.
    ///
    /// @param source the input buffer, consumed through its limit without changing its limit or byte order
    /// @return the raw 32-bit result
    public static int computeInt(ByteBuffer source) {
        return computeInt(source, 0);
    }

    /// Computes the 32-bit checksum using the supplied seed.
    ///
    /// @param source the input buffer, consumed through its limit without changing its limit or byte order
    /// @param seed the seed bit pattern
    /// @return the raw 32-bit result
    public static int computeInt(ByteBuffer source, int seed) {
        ChecksumAccumulator.Width32 accumulator = newAccumulator(seed);
        accumulator.update(source);
        return accumulator.finishInt();
    }

    /// Mixes one four-byte lane into a large-input accumulator.
    ///
    /// @param accumulator the current stripe accumulator
    /// @param lane        the next little-endian lane
    /// @return the updated accumulator
    private static int round(int accumulator, int lane) {
        accumulator += lane * PRIME_2;
        accumulator = Integer.rotateLeft(accumulator, 13);
        return accumulator * PRIME_1;
    }

    /// Holds one incremental XXH32 computation.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator.Width32 {
        /// The configured seed bit pattern.
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

        /// Total input length modulo 2^64.
        private long totalLength;

        /// Number of meaningful bytes in [#memory].
        private int memorySize;

        /// The cached completed result.
        private int result;

        /// Whether this computation has finished.
        private boolean finished;

        /// Creates active state retaining the supplied seed.
        ///
        /// @param seed the seed bit pattern
        private Accumulator(int seed) {
            this.seed = seed;
            reset();
        }

        @Override
        public String name() {
            return "XXH32";
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
            int lane1 = accumulator1;
            int lane2 = accumulator2;
            int lane3 = accumulator3;
            int lane4 = accumulator4;
            try {
                while (view.remaining() >= memory.length) {
                    long first = view.getLong();
                    lane1 = round(lane1, (int) first);
                    lane2 = round(lane2, (int) (first >>> 32));
                    long second = view.getLong();
                    lane3 = round(lane3, (int) second);
                    lane4 = round(lane4, (int) (second >>> 32));
                }
            } finally {
                accumulator1 = lane1;
                accumulator2 = lane2;
                accumulator3 = lane3;
                accumulator4 = lane4;
            }
            memorySize = view.remaining();
            view.get(memory, 0, memorySize);
            source.position(source.limit());
        }

        /// Completes and returns the raw XXH32 result.
        ///
        /// @return the raw 32-bit pattern
        @Override
        public int finishInt() {
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
            result = 0;
            finished = false;
            Arrays.fill(memory, (byte) 0);
        }

        /// Calculates the current finalized result without changing stripe state.
        ///
        /// @return the raw XXH32 result
        private int calculateResult() {
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
                hash += ByteArrayAccess.readIntLittleEndian(memory, position) * PRIME_3;
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
            return hash;
        }

        /// Processes one array-backed 16-byte stripe.
        ///
        /// @param source the source array
        /// @param offset the stripe offset
        private void processStripe(byte[] source, int offset) {
            accumulator1 = round(accumulator1, ByteArrayAccess.readIntLittleEndian(source, offset));
            accumulator2 = round(accumulator2, ByteArrayAccess.readIntLittleEndian(source, offset + 4));
            accumulator3 = round(accumulator3, ByteArrayAccess.readIntLittleEndian(source, offset + 8));
            accumulator4 = round(accumulator4, ByteArrayAccess.readIntLittleEndian(source, offset + 12));
        }

        /// Requires this computation to remain active.
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
