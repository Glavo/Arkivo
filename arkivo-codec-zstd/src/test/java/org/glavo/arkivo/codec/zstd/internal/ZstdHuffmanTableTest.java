// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Checks prefix-table decoding against independently assigned canonical codes and a bitwise decoder.
@NotNullByDefault
final class ZstdHuffmanTableTest {
    /// Infers the final weight and retains independent weights after the description buffer is reused.
    @Test
    void parsesOwnedWeightsIncludingInferredSymbol() throws IOException {
        int @Unmodifiable [] @Unmodifiable [] examples = {{1, 1}, {1, 1, 2}, {2, 1, 1}, {1, 1, 1, 1}, {1, 1, 2, 3}};
        for (int @Unmodifiable [] expected : examples) {
            int explicitCount = expected.length - 1;
            int encodedSize = 1 + ((explicitCount + 1) >>> 1);
            byte[] source = new byte[encodedSize + 4];
            source[2] = (byte) (127 + explicitCount);
            for (int symbol = 0; symbol < explicitCount; symbol++) {
                source[3 + (symbol >>> 1)] |= (byte) (expected[symbol] << ((symbol & 1) == 0 ? 4 : 0));
            }
            var parsed = ZstdEntropy.readHuffmanTable(source, 2, 2 + encodedSize);
            var repeated = ZstdEntropy.readHuffmanTable(source, 2, 2 + encodedSize);
            assertEquals(expected.length, parsed.symbolCount());
            assertEquals(encodedSize, parsed.bytesRead());
            assertArrayEquals(expected, Arrays.copyOf(parsed.weights(), parsed.symbolCount()));
            assertNotSame(parsed.weights(), repeated.weights());
            int @Unmodifiable [] snapshot = parsed.weights().clone();
            Arrays.fill(source, (byte) 0);
            assertArrayEquals(snapshot, parsed.weights());
            assertArrayEquals(snapshot, repeated.weights());
        }
        for (byte[] invalid : new byte[][]{
                {(byte) 128, 0}, {(byte) 128, 0x20}, {(byte) 128, (byte) 0xf0},
                {(byte) 130, 0x22, 0x10}}) {
            assertThrows(IOException.class, () -> ZstdEntropy.readHuffmanTable(invalid, 0, invalid.length));
        }
    }

    /// Checks shuffled complete trees and their incomplete trailing ranges against canonical codes.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void buildsShuffledCompleteTables(int tableLog) throws Exception {
        var field = ZstdEntropy.HuffmanTable.class.getDeclaredField("entries");
        field.setAccessible(true);
        Random random = new Random(41729 + tableLog);
        for (int attempt = 0; attempt < 64; attempt++) {
            int[] weights = new int[256];
            weights[0] = tableLog;
            weights[1] = tableLog;
            int symbolCount = 2;
            int desiredCount = random.nextInt(2, Math.min(256, 1 << tableLog) + 1);
            // Splitting a leaf preserves the tree's total prefix-space weight.
            while (symbolCount < desiredCount) {
                int split = random.nextInt(symbolCount);
                while (weights[split] == 1) split = (split + 1) % symbolCount;
                weights[split]--;
                weights[symbolCount++] = weights[split];
            }
            for (int index = weights.length - 1; index > 0; index--) {
                int selected = random.nextInt(index + 1);
                int weight = weights[index];
                weights[index] = weights[selected];
                weights[selected] = weight;
            }
            short[] expected = new short[1 << tableLog];
            int[] codes = canonicalCodes(weights, tableLog);
            for (int symbol = 0; symbol < weights.length; symbol++) {
                int weight = weights[symbol];
                if (weight == 0) continue;
                int length = tableLog + 1 - weight;
                int start = codes[symbol] << (weight - 1);
                int end = start + (1 << (weight - 1));
                for (int prefix = start; prefix < end; prefix++) {
                    assertEquals(0, expected[prefix]);
                    expected[prefix] = (short) ((symbol << 4) | length);
                }
            }
            var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
            assertArrayEquals(expected, assertInstanceOf(short[].class, field.get(table)));
            verifyPairedTable(table, expected, tableLog);

            // Removing the final canonical leaf leaves every earlier code unchanged.
            int lastSymbol = expected[expected.length - 1] >>> 4;
            int removedWidth = 1 << (weights[lastSymbol] - 1);
            weights[lastSymbol] = 0;
            Arrays.fill(expected, expected.length - removedWidth, expected.length, (short) 0);
            verifyPairedTable(ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog),
                    expected, tableLog);
        }
    }

    /// Checks unused first prefixes and second codes that exceed the paired lookahead.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void buildsSparsePairedTables(int tableLog) throws Exception {
        for (int weight = 1; weight <= tableLog; weight++) {
            int[] weights = new int[256];
            weights[255] = weight;
            short[] expected = new short[1 << tableLog];
            Arrays.fill(expected, 0, 1 << (weight - 1), (short) ((255 << 4) | (tableLog + 1 - weight)));
            verifyPairedTable(ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog),
                    expected, tableLog);
        }
    }

    /// Checks every paired prefix using two independent single-symbol reference lookups.
    private static void verifyPairedTable(ZstdEntropy.HuffmanTable table, short[] single, int tableLog)
            throws Exception {
        int lookupBits = Math.min(12, tableLog * 2);
        int[] expected = new int[1 << lookupBits];
        for (int prefix = 0; prefix < expected.length; prefix++) {
            int first = single[prefix >>> (lookupBits - tableLog)];
            int firstLength = first & 15;
            if (firstLength == 0) continue;
            int secondPrefix = ((prefix << firstLength) & (expected.length - 1)) >>> (lookupBits - tableLog);
            int second = single[secondPrefix];
            int secondLength = second & 15;
            expected[prefix] = ((first >>> 4) << 8) | firstLength | (1 << 30);
            if (secondLength != 0 && firstLength + secondLength <= lookupBits) {
                expected[prefix] = ((first >>> 4) << 8) | ((second >>> 4) << 16)
                        | (firstLength + secondLength) | (2 << 30);
            }
        }
        var method = ZstdEntropy.HuffmanTable.class.getDeclaredMethod("pairedEntries");
        method.setAccessible(true);
        int @Unmodifiable [] actual = assertInstanceOf(int[].class, method.invoke(table));
        assertArrayEquals(expected, actual);
        assertSame(actual, method.invoke(table));
        if (tableLog <= 6 && single[single.length - 1] != 0) {
            for (int entry : actual) assertEquals(2, entry >>> 30);
        }
    }

    /// Checks complete, incomplete, and conflicting tables using aligned prefix-space occupancy.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void validatesCanonicalPrefixOccupancy(int tableLog) throws Exception {
        var field = ZstdEntropy.HuffmanTable.class.getDeclaredField("entries");
        field.setAccessible(true);
        Random random = new Random(7290 + tableLog);
        // Exhaust the small alphabets to check the contiguous-prefix invariant, including rejected gaps.
        int attempts = tableLog <= 4 ? (int) Math.pow(tableLog + 1, 6) : 512;
        for (int attempt = 0; attempt < attempts; attempt++) {
            int[] weights = new int[tableLog <= 4 ? 6 : attempt % 32 + 1];
            int digits = attempt;
            for (int i = 0; i < weights.length; i++) {
                weights[i] = tableLog <= 4 ? digits % (tableLog + 1) : random.nextInt(tableLog + 1);
                digits /= tableLog + 1;
            }
            short[] expected = new short[1 << tableLog];
            int occupied = 0;
            boolean invalid = false;
            for (int weight = 1; weight <= tableLog && !invalid; weight++) {
                int length = tableLog + 1 - weight;
                int width = 1 << (weight - 1);
                for (int symbol = 0; symbol < weights.length; symbol++) {
                    if (weights[symbol] != weight) continue;
                    if (occupied % width != 0 || occupied + width > expected.length) {
                        invalid = true;
                        break;
                    }
                    for (int prefix = occupied; prefix < occupied + width; prefix++) {
                        expected[prefix] = (short) ((symbol << 4) | length);
                    }
                    occupied += width;
                }
            }
            if (invalid) {
                assertThrows(IOException.class,
                        () -> ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog));
            } else {
                var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
                assertArrayEquals(expected, assertInstanceOf(short[].class, field.get(table)));
            }
        }
    }

    /// Rejects overlapping canonical ranges at every supported power-of-two alignment.
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void rejectsMisalignedCanonicalRanges(int tableLog) {
        int[] weights = new int[tableLog + 1];
        for (int symbol = 0; symbol < weights.length; symbol++) weights[symbol] = Math.max(1, symbol);
        for (int removedWeight = 1; removedWeight < tableLog; removedWeight++) {
            // Removing half of a prefix range makes the next wider code overlap the remaining half.
            weights[removedWeight] = 0;
            assertThrows(IOException.class,
                    () -> ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog));
            weights[removedWeight] = removedWeight;
        }
    }

    /// Exercises every supported code length, including tails shorter than the lookup width.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesCompleteAndTruncatedStreams(int tableLog) throws IOException {
        int[] weights = new int[256];
        // A comb-shaped tree supplies two longest codes and one code of each shorter length.
        for (int index = 0; index <= tableLog; index++) {
            weights[255 - index] = Math.max(1, index);
        }
        verifyStreams(weights, tableLog);
        if (tableLog <= 8) {
            Arrays.fill(weights, 0);
            Arrays.fill(weights, 0, 1 << tableLog, 1);
            verifyStreams(weights, tableLog);
        }
    }

    /// Rejects oversized and overlapping tables before decoding, and unused prefixes when encountered.
    @Test
    void rejectsInvalidTablesAndPrefixes() throws IOException {
        assertThrows(IOException.class, () -> ZstdEntropy.HuffmanTable.fromWeights(new int[]{1, 1}, 2, 13));
        assertThrows(IOException.class, () -> ZstdEntropy.HuffmanTable.fromWeights(new int[]{1, 1}, 2, 0));
        assertThrows(IOException.class, () -> ZstdEntropy.HuffmanTable.fromWeights(new int[]{1, 1, 1}, 3, 1));
        assertThrows(IOException.class, () -> ZstdEntropy.HuffmanTable.fromWeights(new int[]{1, 2}, 2, 2));
        byte[] oversized = {(byte) 141, 0x11, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcc};
        assertThrows(IOException.class, () -> ZstdEntropy.readHuffmanTable(oversized, 0, oversized.length));
        var incomplete = ZstdEntropy.HuffmanTable.fromWeights(new int[]{1}, 1, 2);
        assertThrows(IOException.class, () -> incomplete.decode(new byte[]{7}, 0, 1, new byte[1], 0, 1));
    }

    /// Crosses wide-load and variable batch boundaries at every possible terminal byte alignment.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesBatchBoundaries(int tableLog) throws IOException {
        int[] weights = new int[256];
        for (int index = 0; index <= tableLog; index++) weights[255 - index] = Math.max(1, index);
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        for (int longCodes = 0; longCodes <= 136; longCodes++) {
            for (int shortCodes = 0; shortCodes < 8; shortCodes++) {
                byte[] plain = new byte[longCodes + shortCodes];
                for (int index = 0; index < longCodes; index++) plain[index] = (byte) (254 + (index & 1));
                Arrays.fill(plain, longCodes, plain.length, (byte) (255 - tableLog));
                byte[] stream = encode(plain, weights, codes, tableLog);
                compareWithReference(table, weights, codes, tableLog, stream, plain.length);
                if (plain.length != 0) {
                    compareWithReference(table, weights, codes, tableLog, stream, plain.length - 1);
                }
                compareWithReference(table, weights, codes, tableLog, stream, plain.length + 1);
            }
        }
    }

    /// Preserves partial output when an unused prefix occurs anywhere in a long stream.
    @Test
    void rejectsUnusedPrefixesInLongStreams() throws IOException {
        int[] weights = {1};
        int[] codes = {0};
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, 1);
        for (int invalid = 0; invalid < 128; invalid++) {
            byte[] stream = new byte[17];
            stream[16] = 1;
            int bit = 127 - invalid;
            stream[bit >>> 3] |= (byte) (1 << (bit & 7));
            compareWithReference(table, weights, codes, 1, stream, 128);
        }
    }

    /// Decodes valid sparse-table streams while retaining exact rejection and partial output for unused prefixes.
    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesSparseTablesWithCheckedPrefixes(int tableLog) throws IOException {
        int[] weights = {1, 1};
        int[] codes = {0, 1};
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        byte[] plain = new byte[2049];
        for (int index = 0; index < plain.length; index++) plain[index] = (byte) (index & 1);
        byte[] stream = encode(plain, weights, codes, tableLog);
        compareWithReference(table, weights, codes, tableLog, stream, plain.length);
        compareWithReference(table, weights, codes, tableLog, stream, plain.length - 1);
        compareWithReference(table, weights, codes, tableLog, stream, plain.length + 1);
        for (int index : new int[]{0, 1, 57, 63, 64, 65, 128, 512, 2048}) {
            byte[] damaged = stream.clone();
            int bit = (plain.length - index) * tableLog - 1;
            damaged[bit >>> 3] |= (byte) (1 << (bit & 7));
            compareWithReference(table, weights, codes, tableLog, damaged, plain.length);
        }
        byte[] fourStreams = encodeFourStreams(plain, weights, codes, tableLog);
        compareFourWithReference(table, weights, codes, tableLog, fourStreams, plain.length);
        compareFourWithReference(table, weights, codes, tableLog, fourStreams, plain.length - 1);
        compareFourWithReference(table, weights, codes, tableLog,
                Arrays.copyOf(fourStreams, fourStreams.length - 1), plain.length);
    }

    /// Compares interleaved decoding with four independent bitwise decodes, including unequal encoded lengths.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesFourStreamsWithIndependentBoundaries(int tableLog) throws IOException {
        int[] weights = new int[256];
        for (int index = 0; index <= tableLog; index++) weights[255 - index] = Math.max(1, index);
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        Random random = new Random(42 + tableLog);
        int batchSize = 57 / tableLog;
        int wordSize = 64 / tableLog;
        for (int size : new int[]{6, 7, 8, 9, 15, 16, 17, 227, 228, 229, 230, 231, 232,
                255, 256, 257, 1023, 1024, 1025, 2049, 4095, 4096, 4097,
                batchSize * 4 - 1, batchSize * 4, batchSize * 4 + 1,
                batchSize * 8 - 1, batchSize * 8, batchSize * 8 + 1,
                wordSize * 4 - 1, wordSize * 4, wordSize * 4 + 1,
                wordSize * 8 - 1, wordSize * 8, wordSize * 8 + 1}) {
            int segmentSize = (size + 3) / 4;
            for (int shortest = -1; shortest < 4; shortest++) {
                byte[] plain = new byte[size];
                for (int index = 0; index < size; index++) {
                    int lane = index / segmentSize;
                    // Alternate short and long codes so the lane closest to its end changes during decoding.
                    boolean shortCode = shortest < 0 ? ((index / (31 + lane * 7) + lane) & 1) == 0 : lane == shortest;
                    plain[index] = (byte) (shortCode ? 255 - tableLog : 254 + (index & 1));
                }
                byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
                byte[] decoded = new byte[size];
                table.decodeFourStreams(stream, 0, stream.length, decoded, size);
                assertArrayEquals(plain, decoded);
                compareFourWithReference(table, weights, codes, tableLog, stream, size);
                compareFourWithReference(table, weights, codes, tableLog, stream, size - 1);
                compareFourWithReference(table, weights, codes, tableLog, stream, size + 1);
                for (int limit = 0; limit < Math.min(32, stream.length); limit++) {
                    compareFourWithReference(table, weights, codes, tableLog, Arrays.copyOf(stream, limit), size);
                }
                for (int index = 0; index < 16; index++) {
                    byte[] damaged = stream.clone();
                    damaged[random.nextInt(damaged.length)] ^= (byte) random.nextInt(1, 256);
                    compareFourWithReference(table, weights, codes, tableLog, damaged, size);
                }
                int input = 6;
                for (int part = 0; part < 4; part++) {
                    int length = part < 3 ? Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(stream, part * 2))
                            : stream.length - input;
                    byte[] damaged = stream.clone();
                    damaged[input + length - 1] = 0;
                    compareFourWithReference(table, weights, codes, tableLog, damaged, size);
                    compareFourWithReference(table, weights, codes, tableLog,
                            Arrays.copyOf(stream, input + length - 1), size);
                    input += length;
                }
            }
        }
    }

    /// Rejects an unused prefix at every position in two consecutive batches of each lane.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void rejectsUnusedPrefixesAtEveryBatchPosition(int tableLog) throws IOException {
        int[] weights = {1};
        int[] codes = {0};
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        int segmentSize = 256;
        int count = segmentSize * 4;
        int batchSize = 57 / tableLog;
        byte[] stream = encodeFourStreams(new byte[count], weights, codes, tableLog);
        compareFourWithReference(table, weights, codes, tableLog, stream, count);
        for (int part = 0, offset = 6; part < 4; part++) {
            for (int symbol = 0; symbol <= batchSize * 2; symbol++) {
                byte[] damaged = stream.clone();
                int bit = (segmentSize - symbol) * tableLog - 1;
                damaged[offset + (bit >>> 3)] |= (byte) (1 << (bit & 7));
                compareFourWithReference(table, weights, codes, tableLog, damaged, count);
            }
            if (part < 3) offset += Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(stream, part * 2));
        }
    }

    /// Crosses the paired-lookup threshold with unequal code lengths, incomplete tables, damaged streams, and tails.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesLargeFourStreamBoundaries(int tableLog) throws IOException {
        int[] weights = new int[tableLog + 1];
        for (int i = 0; i <= tableLog; i++) weights[i] = Math.max(1, i);
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        Random random = new Random(1234 + tableLog);
        for (int size : new int[]{8191, 8192, 8193, 16383, 16384, 16385, 32767, 32768, 32769, 32799}) {
            byte[] plain = new byte[size];
            for (int i = 0; i < size; i++) plain[i] = (byte) random.nextInt(weights.length);
            byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
            compareFourWithReference(table, weights, codes, tableLog, stream, size);
            compareFourWithReference(table, weights, codes, tableLog, stream, size - 1);
            compareFourWithReference(table, weights, codes, tableLog, stream, size + 1);
            compareFourWithReference(table, weights, codes, tableLog, Arrays.copyOf(stream, stream.length - 1), size);
            for (int part = 0, offset = 6; part < 4; part++) {
                byte[] damaged = stream.clone();
                damaged[offset] ^= (byte) 0xff;
                compareFourWithReference(table, weights, codes, tableLog, damaged, size);
                if (part < 3) offset += Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(stream, part * 2));
            }
        }
        // A missing second symbol must not hide an invalid prefix behind the first valid symbol in a paired entry.
        weights = new int[]{1};
        codes = new int[]{0};
        table = ZstdEntropy.HuffmanTable.fromWeights(weights, 1, tableLog);
        byte[] stream = encodeFourStreams(new byte[32768], weights, codes, tableLog);
        compareFourWithReference(table, weights, codes, tableLog, stream, 32768);
        for (int part = 0; part < 4; part++) {
            for (int symbol : new int[]{0, 1, 7, 8, 57, 63, 64, 65, 2047, 8190, 8191}) {
                byte[] damaged = stream.clone();
                int bit = (8192 - symbol) * tableLog - 1;
                int offset = 6 + part * (1024 * tableLog + 1);
                damaged[offset + (bit >>> 3)] |= (byte) (1 << (bit & 7));
                compareFourWithReference(table, weights, codes, tableLog, damaged, 32768);
            }
        }
    }

    /// Checks complete short-code tables at paired batch boundaries and unequal final segment lengths.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6})
    void decodesCompletePairBatchBoundaries(int tableLog) throws IOException {
        int batchSize = 57 / (tableLog * 2) * 2;
        int alignedSize = (32768 / (batchSize * 4) + 1) * batchSize * 4;
        Random random = new Random(7919 + tableLog);
        for (boolean uniform : new boolean[]{false, true}) {
            int[] weights = new int[256];
            int symbolCount = uniform ? 1 << tableLog : tableLog + 1;
            for (int index = 0; index < symbolCount; index++) {
                weights[255 - index] = uniform ? 1 : Math.max(1, index);
            }
            int[] codes = canonicalCodes(weights, tableLog);
            var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
            for (int delta : new int[]{-1, 0, 1, 2, 3, batchSize * 4 - 1, batchSize * 4 + 1}) {
                int size = alignedSize + delta;
                byte[] plain = new byte[size];
                for (int index = 0; index < size; index++) {
                    plain[index] = (byte) (255 - random.nextInt(symbolCount));
                }
                byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
                byte[] output = new byte[size + 7];
                Arrays.fill(output, (byte) 0xa5);
                table.decodeFourStreams(stream, 0, stream.length, output, size);
                assertArrayEquals(plain, Arrays.copyOf(output, size));
                for (int index = size; index < output.length; index++) assertEquals((byte) 0xa5, output[index]);
                compareFourWithReference(table, weights, codes, tableLog, stream, size - 1);
                compareFourWithReference(table, weights, codes, tableLog, stream, size + 1);
            }
        }
    }

    /// Keeps high-bit symbols separate from code lengths in scalar, four-lane, and paired decoding.
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
    void decodesHighBitSymbols(int tableLog) throws IOException {
        int[] weights = new int[256];
        for (int i = 0; i <= tableLog; i++) weights[255 - i] = Math.max(1, i);
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        Random random = new Random(4321 + tableLog);
        for (int size : new int[]{31, 32, 33, 255, 256, 257, 32767, 32768, 32769, 32775}) {
            byte[] plain = new byte[size];
            for (int i = 0; i < size; i++) plain[i] = (byte) (255 - random.nextInt(tableLog + 1));
            if (size <= 257) {
                byte[] single = encode(plain, weights, codes, tableLog);
                compareWithReference(table, weights, codes, tableLog, single, size);
                compareWithReference(table, weights, codes, tableLog, single, size - 1);
                compareWithReference(table, weights, codes, tableLog, single, size + 1);
            }
            byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
            byte[] source = new byte[stream.length + 11];
            Arrays.fill(source, (byte) 0xa5);
            System.arraycopy(stream, 0, source, 3, stream.length);
            byte[] unchanged = source.clone();
            byte[] expected = Arrays.copyOf(plain, size + 7);
            Arrays.fill(expected, size, expected.length, (byte) 0xa5);
            byte[] actual = new byte[expected.length];
            Arrays.fill(actual, (byte) 0xa5);
            table.decodeFourStreams(source, 3, stream.length + 3, actual, size);
            assertArrayEquals(expected, actual);
            assertArrayEquals(unchanged, source);
            if (size >= 32768) {
                // Keep long codes present while making paired lookup worthwhile for every table width.
                for (int index = 0; index < plain.length; index++) {
                    if ((index & 3) != 0) plain[index] = (byte) (255 - tableLog);
                }
                byte[] paired = encodeFourStreams(plain, weights, codes, tableLog);
                compareFourWithReference(table, weights, codes, tableLog, paired, size);
            }
        }
    }

    /// Checks six-bit average codes with one-bit differences, unequal lanes, and mismatched output lengths.
    @ParameterizedTest
    @ValueSource(ints = {32767, 32768, 32769})
    void decodesAroundPairedLookaheadBoundary(int size) throws IOException {
        int tableLog = 7;
        int[] weights = {1, 1, 2, 3, 4, 5, 6, 7};
        int[] codes = canonicalCodes(weights, tableLog);
        for (int delta : new int[]{-1, 0, 1}) {
            byte[] plain = new byte[size];
            Arrays.fill(plain, (byte) 2);
            plain[size / 2] = (byte) (delta < 0 ? 3 : delta > 0 ? 0 : 2);
            byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
            var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
            compareFourWithReference(table, weights, codes, tableLog, stream, size);
            compareFourWithReference(table, weights, codes, tableLog, stream, size - 1);
            compareFourWithReference(table, weights, codes, tableLog, stream, size + 1);
            compareFourWithReference(table, weights, codes, tableLog,
                    Arrays.copyOf(stream, stream.length - 1), size);
        }
    }

    /// Leaves small tables unexpanded and safely publishes the derived table to concurrent dictionary users.
    @ParameterizedTest
    @ValueSource(ints = {6, 7})
    void publishesPairedTableOnDemand(int tableLog) throws Exception {
        int[] weights = new int[tableLog == 6 ? 64 : 96];
        Arrays.fill(weights, 1);
        if (tableLog == 7) Arrays.fill(weights, 64, weights.length, 2);
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        var cache = ZstdEntropy.HuffmanTable.class.getDeclaredField("pairedEntries");
        cache.setAccessible(true);
        byte[] small = encodeFourStreams(new byte[16384], weights, codes, tableLog);
        table.decodeFourStreams(small, 0, small.length, new byte[16384], 16384);
        assertNull(cache.get(table));
        byte[] plain = new byte[32768];
        byte[] longCodes = new byte[plain.length];
        for (int i = 0; i < plain.length; i++) {
            plain[i] = (byte) (tableLog == 6 ? i & 63 : 64 + (i & 31));
            longCodes[i] = (byte) (i & 63);
        }
        byte[] stream = encodeFourStreams(plain, weights, codes, tableLog);
        byte[] longStream = encodeFourStreams(longCodes, weights, codes, tableLog);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = new ArrayList<Future<byte[]>>();
            for (int worker = 0; worker < 4; worker++) {
                byte @Unmodifiable [] selected = (worker & 1) == 0 ? stream : longStream;
                futures.add(executor.submit(() -> {
                    start.await();
                    byte[] output = new byte[plain.length];
                    table.decodeFourStreams(selected, 0, selected.length, output, output.length);
                    return output;
                }));
            }
            start.countDown();
            for (int worker = 0; worker < futures.size(); worker++) {
                assertArrayEquals((worker & 1) == 0 ? plain : longCodes, futures.get(worker).get());
            }
        } finally {
            executor.shutdownNow();
        }
        int @Unmodifiable [] cached = assertInstanceOf(int[].class, cache.get(table));
        assertEquals(4096, cached.length);
        table.decodeFourStreams(stream, 0, stream.length, new byte[plain.length], plain.length);
        assertSame(cached, cache.get(table));
    }

    /// Rejects unused prefixes in each stream, including failures after a long valid prefix.
    @Test
    void rejectsInvalidPrefixesInEachStream() throws IOException {
        int[] weights = {1};
        int[] codes = {0};
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, 1, 1);
        byte[] stream = encodeFourStreams(new byte[512], weights, codes, 1);
        for (int part = 0; part < 4; part++) {
            for (int symbol : new int[]{0, 1, 3, 4, 56, 57, 58, 63, 64, 113, 114, 115, 127}) {
                byte[] damaged = stream.clone();
                int bit = 127 - symbol;
                damaged[6 + part * 17 + (bit >>> 3)] |= (byte) (1 << (bit & 7));
                compareFourWithReference(table, weights, codes, 1, damaged, 512);
            }
        }
    }

    /// Keeps literals private when any lane has an invalid bit count, including unequal tails, and permits reset.
    @ParameterizedTest
    @ValueSource(ints = {512, 768, 1020, 1023})
    void doesNotPublishInvalidFourStreamBlock(int size) throws IOException {
        byte[] streams = encodeFourStreams(new byte[size], new int[]{1, 1}, new int[]{0, 1}, 1);
        int literalPayloadSize = 2 + streams.length;
        int blockSize = 3 + literalPayloadSize + 1;
        int blockHeader = (blockSize << 3) | 5;
        int literalHeader = (literalPayloadSize << 14) | (size << 4) | 6;
        // Window descriptor zero, one last compressed block, two equal Huffman weights, and no sequences.
        ByteBuffer frame = ByteBuffer.allocate(9 + blockSize).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(0xfd2fb528).putShort((short) 0);
        frame.put((byte) blockHeader).putShort((short) (blockHeader >>> 8));
        frame.put((byte) literalHeader).putShort((short) (literalHeader >>> 8));
        frame.put((byte) 128).put((byte) 0x10);
        int streamOffset = frame.position();
        frame.put(streams).put((byte) 0);
        int laneOffset = 6;
        for (int part = 0; part < 4; part++) {
            int laneLength = part < 3 ? Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(streams, part * 2))
                    : streams.length - laneOffset;
            byte[] damaged = frame.array().clone();
            damaged[streamOffset + laneOffset + laneLength - 1] = 3;
            laneOffset += laneLength;
            try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
                ByteBuffer output = ByteBuffer.allocate(size);
                Arrays.fill(output.array(), (byte) 0xa5);
                byte[] unchanged = output.array().clone();
                assertThrows(IOException.class, () -> decoder.finish(ByteBuffer.wrap(damaged), output));
                assertEquals(0, output.position());
                assertArrayEquals(unchanged, output.array());
                decoder.reset();
                assertEquals(CodecOutcome.FINISHED, decoder.finish(ByteBuffer.wrap(frame.array()), output));
                assertEquals(size, output.position());
                assertArrayEquals(new byte[size], output.array());
            }
        }
    }

    /// Packs four independently terminated streams and their byte lengths.
    private static byte[] encodeFourStreams(byte[] plain, int[] weights, int[] codes, int tableLog) {
        int segmentSize = (plain.length + 3) / 4;
        byte[] header = new byte[6];
        var output = new ByteArrayOutputStream();
        output.writeBytes(header);
        for (int part = 0; part < 4; part++) {
            byte[] segment = Arrays.copyOfRange(plain, part * segmentSize,
                    Math.min(plain.length, (part + 1) * segmentSize));
            byte[] stream = encode(segment, weights, codes, tableLog);
            if (part < 3) ByteArrayAccess.writeShortLittleEndian(header, part * 2, (short) stream.length);
            output.writeBytes(stream);
        }
        byte[] result = output.toByteArray();
        System.arraycopy(header, 0, result, 0, header.length);
        return result;
    }

    /// Checks acceptance and successful output without requiring an order for internal writes on failure.
    private static void compareFourWithReference(ZstdEntropy.HuffmanTable table, int[] weights, int[] codes,
                                                 int tableLog, byte[] stream, int count) throws IOException {
        byte[] source = new byte[stream.length + 8];
        Arrays.fill(source, (byte) 0xa5);
        System.arraycopy(stream, 0, source, 3, stream.length);
        byte[] unchanged = source.clone();
        byte[] expected = new byte[count + 6];
        Arrays.fill(expected, (byte) 0xa5);
        byte[] actual = expected.clone();
        boolean failed = false;
        try {
            if (count < 6 || stream.length < 6) throw new IOException("Invalid four-stream header");
            int segmentSize = (count + 3) / 4;
            int input = 6;
            for (int part = 0; part < 4; part++) {
                int length = part < 3 ? (stream[part * 2] & 255) | ((stream[part * 2 + 1] & 255) << 8)
                        : stream.length - input;
                if (length <= 0 || length > stream.length - input) throw new IOException("Invalid stream length");
                int outputSize = Math.min(segmentSize, count - part * segmentSize);
                byte[] segment = new byte[outputSize + 6];
                decodeReference(Arrays.copyOfRange(stream, input, input + length), weights, codes, tableLog,
                        segment, outputSize);
                System.arraycopy(segment, 3, expected, part * segmentSize, outputSize);
                input += length;
            }
        } catch (IOException ignored) {
            failed = true;
        }
        if (failed) {
            assertThrows(IOException.class, () -> table.decodeFourStreams(source, 3, 3 + stream.length, actual, count));
        } else {
            table.decodeFourStreams(source, 3, 3 + stream.length, actual, count);
            assertArrayEquals(expected, actual);
        }
        assertArrayEquals(Arrays.copyOfRange(expected, count, expected.length),
                Arrays.copyOfRange(actual, count, actual.length));
        assertArrayEquals(unchanged, source);
    }

    /// Assigns codes by cumulative prefix-space occupancy rather than the production code recurrence.
    private static int[] canonicalCodes(int[] weights, int tableLog) {
        int[] codes = new int[weights.length];
        int slot = 0;
        for (int weight = 1; weight <= tableLog; weight++) {
            for (int symbol = 0; symbol < weights.length; symbol++) {
                if (weights[symbol] == weight) {
                    codes[symbol] = slot >>> (weight - 1);
                    slot += 1 << (weight - 1);
                }
            }
        }
        assertEquals(1 << tableLog, slot);
        return codes;
    }

    /// Compares valid, truncated, and damaged streams using one reusable decoding table.
    private static void verifyStreams(int[] weights, int tableLog) throws IOException {
        int[] codes = canonicalCodes(weights, tableLog);
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        Random random = new Random(42 + tableLog);
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9, 31, 63, 64, 65, 256}) {
            byte[] plain = new byte[size];
            for (int index = 0; index < size; index++) {
                int symbol;
                do {
                    symbol = random.nextInt(256);
                } while (weights[symbol] == 0);
                plain[index] = (byte) symbol;
            }
            byte[] stream = encode(plain, weights, codes, tableLog);
            byte[] decoded = new byte[size];
            table.decode(stream, 0, stream.length, decoded, 0, size);
            assertArrayEquals(plain, decoded);
            compareWithReference(table, weights, codes, tableLog, stream, size);
            compareWithReference(table, weights, codes, tableLog, stream, size + 1);
            if (size != 0) compareWithReference(table, weights, codes, tableLog, stream, size - 1);
            for (int limit = 0; limit < stream.length; limit++) {
                compareWithReference(table, weights, codes, tableLog, Arrays.copyOf(stream, limit), size);
            }
            for (int index = 0; index < stream.length; index++) {
                byte[] damaged = stream.clone();
                damaged[index] ^= (byte) random.nextInt(1, 256);
                compareWithReference(table, weights, codes, tableLog, damaged, size);
            }
        }
        // Cover every symbol individually, not just the randomized selections above.
        for (int symbol = 0; symbol < weights.length; symbol++) {
            if (weights[symbol] == 0) continue;
            byte[] plain = {(byte) symbol};
            byte[] stream = encode(plain, weights, codes, tableLog);
            byte[] decoded = new byte[1];
            table.decode(stream, 0, stream.length, decoded, 0, 1);
            assertArrayEquals(plain, decoded);
        }
    }

    /// Packs canonical codes in reverse symbol order, followed by the bitstream marker.
    private static byte[] encode(byte[] plain, int[] weights, int[] codes, int tableLog) {
        var writer = new ZstdEntropy.ReverseBitWriter();
        for (int index = plain.length - 1; index >= 0; index--) {
            int symbol = Byte.toUnsignedInt(plain[index]);
            writer.writeBits(codes[symbol], tableLog + 1 - weights[symbol]);
        }
        return writer.finish();
    }

    /// Checks acceptance, partial output, source preservation, and guards around both byte ranges.
    private static void compareWithReference(ZstdEntropy.HuffmanTable table, int[] weights, int[] codes,
                                             int tableLog, byte[] stream, int count) throws IOException {
        byte[] expected = new byte[count + 6];
        Arrays.fill(expected, (byte) 0xa5);
        boolean failed = false;
        try {
            decodeReference(stream, weights, codes, tableLog, expected, count);
        } catch (IOException ignored) {
            failed = true;
        }
        for (int sourceOffset : new int[]{0, 3}) {
            for (int suffixSize : new int[]{0, 5}) {
                byte[] source = new byte[sourceOffset + stream.length + suffixSize];
                Arrays.fill(source, (byte) 0xa5);
                System.arraycopy(stream, 0, source, sourceOffset, stream.length);
                byte[] unchanged = source.clone();
                byte[] actual = new byte[expected.length];
                Arrays.fill(actual, (byte) 0xa5);
                if (failed) {
                    assertThrows(IOException.class, () -> table.decode(source, sourceOffset,
                            sourceOffset + stream.length, actual, 3, count));
                } else {
                    table.decode(source, sourceOffset, sourceOffset + stream.length, actual, 3, count);
                }
                assertArrayEquals(expected, actual);
                assertArrayEquals(unchanged, source);
            }
        }
    }

    /// Decodes one bit at a time without using the production reader or lookup table.
    private static void decodeReference(byte[] source, int[] weights, int[] codes, int tableLog,
                                        byte[] target, int count) throws IOException {
        if (source.length == 0 || source[source.length - 1] == 0) throw new IOException("Missing marker");
        int position = (source.length - 1) * 8
                + 31 - Integer.numberOfLeadingZeros(Byte.toUnsignedInt(source[source.length - 1]));
        for (int index = 0; index < count; index++) {
            int code = 0;
            boolean found = false;
            for (int length = 1; length <= tableLog && !found; length++) {
                if (position == 0) throw new IOException("Truncated code");
                position--;
                code = (code << 1) | ((source[position >>> 3] >>> (position & 7)) & 1);
                for (int symbol = 0; symbol < weights.length; symbol++) {
                    if (weights[symbol] != 0 && tableLog + 1 - weights[symbol] == length
                            && codes[symbol] == code) {
                        target[3 + index] = (byte) symbol;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) throw new IOException("Invalid code");
        }
        if (position != 0) throw new IOException("Unused bits");
    }
}
