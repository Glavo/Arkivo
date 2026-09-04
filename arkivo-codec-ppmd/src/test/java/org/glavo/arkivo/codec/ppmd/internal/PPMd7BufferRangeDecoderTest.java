// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies incremental PPMd7 range-prefix parsing, suspended operations, and arithmetic validation.
@NotNullByDefault
final class PPMd7BufferRangeDecoderTest {
    /// Binary probability scale required by the PPMd7 range representation.
    private static final int BINARY_SCALE = 1 << 14;

    /// Verifies prefix fragmentation, attachment ownership, malformed prefixes, and initialization idempotence.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void validatesAttachmentAndRangePrefix() throws IOException {
        PPMd7BufferRangeDecoder decoder = new PPMd7BufferRangeDecoder();
        assertThrows(IllegalStateException.class, decoder::initialize);
        assertThrows(NullPointerException.class, () -> decoder.attach(null, false));

        decoder.attach(ByteBuffer.allocate(0), false);
        assertSame(
                PPMdInputUnavailableException.INSTANCE,
                assertThrows(PPMdInputUnavailableException.class, decoder::initialize)
        );
        assertThrows(
                IllegalStateException.class,
                () -> decoder.attach(ByteBuffer.allocate(0), false)
        );
        decoder.detach();

        decoder.reset();
        decoder.attach(ByteBuffer.wrap(new byte[]{0, 0}), true);
        EOFException truncated = assertThrows(EOFException.class, decoder::initialize);
        assertEquals("Truncated PPMd7 range-coded stream", truncated.getMessage());
        decoder.detach();

        decoder.reset();
        decoder.attach(ByteBuffer.wrap(new byte[]{1, 0, 0, 0, 0}), true);
        IOException malformedPrefix = assertThrows(IOException.class, decoder::initialize);
        assertEquals("Malformed PPMd7 range-code prefix", malformedPrefix.getMessage());
        decoder.detach();

        decoder.reset();
        decoder.attach(ByteBuffer.wrap(new byte[]{0, -1, -1, -1, -1}), true);
        IOException malformedCode = assertThrows(IOException.class, decoder::initialize);
        assertEquals("Malformed PPMd7 initial range code", malformedCode.getMessage());
        decoder.detach();

        decoder.reset();
        ByteBuffer validPrefix = rangePrefix(0L);
        decoder.attach(validPrefix, true);
        decoder.initialize();
        decoder.initialize();
        assertFalse(validPrefix.hasRemaining());
        decoder.detach();
    }

    /// Verifies cumulative intervals suspend at source exhaustion and replay only matching operations.
    @Test
    void suspendsAndReplaysGeneralIntervals() throws IOException {
        PPMd7BufferRangeDecoder decoder = initializedDecoder(0L);
        assertFailureMessage(() -> decoder.currentCount(0), "PPMd range scale must be positive");

        assertEquals(0, decoder.currentCount(256));
        assertEquals(0, decoder.currentCount(256));
        assertFailureMessage(
                () -> decoder.currentCount(255),
                "Resumed PPMd range scale does not match its suspended operation"
        );
        assertFailureMessage(() -> decoder.decode(-1, 1), "Invalid PPMd arithmetic interval");
        assertFailureMessage(() -> decoder.decode(0, 0), "Invalid PPMd arithmetic interval");
        assertFailureMessage(() -> decoder.decode(0, 257), "Invalid PPMd arithmetic interval");
        assertFailureMessage(
                () -> decoder.decodeBit(1, BINARY_SCALE),
                "Invalid PPMd binary arithmetic operation sequence"
        );

        decoder.attach(ByteBuffer.allocate(0), false);
        assertSame(
                PPMdInputUnavailableException.INSTANCE,
                assertThrows(PPMdInputUnavailableException.class, () -> decoder.decode(0, 1))
        );
        decoder.detach();

        ByteBuffer normalizationByte = ByteBuffer.wrap(new byte[]{0});
        decoder.attach(normalizationByte, false);
        assertFailureMessage(
                () -> decoder.currentCount(255),
                "Resumed PPMd range scale does not match its suspended operation"
        );
        assertEquals(0, decoder.currentCount(256));
        assertFalse(normalizationByte.hasRemaining());
        assertFailureMessage(
                () -> decoder.decode(1, 2),
                "Replayed PPMd interval does not match its suspended operation"
        );
        assertFailureMessage(
                () -> decoder.decode(0, 2),
                "Replayed PPMd interval does not match its suspended operation"
        );
        decoder.decode(0, 1);
        decoder.detach();

        assertFailureMessage(
                () -> decoder.decode(0, 1),
                "PPMd interval has no prepared cumulative count"
        );

        PPMd7BufferRangeDecoder corrupt = initializedDecoder(0xffff_fffeL);
        assertFailureMessage(
                () -> corrupt.currentCount(2),
                "Corrupt PPMd7 arithmetic interval"
        );
    }

    /// Verifies binary intervals suspend and resume with exact parameters and select both branches.
    @Test
    void suspendsAndResumesBinaryIntervals() throws IOException {
        PPMd7BufferRangeDecoder decoder = initializedDecoder(0L);
        assertFailureMessage(
                () -> decoder.decodeBit(0, BINARY_SCALE),
                "Invalid PPMd7 binary arithmetic interval"
        );
        assertFailureMessage(
                () -> decoder.decodeBit(BINARY_SCALE, BINARY_SCALE),
                "Invalid PPMd7 binary arithmetic interval"
        );
        assertFailureMessage(
                () -> decoder.decodeBit(1, BINARY_SCALE - 1),
                "Invalid PPMd7 binary arithmetic interval"
        );

        decoder.attach(ByteBuffer.allocate(0), false);
        assertSame(
                PPMdInputUnavailableException.INSTANCE,
                assertThrows(
                        PPMdInputUnavailableException.class,
                        () -> decoder.decodeBit(1, BINARY_SCALE)
                )
        );
        decoder.detach();

        assertFailureMessage(
                () -> decoder.currentCount(1),
                "Invalid PPMd arithmetic operation sequence"
        );

        ByteBuffer normalizationByte = ByteBuffer.wrap(new byte[]{0});
        decoder.attach(normalizationByte, false);
        assertFailureMessage(
                () -> decoder.decodeBit(2, BINARY_SCALE),
                "Resumed PPMd binary interval does not match its suspended operation"
        );
        assertFalse(decoder.decodeBit(1, BINARY_SCALE));
        assertFalse(normalizationByte.hasRemaining());
        decoder.detach();

        PPMd7BufferRangeDecoder oneDecoder = initializedDecoder(0x8000_0000L);
        assertTrue(oneDecoder.decodeBit(BINARY_SCALE / 2, BINARY_SCALE));
    }

    /// Verifies the general range-decoder default method commits the selected binary interval.
    @Test
    void defaultBinaryDecoderCommitsSelectedInterval() throws IOException {
        RecordingRangeDecoder decoder = new RecordingRangeDecoder(2);
        assertFalse(decoder.decodeBit(3, 10));
        assertEquals(0, decoder.lowCount());
        assertEquals(3, decoder.highCount());

        decoder.setCount(7);
        assertTrue(decoder.decodeBit(3, 10));
        assertEquals(3, decoder.lowCount());
        assertEquals(10, decoder.highCount());
    }

    /// Returns a decoder initialized from one unsigned 32-bit arithmetic code.
    private static PPMd7BufferRangeDecoder initializedDecoder(long code) throws IOException {
        PPMd7BufferRangeDecoder decoder = new PPMd7BufferRangeDecoder();
        decoder.attach(rangePrefix(code), true);
        decoder.initialize();
        decoder.detach();
        return decoder;
    }

    /// Returns the five-byte PPMd7 prefix for one unsigned 32-bit arithmetic code.
    private static ByteBuffer rangePrefix(long code) {
        if (code < 0L || code > 0xffff_ffffL) {
            throw new IllegalArgumentException("code is not an unsigned 32-bit value: " + code);
        }
        return ByteBuffer.wrap(new byte[]{
                0,
                (byte) (code >>> 24),
                (byte) (code >>> 16),
                (byte) (code >>> 8),
                (byte) code
        });
    }

    /// Asserts an arithmetic operation fails with one exact diagnostic.
    private static void assertFailureMessage(ThrowingOperation operation, String expectedMessage) {
        IOException failure = assertThrows(IOException.class, operation::run);
        assertEquals(expectedMessage, failure.getMessage());
    }

    /// Runs one checked arithmetic operation for failure assertions.
    @FunctionalInterface
    @NotNullByDefault
    private interface ThrowingOperation {
        /// Runs the operation.
        void run() throws IOException;
    }

    /// Supplies deterministic cumulative counts and records committed intervals.
    @NotNullByDefault
    private static final class RecordingRangeDecoder implements PPMdRangeDecoder {
        /// Cumulative count returned by the next query.
        private int count;

        /// Most recently committed inclusive lower bound.
        private int lowCount;

        /// Most recently committed exclusive upper bound.
        private int highCount;

        /// Creates a decoder returning the requested initial cumulative count.
        private RecordingRangeDecoder(int count) {
            this.count = count;
        }

        /// Returns the configured cumulative count.
        @Override
        public int currentCount(int scale) {
            return count;
        }

        /// Records one committed interval.
        @Override
        public void decode(int lowCount, int highCount) {
            this.lowCount = lowCount;
            this.highCount = highCount;
        }

        /// Replaces the cumulative count returned by the next query.
        private void setCount(int count) {
            this.count = count;
        }

        /// Returns the most recently committed lower bound.
        private int lowCount() {
            return lowCount;
        }

        /// Returns the most recently committed upper bound.
        private int highCount() {
            return highCount;
        }
    }
}
