// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Arrays;

/// Decodes the potentially incomplete canonical Huffman trees used by legacy RAR formats.
@NotNullByDefault
final class Rar4HuffmanDecoder {
    /// The largest legacy RAR Huffman code width.
    private static final int MAX_CODE_LENGTH = 15;

    /// The first canonical code assigned at each width.
    private final int[] firstCodes = new int[MAX_CODE_LENGTH + 1];

    /// The first sorted-symbol index assigned at each width.
    private final int[] firstIndexes = new int[MAX_CODE_LENGTH + 1];

    /// The number of symbols assigned at each width.
    private final int[] counts = new int[MAX_CODE_LENGTH + 1];

    /// Symbols ordered by width and numeric value.
    private int[] symbols = new int[0];

    /// Whether the current alphabet contains at least one code.
    private boolean populated;

    /// Creates an empty decoder.
    Rar4HuffmanDecoder() {
    }

    /// Builds one canonical tree and permits unused code space.
    void build(byte[] lengths, int offset, int symbolCount) throws IOException {
        if (offset < 0 || symbolCount < 0 || offset > lengths.length - symbolCount) {
            throw new IllegalArgumentException("Invalid legacy RAR Huffman alphabet range");
        }
        Arrays.fill(firstCodes, 0);
        Arrays.fill(firstIndexes, 0);
        Arrays.fill(counts, 0);

        int assigned = 0;
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int length = lengths[offset + symbol] & 0xff;
            if (length > MAX_CODE_LENGTH) {
                throw new IOException("Legacy RAR Huffman code exceeds 15 bits");
            }
            if (length != 0) {
                counts[length]++;
                assigned++;
            }
        }
        populated = assigned != 0;
        symbols = new int[assigned];
        if (!populated) {
            return;
        }

        int code = 0;
        int symbolIndex = 0;
        for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
            code = (code + counts[length - 1]) << 1;
            firstCodes[length] = code;
            firstIndexes[length] = symbolIndex;
            symbolIndex += counts[length];
            if (code + counts[length] > 1 << length) {
                throw new IOException("Legacy RAR Huffman tree is oversubscribed");
            }
        }

        int[] writeIndexes = firstIndexes.clone();
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int length = lengths[offset + symbol] & 0xff;
            if (length != 0) {
                symbols[writeIndexes[length]++] = symbol;
            }
        }
    }

    /// Decodes one symbol from the supplied legacy RAR bit stream.
    int decode(Rar4BitInput bits) throws IOException {
        if (!populated) {
            throw new IOException("Legacy RAR Huffman alphabet is empty");
        }
        int code = 0;
        for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
            code = code << 1 | bits.readBits(1);
            int offset = code - firstCodes[length];
            if (offset >= 0 && offset < counts[length]) {
                return symbols[firstIndexes[length] + offset];
            }
        }
        throw new IOException("Invalid legacy RAR Huffman code");
    }

    /// Clears the current alphabet.
    void reset() {
        Arrays.fill(firstCodes, 0);
        Arrays.fill(firstIndexes, 0);
        Arrays.fill(counts, 0);
        symbols = new int[0];
        populated = false;
    }
}
