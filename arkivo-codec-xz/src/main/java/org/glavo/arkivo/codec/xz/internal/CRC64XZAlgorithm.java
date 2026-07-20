// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumAlgorithm;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Implements the reflected CRC-64/XZ model.
@NotNullByDefault
final class CRC64XZAlgorithm implements ChecksumAlgorithm.Width64 {
    /// The shared immutable algorithm.
    static final CRC64XZAlgorithm INSTANCE = new CRC64XZAlgorithm();

    /// The reflected CRC polynomial.
    private static final long POLYNOMIAL = 0xc96c_5795_d787_0f42L;

    /// The byte-at-a-time reflected transition table.
    private static final long @Unmodifiable [] TABLE = createTable();

    /// Creates the shared algorithm.
    private CRC64XZAlgorithm() {
    }

    /// Returns the catalogue name.
    ///
    /// @return `CRC-64/XZ`
    @Override
    public String name() {
        return "CRC-64/XZ";
    }

    /// Creates fresh active CRC state.
    ///
    /// @return an independent accumulator
    @Override
    public ChecksumAccumulator.Width64 newAccumulator() {
        return new Accumulator();
    }

    /// Returns the catalogue name.
    ///
    /// @return `CRC-64/XZ`
    @Override
    public String toString() {
        return name();
    }

    /// Creates the reflected transition table.
    ///
    /// @return the immutable table
    private static long @Unmodifiable [] createTable() {
        long[] table = new long[256];
        for (int value = 0; value < table.length; value++) {
            long remainder = value;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                remainder = (remainder & 1L) != 0L
                        ? remainder >>> 1 ^ POLYNOMIAL
                        : remainder >>> 1;
            }
            table[value] = remainder;
        }
        return table;
    }

    /// Holds one CRC-64/XZ computation.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator.Width64 {
        /// The current complemented CRC state.
        private long state = -1L;

        /// The cached completed result.
        private long result;

        /// Whether this computation has finished.
        private boolean finished;

        /// Creates initial CRC state.
        private Accumulator() {
        }

        /// Returns the shared algorithm.
        ///
        /// @return [CRC64XZAlgorithm#INSTANCE]
        @Override
        public ChecksumAlgorithm.Width64 algorithm() {
            return INSTANCE;
        }

        /// Adds one byte.
        ///
        /// @param value the byte to add
        @Override
        public void update(byte value) {
            requireActive();
            updateUnchecked(Byte.toUnsignedInt(value));
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
            int end = offset + length;
            for (int index = offset; index < end; index++) {
                updateUnchecked(Byte.toUnsignedInt(source[index]));
            }
        }

        /// Adds all remaining buffer bytes.
        ///
        /// @param source the source buffer
        @Override
        public void update(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            requireActive();
            while (source.hasRemaining()) {
                updateUnchecked(Byte.toUnsignedInt(source.get()));
            }
        }

        /// Completes and returns the CRC bit pattern.
        ///
        /// @return the raw 64-bit result
        @Override
        public long finishLong() {
            if (!finished) {
                result = ~state;
                finished = true;
            }
            return result;
        }

        /// Restores the model's all-ones initial value.
        @Override
        public void reset() {
            state = -1L;
            result = 0L;
            finished = false;
        }

        /// Applies one unsigned byte without checking lifecycle state.
        ///
        /// @param value a value from zero through 255
        private void updateUnchecked(int value) {
            state = TABLE[(value ^ (int) state) & 0xff] ^ state >>> Byte.SIZE;
        }

        /// Requires this computation to remain active.
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
