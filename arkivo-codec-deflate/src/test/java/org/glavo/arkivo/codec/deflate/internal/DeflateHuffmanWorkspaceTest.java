// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks canonical codes, length limits, and workspace reuse for the zlib-compatible tree builder.
@NotNullByDefault
final class DeflateHuffmanWorkspaceTest {
    /// Empty and single-symbol alphabets receive the same two dummy leaves as zlib.
    @Test
    void suppliesLeavesForDegenerateAlphabets() {
        var workspace = new DeflateEncoderEngine.HuffmanWorkspace();
        var code = new DeflateEncoderEngine.HuffmanCode(19);
        for (int activeSymbol : new int[]{-1, 0, 1, 18}) {
            int[] frequencies = new int[19];
            if (activeSymbol >= 0) {
                frequencies[activeSymbol] = 17;
            }
            workspace.build(frequencies, 7, code);
            int firstSymbol = Math.max(activeSymbol, 0);
            int secondSymbol = activeSymbol < 2 ? firstSymbol + 1 : 0;
            for (int symbol = 0; symbol < frequencies.length; symbol++) {
                boolean present = symbol == firstSymbol || symbol == secondSymbol;
                assertEquals(present ? 1 : 0, code.length(symbol));
            }
        }
    }

    /// Alternates alphabet sizes and frequency distributions without retaining old tree state.
    @Test
    void preservesCanonicalCodesAcrossReuse() {
        var reused = new DeflateEncoderEngine.HuffmanWorkspace();
        var bits = new DeflateBitOutput();
        Random random = new Random(0x48554646L);
        for (int trial = 0; trial < 80; trial++) {
            for (int alphabet : new int[]{286, 19, 30}) {
                int maximumLength = alphabet == 19 ? 7 : 15;
                int[] frequencies = new int[alphabet];
                for (int symbol = 0; symbol < alphabet; symbol++) {
                    frequencies[symbol] = switch (trial % 4) {
                        case 0 -> 1;
                        case 1 -> random.nextBoolean() ? random.nextInt(65536) : 0;
                        case 2 -> 1 << Math.min(symbol, 29);
                        default -> symbol == trial % alphabet ? 65536 : 0;
                    };
                }
                int[] original = frequencies.clone();
                var code = new DeflateEncoderEngine.HuffmanCode(alphabet);
                var expected = new DeflateEncoderEngine.HuffmanCode(alphabet);
                new DeflateEncoderEngine.HuffmanWorkspace().build(frequencies, maximumLength, expected);
                // Populate the result as well as the workspace before building the tested tree.
                reused.build(new int[alphabet], maximumLength, code);
                reused.build(frequencies, maximumLength, code);
                assertArrayEquals(original, frequencies);

                int[] counts = new int[maximumLength + 1];
                for (int symbol = 0; symbol < alphabet; symbol++) {
                    int length = code.length(symbol);
                    assertEquals(expected.length(symbol), length);
                    assertTrue(length >= 0 && length <= maximumLength);
                    if (frequencies[symbol] != 0) {
                        assertTrue(length > 0);
                    }
                    if (length > 0) {
                        counts[length]++;
                    }
                }
                int[] nextCodes = new int[maximumLength + 1];
                int available = 1;
                for (int length = 1; length <= maximumLength; length++) {
                    nextCodes[length] = (nextCodes[length - 1] + counts[length - 1]) << 1;
                    available = (available << 1) - counts[length];
                    assertTrue(available >= 0);
                }
                assertEquals(0, available);

                for (int symbol = 0; symbol < alphabet; symbol++) {
                    int length = code.length(symbol);
                    if (length == 0) {
                        continue;
                    }
                    bits.reset();
                    code.writeSymbol(bits, symbol);
                    bits.finish();
                    ByteBuffer output = bits.takeOutput();
                    int actual = Byte.toUnsignedInt(output.get());
                    if (output.hasRemaining()) {
                        actual |= Byte.toUnsignedInt(output.get()) << 8;
                    }
                    assertEquals(Integer.reverse(nextCodes[length]++) >>> (Integer.SIZE - length), actual);
                }
            }
        }
    }
}
