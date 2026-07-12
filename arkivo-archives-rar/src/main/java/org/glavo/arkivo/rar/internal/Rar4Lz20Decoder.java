// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/// Implements the Huffman/LZ and adaptive audio modes used by RAR 2.x archives.
@NotNullByDefault
final class Rar4Lz20Decoder {
    /// The fixed legacy RAR dictionary size.
    private static final int DICTIONARY_SIZE = 1 << 22;

    /// The normal-mode main alphabet size.
    private static final int MAIN_ALPHABET_SIZE = 298;

    /// The normal-mode distance alphabet size.
    private static final int DISTANCE_ALPHABET_SIZE = 48;

    /// The normal-mode repeated-distance length alphabet size.
    private static final int REPEAT_LENGTH_ALPHABET_SIZE = 28;

    /// The code-length alphabet size.
    private static final int LEVEL_ALPHABET_SIZE = 19;

    /// The per-channel audio alphabet size.
    private static final int AUDIO_ALPHABET_SIZE = 257;

    /// The largest persisted table, covering four audio channels.
    private static final int PERSISTED_TABLE_SIZE = AUDIO_ALPHABET_SIZE * 4;

    /// Base match lengths shared with later RAR generations.
    private static final int[] LENGTH_BASES = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 20,
            24, 28, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224
    };

    /// Extra bits for the shared match-length slots.
    private static final byte[] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2,
            2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5
    };

    /// Base values for RAR 2.x long-distance slots.
    private static final int[] DISTANCE_BASES = {
            0, 1, 2, 3, 4, 6, 8, 12, 16, 24, 32, 48, 64, 96, 128, 192,
            256, 384, 512, 768, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288,
            16384, 24576, 32768, 49152, 65536, 98304, 131072, 196608, 262144,
            327680, 393216, 458752, 524288, 589824, 655360, 720896, 786432,
            851968, 917504, 983040
    };

    /// Extra bits for RAR 2.x long-distance slots.
    private static final byte[] DISTANCE_EXTRA_BITS = {
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14,
            15, 15, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16, 16
    };

    /// Base values for two-byte short matches.
    private static final int[] SHORT_DISTANCE_BASES = {0, 4, 8, 16, 32, 64, 128, 192};

    /// Extra bits for two-byte short matches.
    private static final byte[] SHORT_DISTANCE_EXTRA_BITS = {2, 2, 3, 4, 5, 6, 6, 6};

    /// The normal-mode main decoder.
    private final Rar4HuffmanDecoder mainDecoder = new Rar4HuffmanDecoder();

    /// The normal-mode distance decoder.
    private final Rar4HuffmanDecoder distanceDecoder = new Rar4HuffmanDecoder();

    /// The normal-mode repeated-distance length decoder.
    private final Rar4HuffmanDecoder repeatLengthDecoder = new Rar4HuffmanDecoder();

    /// The four optional per-channel audio decoders.
    private final Rar4HuffmanDecoder[] audioDecoders = {
            new Rar4HuffmanDecoder(),
            new Rar4HuffmanDecoder(),
            new Rar4HuffmanDecoder(),
            new Rar4HuffmanDecoder()
    };

    /// The four adaptive audio predictor states.
    private final AudioState[] audioStates = {
            new AudioState(),
            new AudioState(),
            new AudioState(),
            new AudioState()
    };

    /// Persisted delta table lengths for solid streams.
    private final byte[] previousTable = new byte[PERSISTED_TABLE_SIZE];

    /// The four most recent distances, newest first.
    private final int[] recentDistances = new int[4];

    /// The fixed 4 MiB dictionary.
    private byte[] dictionary = new byte[DICTIONARY_SIZE];

    /// The next dictionary position to overwrite.
    private int dictionaryPosition;

    /// The total number of bytes produced by the current solid sequence.
    private long totalRawSize;

    /// The most recent distance.
    private int lastDistance;

    /// The most recent length.
    private int lastLength;

    /// Whether the current table set decodes adaptive audio deltas.
    private boolean audioMode;

    /// The number of interleaved audio channels.
    private int audioChannels = 1;

    /// The channel receiving the next decoded delta.
    private int currentAudioChannel;

    /// The most recent delta across interleaved channels.
    private int channelDelta;

    /// Whether entropy tables are available for a solid continuation.
    private boolean tablesAvailable;

    /// Creates an empty RAR 2.x decoder.
    Rar4Lz20Decoder() {
    }

    /// Decodes one RAR 2.x entry.
    void decode(InputStream input, OutputStream output, long unpackedSize, boolean solid) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (unpackedSize < 0L) {
            throw new IOException("Compressed RAR2 entry has an unknown unpacked size");
        }
        if (!solid) {
            reset();
        }

        Rar4BitInput bits = new Rar4BitInput(input);
        if (!solid || !tablesAvailable) {
            readTables(bits);
        }
        EntryOutput entryOutput = new EntryOutput(output, unpackedSize);
        while (!entryOutput.isComplete()) {
            if (audioMode) {
                int delta = audioDecoders[currentAudioChannel].decode(bits);
                if (delta == 256) {
                    readTables(bits);
                    continue;
                }
                int value = decodeAudio(delta);
                emit(value, entryOutput);
                currentAudioChannel++;
                if (currentAudioChannel == audioChannels) {
                    currentAudioChannel = 0;
                }
                continue;
            }

            int symbol = mainDecoder.decode(bits);
            if (symbol < 256) {
                emit(symbol, entryOutput);
            } else if (symbol > 269) {
                decodeNewMatch(bits, symbol - 270, entryOutput);
            } else if (symbol == 269) {
                readTables(bits);
            } else if (symbol == 256) {
                if (lastLength == 0 || lastDistance == 0) {
                    throw new IOException("RAR2 stream repeats an unavailable match");
                }
                copyAndRemember(lastDistance, lastLength, entryOutput);
            } else if (symbol < 261) {
                decodeRecentMatch(bits, symbol - 257, entryOutput);
            } else {
                decodeShortMatch(bits, symbol - 261, entryOutput);
            }
        }
        entryOutput.finish();
    }

    /// Invalidates all retained state.
    void invalidate() {
        reset();
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

    /// Resets dictionary, predictor, and entropy state.
    private void reset() {
        ensureDictionary();
        dictionaryPosition = 0;
        totalRawSize = 0L;
        Arrays.fill(recentDistances, 0);
        Arrays.fill(previousTable, (byte) 0);
        for (AudioState state : audioStates) {
            state.reset();
        }
        for (Rar4HuffmanDecoder decoder : audioDecoders) {
            decoder.reset();
        }
        mainDecoder.reset();
        distanceDecoder.reset();
        repeatLengthDecoder.reset();
        lastDistance = 0;
        lastLength = 0;
        audioMode = false;
        audioChannels = 1;
        currentAudioChannel = 0;
        channelDelta = 0;
        tablesAvailable = false;
    }

    /// Reads one normal or adaptive-audio entropy table set.
    private void readTables(Rar4BitInput bits) throws IOException {
        audioMode = bits.readBits(1) != 0;
        boolean keepPrevious = bits.readBits(1) != 0;
        if (!keepPrevious) {
            Arrays.fill(previousTable, (byte) 0);
        }
        if (audioMode) {
            audioChannels = bits.readBits(2) + 1;
            if (currentAudioChannel >= audioChannels) {
                currentAudioChannel = 0;
            }
        }

        byte[] levelLengths = new byte[LEVEL_ALPHABET_SIZE];
        for (int index = 0; index < levelLengths.length; index++) {
            levelLengths[index] = (byte) bits.readBits(4);
        }
        Rar4HuffmanDecoder levelDecoder = new Rar4HuffmanDecoder();
        levelDecoder.build(levelLengths, 0, levelLengths.length);

        int tableSize = audioMode
                ? AUDIO_ALPHABET_SIZE * audioChannels
                : MAIN_ALPHABET_SIZE + DISTANCE_ALPHABET_SIZE + REPEAT_LENGTH_ALPHABET_SIZE;
        byte[] table = new byte[PERSISTED_TABLE_SIZE];
        for (int index = 0; index < tableSize;) {
            int symbol = levelDecoder.decode(bits);
            if (symbol < 16) {
                table[index] = (byte) ((symbol + previousTable[index]) & 0x0f);
                index++;
                continue;
            }

            int count;
            byte value;
            if (symbol == 16) {
                if (index == 0) {
                    throw new IOException("RAR2 Huffman table starts with a repeat symbol");
                }
                count = 3 + bits.readBits(2);
                value = table[index - 1];
            } else {
                int extraBits = symbol == 17 ? 3 : 7;
                count = (symbol == 17 ? 3 : 11) + bits.readBits(extraBits);
                value = 0;
            }
            int end = Math.min(tableSize, index + count);
            Arrays.fill(table, index, end, value);
            index = end;
        }

        if (audioMode) {
            for (int channel = 0; channel < audioChannels; channel++) {
                audioDecoders[channel].build(table, channel * AUDIO_ALPHABET_SIZE, AUDIO_ALPHABET_SIZE);
            }
        } else {
            int mainOffset = 0;
            int distanceOffset = mainOffset + MAIN_ALPHABET_SIZE;
            int repeatLengthOffset = distanceOffset + DISTANCE_ALPHABET_SIZE;
            mainDecoder.build(table, mainOffset, MAIN_ALPHABET_SIZE);
            distanceDecoder.build(table, distanceOffset, DISTANCE_ALPHABET_SIZE);
            repeatLengthDecoder.build(table, repeatLengthOffset, REPEAT_LENGTH_ALPHABET_SIZE);
        }
        System.arraycopy(table, 0, previousTable, 0, table.length);
        tablesAvailable = true;
    }

    /// Decodes one new long-distance match.
    private void decodeNewMatch(Rar4BitInput bits, int lengthSlot, EntryOutput output) throws IOException {
        int length = decodeLength(bits, lengthSlot, 3);
        int distanceSlot = distanceDecoder.decode(bits);
        if (distanceSlot < 0 || distanceSlot >= DISTANCE_BASES.length) {
            throw new IOException("Invalid RAR2 distance slot");
        }
        int distance = DISTANCE_BASES[distanceSlot]
                + bits.readBits(DISTANCE_EXTRA_BITS[distanceSlot] & 0xff)
                + 1;
        if (distance >= 0x2000) {
            length++;
            if (distance >= 0x40000) {
                length++;
            }
        }
        copyAndRemember(distance, length, output);
    }

    /// Decodes one match using a recent distance.
    private void decodeRecentMatch(Rar4BitInput bits, int index, EntryOutput output) throws IOException {
        if (index < 0 || index >= recentDistances.length || recentDistances[index] == 0) {
            throw new IOException("RAR2 stream references an unavailable recent distance");
        }
        int distance = recentDistances[index];
        int length = decodeLength(bits, repeatLengthDecoder.decode(bits), 2);
        if (distance >= 0x101) {
            length++;
            if (distance >= 0x2000) {
                length++;
                if (distance >= 0x40000) {
                    length++;
                }
            }
        }
        copyAndRemember(distance, length, output);
    }

    /// Decodes one fixed two-byte short-distance match.
    private void decodeShortMatch(Rar4BitInput bits, int slot, EntryOutput output) throws IOException {
        if (slot < 0 || slot >= SHORT_DISTANCE_BASES.length) {
            throw new IOException("Invalid RAR2 short-distance slot");
        }
        int distance = SHORT_DISTANCE_BASES[slot]
                + bits.readBits(SHORT_DISTANCE_EXTRA_BITS[slot] & 0xff)
                + 1;
        copyAndRemember(distance, 2, output);
    }

    /// Decodes one shared match-length slot.
    private static int decodeLength(Rar4BitInput bits, int slot, int minimum) throws IOException {
        if (slot < 0 || slot >= LENGTH_BASES.length) {
            throw new IOException("Invalid RAR2 match-length slot");
        }
        return LENGTH_BASES[slot]
                + bits.readBits(LENGTH_EXTRA_BITS[slot] & 0xff)
                + minimum;
    }

    /// Remembers and copies one potentially overlapping dictionary match.
    private void copyAndRemember(int distance, int length, EntryOutput output) throws IOException {
        recentDistances[3] = recentDistances[2];
        recentDistances[2] = recentDistances[1];
        recentDistances[1] = recentDistances[0];
        recentDistances[0] = distance;
        lastDistance = distance;
        lastLength = length;
        copyMatch(distance, length, output);
    }

    /// Copies one match from the retained dictionary.
    private void copyMatch(int distance, int length, EntryOutput output) throws IOException {
        long available = Math.min(totalRawSize, DICTIONARY_SIZE);
        if (distance <= 0 || distance > available) {
            throw new IOException("RAR2 match distance exceeds available dictionary history");
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

    /// Reconstructs one adaptive audio sample from its encoded delta.
    private int decodeAudio(int delta) {
        AudioState state = audioStates[currentAudioChannel];
        state.byteCount++;
        state.derivatives[3] = state.derivatives[2];
        state.derivatives[2] = state.derivatives[1];
        state.derivatives[1] = state.lastDelta - state.derivatives[0];
        state.derivatives[0] = state.lastDelta;

        int prediction = 8 * state.lastCharacter;
        for (int index = 0; index < 4; index++) {
            prediction += state.coefficients[index] * state.derivatives[index];
        }
        prediction += state.coefficients[4] * channelDelta;
        prediction = prediction >>> 3 & 0xff;
        int character = prediction - delta;
        int scaledDelta = (byte) delta << 3;

        state.errors[0] += Math.abs(scaledDelta);
        for (int index = 0; index < 4; index++) {
            state.errors[index * 2 + 1] += Math.abs(scaledDelta - state.derivatives[index]);
            state.errors[index * 2 + 2] += Math.abs(scaledDelta + state.derivatives[index]);
        }
        state.errors[9] += Math.abs(scaledDelta - channelDelta);
        state.errors[10] += Math.abs(scaledDelta + channelDelta);

        state.lastDelta = (byte) (character - state.lastCharacter);
        channelDelta = state.lastDelta;
        state.lastCharacter = character;
        if ((state.byteCount & 31) == 0) {
            adaptAudioCoefficients(state);
        }
        return character & 0xff;
    }

    /// Adjusts one audio predictor coefficient toward the least-error model.
    private static void adaptAudioCoefficients(AudioState state) {
        int bestIndex = 0;
        int bestError = state.errors[0];
        state.errors[0] = 0;
        for (int index = 1; index < state.errors.length; index++) {
            if (state.errors[index] < bestError) {
                bestError = state.errors[index];
                bestIndex = index;
            }
            state.errors[index] = 0;
        }
        if (bestIndex == 0) {
            return;
        }
        int coefficient = (bestIndex - 1) >>> 1;
        int adjustment = (bestIndex & 1) == 0 ? 1 : -1;
        int current = state.coefficients[coefficient];
        if (adjustment < 0 ? current >= -16 : current < 16) {
            state.coefficients[coefficient] = current + adjustment;
        }
    }

    /// Appends one byte to the entry output and dictionary.
    private void emit(int value, EntryOutput output) throws IOException {
        output.accept(value);
        dictionary[dictionaryPosition] = (byte) value;
        dictionaryPosition = dictionaryPosition + 1 & DICTIONARY_SIZE - 1;
        totalRawSize++;
    }

    /// Holds the mutable state for one adaptive audio channel.
    @NotNullByDefault
    private static final class AudioState {
        /// The five adaptive predictor coefficients.
        private final int[] coefficients = new int[5];

        /// The four recent first- and higher-order deltas.
        private final int[] derivatives = new int[4];

        /// Accumulated errors for the eleven candidate models.
        private final int[] errors = new int[11];

        /// The number of samples processed on this channel.
        private int byteCount;

        /// The most recent reconstructed sample.
        private int lastCharacter;

        /// The most recent signed sample delta.
        private int lastDelta;

        /// Creates one zero-initialized predictor state.
        private AudioState() {
        }

        /// Clears all predictor history.
        private void reset() {
            Arrays.fill(coefficients, 0);
            Arrays.fill(derivatives, 0);
            Arrays.fill(errors, 0);
            byteCount = 0;
            lastCharacter = 0;
            lastDelta = 0;
        }
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
                throw new IOException("RAR2 decompressor exceeded the declared unpacked size");
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
                        "RAR2 decompressor produced " + acceptedSize + " bytes; expected " + expectedSize
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
