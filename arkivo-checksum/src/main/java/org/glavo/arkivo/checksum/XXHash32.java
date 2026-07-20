// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

/// Describes an immutable seeded XXH32 algorithm.
///
/// [#DEFAULT] uses the zero seed required by standard LZ4 frame checksums. Each accumulator retains at most one partial
/// 16-byte stripe and never retains caller-provided storage.
@NotNullByDefault
public final class XXHash32 implements ChecksumAlgorithm.Width32 {
    /// The conventional zero-seeded XXH32 algorithm.
    public static final XXHash32 DEFAULT = new XXHash32(0);

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

    /// The configured seed bit pattern.
    private final int seed;

    /// Creates a seeded algorithm.
    ///
    /// @param seed the seed bit pattern
    public XXHash32(int seed) {
        this.seed = seed;
    }

    /// Returns the configured seed bit pattern.
    ///
    /// @return the seed
    public int seed() {
        return seed;
    }

    /// Returns an immutable algorithm using the requested seed.
    ///
    /// @param seed the seed bit pattern
    /// @return this instance when unchanged, otherwise a new algorithm
    public XXHash32 withSeed(int seed) {
        return this.seed == seed ? this : new XXHash32(seed);
    }

    /// Returns the conventional algorithm name.
    ///
    /// @return `XXH32`
    @Override
    public String name() {
        return "XXH32";
    }

    /// Creates fresh active XXH32 state.
    ///
    /// @return an independent accumulator
    @Override
    public ChecksumAccumulator.Width32 newAccumulator() {
        return new Accumulator(this);
    }

    /// Compares configured seed values.
    ///
    /// @param other the object to compare
    /// @return whether `other` describes the same seeded algorithm
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof XXHash32 hash && seed == hash.seed;
    }

    /// Returns the seed hash code.
    ///
    /// @return the configured seed
    @Override
    public int hashCode() {
        return seed;
    }

    /// Returns a diagnostic seeded-algorithm description.
    ///
    /// @return the algorithm name and unsigned hexadecimal seed
    @Override
    public String toString() {
        return "XXH32[seed=0x" + Integer.toUnsignedString(seed, 16) + "]";
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
        /// The immutable seeded algorithm.
        private final XXHash32 algorithm;

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

        /// Creates initial state for one seeded algorithm.
        ///
        /// @param algorithm the immutable seeded algorithm
        private Accumulator(XXHash32 algorithm) {
            this.algorithm = algorithm;
            reset();
        }

        /// Returns the creating algorithm.
        ///
        /// @return the immutable seeded algorithm
        @Override
        public ChecksumAlgorithm.Width32 algorithm() {
            return algorithm;
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
            int seed = algorithm.seed;
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
                hash = algorithm.seed + PRIME_5;
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

        /// Processes one 16-byte stripe from a little-endian buffer view.
        ///
        /// @param source the source view
        private void processStripe(ByteBuffer source) {
            accumulator1 = round(accumulator1, source.getInt());
            accumulator2 = round(accumulator2, source.getInt());
            accumulator3 = round(accumulator3, source.getInt());
            accumulator4 = round(accumulator4, source.getInt());
        }

        /// Requires this computation to remain active.
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
