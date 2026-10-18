// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Arrays;

/// Decodes the potentially incomplete canonical Huffman trees used by legacy RAR formats.
@NotNullByDefault
final class Rar4HuffmanDecoder implements AutoCloseable {
    /// The largest legacy RAR Huffman code width.
    private static final int MAX_CODE_LENGTH = 15;

    /// The payload of the three fixed canonical-code tables.
    static final int FIXED_MEMORY_SIZE = 3 * (MAX_CODE_LENGTH + 1) * Integer.BYTES;

    /// The budget shared with the owning decoder session.
    private final RarDecoderMemoryBudget memory;

    /// The first canonical code assigned at each width.
    private final int[] firstCodes;

    /// The first sorted-symbol index assigned at each width.
    private final int[] firstIndexes;

    /// The number of symbols assigned at each width.
    private final int[] counts;

    /// Symbols ordered by width and numeric value.
    private int[] symbols = new int[0];

    /// Whether the current alphabet contains at least one code.
    private boolean populated;

    /// Whether this decoder has returned its fixed-table reservation.
    private boolean closed;

    /// Reserves and creates an empty decoder; a failed construction leaves the budget unchanged.
    Rar4HuffmanDecoder(RarDecoderMemoryBudget memory) throws IOException {
        this.memory = memory;
        memory.reserve(FIXED_MEMORY_SIZE);
        try {
            firstCodes = new int[MAX_CODE_LENGTH + 1];
            firstIndexes = new int[MAX_CODE_LENGTH + 1];
            counts = new int[MAX_CODE_LENGTH + 1];
        } catch (RuntimeException | Error failure) {
            memory.release(FIXED_MEMORY_SIZE);
            throw failure;
        }
    }

    /// Builds one canonical tree and permits unused code space.
    void build(byte[] lengths, int offset, int symbolCount) throws IOException {
        if (closed) {
            throw new IOException("Legacy RAR Huffman decoder is closed");
        }
        if (offset < 0 || symbolCount < 0 || offset > lengths.length - symbolCount) {
            throw new IllegalArgumentException("Invalid legacy RAR Huffman alphabet range");
        }
        reset();

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
        if (assigned == 0) {
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

        long symbolBytes = (long) assigned * Integer.BYTES;
        memory.reserve(symbolBytes);
        try {
            symbols = new int[assigned];
        } catch (RuntimeException | Error failure) {
            memory.release(symbolBytes);
            throw failure;
        }
        int scratchBytes = firstIndexes.length * Integer.BYTES;
        memory.reserve(scratchBytes);
        try {
            int[] writeIndexes = firstIndexes.clone();
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                int length = lengths[offset + symbol] & 0xff;
                if (length != 0) {
                    symbols[writeIndexes[length]++] = symbol;
                }
            }
            populated = true;
        } finally {
            memory.release(scratchBytes);
        }
    }

    /// Decodes one symbol from the supplied legacy RAR bit stream.
    int decode(Rar4BitInput bits) throws IOException {
        if (closed || !populated) {
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
        long symbolBytes = (long) symbols.length * Integer.BYTES;
        symbols = new int[0];
        memory.release(symbolBytes);
        populated = false;
    }

    /// Discards the alphabet and returns all reservations; repeated closure has no effect.
    @Override
    public void close() {
        if (!closed) {
            reset();
            closed = true;
            memory.release(FIXED_MEMORY_SIZE);
        }
    }
}
