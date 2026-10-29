// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    /// Validates every transition's full destination range before publishing an expanded sequence table.
    @ParameterizedTest
    @ValueSource(ints = {31, 35, 52})
    void validatesExpandedTransitionRanges(int maximumSymbol) throws Exception {
        var constructor = ZstdEntropy.FseTable.class.getDeclaredConstructor(int.class, int[].class);
        constructor.setAccessible(true);
        Class<?> sequenceTable = Class.forName(ZstdBlockDecoder.class.getName() + "$SequenceTable");
        var expand = sequenceTable.getDeclaredMethod("expand", ZstdEntropy.FseTable.class, int.class, long[].class);
        expand.setAccessible(true);
        for (int log = 0; log <= 9; log++) {
            for (int width = 0; width <= log + 1; width++) {
                int lastBase = (1 << log) - (1 << width);
                for (int base : new int[]{0, Math.max(0, lastBase), Math.max(0, lastBase + 1)}) {
                    int[] entries = new int[1 << log];
                    Arrays.fill(entries, (base << 12) | (width << 8));
                    var table = constructor.newInstance(log, entries);
                    if (width > log || base > lastBase) {
                        var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                                () -> expand.invoke(null, table, maximumSymbol, null));
                        assertInstanceOf(IOException.class, failure.getCause());
                    } else {
                        Object expanded = expand.invoke(null, table, maximumSymbol, null);
                        assertInstanceOf(sequenceTable, expanded);
                    }
                }
            }
        }
    }

    /// Decodes both endpoints of every literal and match length code, including block-size overflow.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesLengthCodeEndpoints(boolean matchLengths) throws IOException {
        int[] bases = lengthCodeBaselines(matchLengths);
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

    /// Checks the combined sequence size at the block limit and at both length alphabets' upper endpoints.
    @ParameterizedTest
    @CsvSource({"0, 131074", "131071, 3", "65536, 65539", "131071, 131074",
            "65533, 65539", "65534, 65539", "131069, 3", "131070, 3"})
    void checksCombinedSequenceOutputSize(int literalSize, int matchSize) throws IOException {
        int[] sizes = {literalSize, matchSize};
        int[] codes = new int[2];
        int[] extras = new int[2];
        int[] widths = new int[2];
        for (int alphabet = 0; alphabet < 2; alphabet++) {
            int[] bases = lengthCodeBaselines(alphabet == 1);
            int code = 0;
            while (code + 1 < bases.length && bases[code + 1] <= sizes[alphabet]) code++;
            codes[alphabet] = code;
            extras[alphabet] = sizes[alphabet] - bases[code];
            widths[alphabet] = code + 1 < bases.length
                    ? Integer.numberOfTrailingZeros(bases[code + 1] - bases[code]) : 16;
        }
        var bits = new ZstdEntropy.ReverseBitWriter();
        bits.writeBits(extras[0], widths[0]);
        bits.writeBits(extras[1], widths[1]);
        bits.writeBits(0, 2);
        var block = new ByteArrayOutputStream();
        int header = (literalSize << 4) | 13;
        block.write(header);
        block.write(header >>> 8);
        block.write(header >>> 16);
        block.write(42);
        block.write(1);
        block.write(0x54);
        block.write(codes[0]);
        block.write(2);
        block.write(codes[1]);
        block.writeBytes(bits.finish());
        byte[] payload = block.toByteArray();
        int outputSize = literalSize + matchSize;
        for (int capacity : new int[]{128, ZstdBlockDecoder.MAX_BLOCK_SIZE}) {
            var history = new ZstdHistory(capacity);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.appendRaw(new byte[]{42}, 1);
            if (outputSize <= ZstdBlockDecoder.MAX_BLOCK_SIZE) {
                byte[] expected = new byte[outputSize];
                Arrays.fill(expected, (byte) 42);
                assertEquals(ByteBuffer.wrap(expected),
                        decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                assertEquals(1L + outputSize, decoder.frameSize());
            } else {
                assertThrows(IOException.class,
                        () -> decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                assertEquals(1, decoder.frameSize());
                assertEquals(1, history.size());
                byte[] retained = new byte[1];
                history.copy(1, retained, 0, 1);
                assertArrayEquals(new byte[]{42}, retained);
            }
        }
    }

    /// Checks every dynamic length-table state at both ends of its additional-bit range.
    @ParameterizedTest
    @CsvSource({"false, 6", "false, 7", "false, 8", "false, 9", "true, 6", "true, 7", "true, 8", "true, 9"})
    void decodesDynamicLengthCodeEndpoints(boolean matchLengths, int tableLog) throws IOException {
        int[] bases = lengthCodeBaselines(matchLengths);
        int tableSize = 1 << tableLog;
        int[] probabilities = new int[bases.length];
        Arrays.fill(probabilities, 1);
        for (int i = 0; i < tableSize - probabilities.length; i++) probabilities[i % probabilities.length]++;
        var table = ZstdEntropy.FseTable.fromNormalized(probabilities, probabilities.length, tableLog);
        byte[] description = ZstdEntropy.encodeFseTableDescription(probabilities, probabilities.length, tableLog);
        for (int state = 0; state < tableSize; state++) {
            int code = table.symbol(state);
            int width = code + 1 < bases.length ? bases[code + 1] - bases[code] : 1 << 16;
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
                block.write(matchLengths ? 0x58 : 0x94);
                if (matchLengths) block.write(1);
                else block.writeBytes(description);
                block.write(2);
                if (matchLengths) block.writeBytes(description);
                else block.write(0);
                var bits = new ZstdEntropy.ReverseBitWriter();
                bits.writeBits(extra, Integer.numberOfTrailingZeros(width));
                bits.writeBits(0, 2);
                bits.writeBits(state, tableLog);
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
                            "matchLengths=" + matchLengths + ", state=" + state + ", extra=" + extra);
                }
            }
        }
    }

    /// Exercises every destination of a maximum-width FSE transition and its upper successor in each alphabet.
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 9})
    void decodesFullWidthSequenceTransitions(int tableLog) throws IOException {
        int tableSize = 1 << tableLog;
        for (int alphabet = 0; alphabet < 3; alphabet++) {
            if (alphabet == 1 && tableLog > 8) continue;
            int frequent = alphabet == 1 ? 2 : 0;
            int rare = frequent + 1;
            int[] probabilities = new int[rare + 1];
            probabilities[frequent] = tableSize - 1;
            probabilities[rare] = 1;
            var table = ZstdEntropy.FseTable.fromNormalized(probabilities, probabilities.length, tableLog);
            byte[] description = ZstdEntropy.encodeFseTableDescription(probabilities, probabilities.length, tableLog);
            int initialState = 0;
            while (table.symbol(initialState) != rare) initialState++;
            assertEquals(tableLog, table.numberOfBits(initialState));
            assertEquals(0, table.baseline(initialState));
            for (int nextState = 0; nextState < tableSize; nextState++) {
                int nextSymbol = table.symbol(nextState);
                int nextWidth = table.numberOfBits(nextState);
                int nextValue = (1 << nextWidth) - 1;
                int finalSymbol = table.symbol(table.baseline(nextState) + nextValue);
                int literalCount = alphabet == 0 ? 1 + nextSymbol + finalSymbol : 3;
                int matchCount = alphabet == 2 ? 10 + nextSymbol + finalSymbol : 9;
                var block = new ByteArrayOutputStream();
                block.write((literalCount << 3) | 1);
                block.write(42);
                block.write(3);
                block.write(0x54 + (1 << (6 - alphabet * 2)));
                for (int stream = 0; stream < 3; stream++) {
                    if (stream == alphabet) block.writeBytes(description);
                    else block.write(stream == 0 ? 1 : stream == 1 ? 2 : 0);
                }
                var bits = new ZstdEntropy.ReverseBitWriter();
                // All offsets are explicit; only the selected alphabet has state-transition bits.
                bits.writeBits(0, alphabet == 1 ? finalSymbol : 2);
                bits.writeBits(nextValue, nextWidth);
                bits.writeBits(0, alphabet == 1 ? nextSymbol : 2);
                bits.writeBits(nextState, tableLog);
                bits.writeBits(0, alphabet == 1 ? rare : 2);
                bits.writeBits(initialState, tableLog);
                block.writeBytes(bits.finish());
                byte[] input = block.toByteArray();
                var decoder = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
                byte[] previous = {11, 23, 37, 53, 71, 89, 107, 127};
                decoder.appendRaw(previous, previous.length);
                int outputSize = literalCount + matchCount;
                byte[] expected = Arrays.copyOf(previous, previous.length + outputSize);
                int output = previous.length;
                for (int symbol : new int[]{rare, nextSymbol, finalSymbol}) {
                    int literals = alphabet == 0 ? symbol : 1;
                    int matches = alphabet == 2 ? 3 + symbol : 3;
                    int distance = alphabet == 1 ? (1 << symbol) - 3 : 1;
                    for (int i = 0; i < literals; i++) expected[output++] = 42;
                    for (int i = 0; i < matches; i++, output++) expected[output] = expected[output - distance];
                }
                assertEquals(ByteBuffer.wrap(expected, previous.length, outputSize),
                        decoder.decodeCompressed(input, input.length, outputSize),
                        "alphabet=" + alphabet + ", log=" + tableLog + ", nextState=" + nextState);
            }
        }
    }

    /// Replaces and repeats length tables across size and extra-bit changes without changing predefined tables.
    @Test
    void replacesSequenceTablesAcrossBlocks() throws IOException {
        var decoder = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
        long expectedFrameSize = 0;
        for (int log : new int[]{-1, 9, 5, 8, 0, 6, -1, 5}) {
            int literalCode = (log & 1) == 0 ? 16 : 1;
            int literalCount = literalCode == 16 ? 17 : 1;
            int sequenceCount = 3;
            byte[] expected = new byte[(literalCount + 3) * sequenceCount];
            Arrays.fill(expected, (byte) 42);
            var description = new ByteArrayOutputStream();
            ZstdEntropy.FseTable table;
            int mode;
            if (log < 0) {
                table = ZstdSequenceEntropy.LITERAL_LENGTH_DECODER;
                mode = 0;
            } else if (log == 0) {
                table = ZstdEntropy.FseTable.rle(literalCode);
                description.write(literalCode);
                mode = 1;
            } else {
                int[] counts = new int[literalCode + 1];
                counts[0] = (1 << log) - 1;
                counts[literalCode] = 1;
                table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, log);
                description.writeBytes(ZstdEntropy.encodeFseTableDescription(counts, counts.length, log));
                mode = 2;
            }
            var encoder = ZstdEntropy.FseEncoderTable.fromDecoder(table, log < 0 ? 36 : literalCode + 1);
            for (boolean repeat : new boolean[]{false, true}) {
                var block = new ByteArrayOutputStream();
                int totalLiterals = literalCount * sequenceCount;
                int header = (totalLiterals << 4) | 5;
                block.writeBytes(new byte[]{(byte) header, (byte) (header >>> 8), 42, (byte) sequenceCount});
                block.write(repeat ? 0xfc : (mode << 6) | 0x14);
                if (!repeat) {
                    block.writeBytes(description.toByteArray());
                    block.write(2);
                    block.write(0);
                }
                var bits = new ZstdEntropy.ReverseBitWriter();
                int state = encoder.initialState(literalCode);
                for (int sequence = sequenceCount - 1; sequence >= 0; sequence--) {
                    if (sequence + 1 < sequenceCount) {
                        var transition = encoder.transition(literalCode, state);
                        bits.writeBits(transition.value(), transition.bitCount());
                        state = transition.state();
                    }
                    if (literalCode == 16) bits.writeBits(1, 1);
                    bits.writeBits(0, 2);
                }
                bits.writeBits(state, table.tableLog());
                block.writeBytes(bits.finish());
                byte[] input = block.toByteArray();
                assertEquals(ByteBuffer.wrap(expected),
                        decoder.decodeCompressed(input, input.length, expected.length));
                expectedFrameSize += expected.length;
                assertEquals(expectedFrameSize, decoder.frameSize());
                if (log < 0 && !repeat) {
                    var independent = new ZstdBlockDecoder(new ZstdHistory(128), ZstdDictionaryContext.NONE);
                    assertEquals(ByteBuffer.wrap(expected),
                            independent.decodeCompressed(input, input.length, expected.length));
                }
            }
        }
    }

    /// Returns reference bases for the literal-length or match-length alphabet.
    private static int[] lengthCodeBaselines(boolean matchLengths) {
        return matchLengths
                ? new int[]{3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
                35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027,
                2051, 4099, 8195, 16387, 32771, 65539}
                : new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024, 2048,
                4096, 8192, 16384, 32768, 65536};
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

    /// Interleaves raw literals and short matches in separate and shared storage, including exact-sized tails.
    @ParameterizedTest
    @CsvSource({"3, 1024", "4, 1024", "5, 1024", "6, 1024", "7, 1024", "8, 1024", "9, 1024",
            "15, 1024", "16, 1024", "17, 1024",
            "3, 131072", "4, 131072", "5, 131072", "6, 131072", "7, 131072", "8, 131072", "9, 131072",
            "15, 131072", "16, 131072", "17, 131072"})
    void decodesShortRunsBetweenMatches(int matchLength, int capacity) throws IOException {
        byte[] previous = new byte[32];
        new Random(9800).nextBytes(previous);
        for (int literalLength = 0; literalLength <= 17; literalLength++) {
            for (int tail = 0; tail <= 17; tail++) {
                byte[] literals = new byte[literalLength * 16 + tail];
                new Random(9900 + literalLength * 8 + tail).nextBytes(literals);
                var block = new ByteArrayOutputStream();
                int header = literals.length < 32 ? literals.length << 3 : (literals.length << 4) | 4;
                block.write(header);
                if (literals.length >= 32) block.write(header >>> 8);
                block.writeBytes(literals);
                block.write(16);
                block.write(0x54);
                block.write(Math.min(literalLength, 16));
                block.write(4);
                block.write(matchLength - 3);
                var bits = new ZstdEntropy.ReverseBitWriter();
                // Offset code 4 with extra value 5 encodes distance 16 + 5 - 3 = 18.
                for (int sequence = 0; sequence < 16; sequence++) {
                    if (literalLength >= 16) bits.writeBits(literalLength & 1, 1);
                    bits.writeBits(5, 4);
                }
                block.writeBytes(bits.finish());

                byte[] expected = Arrays.copyOf(previous, previous.length + literals.length + matchLength * 16);
                int position = previous.length;
                int literalPosition = 0;
                for (int sequence = 0; sequence < 16; sequence++) {
                    for (int count = 0; count < literalLength; count++) {
                        expected[position++] = literals[literalPosition++];
                    }
                    for (int count = 0; count < matchLength; count++, position++) {
                        expected[position] = expected[position - 18];
                    }
                }
                System.arraycopy(literals, literalPosition, expected, position, tail);
                var history = new ZstdHistory(capacity);
                var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
                decoder.appendRaw(previous, previous.length);
                byte[] input = block.toByteArray();
                int size = expected.length - previous.length;
                assertEquals(ByteBuffer.wrap(expected, previous.length, size),
                        decoder.decodeCompressed(input, input.length, size),
                        "literals=" + literalLength + ", tail=" + tail + ", match=" + matchLength);
                assertEquals(expected.length, decoder.frameSize());
                byte[] retained = new byte[expected.length];
                history.copy(retained.length, retained, 0, retained.length);
                assertArrayEquals(expected, retained);
            }
        }
    }

    /// Does not commit literal lookahead when unread entropy bits invalidate the block.
    @ParameterizedTest
    @CsvSource({"8, 11", "8, 16", "16, 19", "16, 24", "17, 20", "17, 25"})
    void rejectsTrailingBitsAfterShortLiterals(int literalCount, int maximumSize) throws IOException {
        byte[] previous = {37, 53};
        var block = new ByteArrayOutputStream();
        block.write(literalCount << 3);
        for (int i = 0; i < literalCount; i++) block.write(17 + i);
        // One literal plus a distance-one match, then an unused byte before the end marker.
        block.writeBytes(new byte[]{1, 0x54, 1, 2, 0, 0, 4});
        byte[] input = block.toByteArray();
        for (int capacity : new int[]{1024, 131072}) {
            var history = new ZstdHistory(capacity);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.appendRaw(previous, previous.length);
            IOException failure = assertThrows(IOException.class,
                    () -> decoder.decodeCompressed(input, input.length, maximumSize));
            assertTrue(failure.getMessage().contains("unused bits"));
            assertEquals(previous.length, decoder.frameSize());
            assertEquals(previous.length, history.size());
            byte[] retained = new byte[previous.length];
            history.copy(retained.length, retained, 0, retained.length);
            assertArrayEquals(previous, retained);
        }
    }

    /// Commits only regenerated match bytes at each short-copy boundary, including reused larger workspaces.
    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17})
    void keepsShortMatchLookaheadOutOfHistory(int length) throws IOException {
        byte[] previous = new byte[64];
        new Random(10000).nextBytes(previous);
        for (int prefixSize : new int[]{1, 8, 16, 31}) {
            byte[] prefix = new byte[prefixSize];
            new Random(10001 + prefixSize).nextBytes(prefix);
            for (int distance : new int[]{3, 4, 5, 7, 8, 9, 10, 15, 16, 17, 32}) {
                byte[] expected = expand(prefix, previous, new byte[0], 1024, length, distance);
                byte[] input = singleMatch(prefix, length, distance);
                for (int spare = 0; spare <= Long.BYTES; spare++) {
                    for (boolean reuse : new boolean[]{false, true}) {
                        var history = new ZstdHistory(1024);
                        var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
                        if (reuse) {
                            byte[] warmup = singleMatch(new byte[]{42}, 258, 1);
                            decoder.decodeCompressed(warmup, warmup.length, 512);
                        }
                        decoder.appendRaw(previous, previous.length);
                        long startSize = decoder.frameSize();
                        ByteBuffer result = decoder.decodeCompressed(input, input.length, expected.length + spare);
                        assertEquals(ByteBuffer.wrap(expected), result);
                        assertEquals(expected.length, result.capacity());
                        assertEquals(startSize + expected.length, decoder.frameSize());
                        assertEquals(startSize + expected.length, history.size());
                        byte[] retained = new byte[expected.length];
                        history.copy(expected.length, retained, 0, retained.length);
                        assertArrayEquals(expected, retained);

                        // The next block must start from the last real match byte, not any speculative stores.
                        byte[] followup = singleMatch(new byte[0], 3, 1);
                        byte last = expected[expected.length - 1];
                        assertEquals(ByteBuffer.wrap(new byte[]{last, last, last}),
                                decoder.decodeCompressed(followup, followup.length, 3));
                    }
                }
            }
        }
    }

    /// Leaves committed history unchanged when trailing entropy bits invalidate a block after a short match.
    @ParameterizedTest
    @ValueSource(ints = {3, 4, 5, 6, 7, 8, 9, 15, 16, 17})
    void rejectsTrailingBitsAfterShortMatch(int length) throws IOException {
        byte[] prefix = new byte[16];
        new Random(10100).nextBytes(prefix);
        byte[] valid = singleMatch(prefix, length, 8);
        byte[] invalid = new byte[valid.length + 1];
        System.arraycopy(valid, 0, invalid, 0, valid.length - 1);
        // Insert an unread zero byte before the single-byte reverse bitstream.
        invalid[valid.length] = valid[valid.length - 1];
        for (int capacity : new int[]{128, 131072}) {
            var history = new ZstdHistory(capacity);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.appendRaw(prefix, prefix.length);
            IOException failure = assertThrows(IOException.class,
                    () -> decoder.decodeCompressed(invalid, invalid.length, 64));
            assertTrue(failure.getMessage().contains("unused bits"));
            assertEquals(prefix.length, decoder.frameSize());
            assertEquals(prefix.length, history.size());
            byte[] retained = new byte[prefix.length];
            history.copy(prefix.length, retained, 0, retained.length);
            assertArrayEquals(prefix, retained);
        }
    }

    /// Copies RLE-decoded literal tails from an exact-sized workspace into an exact-sized output.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 16, 17, 18, 23, 24, 25, 31, 32, 33})
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
        for (int count : new int[]{1, 2, 31, 32, 33, 63, 64, 65}) {
            int[][] probabilities = {{511, 1}, {0, 0, 0, 255, 1}, {511, 1}};
            int[] logs = {9, 8, 9};
            int[] rare = {1, 4, 1};
            int[] periods = {3, 7, 5};
            int[][] symbols = new int[3][count];
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
                for (int sequence = 0; sequence < count; sequence++) {
                    symbols[stream][sequence] = rle || sequence % periods[stream] == 0 ? rare[stream] : rare[stream] - 1;
                }
            }
            byte[] literals = new byte[Arrays.stream(symbols[0]).sum()];
            new Random(9600 + rleMask).nextBytes(literals);
            int header = (literals.length << 4) | 4;
            block.write(header);
            block.write(header >>> 8);
            block.writeBytes(literals);
            block.write(count);
            block.write(modes);
            block.writeBytes(descriptions.toByteArray());

            var bits = new ZstdEntropy.ReverseBitWriter();
            int[] states = new int[3];
            for (int stream = 0; stream < 3; stream++) {
                states[stream] = encoders.get(stream).initialState(symbols[stream][count - 1]);
            }
            for (int sequence = count - 1; sequence >= 0; sequence--) {
                if (sequence < count - 1) {
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

            byte[] expected = Arrays.copyOf(previous, previous.length + count * 5);
            int end = previous.length;
            int literalPosition = 0;
            for (int sequence = 0; sequence < count; sequence++) {
                if (symbols[0][sequence] != 0) expected[end++] = literals[literalPosition++];
                int offsetCode = symbols[1][sequence];
                int distance = (1 << offsetCode) + (sequence & ((1 << offsetCode) - 1)) - 3;
                for (int remaining = 3 + symbols[2][sequence]; remaining > 0; remaining--) {
                    expected[end] = expected[end - distance];
                    end++;
                }
            }
            var decoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
            decoder.appendRaw(previous, previous.length);
            byte[] payload = block.toByteArray();
            assertEquals(ByteBuffer.wrap(Arrays.copyOfRange(expected, previous.length, end)),
                    decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
            if (count == 64) {
                var truncatedDecoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
                truncatedDecoder.appendRaw(previous, previous.length);
                assertThrows(IOException.class, () -> truncatedDecoder.decodeCompressed(
                        payload, payload.length - 1, ZstdBlockDecoder.MAX_BLOCK_SIZE));
                assertEquals(previous.length, truncatedDecoder.frameSize());
            }
            byte[] missingMarker = payload.clone();
            missingMarker[missingMarker.length - 1] = 0;
            var invalidDecoder = new ZstdBlockDecoder(new ZstdHistory(1024), ZstdDictionaryContext.NONE);
            invalidDecoder.appendRaw(previous, previous.length);
            assertThrows(IOException.class, () -> invalidDecoder.decodeCompressed(
                    missingMarker, missingMarker.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
            assertEquals(previous.length, invalidDecoder.frameSize());
        }
    }

    /// Reads a wide final sequence without consuming the transition bits advertised by its three states.
    @ParameterizedTest
    @ValueSource(ints = {27, 28, 29, 30, 31})
    void readsWideFinalSequenceWithoutTransitions(int offsetCode) throws IOException {
        int[] symbols = {34, offsetCode, 51};
        int[] logs = {9, 8, 9};
        int[] states = new int[3];
        var descriptions = new ByteArrayOutputStream();
        for (int stream = 0; stream < 3; stream++) {
            int[] counts = new int[symbols[stream] + 1];
            counts[0] = (1 << logs[stream]) - 1;
            counts[symbols[stream]] = 1;
            var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, logs[stream]);
            var encoder = ZstdEntropy.FseEncoderTable.fromDecoder(table, counts.length);
            states[stream] = encoder.initialState(symbols[stream]);
            assertEquals(logs[stream], table.numberOfBits(states[stream]));
            descriptions.writeBytes(ZstdEntropy.encodeFseTableDescription(counts, counts.length, logs[stream]));
        }
        var bits = new ZstdEntropy.ReverseBitWriter();
        bits.writeBits(0, 15);
        bits.writeBits(0, 15);
        bits.writeBits(0, offsetCode);
        for (int stream : new int[]{2, 1, 0}) bits.writeBits(states[stream], logs[stream]);

        var block = new ByteArrayOutputStream();
        int header = (32768 << 4) | 13;
        block.write(header);
        block.write(header >>> 8);
        block.write(header >>> 16);
        block.write(42);
        block.write(1);
        block.write(0xa8);
        block.writeBytes(descriptions.toByteArray());
        block.writeBytes(bits.finish());
        byte[] payload = block.toByteArray();
        var history = new ZstdHistory(ZstdBlockDecoder.MAX_BLOCK_SIZE);
        var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        decoder.appendRaw(new byte[]{17}, 1);
        // The fields are complete; the unavailable offset, not a truncated transition, must reject this block.
        IOException failure = assertThrows(IOException.class,
                () -> decoder.decodeCompressed(payload, payload.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertEquals("Zstandard match offset exceeds available history", failure.getMessage());
        assertEquals(1, decoder.frameSize());
        assertEquals(1, history.size());
        byte[] retained = new byte[1];
        history.copy(1, retained, 0, 1);
        assertArrayEquals(new byte[]{17}, retained);
    }

    /// Crosses the combined-read limit and a machine-word boundary, including all state transitions.
    @ParameterizedTest
    @ValueSource(ints = {56, 57, 58, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72})
    void decodesCombinedSequenceReadBoundary(int totalBits) throws IOException {
        boolean wide = totalBits >= Long.SIZE - 1;
        int offsetBits = wide ? totalBits - 57 : 2;
        int matchBits = wide ? 16 : 15;
        int[] rare = {34, offsetBits, wide ? 52 : 51};
        int[] common = {0, 3, 0};
        int[] logs = {9, wide ? 8 : totalBits - 50, 9};
        var descriptions = new ByteArrayOutputStream();
        var encoders = new ArrayList<ZstdEntropy.FseEncoderTable>(3);
        for (int stream = 0; stream < 3; stream++) {
            int[] counts = new int[Math.max(rare[stream], common[stream]) + 1];
            counts[rare[stream]] = 1;
            counts[common[stream]] = (1 << logs[stream]) - 1;
            var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, logs[stream]);
            encoders.add(ZstdEntropy.FseEncoderTable.fromDecoder(table, counts.length));
            descriptions.writeBytes(ZstdEntropy.encodeFseTableDescription(counts, counts.length, logs[stream]));
        }
        var bits = new ZstdEntropy.ReverseBitWriter();
        bits.writeBits(0, 3); // The final sequence has no length extras or state transitions.
        int[] states = new int[3];
        int transitionBits = 0;
        for (int stream : new int[]{1, 2, 0}) {
            var encoder = encoders.get(stream);
            var transition = encoder.transition(rare[stream], encoder.initialState(common[stream]));
            bits.writeBits(transition.value(), transition.bitCount());
            states[stream] = transition.state();
            transitionBits += transition.bitCount();
        }
        assertEquals(totalBits, transitionBits + 15 + matchBits + offsetBits);
        bits.writeBits(7, 15);
        bits.writeBits(11, matchBits);
        bits.writeBits(0, offsetBits);
        for (int stream : new int[]{2, 1, 0}) bits.writeBits(states[stream], logs[stream]);

        var block = new ByteArrayOutputStream();
        int header = ((32768 + 7) << 4) | 13;
        block.write(header);
        block.write(header >>> 8);
        block.write(header >>> 16);
        block.write(42);
        block.write(2);
        block.write(0xa8);
        block.writeBytes(descriptions.toByteArray());
        block.writeBytes(bits.finish());
        byte[] input = block.toByteArray();
        byte[] expected = new byte[32768 + 7 + (wide ? 65539 : 32771) + 11 + 3];
        Arrays.fill(expected, (byte) 42);
        var decoder = new ZstdBlockDecoder(new ZstdHistory(ZstdBlockDecoder.MAX_BLOCK_SIZE), ZstdDictionaryContext.NONE);
        assertEquals(ByteBuffer.wrap(expected), decoder.decodeCompressed(input, input.length, expected.length));
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

    /// Preserves repeated offsets within sequences and across compressed, raw and run-length blocks.
    @ParameterizedTest
    @CsvSource({"1,0", "2,0", "3,0", "1,1", "2,1", "3,1"})
    void preservesRepeatedOffsetsAcrossBlocks(int offsetValue, int literalLength) throws Exception {
        for (int count : new int[]{1, 2, 5}) {
            var decoder = new ZstdBlockDecoder(new ZstdHistory(2048), ZstdDictionaryContext.NONE);
            byte[] expected = new byte[2048];
            new Random(12000).nextBytes(expected);
            int end = 64;
            decoder.appendRaw(Arrays.copyOf(expected, end), end);
            for (int distance : new int[]{7, 11, 19}) {
                byte[] payload = singleMatch(new byte[0], 3, distance);
                for (int i = 0; i < 3; i++, end++) expected[end] = expected[end - distance];
                assertEquals(ByteBuffer.wrap(Arrays.copyOfRange(expected, end - 3, end)),
                        decoder.decodeCompressed(payload, payload.length, 3));
            }
            int[] offsets = {19, 11, 7};
            for (int block = 0; block < 2; block++) {
                var payload = new ByteArrayOutputStream();
                payload.write((count * literalLength) << 3);
                for (int i = 0; i < count * literalLength; i++) payload.write(42 + i);
                payload.writeBytes(new byte[]{(byte) count, 0x54, (byte) literalLength,
                        (byte) (offsetValue == 1 ? 0 : 1), 0});
                var bits = new ZstdEntropy.ReverseBitWriter();
                for (int i = 0; i < count; i++) {
                    bits.writeBits(offsetValue == 3 ? 1 : 0, offsetValue == 1 ? 0 : 1);
                }
                payload.writeBytes(bits.finish());
                int start = end;
                for (int i = 0; i < count; i++) {
                    if (literalLength != 0) expected[end++] = (byte) (42 + i);
                    int index = offsetValue - 1 + (literalLength == 0 ? 1 : 0);
                    int distance = index == 3 ? offsets[0] - 1 : offsets[index];
                    if (index != 0) {
                        for (int j = Math.min(index, 2); j > 0; j--) offsets[j] = offsets[j - 1];
                        offsets[0] = distance;
                    }
                    for (int j = 0; j < 3; j++, end++) expected[end] = expected[end - distance];
                }
                byte[] bytes = payload.toByteArray();
                assertEquals(ByteBuffer.wrap(Arrays.copyOfRange(expected, start, end)),
                        decoder.decodeCompressed(bytes, bytes.length, end - start));
                for (int i = 0; i < offsets.length; i++) {
                    var field = ZstdBlockDecoder.class.getDeclaredField("repeatedOffset" + (i + 1));
                    field.setAccessible(true);
                    assertEquals(offsets[i], field.getLong(decoder));
                }
                decoder.appendRaw(new byte[]{99}, 1);
                expected[end++] = 99;
                assertEquals(ByteBuffer.wrap(new byte[]{11, 11, 11}), decoder.decodeRle(11, 3));
                Arrays.fill(expected, end, end + 3, (byte) 11);
                end += 3;
                byte[] literalOnly = {8, 42, 0};
                assertEquals(ByteBuffer.wrap(new byte[]{42}), decoder.decodeCompressed(literalOnly, 3, 1));
                expected[end++] = 42;
            }
            assertEquals(end, decoder.frameSize());
        }
    }

    /// Preserves unsigned offset extremes and rejects distances beyond history without committing output.
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31})
    void decodesExplicitOffsetExtremes(int offsetCode) throws IOException {
        for (long extra : new long[]{0, (1L << offsetCode) - 1}) {
            byte[] previous = new byte[64];
            for (int index = 0; index < previous.length; index++) previous[index] = (byte) (index * 37 + 11);
            var history = new ZstdHistory(128);
            var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
            decoder.appendRaw(previous, previous.length);
            var payload = new ByteArrayOutputStream();
            payload.writeBytes(new byte[]{0, 1, 0x54, 0, (byte) offsetCode, 0});
            var bits = new ZstdEntropy.ReverseBitWriter();
            bits.writeBits(extra, offsetCode);
            payload.writeBytes(bits.finish());
            byte[] block = payload.toByteArray();
            long distance = (1L << offsetCode) + extra - 3;
            if (distance > previous.length) {
                IOException failure = assertThrows(IOException.class,
                        () -> decoder.decodeCompressed(block, block.length, 3));
                assertEquals("Zstandard match offset exceeds available history", failure.getMessage());
                assertEquals(previous.length, decoder.frameSize());
                byte[] retained = new byte[previous.length];
                history.copy(previous.length, retained, 0, retained.length);
                assertArrayEquals(previous, retained);
            } else {
                byte[] expanded = Arrays.copyOf(previous, previous.length + 3);
                for (int index = previous.length; index < expanded.length; index++) {
                    expanded[index] = expanded[index - (int) distance];
                }
                assertEquals(ByteBuffer.wrap(Arrays.copyOfRange(expanded, previous.length, expanded.length)),
                        decoder.decodeCompressed(block, block.length, 3));
                assertEquals(expanded.length, decoder.frameSize());
            }
        }
    }

    /// Decodes every dynamic offset-table state at both extra-bit endpoints, including repeat selectors.
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8})
    void decodesDynamicOffsetCodeEndpoints(int tableLog) throws IOException {
        int[] counts = new int[32];
        Arrays.fill(counts, (1 << tableLog) / counts.length);
        var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, tableLog);
        byte[] description = ZstdEntropy.encodeFseTableDescription(counts, counts.length, tableLog);
        byte[] previous = new byte[64];
        for (int index = 0; index < previous.length; index++) previous[index] = (byte) (index * 37 + 11);
        for (int state = 0; state < 1 << tableLog; state++) {
            int code = table.symbol(state);
            for (long extra : new long[]{0, (1L << code) - 1}) {
                for (int literalCount = 0; literalCount <= 1; literalCount++) {
                    var history = new ZstdHistory(128);
                    var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
                    decoder.appendRaw(previous, previous.length);
                    var block = new ByteArrayOutputStream();
                    block.write(literalCount << 3);
                    if (literalCount != 0) block.write(42);
                    block.writeBytes(new byte[]{1, 0x64, (byte) literalCount});
                    block.writeBytes(description);
                    block.write(0);
                    var bits = new ZstdEntropy.ReverseBitWriter();
                    bits.writeBits(extra, code);
                    bits.writeBits(state, tableLog);
                    block.writeBytes(bits.finish());
                    byte[] input = block.toByteArray();
                    long encodedOffset = (1L << code) + extra;
                    long distance;
                    if (encodedOffset > 3) {
                        distance = encodedOffset - 3;
                    } else {
                        int selector = (int) encodedOffset + (literalCount == 0 ? 1 : 0);
                        distance = switch (selector) {
                            case 1 -> 1;
                            case 2 -> 4;
                            case 3 -> 8;
                            case 4 -> 0;
                            default -> throw new AssertionError(selector);
                        };
                    }
                    int outputSize = literalCount + 3;
                    if (distance == 0 || distance > previous.length + literalCount) {
                        IOException failure = assertThrows(IOException.class,
                                () -> decoder.decodeCompressed(input, input.length, outputSize));
                        assertEquals(distance == 0 ? "Invalid zero Zstandard repeated offset"
                                : "Zstandard match offset exceeds available history", failure.getMessage());
                        assertEquals(previous.length, decoder.frameSize());
                        byte[] retained = new byte[previous.length];
                        history.copy(previous.length, retained, 0, retained.length);
                        assertArrayEquals(previous, retained);
                    } else {
                        byte[] expected = Arrays.copyOf(previous, previous.length + outputSize);
                        int position = previous.length;
                        if (literalCount != 0) expected[position++] = 42;
                        while (position < expected.length) {
                            expected[position] = expected[position - (int) distance];
                            position++;
                        }
                        assertEquals(ByteBuffer.wrap(expected, previous.length, outputSize),
                                decoder.decodeCompressed(input, input.length, outputSize),
                                "log=" + tableLog + ", state=" + state + ", extra=" + extra
                                        + ", literals=" + literalCount);
                    }
                }
            }
        }
    }

    /// Rejects the zero distance obtained by subtracting one from the initial repeated offset.
    @Test
    void rejectsZeroRepeatedOffsetWithoutLiterals() throws IOException {
        var history = new ZstdHistory(128);
        var decoder = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        byte[] previous = {10, 11, 12, 13};
        decoder.appendRaw(previous, previous.length);
        byte[] payload = {0, 1, 0x54, 0, 1, 0, 3};
        IOException failure = assertThrows(IOException.class,
                () -> decoder.decodeCompressed(payload, payload.length, 3));
        assertEquals("Invalid zero Zstandard repeated offset", failure.getMessage());
        assertEquals(previous.length, decoder.frameSize());
        byte[] retained = new byte[previous.length];
        history.copy(previous.length, retained, 0, retained.length);
        assertArrayEquals(previous, retained);
    }

    /// Reusing history storage cannot preserve bytes, entropy tables or repeated offsets from another frame.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void startsIndependentStateWithReusedHistory(boolean reuseDecoder) throws Exception {
        ZstdHistory history = new ZstdHistory(1024);
        var first = new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        byte[] block = singleMatch(new byte[]{42, 43}, 7, 2);
        assertEquals(ByteBuffer.wrap(new byte[]{42, 43, 42, 43, 42, 43, 42, 43, 42}),
                first.decodeCompressed(block, block.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        assertEquals(9, history.size());

        var second = reuseDecoder ? first : new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        if (reuseDecoder) second.reset(ZstdDictionaryContext.NONE);
        assertEquals(0, second.frameSize());
        assertEquals(0, history.size());
        for (int i = 1; i <= 3; i++) {
            var field = ZstdBlockDecoder.class.getDeclaredField("repeatedOffset" + i);
            field.setAccessible(true);
            assertEquals(i == 1 ? 1L : i == 2 ? 4L : 8L, field.getLong(second));
        }
        byte[] historyOnly = singleMatch(new byte[0], 3, 1);
        assertThrows(IOException.class, () -> second.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var third = reuseDecoder ? second : new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        if (reuseDecoder) third.reset(ZstdDictionaryContext.NONE);
        byte[] repeatTables = {0, 1, (byte) 0xfc, 1};
        assertThrows(IOException.class, () -> third.decodeCompressed(repeatTables, repeatTables.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));

        var dictionary = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(new byte[]{10, 11, 12, 13, 14, 15, 16, 17}));
        var fourth = reuseDecoder ? third : new ZstdBlockDecoder(history, dictionary);
        if (reuseDecoder) fourth.reset(dictionary);
        assertEquals(ByteBuffer.wrap(new byte[]{17, 17, 17}),
                fourth.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
        var fifth = reuseDecoder ? fourth : new ZstdBlockDecoder(history, ZstdDictionaryContext.NONE);
        if (reuseDecoder) fifth.reset(ZstdDictionaryContext.NONE);
        assertThrows(IOException.class, () -> fifth.decodeCompressed(historyOnly, historyOnly.length, ZstdBlockDecoder.MAX_BLOCK_SIZE));
    }

    /// Retains byte workspaces without carrying dictionary references or entropy tables into another frame.
    @Test
    void clearsFrameStateWithoutDiscardingByteWorkspaces() throws Exception {
        var history = new ZstdHistory(1024);
        var dictionary = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}));
        var decoder = new ZstdBlockDecoder(history, dictionary);
        var literalField = ZstdBlockDecoder.class.getDeclaredField("literals");
        var outputField = ZstdBlockDecoder.class.getDeclaredField("outputWorkspace");
        var dictionaryField = ZstdBlockDecoder.class.getDeclaredField("dictionary");
        literalField.setAccessible(true);
        outputField.setAccessible(true);
        dictionaryField.setAccessible(true);
        byte[] literals = {24, 4, 5, 6, 0};
        assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), decoder.decodeCompressed(literals, literals.length, 16));
        byte[] match = singleMatch(new byte[]{7}, 3, 1);
        assertEquals(ByteBuffer.wrap(new byte[]{7, 7, 7, 7}), decoder.decodeCompressed(match, match.length, 16));
        byte[] literalWorkspace = assertInstanceOf(byte[].class, literalField.get(decoder));
        byte[] outputWorkspace = assertInstanceOf(byte[].class, outputField.get(decoder));
        assertSame(dictionary, dictionaryField.get(decoder));
        decoder.clear();
        assertSame(ZstdDictionaryContext.NONE, dictionaryField.get(decoder));
        assertEquals(0, history.size());
        assertEquals(0, decoder.frameSize());
        for (String name : new String[]{"huffmanTable", "literalLengthTable", "offsetTable", "matchLengthTable"}) {
            var field = ZstdBlockDecoder.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(decoder), name);
        }
        decoder.reset(ZstdDictionaryContext.NONE);
        assertEquals(ByteBuffer.wrap(new byte[]{4, 5, 6}), decoder.decodeCompressed(literals, literals.length, 16));
        assertEquals(ByteBuffer.wrap(new byte[]{7, 7, 7, 7}), decoder.decodeCompressed(match, match.length, 16));
        assertSame(literalWorkspace, literalField.get(decoder));
        assertSame(outputWorkspace, outputField.get(decoder));
        assertEquals(7, decoder.frameSize());
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
        ZstdBlockDecoder decoder = new ZstdBlockDecoder(new ZstdHistory(65536), ZstdDictionaryContext.NONE);
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
        assertNull(field.get(decoder), "contiguous history provides the block workspace");
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

    /// Preserves current-block, history and dictionary references before and after shared storage fills.
    @ParameterizedTest
    @ValueSource(ints = {3, 8, 9, 258})
    void expandsMatchesAfterSharedHistoryRollover(int length) throws IOException {
        int capacity = ZstdBlockDecoder.MAX_BLOCK_SIZE;
        byte[] dictionary = new byte[64];
        new Random(11000).nextBytes(dictionary);
        var context = ZstdDictionaryContext.parse(ZstdDictionary.rawContent(dictionary));
        for (int previousSize : new int[]{0, 13, 64, 131072, 131075, 262145}) {
            byte[] previous = new byte[previousSize];
            new Random(11001).nextBytes(previous);
            for (int prefixSize : new int[]{0, 8, 31}) {
                byte[] prefix = new byte[prefixSize];
                new Random(11002).nextBytes(prefix);
                for (int distance : new int[]{1, 7, 8, 9, 32, 64, 80, 131071, 131072, 131073, 262145}) {
                    var decoder = new ZstdBlockDecoder(new ZstdHistory(capacity), context);
                    for (int start = 0; start < previous.length; start += capacity) {
                        byte[] block = Arrays.copyOfRange(previous, start, Math.min(start + capacity, previous.length));
                        decoder.appendRaw(block, block.length);
                    }
                    byte[] payload = singleMatch(prefix, length, distance);
                    byte[] expected;
                    try {
                        expected = expand(prefix, previous, dictionary, capacity, length, distance);
                    } catch (IOException invalid) {
                        assertThrows(IOException.class,
                                () -> decoder.decodeCompressed(payload, payload.length, prefixSize + length));
                        assertEquals(previousSize, decoder.frameSize());
                        continue;
                    }
                    assertEquals(ByteBuffer.wrap(expected),
                            decoder.decodeCompressed(payload, payload.length, prefixSize + length),
                            "history=" + previousSize + ", prefix=" + prefixSize + ", distance=" + distance);
                    assertEquals(previousSize + expected.length, decoder.frameSize());
                }
            }
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
