// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Decodes the adaptive binary range coding used by LZMA probability models.
@NotNullByDefault
final class LZMARangeDecoder {
    /// The total probability scale.
    static final int PROBABILITY_TOTAL = 1 << 11;

    /// The adaptive-probability movement shift.
    private static final int PROBABILITY_MOVE_BITS = 5;

    /// Maximum adaptive probability updates made while parsing one LZMA symbol.
    private static final int MAXIMUM_TRANSACTION_MUTATIONS = 64;

    /// Shared non-null marker for unused transaction array slots.
    private static final short @Unmodifiable [] EMPTY_PROBABILITIES = new short[0];

    /// The compressed range-coded source.
    private final LZMAInput input;

    /// The current unsigned 32-bit range.
    private long range = 0xffff_ffffL;

    /// The current unsigned 32-bit code value.
    private long code;

    /// Probability arrays changed by the active speculative symbol.
    private final short[][] transactionProbabilityArrays = createTransactionProbabilityArrays();

    /// Indices changed in the corresponding speculative probability arrays.
    private final int[] transactionProbabilityIndices = new int[MAXIMUM_TRANSACTION_MUTATIONS];

    /// Probability values preceding each speculative change.
    private final short[] transactionPreviousProbabilities = new short[MAXIMUM_TRANSACTION_MUTATIONS];

    /// Number of recorded speculative probability changes.
    private int transactionMutationCount;

    /// Range value preceding the active speculative symbol.
    private long transactionRange;

    /// Code value preceding the active speculative symbol.
    private long transactionCode;

    /// Whether one speculative symbol transaction is active.
    private boolean transactionActive;

    /// Creates and initializes a range decoder from its five-byte prefix.
    LZMARangeDecoder(LZMAInput input) throws IOException {
        this.input = Objects.requireNonNull(input, "input");
        if (readRequiredByte() != 0) {
            throw new IOException("Invalid LZMA range coder prefix");
        }
        for (int index = 0; index < 4; index++) {
            code = code << 8 | readRequiredByte();
        }
    }

    /// Initializes every supplied binary probability to one half.
    static void initializeProbabilities(short[] probabilities) {
        java.util.Arrays.fill(probabilities, (short) (PROBABILITY_TOTAL >>> 1));
    }

    /// Decodes one adaptive binary probability.
    int decodeBit(short[] probabilities, int index) throws IOException {
        int probability = Short.toUnsignedInt(probabilities[index]);
        long bound = (range >>> 11) * probability;
        int bit;
        if (code < bound) {
            range = bound;
            probability += (PROBABILITY_TOTAL - probability) >>> PROBABILITY_MOVE_BITS;
            bit = 0;
        } else {
            range -= bound;
            code -= bound;
            probability -= probability >>> PROBABILITY_MOVE_BITS;
            bit = 1;
        }
        recordProbabilityMutation(probabilities, index);
        probabilities[index] = (short) probability;
        normalize();
        return bit;
    }

    /// Decodes a most-significant-bit-first direct-bit value.
    int decodeDirectBits(int count) throws IOException {
        int result = 0;
        for (int index = 0; index < count; index++) {
            range >>>= 1;
            int bit;
            if (code >= range) {
                code -= range;
                bit = 1;
            } else {
                bit = 0;
            }
            result = result << 1 | bit;
            normalize();
        }
        return result;
    }

    /// Decodes a normal bit tree and returns its symbol.
    int decodeBitTree(short[] probabilities, int offset, int bitCount) throws IOException {
        int symbol = 1;
        for (int index = 0; index < bitCount; index++) {
            symbol = symbol << 1 | decodeBit(probabilities, offset + symbol);
        }
        return symbol - (1 << bitCount);
    }

    /// Decodes a least-significant-bit-first bit tree and returns its symbol.
    int decodeReverseBitTree(short[] probabilities, int offset, int bitCount) throws IOException {
        int symbol = 1;
        int result = 0;
        for (int index = 0; index < bitCount; index++) {
            int bit = decodeBit(probabilities, offset + symbol);
            symbol = symbol << 1 | bit;
            result |= bit << index;
        }
        return result;
    }

    /// Returns whether the compressed input supports speculative symbol rollback.
    boolean transactional() {
        return input.transactional();
    }

    /// Begins one speculative range-coded symbol.
    void beginTransaction() {
        if (!transactional()) {
            throw new IllegalStateException("LZMA input does not support transactions");
        }
        if (transactionActive) {
            throw new IllegalStateException("An LZMA range transaction is already active");
        }
        input.beginTransaction();
        transactionRange = range;
        transactionCode = code;
        transactionMutationCount = 0;
        transactionActive = true;
    }

    /// Commits the active speculative symbol and its input position.
    void commitTransaction() {
        requireTransaction();
        input.commitTransaction();
        clearTransactionMutations();
        transactionActive = false;
    }

    /// Restores the range coder, adaptive probabilities, and input position.
    void rollbackTransaction() {
        requireTransaction();
        for (int index = transactionMutationCount - 1; index >= 0; index--) {
            transactionProbabilityArrays[index][transactionProbabilityIndices[index]] =
                    transactionPreviousProbabilities[index];
        }
        range = transactionRange;
        code = transactionCode;
        input.rollbackTransaction();
        clearTransactionMutations();
        transactionActive = false;
    }

    /// Returns whether the final range code has been reduced to zero.
    boolean finished() {
        return code == 0L;
    }

    /// Records one adaptive-probability update for reverse-order rollback.
    private void recordProbabilityMutation(short[] probabilities, int index) {
        if (!transactionActive) {
            return;
        }
        if (transactionMutationCount == transactionProbabilityArrays.length) {
            throw new AssertionError("An LZMA symbol exceeded the probability transaction bound");
        }
        transactionProbabilityArrays[transactionMutationCount] = probabilities;
        transactionProbabilityIndices[transactionMutationCount] = index;
        transactionPreviousProbabilities[transactionMutationCount] = probabilities[index];
        transactionMutationCount++;
    }

    /// Clears strong references recorded for the completed speculative symbol.
    private void clearTransactionMutations() {
        Arrays.fill(
                transactionProbabilityArrays,
                0,
                transactionMutationCount,
                EMPTY_PROBABILITIES
        );
        transactionMutationCount = 0;
    }

    /// Requires one active speculative symbol transaction.
    private void requireTransaction() {
        if (!transactionActive) {
            throw new IllegalStateException("No LZMA range transaction is active");
        }
    }

    /// Creates non-null transaction slots for explicit nullability.
    private static short[][] createTransactionProbabilityArrays() {
        short[][] arrays = new short[MAXIMUM_TRANSACTION_MUTATIONS][];
        Arrays.fill(arrays, EMPTY_PROBABILITIES);
        return arrays;
    }

    /// Reads another source byte whenever the range loses its high byte.
    private void normalize() throws IOException {
        if (range < 1L << 24) {
            range <<= 8;
            code = (code << 8 | readRequiredByte()) & 0xffff_ffffL;
        }
    }

    /// Reads one required compressed byte.
    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Truncated LZMA range-coded stream");
        }
        return value;
    }
}
