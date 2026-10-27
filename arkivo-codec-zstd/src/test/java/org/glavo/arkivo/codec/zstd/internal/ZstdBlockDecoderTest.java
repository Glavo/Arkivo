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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Checks compressed matches across dictionary, history and current-block boundaries.
@NotNullByDefault
final class ZstdBlockDecoderTest {
    /// Decodes both endpoints of every literal and match length code, including block-size overflow.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesLengthCodeEndpoints(boolean matchLengths) throws IOException {
        int[] bases = matchLengths
                ? new int[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
                35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027,
                2051, 4099, 8195, 16387, 32771, 65539}
                : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048,
                4096, 8192, 16384, 32768, 65536};
        for (int code = 0; code < bases.length; code++) {
            int width = code + 1 < bases.length ? bases[code + 1] - bases[code] : 1 << 16;
            int bitCount = Integer.numberOfTrailingZeros(width);
            for (int extra : new int[]{0, width - 1}) {
                int literals = matchLengths ? 1 : bases[code] + extra;
                int matches = matchLengths ? bases[code] + extra : 3;
                var block = new ByteArrayOutputStream();
                int header = (literals << 4) | 13;
                block.write(header);
                block.write(header >>> 8);
                block.write(header >>> 16);
                block.write(42);
                block.write(1);
                block.write(0x54);
                block.write(matchLengths ? 1 : code);
                block.write(2);
                block.write(matchLengths ? code : 0);
                var bits = new ZstdEntropy.ReverseBitWriter();
                bits.writeBits(extra, bitCount);
                bits.writeBits(0, 2);
                block.writeBytes(bits.finish());
                byte[] input = block.toByteArray();
                var decoder = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
                decoder.appendRaw(new byte[]{42}, 1);
                int size = literals + matches;
                if (size > ZstdBlockDecoder.MAX_BLOCK_SIZE) {
                    assertThrows(IOException.class,
                            () -> decoder.decodeCompressed(input, input.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                    assertEquals(1, decoder.frameSize());
                } else {
                    byte[] expected = new byte[size];
                    Arrays.fill(expected, (byte) 42);
                    assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(input, input.length, size),
                            "matchLengths=" + matchLengths + ", code=" + code + ", extra=" + extra);
                }
            }
        }
    }

    /// Rejects out-of-alphabet symbols while reading RLE or normalized sequence tables, before publishing output.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsOutOfRangeSequenceTableSymbols(int tableIndex) throws IOException {
        int[] maximumSymbols = {35, 31, 52};
        int[] validSymbols = {1, 2, 0};
        for (int mode : new int[]{1, 2}) {
            var block = new ByteArrayOutputStream();
            block.writeBytes(new byte[]{8, 42, 1});
            int shift = 6 - tableIndex * 2;
            block.write((0x54 & ~(3 << shift)) | (mode << shift));
            for (int table = 0; table < 3; table++) {
                if (table != tableIndex) {
                    block.write(validSymbols[table]);
                } else if (mode == 1) {
                    block.write(maximumSymbols[table] + 1);
                } else {
                    int[] counts = new int[maximumSymbols[table] + 2];
                    counts[0] = 31;
                    counts[counts.length - 1] = 1;
                    block.writeBytes(ZstdEntropy.encodeFseTableDescription(counts, counts.length, 5));
                }
            }
            block.write(4);
            byte[] input = block.toByteArray();
            var history = new ZstdHistory(128);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            IOException failure = assertThrows(IOException.class,
                    () -> decoder.decodeCompressed(input, input.length, 128));
            assertTrue(failure.getMessage().contains(mode == 1 ? "RLE symbol" : "FSE"));
            assertEquals(0, decoder.frameSize());
            assertEquals(0, history.size());
        }
    }

    /// Reads raw literals directly across header widths without allocating a literal workspace or retaining input.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            4095, 4096, 65536, 131063})
    void readsRawLiteralsWithoutIntermediateStorage(int size) throws Exception {
        byte[] literals = new byte[size];
        new Random(9700 + size).nextBytes(literals);
        var block = new ByteArrayOutputStream();
        int header = size < 32 ? size << 3 : (size << 4) | (size < 4096 ? 4 : 12);
        block.write(header);
        if (size >= 32) block.write(header >>> 8);
        if (size >= 4096) block.write(header >>> 16);
        block.writeBytes(literals);
        block.write(1);
        block.write(0x54);
        block.write(size == 0 ? 0 : 1);
        block.write(2);
        block.write(0);
        block.write(4); // Two zero offset bits followed by the end marker.

        byte[] expected = new byte[size + 3];
        if (size == 0) {
            Arrays.fill(expected, (byte) 42);
        } else {
            Arrays.fill(expected, 0, 4, literals[0]);
            System.arraycopy(literals, 1, expected, 4, size - 1);
        }
        var history = new ZstdHistory(ZstdBlockDecoder.MAX_BLOCK_SIZE);
        var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        decoder.appendRaw(new byte[]{42}, 1);
        var field = ZstdBlockDecoder.class.getDeclaredField("literals");
        field.setAccessible(true);
        byte[] input = block.toByteArray();
        ByteBuffer decoded = decoder.decodeCompressed(input, input.length, expected.length);
        assertEquals(ByteBuffer.wrap(expected), decoded);
        assertNull(field.get(decoder));
        assertEquals(expected.length, decoded.capacity());
        assertThrows(ReadOnlyBufferException.class, () -> decoded.put(0, (byte) 99));
        Arrays.fill(input, (byte) 99);
        assertEquals(ByteBuffer.wrap(expected), decoded);
        byte[] retained = new byte[expected.length];
        history.copy(expected.length, retained, 0, retained.length);
        assertArrayEquals(expected, retained);

        // Zero-sequence blocks still return independent storage, even after the raw-literal fast path.
        byte[] zeroSequences = {24, 4, 5, 6, 0};
        ByteBuffer independent = decoder.decodeCompressed(zeroSequences, zeroSequences.length, 3);
        Arrays.fill(zeroSequences, (byte) 99);
        assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), independent);
        byte[] workspace = assertInstanceOf(byte[].class, field.get(decoder));
        byte[] next = block.toByteArray();
        assertEquals(ByteBuffer.wrap(size == 0 ? new byte[]{6, 6, 6} : expected),
                decoder.decodeCompressed(next, next.length, expected.length));
        assertSame(workspace, field.get(decoder));
    }

    /// Copies RLE-decoded literal tails from an exact-sized workspace into an exact-sized output.
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 17, 18, 23, 24, 25, 31, 32, 33})
    void copiesRleLiteralTailsWithoutPadding(int size) throws IOException {
        var block = new ByteArrayOutputStream();
        int header = size < 32 ? (size << 3) | 1 : (size << 4) | 5;
        block.write(header);
        if (size >= 32) block.write(header >>> 8);
        block.write(42);
        block.writeBytes(new byte[]{1, 0x54, 1, 2, 0, 4});
        byte[] input = block.toByteArray();
        byte[] expected = new byte[size + 3];
        Arrays.fill(expected, (byte) 42);
        var decoder = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
        assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(input, input.length, expected.length));
    }

    /// Rejects a partial state transition without committing the first sequence to frame history.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void rejectsTruncatedSequenceTransitions(int dynamicStream) throws IOException {
        int[] symbols = {0, 2, 0};
        int[] probabilities = new int[32];
        Arrays.fill(probabilities, 1);
        var table = ZstdEntropy.FseTable.fromNormalized(probabilities, probabilities.length, 5);
        int initialState = ZstdEntropy.FseEncoderTable.fromDecoder(table, probabilities.length)
                .initialState(symbols[dynamicStream]);
        assertEquals(5, table.numberOfBits(initialState));
        for (int available = 0; available < 5; available++) {
            var block = new ByteArrayOutputStream();
            block.write(0);
            block.write(2);
            block.write(0x54 + (1 << (6 - 2 * dynamicStream)));
            for (int stream = 0; stream < 3; stream++) {
                if (stream == dynamicStream) {
                    block.writeBytes(ZstdEntropy.encodeFseTableDescription(probabilities, probabilities.length, 5));
                } else {
                    block.write(symbols[stream]);
                }
            }
            var bits = new ZstdEntropy.ReverseBitWriter();
            bits.writeBits(0, available);
            bits.writeBits(0, 2);
            for (int stream : new int[]{2, 1, 0}) {
                bits.writeBits(stream == dynamicStream ? initialState : 0, stream == dynamicStream ? 5 : 0);
            }
            block.writeBytes(bits.finish());
            byte[] payload = block.toByteArray();
            var history = new ZstdHistory(128);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.appendRaw(new byte[]{42}, 1);
            assertThrows(IOException.class,
                    () -> decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
            assertEquals(1, decoder.frameSize());
            assertEquals(1, history.size());
            byte[] retained = new byte[1];
            history.copy(1, retained, 0, 1);
            assertEquals(42, retained[0]);
        }
    }

    /// Checks all RLE/table combinations, including a 26-bit transition and interleaved zero-bit transitions.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void decodesWideSequenceTransitions(int rleMask) throws IOException {
        int[][] probabilities = {{511, 1}, {0, 0, 0, 255, 1}, {511, 1}};
        int[] logs = {9, 8, 9};
        int[] rare = {1, 4, 1};
        int[] periods = {3, 7, 5};
        int[][] symbols = new int[3][64];
        byte[] previous = new byte[128];
        new Random(9500 + rleMask).nextBytes(previous);
        var block = new ByteArrayOutputStream();
        var descriptions = new ByteArrayOutputStream();
        var encoders = new ArrayList<ZstdEntropy.FseEncoderTable>(3);
        int modes = 0;
        for (int stream = 0; stream < 3; stream++) {
            boolean rle = (rleMask & (1 << stream)) != 0;
            var table = rle ? ZstdEntropy.FseTable.rle(rare[stream])
                    : ZstdEntropy.FseTable.fromNormalized(probabilities[stream], probabilities[stream].length, logs[stream]);
            encoders.add(ZstdEntropy.FseEncoderTable.fromDecoder(table, probabilities[stream].length));
            assertEquals(rle ? 0 : logs[stream],
                    table.numberOfBits(encoders.get(stream).initialState(rare[stream])));
            modes |= (rle ? 1 : 2) << (6 - 2 * stream);
            if (rle) {
                descriptions.write(rare[stream]);
            } else {
                descriptions.writeBytes(ZstdEntropy.encodeFseTableDescription(
                        probabilities[stream], probabilities[stream].length, logs[stream]));
            }
            for (int sequence = 0; sequence < 64; sequence++) {
                symbols[stream][sequence] = rle || sequence % periods[stream] == 0 ? rare[stream] : rare[stream] - 1;
            }
        }
        byte[] literals = new byte[Arrays.stream(symbols[0]).sum()];
        new Random(9600 + rleMask).nextBytes(literals);
        int header = (literals.length << 4) | 4;
        block.write(header);
        block.write(header >>> 8);
        block.writeBytes(literals);
        block.write(64);
        block.write(modes);
        block.writeBytes(descriptions.toByteArray());

        var bits = new ZstdEntropy.ReverseBitWriter();
        int[] states = new int[3];
        for (int stream = 0; stream < 3; stream++) {
            states[stream] = encoders.get(stream).initialState(symbols[stream][63]);
        }
        for (int sequence = 63; sequence >= 0; sequence--) {
            if (sequence < 63) {
                for (int stream : new int[]{1, 2, 0}) {
                    var transition = encoders.get(stream).transition(symbols[stream][sequence], states[stream]);
                    bits.writeBits(transition.value(), transition.bitCount());
                    states[stream] = transition.state();
                }
            }
            int offsetCode = symbols[1][sequence];
            bits.writeBits(sequence & ((1 << offsetCode) - 1), offsetCode);
        }
        for (int stream : new int[]{2, 1, 0}) {
            bits.writeBits(states[stream], encoders.get(stream).tableLog());
        }
        block.writeBytes(bits.finish());

        byte[] expected = Arrays.copyOf(previous, previous.length + 64 * 5);
        int end = previous.length;
        int literalPosition = 0;
        for (int sequence = 0; sequence < 64; sequence++) {
            if (symbols[0][sequence] != 0) expected[end++] = literals[literalPosition++];
            int offsetCode = symbols[1][sequence];
            int distance = (1 << offsetCode) + (sequence & ((1 << offsetCode) - 1)) - 3;
            for (int count = 3 + symbols[2][sequence]; count > 0; count--) {
                expected[end] = expected[end - distance];
                end++;
            }
        }
        var decoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
        decoder.appendRaw(previous, previous.length);
        byte[] payload = block.toByteArray();
        assertEquals(ByteBuffer.wrap(Arrays.copyOfRange(expected, previous.length, end)),
                decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var truncatedDecoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
        truncatedDecoder.appendRaw(previous, previous.length);
        assertThrows(IOException.class, () -> truncatedDecoder.decodeCompressed(
                payload, payload.length - 1, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertEquals(previous.length, truncatedDecoder.frameSize());
    }

    /// Checks wide adjacent extra fields, maximum offset values, and truncated payloads.
    @ParameterizedTest
    @ValueSource(ints = {30, 31, 32, 33})
    void decodesSequenceExtraFieldBoundaries(int extraBits) throws IOException {
        int offsetBits = extraBits - 16;
        byte[] literals = new byte[32768 + 123];
        new Random(9300 + extraBits).nextBytes(literals);
        byte[] previous = new byte[2 * ZstdBlockDecoder.MAX_BLOCK_SIZE];
        new Random(9400 + extraBits).nextBytes(previous);
        for (long offsetExtra : new long[]{0, (1L << offsetBits) - 1}) {
            var block = new ByteArrayOutputStream();
            int header = (literals.length << 4) | 12;
            block.write(header);
            block.write(header >>> 8);
            block.write(header >>> 16);
            block.writeBytes(literals);
            block.write(1);
            block.write(0x54);
            block.write(34);
            block.write(offsetBits);
            block.write(32);
            var bits = new ZstdEntropy.ReverseBitWriter();
            bits.writeBits(123, 15);
            bits.writeBits(1, 1);
            bits.writeBits(offsetExtra, offsetBits);
            block.writeBytes(bits.finish());
            byte[] payload = block.toByteArray();
            int distance = (int) ((1L << offsetBits) + offsetExtra - 3);
            byte[] expected = expand(literals, previous, new byte[0], previous.length, 36, distance);
            for (boolean truncated : new boolean[]{false, true}) {
                var history = new ZstdHistory(previous.length);
                var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
                for (int offset = 0; offset < previous.length; offset += ZstdBlockDecoder.MAX_BLOCK_SIZE) {
                    byte[] part = Arrays.copyOfRange(previous, offset, offset + ZstdBlockDecoder.MAX_BLOCK_SIZE);
                    decoder.appendRaw(part, part.length);
                }
                if (truncated) {
                    assertThrows(IOException.class, () -> decoder.decodeCompressed(
                            payload, payload.length - 1, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                    assertEquals(previous.length, decoder.frameSize());
                    byte[] retained = new byte[previous.length];
                    history.copy(previous.length, retained, 0, retained.length);
                    assertArrayEquals(previous, retained);
                } else {
                    assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(
                            payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                    assertEquals(previous.length + expected.length, decoder.frameSize());
                }
            }
        }
    }

    /// Reusing history storage cannot preserve bytes, entropy tables or repeated offsets from another frame.
    @Test
    void startsIndependentStateWithReusedHistory() throws Exception {
        ZstdHistory history = new ZstdHistory(1024);
        var first = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        byte[] block = singleMatch(new byte[]{42, 43}, 7, 2);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 43, 42, 43, 42, 43, 42, 43, 42}),
                first.decodeCompressed(block, block.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertEquals(9, history.size());

        var second = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        assertEquals(0, second.frameSize());
        assertEquals(0, history.size());
        for (int i = 1; i <= 3; i++) {
            var field = ZstdBlockDecoder.class.getDeclaredField("repeatedOffset" + i);
            field.setAccessible(true);
            assertEquals(i == 1 ? 1L : i == 2 ? 4L : 8L, field.getLong(second));
        }
        byte[] historyOnly = singleMatch(new byte[0], 3, 1);
        assertThrows(IOException.class, () -> second.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var third = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        byte[] repeatTables = {0, 1, (byte) 0xfc, 1};
        assertThrows(IOException.class, () -> third.decodeCompressed(repeatTables, repeatTables.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));

        var dictionary = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(new byte[]{10, 11, 12, 13, 14, 15, 16, 17}));
        var fourth = new ZstdBlockDecoder(history, dictionary);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17}),
                fourth.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var fifth = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        assertThrows(IOException.class, () -> fifth.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
    }

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
                    expected = new ZstdBlockDecoder(new ZstdHistory(1 << 17), ZstdDictionaryContext.NONE)
                            .decodeCompressed(exact, end, ZstdBlockDecoder.MAX_BLOCK_SIZE);
                } catch (IOException exception) {
                    failure = exception;
                }
                var decoder = new ZstdBlockDecoder(new ZstdHistory(1 << 17), ZstdDictionaryContext.NONE);
                if (failure == null) {
                    assertEquals(expected, decoder.decodeCompressed(padded, end, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                } else {
                    IOException actual = assertThrows(IOException.class, () -> decoder.decodeCompressed(padded, end, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                    assertEquals(failure.getMessage(), actual.getMessage());
                    assertEquals(0, decoder.frameSize());
                }
            }
        }
        var decoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeCompressed(new byte[3], -1, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeCompressed(new byte[3], 4, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        decoder.appendRaw(new byte[]{42, 99, 99}, 1);
        byte[] match = singleMatch(new byte[0], 3, 1);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}), decoder.decodeCompressed(match, match.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertEquals(4, decoder.frameSize());
    }

    /// Reuses bounded read-only views without sharing history storage or retaining caller output.
    @Test
    void reusesReadOnlyViewsWithoutAliasingHistory() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(new ZstdHistory(131072), ZstdDictionaryContext.NONE);
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        assertNull(field.get(decoder));
        byte[] firstBlock = singleMatch(new byte[]{42}, 65539, 1);
        ByteBuffer first = decoder.decodeCompressed(firstBlock, firstBlock.length, ZstdBlockDecoder.MAX_BLOCK_SIZE);
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
        ByteBuffer second = decoder.decodeCompressed(secondBlock, secondBlock.length, ZstdBlockDecoder.MAX_BLOCK_SIZE);
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
                decoder.decodeCompressed(recentMatch, recentMatch.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        byte[] earlierMatch = singleMatch(new byte[0], 3, 14);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}),
                decoder.decodeCompressed(earlierMatch, earlierMatch.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertSame(workspace, field.get(decoder));
    }

    /// Allocates only the workspaces required by each block and reuses them for smaller sections.
    @Test
    void allocatesWorkspacesOnDemand() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
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
        assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), decoder.decodeCompressed(new byte[]{24, 4, 5, 6, 0}, 5, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertSame(rleWorkspace, outputField.get(decoder));
        assertEquals(3, assertInstanceOf(byte[].class, literalField.get(decoder)).length);
        assertEquals(9, decoder.frameSize());
        assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}),
                decoder.decodeCompressed(new byte[]{32, 1, 2, 3, 4, 0}, 6, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        byte[] literalWorkspace = assertInstanceOf(byte[].class, literalField.get(decoder));
        assertEquals(6, literalWorkspace.length, "growth doubles capacity without preserving previous literals");
        assertEquals(ByteBuffer.wrap(new byte[]{8}), decoder.decodeCompressed(new byte[]{8, 8, 0}, 3, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertSame(literalWorkspace, literalField.get(decoder));
        ByteBuffer empty = decoder.decodeCompressed(new byte[]{0, 0}, 2, ZstdBlockDecoder.MAX_BLOCK_SIZE);
        assertEquals(0, empty.capacity());
        assertSame(literalWorkspace, literalField.get(decoder));
        assertEquals(14, decoder.frameSize());
    }

    /// Reuses RLE output for later RLE and match blocks without overwriting retained history.
    @Test
    void sharesRleAndMatchWorkspace() throws Exception {
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
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
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42}), decoder.decodeCompressed(match, match.length, 128));
        byte[] matchWorkspace = assertInstanceOf(byte[].class, field.get(decoder));
        assertSame(firstWorkspace, matchWorkspace);
        assertEquals(ByteBuffer.wrap(new byte[]{9, 9}), decoder.decodeRle(9, 2));
        assertSame(matchWorkspace, field.get(decoder));
        byte[] priorRle = singleMatch(new byte[0], 3, 8);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17}), decoder.decodeCompressed(priorRle, priorRle.length, 128));
        assertEquals(143, decoder.frameSize());
    }

    /// Bounds match storage by the frame limit while preserving sequences and trailing literals.
    @Test
    void sizesOutputForFrameBlockLimit() throws Exception {
        byte[] literals = new byte[24];
        for (int i = 0; i < literals.length; i++) literals[i] = (byte) (i + 1);
        byte[] block = repeatedMatches(literals, 4, 3);
        var decoder = new ZstdBlockDecoder(new ZstdHistory(17), ZstdDictionaryContext.NONE);
        byte[] expected = new byte[36];
        for (int i = 0; i < 4; i++) Arrays.fill(expected, i * 4, i * 4 + 4, literals[i]);
        System.arraycopy(literals, 4, expected, 16, 20);
        ByteBuffer result = decoder.decodeCompressed(block, block.length, 36);
        assertEquals(ByteBuffer.wrap(expected), result);
        assertEquals(expected.length, result.capacity());
        assertThrows(ReadOnlyBufferException.class, () -> result.put(0, (byte) 0));
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        byte[] workspace = assertInstanceOf(byte[].class, field.get(decoder));
        assertEquals(36, workspace.length);
        byte[] smaller = singleMatch(new byte[]{42}, 7, 1);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 42, 42, 42, 42, 42, 42, 42}),
                decoder.decodeCompressed(smaller, smaller.length, 36));
        assertSame(workspace, field.get(decoder));
        assertEquals(44, decoder.frameSize());
        for (int maximum : new int[]{24, 35}) {
            var invalid = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
            assertThrows(IOException.class, () -> invalid.decodeCompressed(block, block.length, maximum));
            assertEquals(0, invalid.frameSize());
        }
    }

    /// Rejects output beyond a frame limit even when a previously allocated workspace has room.
    @Test
    void checksFrameLimitBeforePublishingHistory() throws Exception {
        byte[] block = repeatedMatches(new byte[]{42, 43}, 2, 3);
        for (int maximum : new int[]{0, 1, 4, 7}) {
            var history = new ZstdHistory(128);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.decodeRle(17, 128);
            assertThrows(IOException.class, () -> decoder.decodeCompressed(block, block.length, maximum));
            assertEquals(128, decoder.frameSize());
            byte[] retained = new byte[128];
            history.copy(128, retained, 0, retained.length);
            byte[] expected = new byte[128];
            Arrays.fill(expected, (byte) 17);
            assertArrayEquals(expected, retained);
        }
        var decoder = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
        assertEquals(ByteBuffer.allocate(0), decoder.decodeCompressed(new byte[]{0, 0}, 2, 0));
        assertThrows(IOException.class, () -> decoder.decodeCompressed(new byte[]{8, 42, 0}, 3, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> decoder.decodeCompressed(block, block.length, -1));
        assertThrows(IndexOutOfBoundsException.class,
                () -> decoder.decodeCompressed(block, block.length, ZstdBlockDecoder.MAX_BLOCK_SIZE + 1));
        assertEquals(0, decoder.frameSize());
    }

    /// Caps output at the block limit and never publishes history from an oversized block.
    @Test
    void boundsBlockOutput() throws Exception {
        var history = new ZstdHistory(ZstdBlockDecoder.MAX_BLOCK_SIZE);
        var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        byte[] block = repeatedMatches(new byte[]{42, 43}, 2, 65535);
        byte[] expected = new byte[ZstdBlockDecoder.MAX_BLOCK_SIZE];
        Arrays.fill(expected, 0, 65536, (byte) 42);
        Arrays.fill(expected, 65536, expected.length, (byte) 43);
        assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(block, block.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var field = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        field.setAccessible(true);
        assertEquals(expected.length, assertInstanceOf(byte[].class, field.get(decoder)).length);
        assertEquals(expected.length, decoder.frameSize());
        for (byte[] oversized : new byte[][]{
                repeatedMatches(new byte[]{42, 43}, 2, 65536),
                repeatedMatches(new byte[]{42, 43, 44}, 2, 65535)
        }) {
            var invalid = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            assertThrows(IOException.class, () -> invalid.decodeCompressed(oversized, oversized.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
            assertEquals(0, invalid.frameSize());
            assertEquals(0, history.size());
        }
    }

    /// Compares exact compressed-block decoding with a bytewise match expansion.
    ///
    /// @param length the regenerated match length
    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 258, 65539})
    void expandsMatchesAcrossSources(int length) throws IOException {
        byte[] dictionary = new byte[64];
        for (int i = 0; i < dictionary.length; i++) dictionary[i] = (byte) (i * 17);
        ZstdDictionaryContext context = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(dictionary));
        for (int capacity : new int[]{17, 1024}) {
            for (int previousSize : new int[]{0, 13, 64}) {
                byte[] previous = new byte[previousSize];
                for (int i = 0; i < previousSize; i++) previous[i] = (byte) (i * 31 + 5);
                for (int prefixSize : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 18, 23, 24, 25, 31}) {
                    byte[] prefix = new byte[prefixSize];
                    for (int i = 0; i < prefixSize; i++) prefix[i] = (byte) (i + 3);
                    for (int distance : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 15, 16, 17, 31, 32, 33, 64, 80, 128}) {
                        ZstdBlockDecoder decoder = new ZstdBlockDecoder(new ZstdHistory(capacity), context);
                        decoder.appendRaw(previous, previous.length);
                        byte[] payload = singleMatch(prefix, length, distance);
                        byte[] expected;
                        try {
                            expected = expand(prefix, previous, dictionary, capacity, length, distance);
                        } catch (IOException invalid) {
                            assertThrows(IOException.class, () -> decoder.decodeCompressed(payload, payload.length, prefixSize + length));
                            assertEquals(previousSize, decoder.frameSize());
                            continue;
                        }
                        assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(payload, payload.length, prefixSize + length),
                                "capacity=" + capacity + ", history=" + previousSize
                                        + ", prefix=" + prefixSize + ", distance=" + distance);
                        assertEquals(previousSize + expected.length, decoder.frameSize());
                    }
                }
            }
        }
    }

    /// Constructs one sequence with at most thirty-one raw literals and single-symbol FSE tables.
    private static byte[] singleMatch(byte[] prefix, int length, int distance) {
        var output = new ByteArrayOutputStream();
        output.write(prefix.length << 3);
        output.writeBytes(prefix);
        output.write(1);
        output.write(0x54);
        int literalBits = prefix.length < 16 ? 0 : prefix.length < 24 ? 1 : 2;
        int literalCode = literalBits == 0 ? prefix.length
                : literalBits == 1 ? 16 + ((prefix.length - 16) >>> 1) : 20 + ((prefix.length - 24) >>> 2);
        output.write(literalCode);
        int offsetValue = distance + 3;
        int offsetCode = 31 - Integer.numberOfLeadingZeros(offsetValue);
        output.write(offsetCode);
        output.write(length <= 34 ? length - 3 : length == 258 ? 43 : 52);
        var bits = new ZstdEntropy.ReverseBitWriter();
        bits.writeBits(prefix.length & ((1 << literalBits) - 1), literalBits);
        if (length == 258) bits.writeBits(127, 7);
        if (length == 65539) bits.writeBits(0, 16);
        bits.writeBits(offsetValue - (1L << offsetCode), offsetCode);
        output.writeBytes(bits.finish());
        return output.toByteArray();
    }

    /// Encodes short raw literals and identical distance-one sequences, each consuming one literal.
    /// The match length is either 3 or in the range covered by match code 51.
    private static byte[] repeatedMatches(byte[] literals, int count, int length) {
        var output = new ByteArrayOutputStream();
        output.write(literals.length << 3);
        output.writeBytes(literals);
        output.write(count);
        output.write(0x54);
        output.write(1);
        output.write(2);
        output.write(length == 3 ? 0 : 51);
        var bits = new ZstdEntropy.ReverseBitWriter();
        for (int i = 0; i < count; i++) {
            if (length != 3) bits.writeBits(length - 32771, 15);
            bits.writeBits(0, 2);
        }
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
