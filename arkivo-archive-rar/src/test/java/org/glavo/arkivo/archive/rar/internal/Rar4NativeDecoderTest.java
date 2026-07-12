// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies synthetic legacy RAR bit streams against Arkivo's native decoders.
@NotNullByDefault
public final class Rar4NativeDecoderTest {
    /// Decodes one literal through the adaptive RAR 1.5 flag and character models.
    @Test
    public void decodesRar15AdaptiveLiteral() throws IOException {
        BitWriter writer = new BitWriter();
        writer.write(0b00001, 5);
        writer.write(0xe5, 8);
        writer.write(0, 16);

        Rar4Decoder.Session session = Rar4Decoder.newSession();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar4Decoder.Result result = session.decode(
                new ByteArrayInputStream(writer.toByteArray()),
                output,
                15,
                1,
                false
        );
        assertArrayEquals("A".getBytes(StandardCharsets.US_ASCII), output.toByteArray());
        assertEquals(1L, result.size());
        session.release();
    }

    /// Decodes a RAR 2.x literal table and reuses it for a solid continuation.
    @Test
    public void decodesRar20LiteralsAndSolidContinuation() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession();
        ByteArrayOutputStream firstOutput = new ByteArrayOutputStream();
        Rar4Decoder.Result firstResult = session.decode(
                new ByteArrayInputStream(rar20LiteralStream(6)),
                firstOutput,
                20,
                6,
                false
        );
        assertArrayEquals("AAAAAA".getBytes(StandardCharsets.US_ASCII), firstOutput.toByteArray());
        assertEquals(6L, firstResult.size());

        ByteArrayOutputStream secondOutput = new ByteArrayOutputStream();
        Rar4Decoder.Result secondResult = session.decode(
                new ByteArrayInputStream(repeatedZeroBits(3)),
                secondOutput,
                20,
                3,
                true
        );
        assertArrayEquals("AAA".getBytes(StandardCharsets.US_ASCII), secondOutput.toByteArray());
        assertEquals(3L, secondResult.size());
        session.release();
    }

    /// Decodes zero deltas through a one-channel RAR 2.x adaptive audio table.
    @Test
    public void decodesRar20AudioDeltas() throws IOException {
        Rar4Decoder.Session session = Rar4Decoder.newSession();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar4Decoder.Result result = session.decode(
                new ByteArrayInputStream(rar20AudioStream(8)),
                output,
                20,
                8,
                false
        );
        assertArrayEquals(new byte[8], output.toByteArray());
        assertEquals(8L, result.size());
        session.release();
    }

    /// Decodes repeated symbols through root updates and a newly created binary RAR3 PPMd context.
    @Test
    public void decodesRar3PpmContextSuccessor() throws IOException {
        byte[] packed = {
                (byte) 0xa1,
                0,
                0x40, (byte) 0xfd, (byte) 0xc8, 0x3f,
                0, 0
        };
        Rar4Decoder.Session session = Rar4Decoder.newSession();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Rar4Decoder.Result result = session.decode(
                new ByteArrayInputStream(packed),
                output,
                29,
                3,
                false
        );
        assertArrayEquals(new byte[]{'A', 'A', 'A'}, output.toByteArray());
        assertEquals(3L, result.size());

        byte[] continuation = {(byte) 0x81, 0, 0, 0, 0, 0, 0};
        ByteArrayOutputStream continuationOutput = new ByteArrayOutputStream();
        Rar4Decoder.Result continuationResult = session.decode(
                new ByteArrayInputStream(continuation),
                continuationOutput,
                29,
                1,
                true
        );
        assertArrayEquals(new byte[]{'A'}, continuationOutput.toByteArray());
        assertEquals(1L, continuationResult.size());
        session.release();
    }

    /// Builds a normal RAR 2.x table containing one literal and one table marker.
    private static byte[] rar20LiteralStream(int literalCount) {
        BitWriter writer = new BitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writeLevelAlphabet(writer);
        writeZeroRun(writer, 65);
        writeLevelOne(writer);
        writeZeroRun(writer, 138);
        writeZeroRun(writer, 65);
        writeLevelOne(writer);
        writeZeroRun(writer, 104);
        writer.write(0, literalCount);
        return writer.toByteArray();
    }

    /// Builds a one-channel audio table containing a zero delta and table marker.
    private static byte[] rar20AudioStream(int sampleCount) {
        BitWriter writer = new BitWriter();
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writeLevelAlphabet(writer);
        writeLevelOne(writer);
        writeZeroRun(writer, 138);
        writeZeroRun(writer, 117);
        writeLevelOne(writer);
        writer.write(0, sampleCount);
        return writer.toByteArray();
    }

    /// Returns a byte-aligned sequence containing the requested number of zero bits.
    private static byte[] repeatedZeroBits(int count) {
        BitWriter writer = new BitWriter();
        writer.write(0, count);
        return writer.toByteArray();
    }

    /// Writes the canonical level alphabet used by both synthetic table descriptions.
    private static void writeLevelAlphabet(BitWriter writer) {
        for (int symbol = 0; symbol < 19; symbol++) {
            int length = symbol == 0 || symbol == 1 ? 2 : symbol == 18 ? 1 : 0;
            writer.write(length, 4);
        }
    }

    /// Writes the code-length symbol representing a literal table length of one.
    private static void writeLevelOne(BitWriter writer) {
        writer.write(0b11, 2);
    }

    /// Writes one or more long zero-run symbols.
    private static void writeZeroRun(BitWriter writer, int count) {
        int remaining = count;
        while (remaining != 0) {
            int current = Math.min(remaining, 138);
            if (current < 11) {
                throw new IllegalArgumentException("Synthetic RAR2 zero run is too short");
            }
            writer.write(0, 1);
            writer.write(current - 11, 7);
            remaining -= current;
        }
    }

    /// Writes most-significant-bit-first fields into a byte array.
    @NotNullByDefault
    private static final class BitWriter {
        /// Completed bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// The partially assembled current byte.
        private int currentByte;

        /// The number of high bits already assigned in {@link #currentByte}.
        private int currentBitCount;

        /// Creates an empty bit writer.
        private BitWriter() {
        }

        /// Writes the requested low bits from most to least significant.
        private void write(int value, int count) {
            if (count < 0 || count > 31) {
                throw new IllegalArgumentException("Invalid synthetic bit count");
            }
            for (int bit = count - 1; bit >= 0; bit--) {
                currentByte = currentByte << 1 | value >>> bit & 1;
                currentBitCount++;
                if (currentBitCount == 8) {
                    output.write(currentByte);
                    currentByte = 0;
                    currentBitCount = 0;
                }
            }
        }

        /// Returns all bits with zero padding in the final low-order positions.
        private byte[] toByteArray() {
            if (currentBitCount != 0) {
                output.write(currentByte << (8 - currentBitCount));
                currentByte = 0;
                currentBitCount = 0;
            }
            return output.toByteArray();
        }
    }
}
