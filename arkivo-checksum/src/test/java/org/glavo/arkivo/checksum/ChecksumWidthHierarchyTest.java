// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.checksum;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies primitive checksum width interfaces and their inherited one-shot operations.
@NotNullByDefault
final class ChecksumWidthHierarchyTest {
    /// Test algorithm exposing the default exact-width 64-bit behavior.
    private static final ChecksumAlgorithm.Width64 ALGORITHM = new TestWidth64Algorithm();

    /// Verifies 64-bit width metadata and independent primitive accumulators.
    @Test
    void exposesExactWidthMetadataAndIndependentAccumulators() {
        assertEquals("test-width-64", ALGORITHM.name());
        assertEquals(Long.SIZE, ALGORITHM.bitSize());
        assertEquals(Long.BYTES, ALGORITHM.checksumSize());

        ChecksumAccumulator.Width64 first = ALGORITHM.newAccumulator();
        ChecksumAccumulator.Width64 second = ALGORITHM.newAccumulator();
        assertNotSame(first, second);
        assertSame(ALGORITHM, first.algorithm());
        assertSame(ALGORITHM, second.algorithm());
    }

    /// Verifies every inherited primitive one-shot overload preserves the complete 64-bit result pattern.
    @Test
    void computesLongFromArraysRangesAndBuffers() {
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        long completeExpected = expectedValue(padded, 0, padded.length);
        long rangeExpected = expectedValue(padded, 2, 9);

        assertEquals(completeExpected, ALGORITHM.computeLong(padded));
        assertEquals(rangeExpected, ALGORITHM.computeLong(padded, 2, 9));
        assertEquals(ChecksumValue.ofLong(completeExpected, Long.BYTES), ALGORITHM.compute(padded));

        ByteBuffer direct = ByteBuffer.allocateDirect(padded.length);
        direct.put(padded).flip().position(2).limit(11);
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        assertEquals(rangeExpected, ALGORITHM.computeLong(readOnly));
        assertEquals(readOnly.limit(), readOnly.position());
        assertEquals(11, direct.limit());
        assertEquals(2, direct.position());
    }

    /// Verifies inherited primitive operations reject null inputs and invalid array ranges.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesPrimitiveComputationArguments() {
        byte[] source = {1, 2, 3};

        assertThrows(NullPointerException.class, () -> ALGORITHM.computeLong((byte[]) null));
        assertThrows(NullPointerException.class, () -> ALGORITHM.computeLong((ByteBuffer) null));
        assertThrows(IndexOutOfBoundsException.class, () -> ALGORITHM.computeLong(source, -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> ALGORITHM.computeLong(source, 1, 3));
    }

    /// Computes the deterministic test checksum for one byte-array range.
    private static long expectedValue(byte[] source, int offset, int length) {
        long value = Long.MIN_VALUE;
        for (int index = offset; index < offset + length; index++) {
            value += Byte.toUnsignedInt(source[index]);
        }
        return value;
    }

    /// Implements an exact-width 64-bit checksum algorithm for default-method testing.
    @NotNullByDefault
    private static final class TestWidth64Algorithm implements ChecksumAlgorithm.Width64 {
        /// Creates the immutable test algorithm.
        private TestWidth64Algorithm() {
        }

        /// Returns the diagnostic algorithm name.
        @Override
        public String name() {
            return "test-width-64";
        }

        /// Creates an independent primitive accumulator.
        @Override
        public ChecksumAccumulator.Width64 newAccumulator() {
            return new TestWidth64Accumulator(this);
        }
    }

    /// Accumulates unsigned byte values starting from a negative 64-bit pattern.
    @NotNullByDefault
    private static final class TestWidth64Accumulator implements ChecksumAccumulator.Width64 {
        /// Algorithm that created this accumulator.
        private final ChecksumAlgorithm.Width64 algorithm;

        /// Current primitive checksum value.
        private long value = Long.MIN_VALUE;

        /// Whether this accumulator has been finished.
        private boolean finished;

        /// Creates an active accumulator for the given algorithm.
        private TestWidth64Accumulator(ChecksumAlgorithm.Width64 algorithm) {
            this.algorithm = algorithm;
        }

        /// Returns the creating algorithm.
        @Override
        public ChecksumAlgorithm.Width64 algorithm() {
            return algorithm;
        }

        /// Adds one unsigned byte value.
        @Override
        public void update(byte source) {
            ensureActive();
            value += Byte.toUnsignedInt(source);
        }

        /// Adds all unsigned byte values in the requested range.
        @Override
        public void update(byte[] source, int offset, int length) {
            Objects.requireNonNull(source, "source");
            Objects.checkFromIndexSize(offset, length, source.length);
            ensureActive();
            for (int index = offset; index < offset + length; index++) {
                value += Byte.toUnsignedInt(source[index]);
            }
        }

        /// Consumes and adds every remaining byte from the source buffer.
        @Override
        public void update(ByteBuffer source) {
            Objects.requireNonNull(source, "source");
            ensureActive();
            while (source.hasRemaining()) {
                value += Byte.toUnsignedInt(source.get());
            }
        }

        /// Finishes and returns the complete 64-bit checksum pattern.
        @Override
        public long finishLong() {
            finished = true;
            return value;
        }

        /// Resets this accumulator to its initial active state.
        @Override
        public void reset() {
            value = Long.MIN_VALUE;
            finished = false;
        }

        /// Requires this accumulator to remain active.
        private void ensureActive() {
            if (finished) {
                throw new IllegalStateException("Test checksum accumulator is finished");
            }
        }
    }
}
