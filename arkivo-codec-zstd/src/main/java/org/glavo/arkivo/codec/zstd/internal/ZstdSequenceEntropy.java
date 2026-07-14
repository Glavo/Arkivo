// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;

/// Provides the predefined Zstandard sequence tables and their inverse encoding transitions.
@NotNullByDefault
final class ZstdSequenceEntropy {
    /// Number of literal-length symbols in the predefined table.
    private static final int LITERAL_LENGTH_SYMBOLS = 36;

    /// Number of match-length symbols in the predefined table.
    private static final int MATCH_LENGTH_SYMBOLS = 53;

    /// Number of offset symbols in the predefined table.
    private static final int OFFSET_SYMBOLS = 29;

    /// Predefined literal-length normalized probabilities.
    private static final int @Unmodifiable [] LITERAL_LENGTH_PROBABILITIES = {
            4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
            -1, -1, -1, -1
    };

    /// Predefined match-length normalized probabilities.
    private static final int @Unmodifiable [] MATCH_LENGTH_PROBABILITIES = {
            1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
            -1, -1, -1, -1, -1
    };

    /// Predefined offset-code normalized probabilities.
    private static final int @Unmodifiable [] OFFSET_PROBABILITIES = {
            1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };

    /// Predefined literal-length decoding table.
    static final ZstdEntropy.FseTable LITERAL_LENGTH_DECODER;

    /// Predefined match-length decoding table.
    static final ZstdEntropy.FseTable MATCH_LENGTH_DECODER;

    /// Predefined offset-code decoding table.
    static final ZstdEntropy.FseTable OFFSET_DECODER;

    /// Inverse transitions for predefined literal-length encoding.
    static final EncoderTable LITERAL_LENGTH_ENCODER;

    /// Inverse transitions for predefined match-length encoding.
    static final EncoderTable MATCH_LENGTH_ENCODER;

    /// Inverse transitions for predefined offset-code encoding.
    static final EncoderTable OFFSET_ENCODER;

    static {
        try {
            LITERAL_LENGTH_DECODER = ZstdEntropy.FseTable.fromNormalized(
                    LITERAL_LENGTH_PROBABILITIES,
                    LITERAL_LENGTH_SYMBOLS,
                    6
            );
            MATCH_LENGTH_DECODER = ZstdEntropy.FseTable.fromNormalized(
                    MATCH_LENGTH_PROBABILITIES,
                    MATCH_LENGTH_SYMBOLS,
                    6
            );
            OFFSET_DECODER = ZstdEntropy.FseTable.fromNormalized(
                    OFFSET_PROBABILITIES,
                    OFFSET_SYMBOLS,
                    5
            );
            LITERAL_LENGTH_ENCODER = EncoderTable.fromDecoder(LITERAL_LENGTH_DECODER, LITERAL_LENGTH_SYMBOLS);
            MATCH_LENGTH_ENCODER = EncoderTable.fromDecoder(MATCH_LENGTH_DECODER, MATCH_LENGTH_SYMBOLS);
            OFFSET_ENCODER = EncoderTable.fromDecoder(OFFSET_DECODER, OFFSET_SYMBOLS);
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Creates no instances.
    private ZstdSequenceEntropy() {
    }

    /// Maps symbols and following decoder states to inverse FSE encoding transitions.
    @NotNullByDefault
    static final class EncoderTable {
        /// Number of bits used to flush a state.
        private final int tableLog;

        /// Number of decoder states.
        private final int tableSize;

        /// Decoder state selected for each symbol and following state.
        private final int @Unmodifiable [] states;

        /// Transition value emitted for each symbol and following state.
        private final int @Unmodifiable [] values;

        /// Transition bit count emitted for each symbol and following state.
        private final int @Unmodifiable [] bitCounts;

        /// One decoder state representing each symbol before any transition is needed.
        private final int @Unmodifiable [] initialStates;

        /// Creates one immutable inverse table.
        private EncoderTable(
                int tableLog,
                int tableSize,
                int[] states,
                int[] values,
                int[] bitCounts,
                int[] initialStates
        ) {
            this.tableLog = tableLog;
            this.tableSize = tableSize;
            this.states = states;
            this.values = values;
            this.bitCounts = bitCounts;
            this.initialStates = initialStates;
        }

        /// Builds inverse transitions from a decoding table.
        private static EncoderTable fromDecoder(
                ZstdEntropy.FseTable decoder,
                int symbolCount
        ) throws IOException {
            int tableLog = decoder.tableLog();
            int tableSize = 1 << tableLog;
            int[] states = new int[symbolCount * tableSize];
            int[] values = new int[states.length];
            int[] bitCounts = new int[states.length];
            int[] initialStates = new int[symbolCount];
            Arrays.fill(states, -1);
            Arrays.fill(initialStates, -1);

            for (int state = 0; state < tableSize; state++) {
                int symbol = decoder.symbol(state);
                int bitCount = decoder.numberOfBits(state);
                int baseline = decoder.baseline(state);
                if (initialStates[symbol] < 0) {
                    initialStates[symbol] = state;
                }
                for (int value = 0; value < 1 << bitCount; value++) {
                    int nextState = baseline + value;
                    int index = symbol * tableSize + nextState;
                    if (states[index] >= 0) {
                        throw new IOException("Ambiguous Zstandard FSE encoding transition");
                    }
                    states[index] = state;
                    values[index] = value;
                    bitCounts[index] = bitCount;
                }
            }

            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (initialStates[symbol] < 0) {
                    throw new IOException("Missing Zstandard FSE encoding symbol");
                }
                for (int nextState = 0; nextState < tableSize; nextState++) {
                    if (states[symbol * tableSize + nextState] < 0) {
                        throw new IOException("Incomplete Zstandard FSE encoding transition");
                    }
                }
            }
            return new EncoderTable(tableLog, tableSize, states, values, bitCounts, initialStates);
        }

        /// Returns the number of bits used to flush a state.
        int tableLog() {
            return tableLog;
        }

        /// Returns one decoder state that represents the given final symbol.
        int initialState(int symbol) {
            if (symbol < 0 || symbol >= initialStates.length) {
                throw new IllegalArgumentException("Invalid Zstandard FSE symbol");
            }
            return initialStates[symbol];
        }

        /// Returns the inverse transition encoding a symbol before the given following state.
        Transition transition(int symbol, int nextState) {
            if (symbol < 0 || symbol >= initialStates.length
                    || nextState < 0 || nextState >= tableSize) {
                throw new IllegalArgumentException("Invalid Zstandard FSE encoding transition");
            }
            int index = symbol * tableSize + nextState;
            return new Transition(states[index], values[index], bitCounts[index]);
        }
    }

    /// Describes one inverse FSE state transition.
    ///
    /// @param state decoder state representing the encoded symbol
    /// @param value transition bits that advance to the following state
    /// @param bitCount number of transition bits
    record Transition(int state, int value, int bitCount) {
    }
}
