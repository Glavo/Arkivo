// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks compressed matches across dictionary, history and current-block boundaries.
@NotNullByDefault
final class ZstdBlockDecoderTest {
    /// Checks logical truncation against exact arrays even when valid bytes remain beyond the limit.
    @Test
    void ignoresBytesOutsideCompressedBlockLimit() throws IOException {
        byte[] literals = new byte[4096];
        new Random(42).nextBytes(literals);
        for (int i = 0; i < literals.length; i++) literals[i] &= 7;
        byte[] section = ZstdLiteralEncoder.encode(literals);
        assertEquals(2, section[0] & 3);
        byte[][] blocks = {
                new byte[]{24, 4, 5, 6, 0},
                new byte[]{25, 7, 0},
                new byte[]{0, (byte) 255, 0, 0},
                singleMatch(new byte[]{42}, 65539, 1),
                Arrays.copyOf(section, section.length + 1)
        };
        for (byte[] block : blocks) {
            byte[] padded = Arrays.copyOf(block, block.length + 31);
            Arrays.fill(padded, block.length, padded.length, (byte) 0xa5);
            for (int limit = 0; limit <= block.length; limit++) {
                if (limit >= 16 && limit < block.length - 16 && limit % 71 != 0) continue;
                int end = limit;
                byte[] exact = Arrays.copyOf(block, end);
                @Nullable ByteBuffer expected = null;
                @Nullable IOException failure = null;
                try {
                    expected = new ZstdBlockDecoder(1 << 17, ZstdDictionaryContext.NONE)
                            .decodeCompressed(exact, end);
                } catch (IOException exception) {
                    failure = exception;
                }
                var decoder = new ZstdBlockDecoder(1 << 17, ZstdDictionaryContext.NONE);
                if (failure == null) {
                    assertEquals(expected, decoder.decodeCompressed(padded, end));
                } else {
                    IOException actual = assertThrows(IOException.class, () -> decoder.decodeCompressed(padded, end));
                    assertEquals(failure.getMessage(), actual.getMessage());
                    assertEquals(0, decoder.frameSize());
                }
            }
        }
        var decoder = new ZstdBlockDecoder(1024, ZstdDictionaryContext.NONE);
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeCompressed(new byte[3], -1));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeCompressed(new byte[3], 4));
        decoder.appendRaw(new byte[]{42, 99, 99}, 1);
        byte[] match = singleMatch(new byte[0], 3, 1);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}), decoder.decodeCompressed(match, match.length));
        assertEquals(4, decoder.frameSize());
    }

    /// Reuses bounded read-only views without sharing history storage or retaining caller output.
    @Test
    void reusesReadOnlyViewsWithoutAliasingHistory() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(131072, ZstdDictionaryContext.NONE);
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        assertNull(field.get(decoder));
        byte[] firstBlock = singleMatch(new byte[]{42}, 65539, 1);
        ByteBuffer first = decoder.decodeCompressed(firstBlock, firstBlock.length);
        byte[] expected = new byte[65540];
        Arrays.fill(expected, (byte) 42);
        assertEquals(ByteBuffer.wrap(expected), first);
        assertEquals(expected.length, first.capacity());
        assertThrows(ReadOnlyBufferException.class, () -> first.put(0, (byte) 99));
        byte[] delivered = new byte[first.remaining()];
        first.get(delivered);
        byte[] workspace = assertInstanceOf(byte[].class, field.get(decoder));
        assertEquals(131072, workspace.length);
        byte[] secondBlock = singleMatch(new byte[]{17}, 7, 1);
        ByteBuffer second = decoder.decodeCompressed(secondBlock, secondBlock.length);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17, 17, 17, 17, 17, 17}), second);
        assertEquals(8, second.capacity());
        assertThrows(ReadOnlyBufferException.class, () -> second.put(0, (byte) 99));
        assertArrayEquals(expected, delivered);
        assertSame(workspace, field.get(decoder));

        // Workspace reuse and view cursor changes must not affect retained history.
        Arrays.fill(workspace, (byte) 99);
        second.position(second.limit());
        byte[] recentMatch = singleMatch(new byte[0], 3, 1);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17}),
                decoder.decodeCompressed(recentMatch, recentMatch.length));
        byte[] earlierMatch = singleMatch(new byte[0], 3, 14);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}),
                decoder.decodeCompressed(earlierMatch, earlierMatch.length));
        assertSame(workspace, field.get(decoder));
    }

    /// Allocates only the workspaces required by each block and reuses them for smaller sections.
    @Test
    void allocatesWorkspacesOnDemand() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(1024, ZstdDictionaryContext.NONE);
        var literalField = ZstdBlockDecoder.class.getDeclaredField("literals");
        var outputField = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        literalField.setAccessible(true);
        outputField.setAccessible(true);
        decoder.appendRaw(new byte[]{1, 2, 3}, 3);
        assertNull(literalField.get(decoder));
        assertNull(outputField.get(decoder));
        assertEquals(ByteBuffer.wrap(new byte[]{7, 7, 7}), decoder.decodeRle(7, 3));
        assertNull(literalField.get(decoder));
        byte[] rleWorkspace = assertInstanceOf(byte[].class, outputField.get(decoder));
        assertEquals(3, rleWorkspace.length);
        assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), decoder.decodeCompressed(new byte[]{24, 4, 5, 6, 0}, 5));
        assertSame(rleWorkspace, outputField.get(decoder));
        assertEquals(3, assertInstanceOf(byte[].class, literalField.get(decoder)).length);
        assertEquals(9, decoder.frameSize());
        assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}),
                decoder.decodeCompressed(new byte[]{32, 1, 2, 3, 4, 0}, 6));
        byte[] literalWorkspace = assertInstanceOf(byte[].class, literalField.get(decoder));
        assertEquals(6, literalWorkspace.length, "growth doubles capacity without preserving previous literals");
        assertEquals(ByteBuffer.wrap(new byte[]{8}), decoder.decodeCompressed(new byte[]{8, 8, 0}, 3));
        assertSame(literalWorkspace, literalField.get(decoder));
        ByteBuffer empty = decoder.decodeCompressed(new byte[]{0, 0}, 2);
        assertEquals(0, empty.capacity());
        assertSame(literalWorkspace, literalField.get(decoder));
        assertEquals(14, decoder.frameSize());
    }

    /// Reuses RLE output for later RLE and match blocks without overwriting retained history.
    @Test
    void sharesRleAndMatchWorkspace() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(1024, ZstdDictionaryContext.NONE);
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        byte[] expected = new byte[128];
        Arrays.fill(expected, (byte) 42);
        assertEquals(ByteBuffer.wrap(expected), decoder.decodeRle(42, 128));
        byte[] firstWorkspace = assertInstanceOf(byte[].class, field.get(decoder));
        ByteBuffer small = decoder.decodeRle(17, 7);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17, 17, 17, 17, 17}), small);
        assertEquals(7, small.capacity());
        assertThrows(ReadOnlyBufferException.class, () -> small.put(0, (byte) 99));
        assertSame(firstWorkspace, field.get(decoder));
        byte[] match = singleMatch(new byte[0], 3, 10);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}), decoder.decodeCompressed(match, match.length));
        byte[] matchWorkspace = assertInstanceOf(byte[].class, field.get(decoder));
        assertEquals(ZstdBlockDecoder.MAX_BLOCK_SIZE, matchWorkspace.length);
        assertEquals(ByteBuffer.wrap(new byte[]{9, 9}), decoder.decodeRle(9, 2));
        assertSame(matchWorkspace, field.get(decoder));
        byte[] priorRle = singleMatch(new byte[0], 3, 8);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17}), decoder.decodeCompressed(priorRle, priorRle.length));
        assertEquals(143, decoder.frameSize());
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
                        decoder.appendRaw(previous, previous.length);
                        byte[] payload = singleMatch(prefix, length, distance);
                        byte[] expected;
                        try {
                            expected = expand(prefix, previous, dictionary, capacity, length, distance);
                        } catch (IOException invalid) {
                            assertThrows(IOException.class, () -> decoder.decodeCompressed(payload, payload.length));
                            assertEquals(previousSize, decoder.frameSize());
                            continue;
                        }
                        assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(payload, payload.length),
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
