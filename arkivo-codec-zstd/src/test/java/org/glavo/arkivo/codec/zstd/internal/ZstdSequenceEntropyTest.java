// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies adaptive sequence-code table selection.
@NotNullByDefault
public final class ZstdSequenceEntropyTest {
    /// Compares mixed-width reads across words and short tails with independent bitwise extraction.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 8, 9, 15, 16, 17, 23, 24, 25, 31, 32, 33,
            39, 40, 41, 47, 48, 49, 55, 56, 57, 63, 64, 65, 127, 128, 1024})
    public void readsReverseFieldsAcrossWordBoundaries(int usefulBits) throws IOException {
        Random random = new Random(8200 + usefulBits);
        for (int prefix : new int[]{0, 1, 8}) {
            for (int suffix : new int[]{0, 1, 8}) {
                int bytes = usefulBits / 8 + 1;
                byte[] source = new byte[prefix + bytes + suffix];
                random.nextBytes(source);
                Arrays.fill(source, 0, prefix, (byte) 0xff);
                Arrays.fill(source, prefix + bytes, source.length, (byte) 0xff);
                int markerBits = usefulBits & 7;
                source[prefix + bytes - 1] = (byte) ((source[prefix + bytes - 1] & ((1 << markerBits) - 1))
                        | (1 << markerBits));
                var reader = new ZstdEntropy.ReverseBitReader(source, prefix, prefix + bytes);
                int remaining = usefulBits;
                while (remaining > 0) {
                    assertEquals(0, reader.readBits(0));
                    assertThrows(IOException.class, () -> reader.readBits(-1));
                    assertThrows(IOException.class, () -> reader.readBits(32));
                    if (remaining < 31) {
                        int tooMany = remaining + 1;
                        assertThrows(IOException.class, () -> reader.readBits(tooMany));
                    }
                    assertEquals(remaining, reader.remainingBits());
                    int count = Math.min(remaining, random.nextInt(1, 32));
                    remaining -= count;
                    int expected = 0;
                    for (int bit = 0; bit < count; bit++) {
                        int position = prefix * 8 + remaining + bit;
                        expected |= ((source[position >>> 3] >>> (position & 7)) & 1) << bit;
                    }
                    assertEquals(expected, reader.readBits(count));
                    assertEquals(remaining, reader.remainingBits());
                }
                reader.requireFullyConsumed();
                assertEquals(0, reader.readBits(0));
                assertThrows(IOException.class, () -> reader.readBits(1));
            }
        }
    }

    /// Checks symbol frequencies and disjoint, complete next-state ranges for every present byte symbol.
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 9})
    public void preservesFseStatePartitions(int tableLog) throws IOException {
        int tableSize = 1 << tableLog;
        int[] counts = new int[256];
        counts[0] = counts[255] = 1;
        Random random = new Random(9100 + tableLog);
        for (int i = 2; i < tableSize; i++) counts[random.nextInt(counts.length)]++;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 1 && random.nextBoolean()) counts[i] = -1;
        }
        var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, tableLog);
        assertEquals(tableLog, table.tableLog());
        for (int symbol = 0; symbol < counts.length; symbol++) {
            boolean[] covered = new boolean[tableSize];
            int occurrences = 0;
            for (int state = 0; state < tableSize; state++) {
                if (table.symbol(state) != symbol) continue;
                occurrences++;
                int bitCount = table.numberOfBits(state);
                int baseline = table.baseline(state);
                assertTrue(bitCount >= 0 && bitCount <= tableLog);
                assertTrue(baseline >= 0 && baseline <= tableSize - (1 << bitCount));
                for (int value = 0; value < 1 << bitCount; value++) {
                    assertFalse(covered[baseline + value]);
                    covered[baseline + value] = true;
                }
                for (int value : new int[]{0, (1 << bitCount) - 1}) {
                    var writer = new ZstdEntropy.ReverseBitWriter();
                    writer.writeBits(value, bitCount);
                    byte[] stream = writer.finish();
                    var reader = new ZstdEntropy.ReverseBitReader(stream, 0, stream.length);
                    assertEquals(baseline + value, table.nextState(state, reader));
                    reader.requireFullyConsumed();
                }
            }
            assertEquals(Math.abs(counts[symbol]), occurrences);
            for (boolean present : covered) assertEquals(occurrences != 0, present);
        }
        var empty = new ZstdEntropy.ReverseBitReader(new byte[]{1}, 0, 1);
        for (int state : new int[]{-1, tableSize, Integer.MAX_VALUE}) {
            assertThrows(IOException.class, () -> table.symbol(state));
            assertThrows(IOException.class, () -> table.numberOfBits(state));
            assertThrows(IOException.class, () -> table.baseline(state));
            assertThrows(IOException.class, () -> table.nextState(state, empty));
        }
        for (int state = 0; state < tableSize; state++) {
            if (table.numberOfBits(state) != 0) {
                int selected = state;
                assertThrows(IOException.class, () -> table.nextState(selected, empty));
                assertEquals(0, empty.remainingBits());
            }
        }
    }

    /// Preserves the full byte alphabet for zero-bit RLE transitions and the largest supported transition count.
    @Test
    public void preservesFseFieldBoundaries() throws IOException {
        var empty = new ZstdEntropy.ReverseBitReader(new byte[]{1}, 0, 1);
        for (int symbol = 0; symbol <= 255; symbol++) {
            var table = ZstdEntropy.FseTable.rle(symbol);
            assertEquals(0, table.tableLog());
            assertEquals(symbol, table.symbol(0));
            assertEquals(0, table.numberOfBits(0));
            assertEquals(0, table.baseline(0));
            assertEquals(0, table.nextState(0, empty));
            assertEquals(0, empty.remainingBits());
        }
        int[] counts = new int[256];
        counts[0] = (1 << 15) - 1;
        counts[255] = -1;
        var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, 15);
        assertEquals(255, table.symbol((1 << 15) - 1));
        assertEquals(15, table.numberOfBits((1 << 15) - 1));
        assertEquals(0, table.baseline((1 << 15) - 1));
        boolean highBaseline = false;
        for (int state = 0; state < (1 << 15) - 1; state++) {
            assertEquals(0, table.symbol(state));
            highBaseline |= table.baseline(state) >= 1 << 14;
        }
        assertTrue(highBaseline);
        assertThrows(IllegalArgumentException.class, () -> ZstdEntropy.FseTable.rle(-1));
        assertThrows(IllegalArgumentException.class, () -> ZstdEntropy.FseTable.rle(256));
        assertThrows(IOException.class, () -> ZstdEntropy.FseTable.fromNormalized(counts, 257, 9));
        assertThrows(IOException.class, () -> ZstdEntropy.FseTable.fromNormalized(counts, 256, 16));
    }

    /// Checks wide bit loads and their short-array fallback at every field width and bit alignment.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void readsReverseFieldsWithoutIncludingAdjacentBytes(boolean grouped) throws IOException {
        Random random = new Random(42);
        for (int count = 0; count <= (grouped ? 56 : 31); count++) {
            for (int alignment = 0; alignment < 8; alignment++) {
                long value = random.nextLong() & ((1L << count) - 1);
                var writer = new ZstdEntropy.ReverseBitWriter();
                writer.writeBits(0, alignment);
                if (count <= 31) {
                    writer.writeBits(value, count);
                } else {
                    writer.writeBits(value & 0x7fff_ffffL, 31);
                    writer.writeBits(value >>> 31, count - 31);
                }
                byte[] stream = writer.finish();
                for (int prefix = 0; prefix <= Long.BYTES; prefix++) {
                    for (int suffix = 0; suffix <= Long.BYTES; suffix++) {
                        byte[] source = new byte[prefix + stream.length + suffix];
                        Arrays.fill(source, (byte) 0xff);
                        System.arraycopy(stream, 0, source, prefix, stream.length);
                        var reader = new ZstdEntropy.ReverseBitReader(source, prefix, prefix + stream.length);
                        if (grouped) {
                            assertThrows(IOException.class, () -> reader.readSequenceBits(reader.remainingBits() + 1));
                            assertThrows(IOException.class, () -> reader.readSequenceBits(-1));
                            assertEquals(count + alignment, reader.remainingBits());
                            assertEquals(value, reader.readSequenceBits(count));
                        } else {
                            assertThrows(IOException.class, () -> reader.readBits(reader.remainingBits() + 1));
                            assertEquals(value, Integer.toUnsignedLong(reader.readBits(count)));
                        }
                        assertEquals(0, reader.readBits(alignment));
                        reader.requireFullyConsumed();
                        assertEquals(0, grouped ? reader.readSequenceBits(0) : reader.readBits(0));
                        if (grouped) assertThrows(IOException.class, () -> reader.readSequenceBits(1));
                        else assertThrows(IOException.class, () -> reader.readBits(1));
                    }
                }
            }
        }
    }

    /// Accepts a terminal zero-frequency run without requiring unrelated bytes after the complete table.
    @Test
    public void readsShortTerminalZeroRun() throws IOException {
        int[] counts = new int[33];
        Arrays.fill(counts, 0, 31, -1);
        counts[32] = 1;
        byte[] description = ZstdEntropy.encodeFseTableDescription(counts, counts.length, 5);
        for (int suffix : new int[]{0, 1, 2, 16}) {
            byte[] source = Arrays.copyOf(description, description.length + suffix);
            Arrays.fill(source, description.length, source.length, (byte) 0xff);
            var parsed = ZstdEntropy.readFseTable(source, 0, source.length, 32, 5);
            assertEquals(description.length, parsed.bytesRead());
            assertEquals(5, parsed.table().tableLog());
            assertEquals(32, parsed.table().symbol(0));
            for (int state = 1; state < 32; state++) {
                assertEquals(31 - state, parsed.table().symbol(state));
                assertEquals(5, parsed.table().numberOfBits(state));
                assertEquals(0, parsed.table().baseline(state));
            }
        }
        for (int size = 0; size < description.length; size++) {
            int limit = size;
            assertThrows(IOException.class, () -> ZstdEntropy.readFseTable(description, 0, limit, 32, 5));
        }
    }

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
