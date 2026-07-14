// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies adaptive sequence-code table selection.
@NotNullByDefault
public final class ZstdSequenceEntropyTest {
    /// Verifies a skewed stream establishes a compressed table and then repeats it.
    @Test
    public void selectsDynamicThenRepeatTable() {
        int[] symbols = new int[4_096];
        for (int index = 0; index < symbols.length; index++) {
            symbols[index] = index % 17 == 0 ? 15 : index % 5 == 0 ? 4 : 0;
        }

        ZstdSequenceEntropy.TableEncoding dynamic =
                ZstdSequenceEntropy.selectLiteralLengths(symbols, null);
        ZstdSequenceEntropy.TableEncoding repeat =
                ZstdSequenceEntropy.selectLiteralLengths(symbols, dynamic.table());

        assertEquals(2, dynamic.mode());
        assertEquals(3, repeat.mode());
    }

    /// Verifies a full offset alphabet survives description and transition round trips.
    @Test
    public void roundTripsFullOffsetAlphabet() throws IOException {
        int[] symbols = new int[4_096];
        for (int symbol = 0; symbol < 32; symbol++) {
            symbols[symbol] = symbol;
        }
        for (int index = 32; index < symbols.length; index++) {
            symbols[index] = index % 19 == 0 ? index & 31 : 0;
        }

        ZstdSequenceEntropy.TableEncoding encoding =
                ZstdSequenceEntropy.selectOffsets(symbols, null);
        assertEquals(2, encoding.mode());
        ZstdEntropy.FseParseResult parsed = ZstdEntropy.readFseTable(
                encoding.description(),
                0,
                encoding.description().length,
                31,
                8
        );
        assertEquals(encoding.description().length, parsed.bytesRead());

        ZstdEntropy.FseEncoderTable encoder = encoding.table();
        ZstdEntropy.ReverseBitWriter writer = new ZstdEntropy.ReverseBitWriter();
        int state = encoder.initialState(symbols[symbols.length - 1]);
        for (int index = symbols.length - 2; index >= 0; index--) {
            ZstdEntropy.FseTransition transition = encoder.transition(symbols[index], state);
            writer.writeBits(transition.value(), transition.bitCount());
            state = transition.state();
        }
        writer.writeBits(state, encoder.tableLog());
        byte[] stream = writer.finish();

        ZstdEntropy.FseTable decoder = parsed.table();
        ZstdEntropy.ReverseBitReader reader =
                new ZstdEntropy.ReverseBitReader(stream, 0, stream.length);
        state = reader.readBits(decoder.tableLog());
        for (int index = 0; index < symbols.length; index++) {
            assertEquals(symbols[index], decoder.symbol(state));
            if (index + 1 < symbols.length) {
                state = decoder.nextState(state, reader);
            }
        }
        reader.requireFullyConsumed();
    }

    /// Verifies a constant stream selects the one-symbol representation.
    @Test
    public void selectsRleTable() {
        int[] symbols = new int[256];
        Arrays.fill(symbols, 7);

        assertEquals(1, ZstdSequenceEntropy.selectMatchLengths(symbols, null).mode());
    }
}

