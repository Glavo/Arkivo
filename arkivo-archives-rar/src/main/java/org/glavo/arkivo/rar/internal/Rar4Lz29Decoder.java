// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/// Implements the canonical-Huffman LZ mode used by RAR 3.x and 4.x archives.
@NotNullByDefault
final class Rar4Lz29Decoder {
    /// The fixed RAR 3.x dictionary size.
    private static final int DICTIONARY_SIZE = 1 << 22;

    /// The number of symbols in the main alphabet.
    private static final int MAIN_ALPHABET_SIZE = 299;

    /// The number of symbols in the distance alphabet.
    private static final int DISTANCE_ALPHABET_SIZE = 60;

    /// The number of symbols in the low-distance alphabet.
    private static final int LOW_DISTANCE_ALPHABET_SIZE = 17;

    /// The number of symbols in the repeated-distance length alphabet.
    private static final int REPEAT_LENGTH_ALPHABET_SIZE = 28;

    /// The number of symbols in the code-length alphabet.
    private static final int LEVEL_ALPHABET_SIZE = 20;

    /// The total number of persisted code lengths.
    private static final int TABLE_LENGTH_COUNT = MAIN_ALPHABET_SIZE
            + DISTANCE_ALPHABET_SIZE
            + LOW_DISTANCE_ALPHABET_SIZE
            + REPEAT_LENGTH_ALPHABET_SIZE;

    /// The number of times a repeated low distance can be reused.
    private static final int LOW_DISTANCE_REPETITIONS = 16;

    /// Base match lengths selected by the 28 length slots.
    private static final int[] LENGTH_BASES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20,
            24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224
    };

    /// Extra bit widths selected by the 28 length slots.
    private static final byte[] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2,
            2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5
    };

    /// Base distances selected by the eight short-distance slots.
    private static final int[] SHORT_DISTANCE_BASES = {0, 4, 8, 16, 32, 64, 128, 192};

    /// Extra bit widths selected by the eight short-distance slots.
    private static final byte[] SHORT_DISTANCE_EXTRA_BITS = {2, 2, 3, 4, 5, 6, 6, 6};

    /// Counts used to derive all 60 long-distance slots.
    private static final int[] DISTANCE_BIT_LENGTH_COUNTS = {
            4, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 14, 0, 12
    };

    /// Base values for all long-distance slots.
    private static final int[] DISTANCE_BASES = new int[DISTANCE_ALPHABET_SIZE];

    /// Extra bit widths for all long-distance slots.
    private static final byte[] DISTANCE_EXTRA_BITS = new byte[DISTANCE_ALPHABET_SIZE];

    static {
        int slot = 0;
        int distance = 0;
        for (int bitLength = 0; bitLength < DISTANCE_BIT_LENGTH_COUNTS.length; bitLength++) {
            int count = DISTANCE_BIT_LENGTH_COUNTS[bitLength];
            for (int index = 0; index < count; index++) {
                DISTANCE_BASES[slot] = distance;
                DISTANCE_EXTRA_BITS[slot] = (byte) bitLength;
                slot++;
                distance += 1 << bitLength;
            }
        }
        if (slot != DISTANCE_ALPHABET_SIZE) {
            throw new ExceptionInInitializerError("Invalid RAR4 distance table");
        }
    }

    /// The main literal and control decoder.
    private final Rar4HuffmanDecoder mainDecoder = new Rar4HuffmanDecoder();

    /// The long-distance decoder.
    private final Rar4HuffmanDecoder distanceDecoder = new Rar4HuffmanDecoder();

    /// The low-distance decoder.
    private final Rar4HuffmanDecoder lowDistanceDecoder = new Rar4HuffmanDecoder();

    /// The repeated-distance length decoder.
    private final Rar4HuffmanDecoder repeatLengthDecoder = new Rar4HuffmanDecoder();

    /// Filter definitions and state retained across solid entries.
    private final Rar3FilterManager filterManager = new Rar3FilterManager();

    /// Code lengths retained for delta table descriptions in solid streams.
    private final byte[] previousTable = new byte[TABLE_LENGTH_COUNT];

    /// The four most recent match distances.
    private final int[] recentDistances = new int[4];

    /// The fixed 4 MiB solid dictionary.
    private byte[] dictionary = new byte[DICTIONARY_SIZE];

    /// The next dictionary position to overwrite.
    private int dictionaryPosition;

    /// The total number of raw bytes produced by the current solid sequence.
    private long totalRawSize;

    /// The most recent match distance.
    private int lastDistance;

    /// The most recent match length.
    private int lastLength;

    /// The low-distance value eligible for compact repetition.
    private int previousLowDistance;

    /// The number of remaining compact low-distance repetitions.
    private int lowDistanceRepeatCount;

    /// Whether the current Huffman tables are available for reuse.
    private boolean tablesAvailable;

    /// Creates a decoder with an empty solid dictionary.
    Rar4Lz29Decoder() {
    }

    /// Decodes one RAR 3.x/4.x entry.
    void decode(InputStream input, OutputStream output, long unpackedSize, boolean solid) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (unpackedSize < 0L) {
            throw new IOException("Compressed RAR4 entry has an unknown unpacked size");
        }
        if (!solid) {
            reset();
        }

        Rar4BitInput bits = new Rar4BitInput(input);
        if (!solid || !tablesAvailable) {
            readTables(bits);
        }
        Rar3OutputPipeline entryOutput = new Rar3OutputPipeline(output, unpackedSize);

        while (!entryOutput.isComplete()) {
            int symbol = mainDecoder.decode(bits);
            if (symbol < 256) {
                emit(symbol, entryOutput);
            } else if (symbol >= 271) {
                decodeNewMatch(bits, symbol - 271, entryOutput);
            } else if (symbol == 256) {
                if (!readEndOfBlock(bits)) {
                    break;
                }
            } else if (symbol == 257) {
                entryOutput.schedule(filterManager.parse(readFilterDescriptor(bits)));
            } else if (symbol == 258) {
                if (lastLength == 0 || lastDistance == 0) {
                    throw new IOException("RAR4 stream repeats an unavailable match");
                }
                copyMatch(lastDistance, lastLength, entryOutput);
            } else if (symbol < 263) {
                decodeRecentMatch(bits, symbol - 259, entryOutput);
            } else {
                decodeShortMatch(bits, symbol - 263, entryOutput);
            }
        }
        entryOutput.finish();
    }

    /// Invalidates all solid history after a skipped or failed entry.
    void invalidate() {
        reset();
        tablesAvailable = false;
    }

    /// Releases the fixed dictionary immediately.
    void release() {
        dictionary = new byte[0];
        invalidate();
    }

    /// Restores a released dictionary before reuse.
    private void ensureDictionary() {
        if (dictionary.length != DICTIONARY_SIZE) {
            dictionary = new byte[DICTIONARY_SIZE];
        }
    }

    /// Resets dictionary, match, and entropy state for a non-solid sequence.
    private void reset() {
        ensureDictionary();
        dictionaryPosition = 0;
        totalRawSize = 0L;
        Arrays.fill(recentDistances, 0);
        Arrays.fill(previousTable, (byte) 0);
        lastDistance = 0;
        lastLength = 0;
        previousLowDistance = 0;
        lowDistanceRepeatCount = 0;
        tablesAvailable = false;
        mainDecoder.reset();
        distanceDecoder.reset();
        lowDistanceDecoder.reset();
        repeatLengthDecoder.reset();
        filterManager.reset();
    }

    /// Reads a complete set of delta-coded RAR3 Huffman tables.
    private void readTables(Rar4BitInput bits) throws IOException {
        bits.alignToByte();
        if (bits.readBits(1) != 0) {
            throw new IOException("RAR3 PPM compression is not implemented by the native decoder");
        }
        boolean keepPreviousTable = bits.readBits(1) != 0;
        if (!keepPreviousTable) {
            Arrays.fill(previousTable, (byte) 0);
        }
        previousLowDistance = 0;
        lowDistanceRepeatCount = 0;

        byte[] levelLengths = new byte[LEVEL_ALPHABET_SIZE];
        for (int index = 0; index < levelLengths.length;) {
            int length = bits.readBits(4);
            if (length == 15) {
                int zeroCount = bits.readBits(4);
                if (zeroCount != 0) {
                    int end = Math.min(levelLengths.length, index + zeroCount + 2);
                    Arrays.fill(levelLengths, index, end, (byte) 0);
                    index = end;
                    continue;
                }
            }
            levelLengths[index++] = (byte) length;
        }

        Rar4HuffmanDecoder levelDecoder = new Rar4HuffmanDecoder();
        levelDecoder.build(levelLengths, 0, levelLengths.length);
        byte[] table = new byte[TABLE_LENGTH_COUNT];
        for (int index = 0; index < table.length;) {
            int symbol = levelDecoder.decode(bits);
            if (symbol < 16) {
                table[index] = (byte) ((symbol + previousTable[index]) & 0x0f);
                index++;
                continue;
            }

            int count;
            byte value;
            if (symbol < 18) {
                if (index == 0) {
                    throw new IOException("RAR4 Huffman table starts with a repeat symbol");
                }
                int extraBits = symbol == 16 ? 3 : 7;
                count = (symbol == 16 ? 3 : 11) + bits.readBits(extraBits);
                value = table[index - 1];
            } else {
                int extraBits = symbol == 18 ? 3 : 7;
                count = (symbol == 18 ? 3 : 11) + bits.readBits(extraBits);
                value = 0;
            }
            int end = Math.min(table.length, index + count);
            Arrays.fill(table, index, end, value);
            index = end;
        }

        int mainOffset = 0;
        int distanceOffset = mainOffset + MAIN_ALPHABET_SIZE;
        int lowDistanceOffset = distanceOffset + DISTANCE_ALPHABET_SIZE;
        int repeatLengthOffset = lowDistanceOffset + LOW_DISTANCE_ALPHABET_SIZE;
        mainDecoder.build(table, mainOffset, MAIN_ALPHABET_SIZE);
        distanceDecoder.build(table, distanceOffset, DISTANCE_ALPHABET_SIZE);
        lowDistanceDecoder.build(table, lowDistanceOffset, LOW_DISTANCE_ALPHABET_SIZE);
        repeatLengthDecoder.build(table, repeatLengthOffset, REPEAT_LENGTH_ALPHABET_SIZE);
        System.arraycopy(table, 0, previousTable, 0, table.length);
        tablesAvailable = true;
    }

    /// Processes an end-of-block control and optionally reads replacement tables.
    private boolean readEndOfBlock(Rar4BitInput bits) throws IOException {
        if (bits.readBits(1) != 0) {
            readTables(bits);
            return true;
        }
        boolean nextFileUsesNewTables = bits.readBits(1) != 0;
        tablesAvailable = !nextFileUsesNewTables;
        return false;
    }

    /// Decodes a new length and long-distance pair.
    private void decodeNewMatch(Rar4BitInput bits, int lengthSlot, Rar3OutputPipeline output) throws IOException {
        int length = decodeLength(bits, lengthSlot, 3);
        int distanceSlot = distanceDecoder.decode(bits);
        if (distanceSlot < 0 || distanceSlot >= DISTANCE_ALPHABET_SIZE) {
            throw new IOException("Invalid RAR4 distance slot");
        }
        int extraBits = DISTANCE_EXTRA_BITS[distanceSlot] & 0xff;
        long distance = DISTANCE_BASES[distanceSlot] + 1L;
        if (extraBits != 0) {
            if (distanceSlot > 9) {
                if (extraBits > 4) {
                    distance += (long) bits.readBits(extraBits - 4) << 4;
                }
                if (lowDistanceRepeatCount != 0) {
                    lowDistanceRepeatCount--;
                    distance += previousLowDistance;
                } else {
                    int lowDistance = lowDistanceDecoder.decode(bits);
                    if (lowDistance == 16) {
                        lowDistanceRepeatCount = LOW_DISTANCE_REPETITIONS - 1;
                        distance += previousLowDistance;
                    } else {
                        distance += lowDistance;
                        previousLowDistance = lowDistance;
                    }
                }
            } else {
                distance += bits.readBits(extraBits);
            }
        }
        if (distance >= 0x2000L) {
            length++;
            if (distance >= 0x40000L) {
                length++;
            }
        }
        if (distance > Integer.MAX_VALUE) {
            throw new IOException("RAR4 match distance overflows");
        }
        insertRecentDistance((int) distance);
        lastDistance = (int) distance;
        lastLength = length;
        copyMatch((int) distance, length, output);
    }

    /// Decodes a match using one of the four recent distances.
    private void decodeRecentMatch(Rar4BitInput bits, int index, Rar3OutputPipeline output) throws IOException {
        if (index < 0 || index >= recentDistances.length || recentDistances[index] == 0) {
            throw new IOException("RAR4 stream references an unavailable recent distance");
        }
        int distance = recentDistances[index];
        for (int current = index; current > 0; current--) {
            recentDistances[current] = recentDistances[current - 1];
        }
        recentDistances[0] = distance;
        int length = decodeLength(bits, repeatLengthDecoder.decode(bits), 2);
        lastDistance = distance;
        lastLength = length;
        copyMatch(distance, length, output);
    }

    /// Decodes one fixed two-byte match using the short-distance alphabet.
    private void decodeShortMatch(Rar4BitInput bits, int slot, Rar3OutputPipeline output) throws IOException {
        if (slot < 0 || slot >= SHORT_DISTANCE_BASES.length) {
            throw new IOException("Invalid RAR4 short-distance slot");
        }
        int distance = SHORT_DISTANCE_BASES[slot]
                + bits.readBits(SHORT_DISTANCE_EXTRA_BITS[slot] & 0xff)
                + 1;
        insertRecentDistance(distance);
        lastDistance = distance;
        lastLength = 2;
        copyMatch(distance, 2, output);
    }

    /// Decodes one of the shared 28 match-length slots.
    private static int decodeLength(Rar4BitInput bits, int slot, int minimum) throws IOException {
        if (slot < 0 || slot >= LENGTH_BASES.length) {
            throw new IOException("Invalid RAR4 match-length slot");
        }
        int extraBits = LENGTH_EXTRA_BITS[slot] & 0xff;
        return LENGTH_BASES[slot] + bits.readBits(extraBits) + minimum;
    }

    /// Inserts one new distance at the front of the four-entry queue.
    private void insertRecentDistance(int distance) {
        recentDistances[3] = recentDistances[2];
        recentDistances[2] = recentDistances[1];
        recentDistances[1] = recentDistances[0];
        recentDistances[0] = distance;
    }

    /// Copies one potentially overlapping match from the 4 MiB dictionary.
    private void copyMatch(int distance, int length, Rar3OutputPipeline output) throws IOException {
        long available = Math.min(totalRawSize, DICTIONARY_SIZE);
        if (distance <= 0 || distance > available) {
            throw new IOException("RAR4 match distance exceeds available dictionary history");
        }
        int source = dictionaryPosition - distance;
        if (source < 0) {
            source += DICTIONARY_SIZE;
        }
        for (int index = 0; index < length; index++) {
            int value = dictionary[source] & 0xff;
            source = source + 1 & DICTIONARY_SIZE - 1;
            emit(value, output);
        }
    }

    /// Appends one raw byte to the entry output and solid dictionary.
    private void emit(int value, Rar3OutputPipeline output) throws IOException {
        output.accept(value);
        dictionary[dictionaryPosition] = (byte) value;
        dictionaryPosition = dictionaryPosition + 1 & DICTIONARY_SIZE - 1;
        totalRawSize++;
    }

    /// Reads one complete LZ-mode filter descriptor including its flags byte.
    private static byte[] readFilterDescriptor(Rar4BitInput bits) throws IOException {
        int flags = bits.readBits(8);
        int payloadSize = (flags & 7) + 1;
        if (payloadSize == 7) {
            payloadSize = bits.readBits(8) + 7;
        } else if (payloadSize == 8) {
            payloadSize = bits.readBits(16);
        }
        byte[] descriptor = new byte[payloadSize + 1];
        descriptor[0] = (byte) flags;
        for (int index = 0; index < payloadSize; index++) descriptor[index + 1] = (byte) bits.readBits(8);
        return descriptor;
    }
}
