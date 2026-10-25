// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import net.jpountz.lz4.LZ4Factory;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/// Checks exact match-copy bounds, dictionary transitions, and bounded output growth.
@NotNullByDefault
final class LZ4BlockDecompressionTest {
    /// Final literals satisfying the block format's end constraints even for a four-byte match.
    private static final byte @Unmodifiable [] TAIL = {91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102};

    /// Checks aligned and unaligned matches against scalar expansion, including partially dictionary-backed matches.
    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7, 8, 9, 16, 17})
    void copiesOnlyProducedBytes(int matchLength) throws IOException {
        for (int dictionarySize : new int[]{0, 32}) {
            byte[] dictionary = new byte[dictionarySize * 2];
            for (int index = 0; index < dictionary.length; index++) dictionary[index] = (byte) (index * 37);
            for (int alignment = 0; alignment < 8; alignment++) {
                for (int distance : new int[]{1, 2, 3, 4, 7, 8, 9, 16, 31, 65535}) {
                    int literalLength = dictionarySize == 0 ? distance + alignment : alignment;
                    if (distance > dictionarySize + literalLength) continue;
                    byte[] literals = new byte[literalLength];
                    for (int index = 0; index < literals.length; index++) literals[index] = (byte) (index * 19);
                    int matchEnd = literalLength + matchLength;
                    byte[] expected = Arrays.copyOf(literals, matchEnd + TAIL.length);
                    for (int position = literalLength; position < matchEnd; position++) {
                        int reference = position - distance;
                        expected[position] = reference < 0 ? dictionary[dictionarySize + reference] : expected[reference];
                    }
                    System.arraycopy(TAIL, 0, expected, matchEnd, TAIL.length);

                    byte[] encoded = block(literals, distance, matchLength, true);
                    byte[] storage = new byte[expected.length + 16];
                    Arrays.fill(storage, (byte) 0x5a);
                    var decoded = LZ4BlockDecompression.decompress(encoded, encoded.length, dictionary,
                            dictionarySize, storage, expected.length, -1, -1);
                    assertSame(storage, decoded.bytes());
                    assertEquals(expected.length, decoded.length());
                    assertArrayEquals(expected, Arrays.copyOf(storage, expected.length));
                    assertGuard(storage, expected.length);
                    if (dictionarySize == 0) {
                        assertArrayEquals(expected, LZ4Factory.safeInstance().safeDecompressor()
                                .decompress(encoded, expected.length));
                    }

                    // A missing final sequence exposes any write beyond the match that literals would otherwise hide.
                    byte[] truncated = block(literals, distance, matchLength, false);
                    Arrays.fill(storage, (byte) 0x5a);
                    assertThrows(IOException.class, () -> LZ4BlockDecompression.decompress(truncated, truncated.length,
                            dictionary, dictionarySize, storage, expected.length, -1, -1));
                    assertArrayEquals(Arrays.copyOf(expected, matchEnd), Arrays.copyOf(storage, matchEnd));
                    assertGuard(storage, matchEnd);
                }
            }
        }
    }

    /// Leaves room for final literals after a large match without exceeding the decoded-size bound.
    @Test
    void roundsLargeGrowthBeforeFinalLiterals() throws IOException {
        byte[] encoded = block(new byte[]{42}, 1, 65536 - 1 - TAIL.length, true);
        var decoded = LZ4BlockDecompression.decompress(encoded, 100000, -1, -1);
        assertEquals(65536, decoded.length());
        assertEquals(65536, decoded.bytes().length);
        for (int index = 0; index < decoded.length() - TAIL.length; index++) assertEquals(42, decoded.bytes()[index]);
        assertArrayEquals(TAIL, Arrays.copyOfRange(decoded.bytes(), decoded.length() - TAIL.length, decoded.length()));
    }

    /// Clamps spare capacity to memory and output limits while still accepting an exact-fit block.
    @Test
    void clampsGrowthToConfiguredLimits() throws IOException {
        int size = 50000;
        byte[] encoded = block(new byte[]{42}, 1, size - 1 - TAIL.length, true);
        var outputBounded = LZ4BlockDecompression.decompress(encoded, size, -1, -1);
        assertEquals(size, outputBounded.bytes().length);
        long memoryLimit = encoded.length + (long) size;
        var memoryBounded = LZ4BlockDecompression.decompress(encoded, 100000, -1, memoryLimit);
        assertEquals(size, memoryBounded.bytes().length);
        assertArrayEquals(outputBounded.bytes(), memoryBounded.bytes());
        assertThrows(DecompressionMemoryLimitException.class,
                () -> LZ4BlockDecompression.decompress(encoded, 100000, -1, memoryLimit - 1));
        assertThrows(IOException.class, () -> LZ4BlockDecompression.decompress(encoded, size - 1, -1, -1));
    }

    /// Encodes literals, one match, and an optional final literal sequence without using a compressor.
    private static byte[] block(byte[] literals, int distance, int matchLength, boolean complete) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int matchCode = matchLength - 4;
        output.write(Math.min(literals.length, 15) << 4 | Math.min(matchCode, 15));
        if (literals.length >= 15) writeLength(output, literals.length - 15);
        output.writeBytes(literals);
        byte[] offset = new byte[Short.BYTES];
        ByteArrayAccess.writeShortLittleEndian(offset, 0, (short) distance);
        output.writeBytes(offset);
        if (matchCode >= 15) writeLength(output, matchCode - 15);
        if (complete) {
            output.write(TAIL.length << 4);
            output.writeBytes(TAIL);
        }
        return output.toByteArray();
    }

    /// Writes a length extension including its terminating byte.
    private static void writeLength(ByteArrayOutputStream output, int length) {
        while (length >= 255) {
            output.write(255);
            length -= 255;
        }
        output.write(length);
    }

    /// Verifies no byte after the produced prefix was overwritten.
    private static void assertGuard(byte[] storage, int start) {
        for (int index = start; index < storage.length; index++) assertEquals((byte) 0x5a, storage[index]);
    }
}
