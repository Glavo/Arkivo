// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Arrays;

/// Decodes canonical Huffman codes used by RAR5 compressed blocks.
@NotNullByDefault
final class Rar5HuffmanDecoder {
    /// The largest code width accepted by the RAR5 format.
    private static final int MAX_CODE_LENGTH = 15;

    /// The first canonical code assigned at each bit width.
    private final int[] firstCodes = new int[MAX_CODE_LENGTH + 1];

    /// The first sorted-symbol index assigned at each bit width.
    private final int[] firstSymbolIndexes = new int[MAX_CODE_LENGTH + 1];

    /// The number of symbols assigned at each bit width.
    private final int[] lengthCounts = new int[MAX_CODE_LENGTH + 1];

    /// Symbols ordered first by code width and then by their numeric value.
    private int[] sortedSymbols = new int[0];

    /// Whether this decoder contains at least one canonical code.
    private boolean populated;

    /// Creates an empty canonical decoder.
    Rar5HuffmanDecoder() {
    }

    /// Builds a complete canonical tree, also accepting a completely empty alphabet.
    void build(byte[] lengths, int offset, int symbolCount) throws IOException {
        if (offset < 0 || symbolCount < 0 || offset > lengths.length - symbolCount) {
            throw new IllegalArgumentException("Invalid RAR5 Huffman alphabet range");
        }

        Arrays.fill(firstCodes, 0);
        Arrays.fill(firstSymbolIndexes, 0);
        Arrays.fill(lengthCounts, 0);
        sortedSymbols = new int[symbolCount];

        int assignedSymbols = 0;
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int length = lengths[offset + symbol] & 0xff;
            if (length > MAX_CODE_LENGTH) {
                throw new IOException("RAR5 Huffman code exceeds 15 bits");
            }
            if (length != 0) {
                lengthCounts[length]++;
                assignedSymbols++;
            }
        }

        populated = assignedSymbols != 0;
        if (!populated) {
            sortedSymbols = new int[0];
            return;
        }

        int nextCode = 0;
        int nextSymbolIndex = 0;
        for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
            nextCode = (nextCode + lengthCounts[length - 1]) << 1;
            firstCodes[length] = nextCode;
            firstSymbolIndexes[length] = nextSymbolIndex;
            nextSymbolIndex += lengthCounts[length];
        }
        if (nextCode + lengthCounts[MAX_CODE_LENGTH] != 1 << MAX_CODE_LENGTH) {
            throw new IOException("RAR5 Huffman tree is incomplete or oversubscribed");
        }

        int[] writeIndexes = firstSymbolIndexes.clone();
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int length = lengths[offset + symbol] & 0xff;
            if (length != 0) {
                sortedSymbols[writeIndexes[length]++] = symbol;
            }
        }
    }

    /// Decodes one symbol from the supplied compressed bit stream.
    int decode(Rar5BitInput input) throws IOException {
        if (!populated) {
            throw new IOException("RAR5 Huffman decoder has an empty alphabet");
        }

        int code = 0;
        for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
            code = (code << 1) | input.readBits(1);
            int offset = code - firstCodes[length];
            if (offset >= 0 && offset < lengthCounts[length]) {
                return sortedSymbols[firstSymbolIndexes[length] + offset];
            }
        }
        throw new IOException("Invalid RAR5 Huffman code");
    }

    /// Clears this decoder so a solid continuation cannot reuse stale tables.
    void reset() {
        Arrays.fill(firstCodes, 0);
        Arrays.fill(firstSymbolIndexes, 0);
        Arrays.fill(lengthCounts, 0);
        sortedSymbols = new int[0];
        populated = false;
    }

    /// Returns whether at least one canonical code is available.
    boolean isPopulated() {
        return populated;
    }
}
