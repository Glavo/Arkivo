// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Checks primitive-workspace construction against an independent priority-queue reference.
@NotNullByDefault
final class Deflate64HuffmanWorkspaceTest {
    /// Covers sparse, tied, skewed, and changing alphabets while repeatedly reusing one workspace.
    @Test
    void preservesCodeLengthsAcrossReuse() {
        var workspace = new Deflate64EncoderEngine.HuffmanWorkspace();
        Random random = new Random(0x48554646L);
        for (int alphabet : new int[]{19, 32, 286}) {
            var code = new Deflate64EncoderEngine.HuffmanCode(alphabet);
            int maximumLength = alphabet == 19 ? 7 : 15;
            for (int trial = 0; trial < 500; trial++) {
                int[] frequencies = new int[alphabet];
                for (int symbol = 0; symbol < alphabet; symbol++) {
                    frequencies[symbol] = switch (trial % 5) {
                        case 0 -> 0;
                        case 1 -> symbol == trial % alphabet ? 65536 : 0;
                        case 2 -> 1;
                        case 3 -> random.nextBoolean() ? random.nextInt(65536) : 0;
                        default -> 1 << Math.min(symbol, 29);
                    };
                }
                int[] expected = referenceLengths(frequencies, maximumLength);
                workspace.build(frequencies, maximumLength, code);
                for (int i = 0; i < alphabet; i++) assertEquals(expected[i], code.length(i));
            }
        }
    }

    /// Builds a length-limited canonical tree from symbol frequencies.
    private static int[] referenceLengths(int[] frequencies, int maximumLength) {
        int symbolCount = frequencies.length;
        long[] weights = new long[symbolCount * 2];
        int[] minimumSymbols = new int[symbolCount * 2];
        int[] parents = new int[symbolCount * 2];
        Arrays.fill(parents, -1);
        int activeSymbols = 0;
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (frequencies[symbol] > 0) {
                weights[symbol] = frequencies[symbol];
                minimumSymbols[symbol] = symbol;
                activeSymbols++;
            }
        }
        for (int symbol = 0; activeSymbols < 2 && symbol < symbolCount; symbol++) {
            if (weights[symbol] == 0L) {
                weights[symbol] = 1L;
                minimumSymbols[symbol] = symbol;
                activeSymbols++;
            }
        }

        Comparator<Integer> order = Comparator
                .comparingLong((Integer node) -> weights[node])
                .thenComparingInt(node -> minimumSymbols[node]);
        PriorityQueue<Integer> queue = new PriorityQueue<>(order);
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (weights[symbol] != 0L) {
                queue.add(symbol);
            }
        }
        int nextNode = symbolCount;
        while (queue.size() > 1) {
            int left = queue.remove();
            int right = queue.remove();
            int parent = nextNode++;
            weights[parent] = weights[left] + weights[right];
            minimumSymbols[parent] = Math.min(minimumSymbols[left], minimumSymbols[right]);
            parents[left] = parent;
            parents[right] = parent;
            queue.add(parent);
        }

        int[] lengthCounts = new int[maximumLength + 1];
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (weights[symbol] == 0L) {
                continue;
            }
            int length = 0;
            for (int node = symbol; parents[node] >= 0; node = parents[node]) {
                length++;
            }
            lengthCounts[Math.min(length, maximumLength)]++;
        }

        int remainingCodes = remainingCodeSlots(lengthCounts, maximumLength);
        while (remainingCodes < 0) {
            int length = maximumLength - 1;
            while (length > 0 && lengthCounts[length] == 0) {
                length--;
            }
            if (length == 0 || lengthCounts[maximumLength] == 0) {
                throw new AssertionError("Unable to limit Huffman code lengths");
            }
            lengthCounts[length]--;
            lengthCounts[length + 1] += 2;
            lengthCounts[maximumLength]--;
            remainingCodes++;
        }
        if (remainingCodes != 0) {
            throw new AssertionError("Length-limited Huffman tree is incomplete");
        }

        Integer[] orderedSymbols = new Integer[activeSymbols];
        int orderedIndex = 0;
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (weights[symbol] != 0L) {
                orderedSymbols[orderedIndex++] = symbol;
            }
        }
        Arrays.sort(orderedSymbols, Comparator
                .comparingLong((Integer symbol) -> weights[symbol])
                .thenComparingInt(Integer::intValue));
        int[] lengths = new int[symbolCount];
        orderedIndex = 0;
        for (int length = maximumLength; length >= 1; length--) {
            for (int count = lengthCounts[length]; count > 0; count--) {
                lengths[orderedSymbols[orderedIndex++]] = length;
            }
        }
        if (orderedIndex != orderedSymbols.length) {
            throw new AssertionError("Huffman length assignment is incomplete");
        }

        return lengths;
    }

    /// Counts unused canonical slots at the maximum code depth.
    private static int remainingCodeSlots(int[] counts, int maximumLength) {
        int remaining = 1;
        for (int length = 1; length <= maximumLength; length++) remaining = (remaining << 1) - counts[length];
        return remaining;
    }
}
