// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.ChecksumAlgorithm;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Implements the non-reflected CRC-32/BZIP2 model.
@NotNullByDefault
final class BZip2CRC32Algorithm implements ChecksumAlgorithm.Width32 {
    /// The shared immutable algorithm.
    static final BZip2CRC32Algorithm INSTANCE = new BZip2CRC32Algorithm();

    /// The normal-form CRC polynomial without its implicit top bit.
    private static final int POLYNOMIAL = 0x04c1_1db7;

    /// The byte-at-a-time normal-form transition table.
    private static final int @Unmodifiable [] TABLE = createTable();

    /// Creates the shared algorithm.
    private BZip2CRC32Algorithm() {
    }

    /// Returns the catalogue name.
    ///
    /// @return `CRC-32/BZIP2`
    @Override
    public String name() {
        return "CRC-32/BZIP2";
    }

    /// Creates fresh active CRC state.
    ///
    /// @return an independent accumulator
    @Override
    public ChecksumAccumulator.Width32 newAccumulator() {
        return new Accumulator();
    }

    /// Returns the catalogue name.
    ///
    /// @return `CRC-32/BZIP2`
    @Override
    public String toString() {
        return name();
    }

    /// Creates the normal-form transition table.
    ///
    /// @return the immutable table
    private static int @Unmodifiable [] createTable() {
        int[] table = new int[256];
        for (int value = 0; value < table.length; value++) {
            int remainder = value << 24;
            for (int bit = 0; bit < Byte.SIZE; bit++) {
                remainder = remainder << 1 ^ ((remainder & 0x8000_0000) != 0 ? POLYNOMIAL : 0);
            }
            table[value] = remainder;
        }
        return table;
    }

    /// Holds one CRC-32/BZIP2 computation.
    @NotNullByDefault
    private static final class Accumulator implements ChecksumAccumulator.Width32 {
        /// The current uncomplemented CRC state.
        private int state = -1;

        /// The cached completed result.
        private int result;

        /// Whether this computation has finished.
        private boolean finished;

        /// Creates initial CRC state.
        private Accumulator() {
        }

        /// Returns the shared algorithm.
        ///
        /// @return [BZip2CRC32Algorithm#INSTANCE]
        @Override
        public ChecksumAlgorithm.Width32 algorithm() {
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

        /// Completes and returns the complemented CRC.
        ///
        /// @return the raw 32-bit result
        @Override
        public int finishInt() {
            if (!finished) {
                result = ~state;
                finished = true;
            }
            return result;
        }

        /// Restores the model's all-ones initial value.
        @Override
        public void reset() {
            state = -1;
            result = 0;
            finished = false;
        }

        /// Applies one unsigned byte without checking lifecycle state.
        ///
        /// @param value a value from zero through 255
        private void updateUnchecked(int value) {
            state = state << Byte.SIZE ^ TABLE[(state >>> 24 ^ value) & 0xff];
        }

        /// Requires this computation to remain active.
        private void requireActive() {
            if (finished) {
                throw new IllegalStateException("Checksum computation is finished");
            }
        }
    }
}
