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
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies primitive width defaults and unsigned result carriers.
@NotNullByDefault
final class ChecksumWidthHierarchyTest {
    /// Verifies width metadata and independent computation state.
    @Test
    void exposesExactWidthMetadataAndIndependentAccumulators() {
        var first = new TestWidth64Accumulator();
        var second = new TestWidth64Accumulator();
        assertEquals("test-width-64", first.name());
        assertEquals(64, first.bitSize());
        assertEquals(8, first.checksumSize());
        assertNotSame(first, second);
        first.update((byte) 1);
        assertEquals(Long.MIN_VALUE + 1, first.finishLong());
        assertEquals(Long.MIN_VALUE, second.finishLong());
    }

    /// Verifies array, range and buffer updates preserve the entire 64-bit result.
    @Test
    void accumulatesArraysRangesAndBuffers() {
        byte[] padded = "xx123456789yy".getBytes(StandardCharsets.US_ASCII);
        var accumulator = new TestWidth64Accumulator();
        accumulator.update(padded, 2, 9);
        long expected = accumulator.finishLong();
        assertEquals(ChecksumValue.ofLong(expected, Long.BYTES), accumulator.finish());
        accumulator.reset();
        ByteBuffer direct = ByteBuffer.allocateDirect(padded.length);
        direct.put(padded).flip().position(2).limit(11);
        ByteBuffer readOnly = direct.asReadOnlyBuffer();
        accumulator.update(readOnly);
        assertEquals(expected, accumulator.finishLong());
        assertEquals(11, readOnly.position());
        assertEquals(2, direct.position());
        accumulator.reset();
        assertThrows(IndexOutOfBoundsException.class, () -> accumulator.update(padded, -1, 1));
        assertEquals(Long.MIN_VALUE, accumulator.finishLong());
    }

    /// Accumulates unsigned byte values starting from a negative 64-bit pattern.
    @NotNullByDefault
    private static final class TestWidth64Accumulator implements ChecksumAccumulator.Width64 {
        /// Current primitive checksum value.
        private long value = Long.MIN_VALUE;

        /// Whether this accumulator has been finished.
        private boolean finished;

        /// Creates an active test computation.
        private TestWidth64Accumulator() {
        }

        @Override
        public String name() {
            return "test-width-64";
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
