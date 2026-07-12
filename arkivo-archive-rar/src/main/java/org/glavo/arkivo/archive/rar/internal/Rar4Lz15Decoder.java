// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/// Implements the adaptive character and LZ model used by RAR 1.5 archives.
@NotNullByDefault
final class Rar4Lz15Decoder {
    /// The fixed legacy RAR dictionary size.
    private static final int DICTIONARY_SIZE = 1 << 22;

    /// The initial width used by the first short-length table.
    private static final int LENGTH_START_1 = 2;

    /// Prefix boundaries for the first short-length table.
    private static final int @Unmodifiable [] LENGTH_LIMITS_1 = {
            0x8000, 0xa000, 0xc000, 0xd000, 0xe000, 0xea00,
            0xee00, 0xf000, 0xf200, 0xf200, 0xffff
    };

    /// Symbol offsets for the first short-length table.
    private static final int @Unmodifiable [] LENGTH_POSITIONS_1 = {
            0, 0, 0, 2, 3, 5, 7, 11, 16, 20, 24, 32, 32
    };

    /// The initial width used by the second short-length table.
    private static final int LENGTH_START_2 = 3;

    /// Prefix boundaries for the second short-length table.
    private static final int @Unmodifiable [] LENGTH_LIMITS_2 = {
            0xa000, 0xc000, 0xd000, 0xe000, 0xea00,
            0xee00, 0xf000, 0xf200, 0xf240, 0xffff
    };

    /// Symbol offsets for the second short-length table.
    private static final int @Unmodifiable [] LENGTH_POSITIONS_2 = {
            0, 0, 0, 0, 5, 7, 9, 13, 18, 22, 26, 34, 36
    };

    /// The initial width used by adaptive table zero.
    private static final int HUFFMAN_START_0 = 4;

    /// Prefix boundaries for adaptive table zero.
    private static final int @Unmodifiable [] HUFFMAN_LIMITS_0 = {
            0x8000, 0xc000, 0xe000, 0xf200, 0xf200, 0xf200, 0xf200, 0xf200, 0xffff
    };

    /// Symbol offsets for adaptive table zero.
    private static final int @Unmodifiable [] HUFFMAN_POSITIONS_0 = {
            0, 0, 0, 0, 0, 8, 16, 24, 33, 33, 33, 33, 33
    };

    /// The initial width used by adaptive table one.
    private static final int HUFFMAN_START_1 = 5;

    /// Prefix boundaries for adaptive table one.
    private static final int @Unmodifiable [] HUFFMAN_LIMITS_1 = {
            0x2000, 0xc000, 0xe000, 0xf000, 0xf200, 0xf200, 0xf7e0, 0xffff
    };

    /// Symbol offsets for adaptive table one.
    private static final int @Unmodifiable [] HUFFMAN_POSITIONS_1 = {
            0, 0, 0, 0, 0, 0, 4, 44, 60, 76, 80, 80, 127
    };

    /// The initial width used by adaptive table two.
    private static final int HUFFMAN_START_2 = 5;

    /// Prefix boundaries for adaptive table two.
    private static final int @Unmodifiable [] HUFFMAN_LIMITS_2 = {
            0x1000, 0x2400, 0x8000, 0xc000, 0xfa00, 0xffff, 0xffff, 0xffff
    };

    /// Symbol offsets for adaptive table two.
    private static final int @Unmodifiable [] HUFFMAN_POSITIONS_2 = {
            0, 0, 0, 0, 0, 0, 2, 7, 53, 117, 233, 0, 0
    };

    /// The initial width used by adaptive table three.
    private static final int HUFFMAN_START_3 = 6;

    /// Prefix boundaries for adaptive table three.
    private static final int @Unmodifiable [] HUFFMAN_LIMITS_3 = {
            0x0800, 0x2400, 0xee00, 0xfe80, 0xffff, 0xffff, 0xffff
    };

    /// Symbol offsets for adaptive table three.
    private static final int @Unmodifiable [] HUFFMAN_POSITIONS_3 = {
            0, 0, 0, 0, 0, 0, 0, 2, 16, 218, 251, 0, 0
    };

    /// The initial width used by adaptive table four.
    private static final int HUFFMAN_START_4 = 8;

    /// Prefix boundaries for adaptive table four.
    private static final int @Unmodifiable [] HUFFMAN_LIMITS_4 = {
            0xff00, 0xffff, 0xffff, 0xffff, 0xffff, 0xffff
    };

    /// Symbol offsets for adaptive table four.
    private static final int @Unmodifiable [] HUFFMAN_POSITIONS_4 = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 0, 0, 0
    };

    /// Bit widths for the first compact short-match code set.
    private static final int @Unmodifiable [] SHORT_LENGTHS_1 = {
            1, 3, 4, 4, 5, 6, 7, 8, 8, 4, 4, 5, 6, 6, 4, 0
    };

    /// Prefix values for the first compact short-match code set.
    private static final int @Unmodifiable [] SHORT_PREFIXES_1 = {
            0, 0xa0, 0xd0, 0xe0, 0xf0, 0xf8, 0xfc, 0xfe,
            0xff, 0xc0, 0x80, 0x90, 0x98, 0x9c, 0xb0
    };

    /// Bit widths for the second compact short-match code set.
    private static final int @Unmodifiable [] SHORT_LENGTHS_2 = {
            2, 3, 3, 3, 4, 4, 5, 6, 6, 4, 4, 5, 6, 6, 4, 0
    };

    /// Prefix values for the second compact short-match code set.
    private static final int @Unmodifiable [] SHORT_PREFIXES_2 = {
            0, 0x40, 0x60, 0xa0, 0xd0, 0xe0, 0xf0, 0xf8,
            0xfc, 0xc0, 0x80, 0x90, 0x98, 0x9c, 0xb0
    };

    /// The adaptive literal ordering.
    private final int[] characters = new int[256];

    /// The adaptive short-distance ordering.
    private final int[] shortDistances = new int[256];

    /// The adaptive long-distance ordering.
    private final int[] longDistances = new int[256];

    /// The adaptive flag-byte ordering.
    private final int[] flags = new int[256];

    /// Frequency-to-position mapping for literals.
    private final int[] characterPositions = new int[256];

    /// Frequency-to-position mapping for long distances.
    private final int[] longDistancePositions = new int[256];

    /// Frequency-to-position mapping for flag bytes.
    private final int[] flagPositions = new int[256];

    /// The four recently used distances in circular insertion order.
    private final int[] oldDistances = new int[4];

    /// The fixed 4 MiB dictionary.
    private byte[] dictionary = new byte[DICTIONARY_SIZE];

    /// The next dictionary position to overwrite.
    private int dictionaryPosition;

    /// The total number of dictionary bytes produced by the current solid sequence.
    private long totalRawSize;

    /// The circular insertion position for recent distances.
    private int oldDistancePosition;

    /// The current decoded flag byte shifted toward its next decision bit.
    private int flagBuffer;

    /// The remaining decision bits in the current flag byte.
    private int flagCount;

    /// Adaptive average literal position.
    private int averagePlace;

    /// Adaptive average long-distance position.
    private int averageDistancePlace;

    /// Adaptive average short length.
    private int averageLength1;

    /// Adaptive average long length.
    private int averageLength2;

    /// Adaptive long-match distance heuristic.
    private int averageLength3;

    /// Compatibility toggle encoded by one special short-match symbol.
    private int shortCodeToggle;

    /// Number of consecutive Huffman symbols used for mode selection.
    private int huffmanCount;

    /// Whether compact static mode is currently active.
    private int staticMode;

    /// Counter controlling repeated short matches.
    private int shortRepeatCount;

    /// Adaptive Huffman activity score.
    private int huffmanActivity;

    /// Adaptive LZ activity score.
    private int lzActivity;

    /// Current threshold separating medium and large distances.
    private int largeDistanceThreshold;

    /// The most recent match distance.
    private int lastDistance;

    /// The most recent match length.
    private int lastLength;

    /// Creates an empty RAR 1.5 decoder.
    Rar4Lz15Decoder() {
        reset(false);
    }

    /// Decodes one RAR 1.5 entry.
    void decode(InputStream input, OutputStream output, long unpackedSize, boolean solid) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (unpackedSize < 0L) {
            throw new IOException("Compressed RAR1.5 entry has an unknown unpacked size");
        }
        reset(solid);
        Rar4BitInput bits = new Rar4BitInput(input);
        EntryOutput entryOutput = new EntryOutput(output, unpackedSize);
        if (!entryOutput.isComplete()) {
            readFlagByte(bits);
            flagCount = 8;
        }

        while (!entryOutput.isComplete()) {
            if (staticMode != 0) {
                decodeLiteral(bits, entryOutput);
                continue;
            }
            if (--flagCount < 0) {
                readFlagByte(bits);
                flagCount = 7;
            }
            if ((flagBuffer & 0x80) != 0) {
                flagBuffer <<= 1;
                if (lzActivity > huffmanActivity) {
                    decodeLongMatch(bits, entryOutput);
                } else {
                    decodeLiteral(bits, entryOutput);
                }
                continue;
            }

            flagBuffer <<= 1;
            if (--flagCount < 0) {
                readFlagByte(bits);
                flagCount = 7;
            }
            if ((flagBuffer & 0x80) != 0) {
                flagBuffer <<= 1;
                if (lzActivity > huffmanActivity) {
                    decodeLiteral(bits, entryOutput);
                } else {
                    decodeLongMatch(bits, entryOutput);
                }
            } else {
                flagBuffer <<= 1;
                decodeShortMatch(bits, entryOutput);
            }
        }
        entryOutput.finish();
    }

    /// Invalidates all retained solid state.
    void invalidate() {
        reset(false);
    }

    /// Releases the fixed dictionary immediately.
    void release() {
        dictionary = new byte[0];
        invalidate();
    }

    /// Resets per-entry state and, when required, the complete adaptive model.
    private void reset(boolean solid) {
        ensureDictionary();
        if (!solid) {
            dictionaryPosition = 0;
            totalRawSize = 0L;
            oldDistancePosition = 0;
            Arrays.fill(oldDistances, 0);
            averageDistancePlace = 0;
            averageLength1 = 0;
            averageLength2 = 0;
            averageLength3 = 0;
            huffmanCount = 0;
            shortCodeToggle = 0;
            averagePlace = 0x3500;
            largeDistanceThreshold = 0x2001;
            huffmanActivity = 0x80;
            lzActivity = 0x80;
            lastDistance = 0;
            lastLength = 0;
            initializeAdaptiveTables();
        }
        flagCount = 0;
        flagBuffer = 0;
        staticMode = 0;
        shortRepeatCount = 0;
    }

    /// Restores a released dictionary before reuse.
    private void ensureDictionary() {
        if (dictionary.length != DICTIONARY_SIZE) {
            dictionary = new byte[DICTIONARY_SIZE];
        }
    }

    /// Initializes all adaptive symbol orderings for a new non-solid stream.
    private void initializeAdaptiveTables() {
        Arrays.fill(characterPositions, 0);
        Arrays.fill(longDistancePositions, 0);
        Arrays.fill(flagPositions, 0);
        for (int index = 0; index < 256; index++) {
            characters[index] = index << 8;
            shortDistances[index] = index;
            longDistances[index] = index << 8;
            flags[index] = (-index & 0xff) << 8;
        }
        correctFrequencies(longDistances, longDistancePositions);
    }

    /// Reads and adapts the next flag byte.
    private void readFlagByte(Rar4BitInput bits) throws IOException {
        int place = decodeNumber(bits, HUFFMAN_START_2, HUFFMAN_LIMITS_2, HUFFMAN_POSITIONS_2);
        if (place < 0 || place >= flags.length) {
            throw new IOException("Invalid RAR1.5 flag position");
        }
        int value = flags[place];
        flagBuffer = value >>> 8;
        int newPlace = flagPositions[value & 0xff]++;
        value++;
        if ((value & 0xff) == 0) {
            correctFrequencies(flags, flagPositions);
        }
        swap(flags, place, newPlace, value);
    }

    /// Decodes one adaptive literal or a static-mode match control.
    private void decodeLiteral(Rar4BitInput bits, EntryOutput output) throws IOException {
        int prefix = bits.peekBits(16);
        int place;
        if (averagePlace > 0x75ff) {
            place = decodeNumber(bits, HUFFMAN_START_4, HUFFMAN_LIMITS_4, HUFFMAN_POSITIONS_4);
        } else if (averagePlace > 0x5dff) {
            place = decodeNumber(bits, HUFFMAN_START_3, HUFFMAN_LIMITS_3, HUFFMAN_POSITIONS_3);
        } else if (averagePlace > 0x35ff) {
            place = decodeNumber(bits, HUFFMAN_START_2, HUFFMAN_LIMITS_2, HUFFMAN_POSITIONS_2);
        } else if (averagePlace > 0x0dff) {
            place = decodeNumber(bits, HUFFMAN_START_1, HUFFMAN_LIMITS_1, HUFFMAN_POSITIONS_1);
        } else {
            place = decodeNumber(bits, HUFFMAN_START_0, HUFFMAN_LIMITS_0, HUFFMAN_POSITIONS_0);
        }
        place &= 0xff;

        if (staticMode != 0) {
            if (place == 0 && prefix > 0x0fff) {
                place = 0x100;
            }
            place--;
            if (place < 0) {
                if (bits.readBits(1) != 0) {
                    huffmanCount = 0;
                    staticMode = 0;
                    return;
                }
                int length = bits.readBits(1) != 0 ? 4 : 3;
                int distance = decodeNumber(
                        bits,
                        HUFFMAN_START_2,
                        HUFFMAN_LIMITS_2,
                        HUFFMAN_POSITIONS_2
                ) << 5;
                distance |= bits.readBits(5);
                copyMatch(distance, length, output);
                return;
            }
        } else if (huffmanCount++ >= 16 && flagCount == 0) {
            staticMode = 1;
        }
        if (place >= characters.length) {
            throw new IOException("Invalid RAR1.5 literal position");
        }

        averagePlace += place;
        averagePlace -= averagePlace >>> 8;
        huffmanActivity += 16;
        if (huffmanActivity > 0xff) {
            huffmanActivity = 0x90;
            lzActivity >>>= 1;
        }

        int characterState = characters[place];
        emit(characterState >>> 8, output);
        int newPlace = characterPositions[characterState & 0xff]++;
        characterState++;
        if ((characterState & 0xff) > 0xa1) {
            correctFrequencies(characters, characterPositions);
        }
        swap(characters, place, newPlace, characterState);
    }

    /// Decodes one long match using adaptive length and distance models.
    private void decodeLongMatch(Rar4BitInput bits, EntryOutput output) throws IOException {
        huffmanCount = 0;
        lzActivity += 16;
        if (lzActivity > 0xff) {
            lzActivity = 0x90;
            huffmanActivity >>>= 1;
        }
        int oldAverageLength2 = averageLength2;

        int prefix = bits.peekBits(16);
        int length;
        if (averageLength2 >= 122) {
            length = decodeNumber(bits, LENGTH_START_2, LENGTH_LIMITS_2, LENGTH_POSITIONS_2);
        } else if (averageLength2 >= 64) {
            length = decodeNumber(bits, LENGTH_START_1, LENGTH_LIMITS_1, LENGTH_POSITIONS_1);
        } else if (prefix < 0x0100) {
            length = prefix;
            bits.skipBits(16);
        } else {
            length = Integer.numberOfLeadingZeros(prefix) - 16;
            bits.skipBits(length + 1);
        }
        averageLength2 += length;
        averageLength2 -= averageLength2 >>> 5;

        int distancePlace;
        if (averageDistancePlace > 0x28ff) {
            distancePlace = decodeNumber(bits, HUFFMAN_START_2, HUFFMAN_LIMITS_2, HUFFMAN_POSITIONS_2);
        } else if (averageDistancePlace > 0x06ff) {
            distancePlace = decodeNumber(bits, HUFFMAN_START_1, HUFFMAN_LIMITS_1, HUFFMAN_POSITIONS_1);
        } else {
            distancePlace = decodeNumber(bits, HUFFMAN_START_0, HUFFMAN_LIMITS_0, HUFFMAN_POSITIONS_0);
        }
        averageDistancePlace += distancePlace;
        averageDistancePlace -= averageDistancePlace >>> 8;
        if (distancePlace < 0 || distancePlace >= longDistances.length) {
            throw new IOException("Invalid RAR1.5 long-distance position");
        }

        int distanceState = longDistances[distancePlace];
        int newPlace = longDistancePositions[distanceState & 0xff]++;
        distanceState++;
        if ((distanceState & 0xff) == 0) {
            correctFrequencies(longDistances, longDistancePositions);
        }
        swap(longDistances, distancePlace, newPlace, distanceState);

        int distance = ((distanceState & 0xff00) | bits.peekBits(16) >>> 8) >>> 1;
        bits.skipBits(7);
        int oldAverageLength3 = averageLength3;
        if (length != 1 && length != 4) {
            if (length == 0 && distance <= largeDistanceThreshold) {
                averageLength3++;
                averageLength3 -= averageLength3 >>> 8;
            } else if (averageLength3 > 0) {
                averageLength3--;
            }
        }
        length += 3;
        if (distance >= largeDistanceThreshold) {
            length++;
        }
        if (distance <= 256) {
            length += 8;
        }
        largeDistanceThreshold = oldAverageLength3 > 0xb0
                || averagePlace >= 0x2a00 && oldAverageLength2 < 0x40
                ? 0x7f00
                : 0x2001;
        rememberMatch(distance, length);
        copyMatch(distance, length, output);
    }

    /// Decodes one compact short match or recent-distance control.
    private void decodeShortMatch(Rar4BitInput bits, EntryOutput output) throws IOException {
        huffmanCount = 0;
        int prefix = bits.peekBits(16);
        if (shortRepeatCount == 2) {
            bits.skipBits(1);
            if (prefix >= 0x8000) {
                repeatLastMatch(output);
                return;
            }
            prefix <<= 1;
            shortRepeatCount = 0;
        }

        int highByte = prefix >>> 8;
        int symbol = findShortSymbol(highByte);
        int codeLength = shortCodeLength(symbol);
        bits.skipBits(codeLength);
        if (symbol >= 9) {
            decodeShortControl(bits, symbol, output);
            return;
        }

        shortRepeatCount = 0;
        averageLength1 += symbol;
        averageLength1 -= averageLength1 >>> 4;
        int distancePlace = decodeNumber(
                bits,
                HUFFMAN_START_2,
                HUFFMAN_LIMITS_2,
                HUFFMAN_POSITIONS_2
        ) & 0xff;
        int distance = shortDistances[distancePlace];
        if (distancePlace != 0) {
            int previous = shortDistances[distancePlace - 1];
            shortDistances[distancePlace] = previous;
            shortDistances[distancePlace - 1] = distance;
        }
        int length = symbol + 2;
        distance++;
        rememberMatch(distance, length);
        copyMatch(distance, length, output);
    }

    /// Decodes one recent-distance or special short-match control.
    private void decodeShortControl(Rar4BitInput bits, int symbol, EntryOutput output) throws IOException {
        if (symbol == 9) {
            shortRepeatCount++;
            repeatLastMatch(output);
            return;
        }
        if (symbol == 14) {
            shortRepeatCount = 0;
            int length = decodeNumber(bits, LENGTH_START_2, LENGTH_LIMITS_2, LENGTH_POSITIONS_2) + 5;
            int distance = (bits.peekBits(16) >>> 1) | 0x8000;
            bits.skipBits(15);
            rememberMatch(distance, length);
            copyMatch(distance, length, output);
            return;
        }

        shortRepeatCount = 0;
        int distanceIndex = (oldDistancePosition - (symbol - 9)) & 3;
        int distance = oldDistances[distanceIndex];
        int length = decodeNumber(bits, LENGTH_START_1, LENGTH_LIMITS_1, LENGTH_POSITIONS_1) + 2;
        if (length == 0x101 && symbol == 10) {
            shortCodeToggle ^= 1;
            return;
        }
        if (distance > 256) {
            length++;
        }
        if (distance >= largeDistanceThreshold) {
            length++;
        }
        rememberMatch(distance, length);
        copyMatch(distance, length, output);
    }

    /// Returns the compact short-match symbol selected by one high input byte.
    private int findShortSymbol(int highByte) throws IOException {
        for (int symbol = 0; symbol < 15; symbol++) {
            int length = shortCodeLength(symbol);
            int prefix = averageLength1 < 37 ? SHORT_PREFIXES_1[symbol] : SHORT_PREFIXES_2[symbol];
            int mask = 0xff << (8 - length) & 0xff;
            if (((highByte ^ prefix) & mask) == 0) {
                return symbol;
            }
        }
        throw new IOException("Invalid RAR1.5 short-match prefix");
    }

    /// Returns the active compact code width for one short-match symbol.
    private int shortCodeLength(int symbol) {
        if (averageLength1 < 37) {
            return symbol == 1 ? shortCodeToggle + 3 : SHORT_LENGTHS_1[symbol];
        }
        return symbol == 3 ? shortCodeToggle + 3 : SHORT_LENGTHS_2[symbol];
    }

    /// Repeats the most recent match after validating its availability.
    private void repeatLastMatch(EntryOutput output) throws IOException {
        if (lastDistance <= 0 || lastLength <= 0) {
            throw new IOException("RAR1.5 stream repeats an unavailable match");
        }
        copyMatch(lastDistance, lastLength, output);
    }

    /// Stores one match in the recent-distance ring and as the latest match.
    private void rememberMatch(int distance, int length) {
        oldDistances[oldDistancePosition] = distance;
        oldDistancePosition = oldDistancePosition + 1 & 3;
        lastDistance = distance;
        lastLength = length;
    }

    /// Copies one potentially overlapping dictionary match.
    private void copyMatch(int distance, int length, EntryOutput output) throws IOException {
        long available = Math.min(totalRawSize, DICTIONARY_SIZE);
        if (distance <= 0 || distance > available) {
            throw new IOException("RAR1.5 match distance exceeds available dictionary history");
        }
        int source = dictionaryPosition - distance & DICTIONARY_SIZE - 1;
        for (int index = 0; index < length; index++) {
            int value = dictionary[source] & 0xff;
            source = source + 1 & DICTIONARY_SIZE - 1;
            emit(value, output);
        }
    }

    /// Appends one byte to the entry output and dictionary.
    private void emit(int value, EntryOutput output) throws IOException {
        output.accept(value);
        dictionary[dictionaryPosition] = (byte) value;
        dictionaryPosition = dictionaryPosition + 1 & DICTIONARY_SIZE - 1;
        totalRawSize++;
    }

    /// Decodes one value from an early RAR prefix table.
    private static int decodeNumber(
            Rar4BitInput bits,
            int startWidth,
            int[] limits,
            int[] positions
    ) throws IOException {
        int prefix = bits.peekBits(16) & 0xfff0;
        int tableIndex = 0;
        int width = startWidth;
        while (tableIndex < limits.length && limits[tableIndex] <= prefix) {
            tableIndex++;
            width++;
        }
        if (tableIndex >= limits.length || width > 16 || width >= positions.length) {
            throw new IOException("Invalid RAR1.5 adaptive prefix");
        }
        bits.skipBits(width);
        int previousLimit = tableIndex == 0 ? 0 : limits[tableIndex - 1];
        return ((prefix - previousLimit) >>> (16 - width)) + positions[width];
    }

    /// Rebuilds one adaptive frequency ordering after its counters wrap.
    private static void correctFrequencies(int[] values, int[] positions) {
        int valueIndex = 0;
        for (int frequency = 7; frequency >= 0; frequency--) {
            for (int count = 0; count < 32; count++) {
                values[valueIndex] = values[valueIndex] & ~0xff | frequency;
                valueIndex++;
            }
        }
        Arrays.fill(positions, 0);
        for (int frequency = 6; frequency >= 0; frequency--) {
            positions[frequency] = (7 - frequency) * 32;
        }
    }

    /// Replaces one adaptive position with its frequency-selected predecessor.
    private static void swap(int[] values, int oldPlace, int newPlace, int updatedValue) throws IOException {
        if (newPlace < 0 || newPlace >= values.length) {
            throw new IOException("RAR1.5 adaptive position is out of range");
        }
        values[oldPlace] = values[newPlace];
        values[newPlace] = updatedValue;
    }

    /// Buffers one entry's output while enforcing its declared size.
    @NotNullByDefault
    private static final class EntryOutput {
        /// The caller-owned decompressed output.
        private final OutputStream output;

        /// The declared decompressed size.
        private final long expectedSize;

        /// The reusable bulk-write buffer.
        private final byte[] buffer = new byte[64 * 1024];

        /// The number of staged bytes.
        private int bufferSize;

        /// The number of accepted bytes.
        private long acceptedSize;

        /// Creates one bounded output.
        private EntryOutput(OutputStream output, long expectedSize) {
            this.output = Objects.requireNonNull(output, "output");
            this.expectedSize = expectedSize;
        }

        /// Returns whether the declared output size has been produced.
        private boolean isComplete() {
            return acceptedSize == expectedSize;
        }

        /// Accepts one decompressed byte.
        private void accept(int value) throws IOException {
            if (acceptedSize >= expectedSize) {
                throw new IOException("RAR1.5 decompressor exceeded the declared unpacked size");
            }
            buffer[bufferSize++] = (byte) value;
            acceptedSize++;
            if (bufferSize == buffer.length) {
                flush();
            }
        }

        /// Validates and flushes this entry.
        private void finish() throws IOException {
            if (acceptedSize != expectedSize) {
                throw new IOException(
                        "RAR1.5 decompressor produced " + acceptedSize + " bytes; expected " + expectedSize
                );
            }
            flush();
        }

        /// Writes staged bytes to the caller.
        private void flush() throws IOException {
            if (bufferSize != 0) {
                output.write(buffer, 0, bufferSize);
                bufferSize = 0;
            }
        }
    }
}
