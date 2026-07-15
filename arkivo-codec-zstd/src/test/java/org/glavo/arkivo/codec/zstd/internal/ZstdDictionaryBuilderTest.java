// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies sample-derived entropy tables in formatted Zstandard dictionaries.
@NotNullByDefault
public final class ZstdDictionaryBuilderTest {
    /// Verifies repetitive samples produce sparse tables instead of full uniform alphabets.
    @Test
    public void trainsSequenceTablesFromDictionaryMatches() throws IOException {
        byte[] sample = "abcd".repeat(64).getBytes(StandardCharsets.US_ASCII);
        int[] sampleSizes = new int[32];
        Arrays.fill(sampleSizes, sample.length);
        byte[] samples = new byte[sample.length * sampleSizes.length];
        for (int index = 0; index < sampleSizes.length; index++) {
            System.arraycopy(sample, 0, samples, index * sample.length, sample.length);
        }

        ZstdDictionary dictionary = parse(samples, sampleSizes);
        ZstdEntropy.FseTable offsets = dictionary.offsetTable();
        ZstdEntropy.FseTable matches = dictionary.matchLengthTable();
        ZstdEntropy.FseTable literals = dictionary.literalLengthTable();
        assertNotNull(offsets);
        assertNotNull(matches);
        assertNotNull(literals);

        assertTrue(distinctSymbols(offsets) < 31);
        assertTrue(distinctSymbols(matches) < 53);
        assertTrue(distinctSymbols(literals) < 36);
    }

    /// Verifies samples shorter than the minimum match retain complete fallback alphabets.
    @Test
    public void fallsBackWhenSamplesContainNoSequences() throws IOException {
        byte[] samples = {
                0x10, 0x11,
                0x20, 0x21,
                0x30, 0x31,
                0x40, 0x41
        };
        ZstdDictionary dictionary = parse(samples, new int[]{2, 2, 2, 2});
        ZstdEntropy.FseTable offsets = dictionary.offsetTable();
        ZstdEntropy.FseTable matches = dictionary.matchLengthTable();
        ZstdEntropy.FseTable literals = dictionary.literalLengthTable();
        assertNotNull(offsets);
        assertNotNull(matches);
        assertNotNull(literals);

        assertEquals(31, distinctSymbols(offsets));
        assertEquals(53, distinctSymbols(matches));
        assertEquals(36, distinctSymbols(literals));
    }

    /// Builds and parses one formatted dictionary fixture.
    private static ZstdDictionary parse(
            byte[] samples,
            int @Unmodifiable [] sampleSizes
    ) throws IOException {
        byte[] bytes = ZstdDictionaryBuilder.build(
                samples,
                sampleSizes,
                1_024,
                3,
                false
        );
        return ZstdDictionary.parse(CompressionDictionary.of(bytes));
    }

    /// Counts symbols represented by at least one FSE state.
    private static int distinctSymbols(ZstdEntropy.FseTable table) throws IOException {
        boolean[] seen = new boolean[64];
        int count = 0;
        for (int state = 0; state < 1 << table.tableLog(); state++) {
            int symbol = table.symbol(state);
            if (!seen[symbol]) {
                seen[symbol] = true;
                count++;
            }
        }
        return count;
    }
}
