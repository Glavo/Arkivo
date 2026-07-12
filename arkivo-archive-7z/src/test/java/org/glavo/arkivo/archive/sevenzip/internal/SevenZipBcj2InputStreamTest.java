// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the in-memory 7z BCJ2 stream merger.
@NotNullByDefault
public final class SevenZipBcj2InputStreamTest {
    /// A valid range stream whose first decision is zero.
    private static final byte @Unmodifiable [] FIRST_DECISION_ZERO = {0, 0, 0, 0, 0};

    /// A valid range stream whose first decision is one.
    private static final byte @Unmodifiable [] FIRST_DECISION_ONE = {0, (byte) 0x80, 0, 0, 0};

    /// Verifies that ordinary bytes pass through and bulk reads stop at the output limit.
    @Test
    public void readsMainStreamWithoutBranches() throws IOException {
        byte[] main = {0x01, 0x0f, 0x7f, (byte) 0xe7, (byte) 0xea, 0x55};
        byte[] buffer = new byte[main.length + 4];

        try (SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ShortReadInputStream(main),
                new ShortReadInputStream(new byte[0]),
                new ShortReadInputStream(new byte[0]),
                new ShortReadInputStream(FIRST_DECISION_ZERO),
                main.length
        )) {
            assertEquals(main.length, input.read(buffer, 2, main.length + 2));
            assertArrayEquals(main, java.util.Arrays.copyOfRange(buffer, 2, 2 + main.length));
            assertEquals(0, input.read(buffer, 0, 0));
            assertEquals(-1, input.read());
        }
    }

    /// Verifies CALL conversion with a range decision of one and short source reads.
    @Test
    public void convertsCallAddress() throws IOException {
        assertDecodedBranch(
                new byte[]{0x41, (byte) 0xe8},
                new byte[]{0x12, 0x34, 0x56, 0x78},
                new byte[0],
                new byte[]{0x41, (byte) 0xe8, 0x72, 0x56, 0x34, 0x12}
        );
    }

    /// Verifies E9 JUMP conversion and unsigned 32-bit address wraparound.
    @Test
    public void convertsE9JumpAddressWithWraparound() throws IOException {
        assertDecodedBranch(
                new byte[]{(byte) 0xe9},
                new byte[0],
                new byte[]{0, 0, 0, 1},
                new byte[]{(byte) 0xe9, (byte) 0xfc, (byte) 0xff, (byte) 0xff, (byte) 0xff}
        );
    }

    /// Verifies two-byte 0F 8x JUMP marker conversion.
    @Test
    public void convertsConditionalJumpAddress() throws IOException {
        assertDecodedBranch(
                new byte[]{0x0f, (byte) 0x80},
                new byte[0],
                new byte[]{0x10, 0x20, 0x30, 0x40},
                new byte[]{0x0f, (byte) 0x80, 0x3a, 0x30, 0x20, 0x10}
        );
    }

    /// Verifies adaptive probability selection across repeated E9, E8, and odd conditional-jump markers.
    @Test
    public void adaptsIndependentMarkerProbabilityModels() throws IOException {
        TestRangeEncoder rangeEncoder = new TestRangeEncoder();
        rangeEncoder.encode(1, 0);
        rangeEncoder.encode(1, 1);
        rangeEncoder.encode(2, 1);
        rangeEncoder.encode(0, 0);

        byte[] main = {(byte) 0xe9, (byte) 0xe9, (byte) 0xe8, 0x0f, (byte) 0x81};
        byte[] call = {0x00, 0x00, 0x00, 0x0c};
        byte[] jump = {0x00, 0x00, 0x00, 0x06};
        byte[] expected = {
                (byte) 0xe9,
                (byte) 0xe9, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xe8, 0x01, 0x00, 0x00, 0x00,
                0x0f, (byte) 0x81
        };

        try (SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ByteArrayInputStream(main),
                new ByteArrayInputStream(call),
                new ByteArrayInputStream(jump),
                new ByteArrayInputStream(rangeEncoder.finish()),
                expected.length
        )) {
            assertArrayEquals(expected, input.readAllBytes());
        }
    }

    /// Verifies that a truncated range decoder header is rejected.
    @Test
    public void rejectsTruncatedRangeStream() {
        SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ByteArrayInputStream(new byte[]{1}),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]),
                new ShortReadInputStream(new byte[]{0, 0, 0, 0}),
                1
        );

        EOFException exception = assertThrows(EOFException.class, input::read);
        assertEquals("Unexpected end of 7z BCJ2 range stream", exception.getMessage());
    }

    /// Verifies that a truncated main stream is rejected before the output limit.
    @Test
    public void rejectsTruncatedMainStream() throws IOException {
        SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ByteArrayInputStream(new byte[]{1}),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(FIRST_DECISION_ZERO),
                2
        );

        assertEquals(1, input.read());
        EOFException exception = assertThrows(EOFException.class, input::read);
        assertEquals("Unexpected end of 7z BCJ2 main stream", exception.getMessage());
    }

    /// Verifies that a selected but truncated CALL address is rejected.
    @Test
    public void rejectsTruncatedCallStream() throws IOException {
        SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ByteArrayInputStream(new byte[]{(byte) 0xe8}),
                new ShortReadInputStream(new byte[]{1, 2, 3}),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(FIRST_DECISION_ONE),
                5
        );

        assertEquals(0xe8, input.read());
        EOFException exception = assertThrows(EOFException.class, input::read);
        assertEquals("Unexpected end of 7z BCJ2 CALL stream", exception.getMessage());
    }

    /// Verifies that close attempts every input and suppresses failures after the first.
    @Test
    public void closesAllInputsAndSuppressesLaterFailures() throws IOException {
        CloseFailingInputStream main = new CloseFailingInputStream("main close failed");
        CloseFailingInputStream call = new CloseFailingInputStream("call close failed");
        CloseFailingInputStream jump = new CloseFailingInputStream("jump close failed");
        CloseFailingInputStream range = new CloseFailingInputStream("range close failed");
        SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(main, call, jump, range, 0);

        IOException exception = assertThrows(IOException.class, input::close);

        assertEquals("main close failed", exception.getMessage());
        assertEquals(3, exception.getSuppressed().length);
        assertEquals("call close failed", exception.getSuppressed()[0].getMessage());
        assertEquals("jump close failed", exception.getSuppressed()[1].getMessage());
        assertEquals("range close failed", exception.getSuppressed()[2].getMessage());
        assertEquals(1, main.closeCount());
        assertEquals(1, call.closeCount());
        assertEquals(1, jump.closeCount());
        assertEquals(1, range.closeCount());
        assertThrows(IOException.class, input::read);

        input.close();
        assertEquals(1, main.closeCount());
        assertEquals(1, call.closeCount());
        assertEquals(1, jump.closeCount());
        assertEquals(1, range.closeCount());
    }

    /// Decodes one selected branch using one-byte source reads and verifies the full result.
    private static void assertDecodedBranch(byte[] main, byte[] call, byte[] jump, byte[] expected) throws IOException {
        try (SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ShortReadInputStream(main),
                new ShortReadInputStream(call),
                new ShortReadInputStream(jump),
                new ShortReadInputStream(FIRST_DECISION_ONE),
                expected.length
        )) {
            byte[] actual = new byte[expected.length];
            assertEquals(expected.length, input.read(actual));
            assertArrayEquals(expected, actual);
            assertEquals(-1, input.read(actual));
        }
    }

    /// Provides an in-memory stream whose bulk reads return at most one byte.
    @NotNullByDefault
    private static final class ShortReadInputStream extends InputStream {
        /// The backing stream.
        private final ByteArrayInputStream input;

        /// Creates a short-reading stream over the given bytes.
        private ShortReadInputStream(byte[] bytes) {
            input = new ByteArrayInputStream(Objects.requireNonNull(bytes, "bytes"));
        }

        /// Reads one byte.
        @Override
        public int read() {
            return input.read();
        }

        /// Reads at most one byte into the target array.
        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            return input.read(buffer, offset, Math.min(length, 1));
        }

        /// Closes the backing stream.
        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    /// Provides an empty input stream that fails whenever it is closed.
    @NotNullByDefault
    private static final class CloseFailingInputStream extends InputStream {
        /// The close failure message.
        private final String failureMessage;

        /// The number of close attempts.
        private int closeCount;

        /// Creates a close-failing stream with the given message.
        private CloseFailingInputStream(String failureMessage) {
            this.failureMessage = Objects.requireNonNull(failureMessage, "failureMessage");
        }

        /// Reports end of stream.
        @Override
        public int read() {
            return -1;
        }

        /// Records the close attempt and fails.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw new IOException(failureMessage);
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Encodes a short BCJ2 range-decision sequence for interoperability tests.
    @NotNullByDefault
    private static final class TestRangeEncoder {
        /// The range encoder normalization threshold.
        private static final long TOP_VALUE = 1L << 24;

        /// The encoded bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// The adaptive probability models.
        private final int[] probabilities = new int[258];

        /// The pending low value including carry bits.
        private long low;

        /// The current unsigned 32-bit range.
        private long range = 0xffff_ffffL;

        /// The delayed output byte.
        private int cache;

        /// The number of delayed bytes sharing the pending carry.
        private long cacheSize = 1;

        /// Creates a range encoder with neutral BCJ2 probabilities.
        private TestRangeEncoder() {
            java.util.Arrays.fill(probabilities, 1 << 10);
        }

        /// Encodes one binary decision using the given BCJ2 probability slot.
        private void encode(int probabilityIndex, int bit) {
            int probability = probabilities[probabilityIndex];
            long bound = (range >>> 11) * probability;
            if (bit == 0) {
                range = bound;
                probabilities[probabilityIndex] = probability + (((1 << 11) - probability) >>> 5);
            } else {
                low += bound;
                range -= bound;
                probabilities[probabilityIndex] = probability - (probability >>> 5);
            }
            while (range < TOP_VALUE) {
                range <<= 8;
                shiftLow();
            }
        }

        /// Flushes the final range state and returns the encoded bytes.
        private byte[] finish() {
            for (int index = 0; index < 5; index++) {
                shiftLow();
            }
            return output.toByteArray();
        }

        /// Emits stabilized high bytes while carrying into delayed `0xff` bytes when needed.
        private void shiftLow() {
            long low32 = low & 0xffff_ffffL;
            long high = low >>> 32;
            if (low32 < 0xff00_0000L || high != 0) {
                int value = cache;
                do {
                    output.write((value + (int) high) & 0xff);
                    value = 0xff;
                } while (--cacheSize != 0);
                cache = (int) (low32 >>> 24);
            }
            cacheSize++;
            low = (low32 & 0x00ff_ffffL) << 8;
        }
    }
}
