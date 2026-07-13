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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final byte @Unmodifiable [] FIRST_DECISION_ONE = {0, 0x7f, (byte) 0xff, (byte) 0xfc, 0};

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

    /// Verifies a final marker still consumes its mandatory no-conversion range decision.
    @Test
    public void validatesFinalMarkerDecision() throws IOException {
        TestRangeEncoder rangeEncoder = new TestRangeEncoder();
        rangeEncoder.encode(2, 0);
        byte[] main = {(byte) 0xe8};
        byte[] range = rangeEncoder.finish();

        try (SevenZipBcj2InputStream input = strictInput(main, new byte[0], new byte[0], range, 1, 1)) {
            assertArrayEquals(main, input.readAllBytes());
        }
    }

    /// Verifies a selected final marker cannot require address bytes beyond the declared output.
    @Test
    public void rejectsBranchBeyondDeclaredOutput() {
        TestRangeEncoder rangeEncoder = new TestRangeEncoder();
        rangeEncoder.encode(2, 1);
        byte[] main = {(byte) 0xe8};
        byte[] range = rangeEncoder.finish();

        IOException exception = assertThrows(
                IOException.class,
                () -> strictInput(main, new byte[0], new byte[0], range, 1, 1).readAllBytes()
        );
        assertEquals(
                "7z BCJ2 stream requires branch bytes beyond its declared output size",
                exception.getMessage()
        );
    }

    /// Verifies the complete stream rejects a nonzero terminal range code.
    @Test
    public void rejectsNonzeroTerminalRangeCode() {
        byte[] range = {0, 0, 0, 0, 1};
        IOException exception = assertThrows(
                IOException.class,
                () -> strictInput(new byte[0], new byte[0], new byte[0], range, 0, 0).readAllBytes()
        );
        assertEquals("7z BCJ2 range decoder did not finish with a zero code", exception.getMessage());

        SevenZipBcj2InputStream closeInput = strictInput(
                new byte[0],
                new byte[0],
                new byte[0],
                range,
                0,
                0
        );
        exception = assertThrows(IOException.class, closeInput::close);
        assertEquals("7z BCJ2 range decoder did not finish with a zero code", exception.getMessage());
    }

    /// Verifies the complete stream rejects range bytes not consumed by the official decoder state machine.
    @Test
    public void rejectsUnusedRangeBytes() {
        byte[] main = {1};
        byte[] range = {0, 0, 0, 0, 0, 0};
        IOException exception = assertThrows(
                IOException.class,
                () -> strictInput(main, new byte[0], new byte[0], range, 1, 1).readAllBytes()
        );
        assertEquals("7z BCJ2 range stream was not consumed exactly", exception.getMessage());
    }

    /// Verifies exact graph sizes reject decoded input beyond a declared BCJ2 branch boundary.
    @Test
    public void rejectsInputBeyondDeclaredSize() {
        SevenZipBcj2InputStream input = new SevenZipBcj2InputStream(
                new ByteArrayInputStream(new byte[]{1, 2}),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayInputStream(FIRST_DECISION_ZERO),
                1,
                0,
                0,
                FIRST_DECISION_ZERO.length,
                1,
                1
        );
        IOException exception = assertThrows(IOException.class, input::readAllBytes);
        assertEquals("7z BCJ2 MAIN stream exceeds its declared size", exception.getMessage());
    }

    /// Verifies folder graph sizes enforce the BCJ2 output and branch-stream structure.
    @Test
    public void rejectsInvalidDeclaredStreamSizes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipBcj2InputStream(
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        1,
                        4,
                        0,
                        5,
                        4,
                        4
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new SevenZipBcj2InputStream(
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream(),
                        1,
                        1,
                        0,
                        5,
                        2,
                        2
                )
        );
    }

    /// Verifies a solid-folder prefix can stop before complete BCJ2 terminal-state validation.
    @Test
    public void readsPartialFolderOutput() throws IOException {
        byte[] main = {1, 2, 3, 4, 5, 6};
        try (SevenZipBcj2InputStream input = strictInput(
                main,
                new byte[0],
                new byte[0],
                FIRST_DECISION_ZERO,
                3,
                main.length
        )) {
            assertArrayEquals(new byte[]{1, 2, 3}, input.readAllBytes());
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

    /// Verifies the production splitter exactly matches the independent reference encoder.
    @Test
    public void productionEncoderMatchesReferenceStreams() throws IOException {
        byte[] original = referenceInput();
        EncodedStreams expected = ReferenceBcj2Encoder.encode(original);
        ByteArrayOutputStream main = new ByteArrayOutputStream();
        ByteArrayOutputStream call = new ByteArrayOutputStream();
        ByteArrayOutputStream jump = new ByteArrayOutputStream();
        ByteArrayOutputStream range = new ByteArrayOutputStream();

        SevenZipBcj2Encoder.encode(
                new ShortReadInputStream(original),
                original.length,
                main,
                call,
                jump,
                range
        );

        assertArrayEquals(expected.main(), main.toByteArray());
        assertArrayEquals(expected.call(), call.toByteArray());
        assertArrayEquals(expected.jump(), jump.toByteArray());
        assertArrayEquals(expected.range(), range.toByteArray());
    }

    /// Verifies the production splitter enforces its exact declared source boundary.
    @Test
    public void productionEncoderRejectsDeclaredSizeMismatch() {
        assertThrows(
                EOFException.class,
                () -> SevenZipBcj2Encoder.encode(
                        new ByteArrayInputStream(new byte[]{1}),
                        2,
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()
                )
        );
        assertThrows(
                IOException.class,
                () -> SevenZipBcj2Encoder.encode(
                        new ByteArrayInputStream(new byte[]{1, 2}),
                        1,
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream(),
                        new ByteArrayOutputStream()
                )
        );
    }
    /// Verifies independently encoded side streams against the exact 7-Zip 26.01 BCJ2 output.
    @Test
    public void matchesSevenZip2601ReferenceStreams() throws IOException, NoSuchAlgorithmException {
        byte[] original = referenceInput();
        EncodedStreams encoded = ReferenceBcj2Encoder.encode(original);

        assertEquals(1004, encoded.main().length);
        assertEquals(8, encoded.call().length);
        assertEquals(12, encoded.jump().length);
        assertEquals(6, encoded.range().length);
        assertEquals(
                "029293b9608f582eee36b494da96983bcaf34d944206dbbf79335e3cacacf2d7",
                sha256(encoded.main())
        );
        assertEquals(
                "ae0248b6b7203b94818e5153f9e8633b8c88abe9ef6f8f15f84ade6ff1a753e9",
                sha256(encoded.call())
        );
        assertEquals(
                "8dfbab7875759df8b8dfac95e523ae234ffc36ac3e674dc7c741a76ea0c43fdd",
                sha256(encoded.jump())
        );
        assertEquals(
                "2fb50118c669fbd095ef88761a84fbb9c3263a645e51436fb59f28afad8f4609",
                sha256(encoded.range())
        );

        try (SevenZipBcj2InputStream input = strictInput(
                encoded.main(),
                encoded.call(),
                encoded.jump(),
                encoded.range(),
                original.length,
                original.length
        )) {
            assertArrayEquals(original, input.readAllBytes());
        }
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

    /// Returns the deterministic executable-like input encoded by the 7-Zip 26.01 reference invocation.
    private static byte[] referenceInput() {
        byte[] bytes = new byte[1024];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 37 + 11);
        }
        writeRelativeBranch(bytes, 0, 0xe8, 100, 5);
        writeRelativeBranch(bytes, 16, 0xe9, 300, 5);
        writeRelativeBranch(bytes, 32, 0x85, 700, 6);
        writeRelativeBranch(bytes, 128, 0xe8, 900, 5);
        writeRelativeBranch(bytes, 512, 0xe9, 64, 5);
        return bytes;
    }

    /// Writes one x86 relative branch that targets an absolute position in the reference input.
    private static void writeRelativeBranch(
            byte[] bytes,
            int position,
            int opcode,
            int target,
            int instructionSize
    ) {
        int offsetPosition;
        if (instructionSize == 6) {
            bytes[position] = 0x0f;
            bytes[position + 1] = (byte) opcode;
            offsetPosition = position + 2;
        } else {
            bytes[position] = (byte) opcode;
            offsetPosition = position + 1;
        }
        int relative = target - position - instructionSize;
        bytes[offsetPosition] = (byte) relative;
        bytes[offsetPosition + 1] = (byte) (relative >>> 8);
        bytes[offsetPosition + 2] = (byte) (relative >>> 16);
        bytes[offsetPosition + 3] = (byte) (relative >>> 24);
    }

    /// Returns the lowercase SHA-256 digest of one reference stream.
    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /// Creates a BCJ2 decoder with exact folder graph stream sizes.
    private static SevenZipBcj2InputStream strictInput(
            byte[] main,
            byte[] call,
            byte[] jump,
            byte[] range,
            long outputLimit,
            long expectedOutputSize
    ) {
        return new SevenZipBcj2InputStream(
                new ByteArrayInputStream(main),
                new ByteArrayInputStream(call),
                new ByteArrayInputStream(jump),
                new ByteArrayInputStream(range),
                main.length,
                call.length,
                jump.length,
                range.length,
                outputLimit,
                expectedOutputSize
        );
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

    /// Encodes one complete byte array with the BCJ2 branch-selection rules used by modern 7-Zip.
    @NotNullByDefault
    private static final class ReferenceBcj2Encoder {
        /// The default relative-address conversion limit used by 7-Zip 23.00 and newer.
        private static final int RELATIVE_LIMIT = 0x0f00_0000;

        /// Creates no instances.
        private ReferenceBcj2Encoder() {
        }

        /// Splits original x86 bytes into the four BCJ2 streams.
        private static EncodedStreams encode(byte[] original) {
            Objects.requireNonNull(original, "original");
            ByteArrayOutputStream main = new ByteArrayOutputStream(original.length);
            ByteArrayOutputStream call = new ByteArrayOutputStream();
            ByteArrayOutputStream jump = new ByteArrayOutputStream();
            TestRangeEncoder range = new TestRangeEncoder();

            int previous = 0;
            int position = 0;
            while (position < original.length) {
                int current = Byte.toUnsignedInt(original[position]);
                main.write(current);
                int context = (previous << 24) | current;
                boolean marker = (current & 0xfe) == 0xe8
                        || (previous == 0x0f && (current & 0xf0) == 0x80);
                if (!marker) {
                    previous = current;
                    position++;
                    continue;
                }

                int probabilityIndex = current == 0xe8 ? previous + 2 : current == 0xe9 ? 1 : 0;
                int instructionPointer = position + 1;
                boolean convert = false;
                int relative = 0;
                if (original.length - position - 1 >= 4) {
                    relative = readLittleEndianInt(original, position + 1);
                    long target = (long) instructionPointer + 4L + relative;
                    int overlapBoundary = ((context + 0x20) >>> 5) & 1;
                    long limitedRelative = Integer.toUnsignedLong(relative + RELATIVE_LIMIT) >>> 1;
                    convert = instructionPointer > overlapBoundary
                            && target >= 0
                            && target < original.length
                            && limitedRelative < RELATIVE_LIMIT;
                }

                range.encode(probabilityIndex, convert ? 1 : 0);
                if (!convert) {
                    previous = current;
                    position++;
                    continue;
                }

                int absolute = instructionPointer + 4 + relative;
                ByteArrayOutputStream branch = current == 0xe8 ? call : jump;
                writeBigEndianInt(branch, absolute);
                previous = Byte.toUnsignedInt(original[position + 4]);
                position += 5;
            }

            return new EncodedStreams(
                    main.toByteArray(),
                    call.toByteArray(),
                    jump.toByteArray(),
                    range.finish()
            );
        }

        /// Reads one little-endian signed 32-bit relative address.
        private static int readLittleEndianInt(byte[] bytes, int offset) {
            return Byte.toUnsignedInt(bytes[offset])
                    | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                    | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                    | (bytes[offset + 3] << 24);
        }

        /// Writes one unsigned 32-bit absolute address in BCJ2 big-endian order.
        private static void writeBigEndianInt(ByteArrayOutputStream output, int value) {
            output.write(value >>> 24);
            output.write(value >>> 16);
            output.write(value >>> 8);
            output.write(value);
        }
    }

    /// Holds the four streams produced by the independent BCJ2 reference encoder.
    ///
    /// @param main the unconverted instruction stream
    /// @param call the big-endian CALL targets
    /// @param jump the big-endian JUMP targets
    /// @param range the range-coded conversion decisions
    @NotNullByDefault
    private record EncodedStreams(byte[] main, byte[] call, byte[] jump, byte[] range) {
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
