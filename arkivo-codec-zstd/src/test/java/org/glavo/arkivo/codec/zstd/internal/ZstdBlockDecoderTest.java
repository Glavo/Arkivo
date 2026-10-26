// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks compressed matches across dictionary, history and current-block boundaries.
@NotNullByDefault
final class ZstdBlockDecoderTest {
    /// Reuses the private workspace while returning exact-sized, independently owned block results.
    @Test
    void reusesWorkspaceWithoutAliasingResultsOrHistory() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(131072, ZstdDictionaryContext.NONE);
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        assertNull(field.get(decoder));
        byte[] first = decoder.decodeCompressed(singleMatch(new byte[]{42}, 65539, 1));
        byte[] expected = new byte[65540];
        Arrays.fill(expected, (byte) 42);
        assertArrayEquals(expected, first);
        byte[] workspace = assertInstanceOf(byte[].class, field.get(decoder));
        assertEquals(131072, workspace.length);
        byte[] second = decoder.decodeCompressed(singleMatch(new byte[]{17}, 7, 1));
        assertArrayEquals(new byte[]{17, 17, 17, 17, 17, 17, 17, 17}, second);
        assertArrayEquals(expected, first);
        assertSame(workspace, field.get(decoder));

        // Caller changes must affect neither retained history nor the next match expansion.
        Arrays.fill(first, (byte) 99);
        Arrays.fill(second, (byte) 99);
        assertArrayEquals(new byte[]{17, 17, 17},
                decoder.decodeCompressed(singleMatch(new byte[0], 3, 1)));
        assertArrayEquals(new byte[]{42, 42, 42},
                decoder.decodeCompressed(singleMatch(new byte[0], 3, 14)));
        assertSame(workspace, field.get(decoder));
    }

    /// Raw, RLE and literal-only blocks do not allocate the match workspace.
    @Test
    void allocatesWorkspaceOnlyForMatches() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(1024, ZstdDictionaryContext.NONE);
        decoder.appendRaw(new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{7, 7, 7}, decoder.decodeRle(7, 3));
        assertArrayEquals(new byte[]{4, 5, 6}, decoder.decodeCompressed(new byte[]{24, 4, 5, 6, 0}));
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        assertNull(field.get(decoder));
        assertEquals(9, decoder.frameSize());
    }

    /// Compares exact compressed-block decoding with a bytewise match expansion.
    ///
    /// @param length the regenerated match length
    @ParameterizedTest
    @ValueSource(ints = {3, 7, 32, 258, 65539})
    void expandsMatchesAcrossSources(int length) throws IOException {
        byte[] dictionary = new byte[64];
        for (int i = 0; i < dictionary.length; i++) dictionary[i] = (byte) (i * 17);
        ZstdDictionaryContext context = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(dictionary));
        for (int capacity : new int[]{17, 1024}) {
            for (int previousSize : new int[]{0, 13, 64}) {
                byte[] previous = new byte[previousSize];
                for (int i = 0; i < previousSize; i++) previous[i] = (byte) (i * 31 + 5);
                for (int prefixSize : new int[]{0, 1, 7, 15}) {
                    byte[] prefix = new byte[prefixSize];
                    for (int i = 0; i < prefixSize; i++) prefix[i] = (byte) (i + 3);
                    for (int distance : new int[]{1, 2, 3, 8, 17, 32, 64, 80, 128}) {
                        ZstdBlockDecoder decoder = new ZstdBlockDecoder(capacity, context);
                        decoder.appendRaw(previous);
                        byte[] payload = singleMatch(prefix, length, distance);
                        byte[] expected;
                        try {
                            expected = expand(prefix, previous, dictionary, capacity, length, distance);
                        } catch (IOException invalid) {
                            assertThrows(IOException.class, () -> decoder.decodeCompressed(payload));
                            assertEquals(previousSize, decoder.frameSize());
                            continue;
                        }
                        assertArrayEquals(expected, decoder.decodeCompressed(payload),
                                "capacity=" + capacity + ", history=" + previousSize
                                        + ", prefix=" + prefixSize + ", distance=" + distance);
                        assertEquals(previousSize + expected.length, decoder.frameSize());
                    }
                }
            }
        }
    }

    /// Constructs one sequence using raw literals and single-symbol FSE tables.
    private static byte[] singleMatch(byte[] prefix, int length, int distance) {
        var output = new ByteArrayOutputStream();
        output.write(prefix.length << 3);
        output.writeBytes(prefix);
        output.write(1);
        output.write(0x54);
        output.write(prefix.length);
        int offsetValue = distance + 3;
        int offsetCode = 31 - Integer.numberOfLeadingZeros(offsetValue);
        output.write(offsetCode);
        output.write(length <= 34 ? length - 3 : length == 258 ? 43 : 52);
        var bits = new ZstdEntropy.ReverseBitWriter();
        if (length == 258) bits.writeBits(127, 7);
        if (length == 65539) bits.writeBits(0, 16);
        bits.writeBits(offsetValue - (1L << offsetCode), offsetCode);
        output.writeBytes(bits.finish());
        return output.toByteArray();
    }

    /// Expands one match byte by byte, rejecting unavailable or expired dictionary references.
    private static byte[] expand(byte[] prefix, byte[] previous, byte[] dictionary,
                                 int capacity, int length, int distance) throws IOException {
        byte[] output = Arrays.copyOf(prefix, prefix.length + length);
        for (int position = prefix.length; position < output.length; position++) {
            int source = position - distance;
            if (source >= 0) {
                output[position] = output[source];
            } else if (-source <= Math.min(capacity, previous.length)) {
                output[position] = previous[previous.length + source];
            } else {
                int dictionaryPosition = dictionary.length + previous.length + source;
                if (previous.length + position > capacity
                        || dictionaryPosition < 0 || dictionaryPosition >= dictionary.length) {
                    throw new IOException("Unavailable match history");
                }
                output[position] = dictionary[dictionaryPosition];
            }
        }
        return output;
    }
}
