// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.ArrayList;

/// Builds and selects predefined, RLE, compressed, and repeat Zstandard sequence tables.
@NotNullByDefault
final class ZstdSequenceEntropy {
    /// Number of literal-length symbols in the predefined table.
    private static final int LITERAL_LENGTH_SYMBOLS = 36;

    /// Number of match-length symbols in the predefined table.
    private static final int MATCH_LENGTH_SYMBOLS = 53;

    /// Number of offset symbols accepted by the format.
    private static final int OFFSET_SYMBOLS = 32;

    /// Number of offset symbols represented by the predefined table.
    private static final int PREDEFINED_OFFSET_SYMBOLS = 29;

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
    static final ZstdEntropy.FseEncoderTable LITERAL_LENGTH_ENCODER;

    /// Inverse transitions for predefined match-length encoding.
    static final ZstdEntropy.FseEncoderTable MATCH_LENGTH_ENCODER;

    /// Inverse transitions for predefined offset-code encoding.
    static final ZstdEntropy.FseEncoderTable OFFSET_ENCODER;

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
                    PREDEFINED_OFFSET_SYMBOLS,
                    5
            );
            LITERAL_LENGTH_ENCODER = ZstdEntropy.FseEncoderTable.fromDecoder(
                    LITERAL_LENGTH_DECODER, LITERAL_LENGTH_SYMBOLS
            );
            MATCH_LENGTH_ENCODER = ZstdEntropy.FseEncoderTable.fromDecoder(
                    MATCH_LENGTH_DECODER, MATCH_LENGTH_SYMBOLS
            );
            OFFSET_ENCODER = ZstdEntropy.FseEncoderTable.fromDecoder(
                    OFFSET_DECODER, PREDEFINED_OFFSET_SYMBOLS
            );
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Selects an encoding table for literal-length symbols.
    static TableEncoding selectLiteralLengths(
            int[] symbols,
            @Nullable ZstdEntropy.FseEncoderTable previous
    ) {
        return select(
                symbols,
                LITERAL_LENGTH_SYMBOLS,
                9,
                LITERAL_LENGTH_ENCODER,
                previous
        );
    }

    /// Selects an encoding table for offset symbols.
    static TableEncoding selectOffsets(
            int[] symbols,
            @Nullable ZstdEntropy.FseEncoderTable previous
    ) {
        return select(symbols, OFFSET_SYMBOLS, 8, OFFSET_ENCODER, previous);
    }

    /// Selects an encoding table for match-length symbols.
    static TableEncoding selectMatchLengths(
            int[] symbols,
            @Nullable ZstdEntropy.FseEncoderTable previous
    ) {
        return select(symbols, MATCH_LENGTH_SYMBOLS, 9, MATCH_LENGTH_ENCODER, previous);
    }

    /// Inverts a dictionary literal-length table for repeat-mode encoding.
    static @Nullable ZstdEntropy.FseEncoderTable invertLiteralLengths(
            @Nullable ZstdEntropy.FseTable decoder
    ) {
        return invert(decoder, LITERAL_LENGTH_SYMBOLS);
    }

    /// Inverts a dictionary offset table for repeat-mode encoding.
    static @Nullable ZstdEntropy.FseEncoderTable invertOffsets(
            @Nullable ZstdEntropy.FseTable decoder
    ) {
        return invert(decoder, OFFSET_SYMBOLS);
    }

    /// Inverts a dictionary match-length table for repeat-mode encoding.
    static @Nullable ZstdEntropy.FseEncoderTable invertMatchLengths(
            @Nullable ZstdEntropy.FseTable decoder
    ) {
        return invert(decoder, MATCH_LENGTH_SYMBOLS);
    }

    /// Selects the cheapest compatible representation for one sequence-code stream.
    private static TableEncoding select(
            int[] symbols,
            int symbolCount,
            int maximumTableLog,
            ZstdEntropy.FseEncoderTable predefined,
            @Nullable ZstdEntropy.FseEncoderTable previous
    ) {
        if (symbols.length == 0) {
            throw new IllegalArgumentException("A Zstandard sequence table requires symbols");
        }

        ArrayList<TableEncoding> candidates = new ArrayList<>(maximumTableLog + 1);
        if (covers(predefined, symbols)) {
            candidates.add(new TableEncoding(0, new byte[0], predefined));
        }
        if (previous != null && covers(previous, symbols)) {
            candidates.add(new TableEncoding(3, new byte[0], previous));
        }

        int[] frequencies = new int[symbolCount];
        int maximumSymbol = 0;
        int distinctSymbols = 0;
        for (int symbol : symbols) {
            if (symbol < 0 || symbol >= symbolCount) {
                throw new IllegalArgumentException("Invalid Zstandard sequence symbol");
            }
            if (frequencies[symbol]++ == 0) {
                distinctSymbols++;
                maximumSymbol = Math.max(maximumSymbol, symbol);
            }
        }

        if (distinctSymbols == 1) {
            ZstdEntropy.FseEncoderTable rle = invertRequired(
                    ZstdEntropy.FseTable.rle(maximumSymbol),
                    maximumSymbol + 1
            );
            candidates.add(new TableEncoding(
                    1,
                    new byte[]{(byte) maximumSymbol},
                    rle
            ));
        } else {
            int minimumTableLog = Math.max(
                    5,
                    32 - Integer.numberOfLeadingZeros(distinctSymbols - 1)
            );
            for (int tableLog = minimumTableLog; tableLog <= maximumTableLog; tableLog++) {
                int[] normalized = normalize(
                        frequencies,
                        maximumSymbol + 1,
                        symbols.length,
                        tableLog
                );
                byte[] description = ZstdEntropy.encodeFseTableDescription(
                        normalized,
                        maximumSymbol + 1,
                        tableLog
                );
                try {
                    ZstdEntropy.FseTable decoder = ZstdEntropy.FseTable.fromNormalized(
                            normalized,
                            maximumSymbol + 1,
                            tableLog
                    );
                    candidates.add(new TableEncoding(
                            2,
                            description,
                            ZstdEntropy.FseEncoderTable.fromDecoder(
                                    decoder,
                                    maximumSymbol + 1
                            )
                    ));
                } catch (IOException exception) {
                    throw new IllegalStateException(
                            "Cannot build Zstandard sequence FSE table",
                            exception
                    );
                }
            }
        }

        TableEncoding selected = candidates.get(0);
        long selectedCost = encodedCost(selected, symbols);
        for (int index = 1; index < candidates.size(); index++) {
            TableEncoding candidate = candidates.get(index);
            long cost = encodedCost(candidate, symbols);
            if (cost < selectedCost) {
                selected = candidate;
                selectedCost = cost;
            }
        }
        return selected;
    }

    /// Returns whether a table covers every symbol in a stream.
    private static boolean covers(ZstdEntropy.FseEncoderTable table, int[] symbols) {
        for (int symbol : symbols) {
            if (!table.canEncode(symbol)) {
                return false;
            }
        }
        return true;
    }

    /// Returns description and state-transition cost in bits.
    private static long encodedCost(TableEncoding encoding, int[] symbols) {
        ZstdEntropy.FseEncoderTable table = encoding.table();
        long cost = (long) encoding.description().length * 8L + table.tableLog();
        int state = table.initialState(symbols[symbols.length - 1]);
        for (int index = symbols.length - 2; index >= 0; index--) {
            ZstdEntropy.FseTransition transition = table.transition(symbols[index], state);
            cost += transition.bitCount();
            state = transition.state();
        }
        return cost;
    }

    /// Normalizes observed frequencies to one complete power-of-two FSE distribution.
    private static int[] normalize(
            int[] frequencies,
            int symbolCount,
            int total,
            int tableLog
    ) {
        int tableSize = 1 << tableLog;
        int distinctSymbols = 0;
        int[] normalized = new int[symbolCount];
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (frequencies[symbol] != 0) {
                normalized[symbol] = 1;
                distinctSymbols++;
            }
        }
        int distributable = tableSize - distinctSymbols;
        int assigned = 0;
        long[] remainders = new long[symbolCount];
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            if (frequencies[symbol] == 0) {
                remainders[symbol] = -1L;
                continue;
            }
            long scaled = (long) frequencies[symbol] * distributable;
            int addition = (int) (scaled / total);
            normalized[symbol] += addition;
            assigned += addition;
            remainders[symbol] = scaled % total;
        }

        int remaining = distributable - assigned;
        while (remaining-- > 0) {
            int best = -1;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (best < 0
                        || remainders[symbol] > remainders[best]
                        || remainders[symbol] == remainders[best]
                        && frequencies[symbol] > frequencies[best]) {
                    best = symbol;
                }
            }
            if (best < 0 || remainders[best] < 0L) {
                throw new IllegalStateException("Cannot normalize Zstandard sequence frequencies");
            }
            normalized[best]++;
            remainders[best] = -1L;
        }
        return normalized;
    }

    /// Inverts a decoder table while preserving an absent dictionary table.
    private static @Nullable ZstdEntropy.FseEncoderTable invert(
            @Nullable ZstdEntropy.FseTable decoder,
            int symbolCount
    ) {
        return decoder == null ? null : invertRequired(decoder, symbolCount);
    }

    /// Inverts one required decoder table.
    private static ZstdEntropy.FseEncoderTable invertRequired(
            ZstdEntropy.FseTable decoder,
            int symbolCount
    ) {
        try {
            return ZstdEntropy.FseEncoderTable.fromDecoder(decoder, symbolCount);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot invert Zstandard sequence FSE table", exception);
        }
    }

    /// Holds one selected sequence-code table representation.
    ///
    /// @param mode sequence table mode
    /// @param description byte-aligned mode payload
    /// @param table inverse FSE transitions used by the sequence bitstream
    record TableEncoding(
            int mode,
            byte @Unmodifiable [] description,
            ZstdEntropy.FseEncoderTable table
    ) {
    }

    /// Creates no instances.
    private ZstdSequenceEntropy() {
    }
}
