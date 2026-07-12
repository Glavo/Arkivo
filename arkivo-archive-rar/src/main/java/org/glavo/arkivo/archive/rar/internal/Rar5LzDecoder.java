// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/// Implements the RAR5 canonical-Huffman and LZ dictionary decoding state machine.
@NotNullByDefault
final class Rar5LzDecoder {
    /// The number of literal symbols in the main alphabet.
    private static final int LITERAL_COUNT = 256;

    /// The main symbol introducing one post-processing filter.
    private static final int FILTER_SYMBOL = 256;

    /// The main symbol repeating the most recent length and distance.
    private static final int REPEAT_LAST_SYMBOL = 257;

    /// The first main symbol selecting one of four recent distances.
    private static final int REPEAT_DISTANCE_SYMBOL = 258;

    /// The first main symbol encoding a new length and distance pair.
    private static final int NEW_MATCH_SYMBOL = 262;

    /// The number of symbols in the main Huffman alphabet.
    private static final int MAIN_ALPHABET_SIZE = 306;

    /// The number of symbols in the match-length alphabet.
    private static final int LENGTH_ALPHABET_SIZE = 44;

    /// The RAR5 v6 distance alphabet size.
    private static final int DISTANCE_ALPHABET_SIZE_V6 = 64;

    /// The RAR5 v7 distance alphabet size.
    private static final int DISTANCE_ALPHABET_SIZE_V7 = 80;

    /// The number of low-distance alignment symbols.
    private static final int ALIGNMENT_ALPHABET_SIZE = 16;

    /// The number of symbols used to describe all other Huffman code lengths.
    private static final int LEVEL_ALPHABET_SIZE = 20;

    /// The number of recent distances retained by the RAR5 LZ model.
    private static final int REPETITION_COUNT = 4;

    /// The fixed number of low distance bits represented by the alignment alphabet.
    private static final int ALIGNMENT_BITS = 4;

    /// The checksum constant used by every RAR5 compressed-block header.
    private static final int BLOCK_HEADER_CHECKSUM = 0x5a;

    /// The block flag indicating a new set of Huffman tables.
    private static final int BLOCK_FLAG_NEW_TABLES = 0x80;

    /// The block flag indicating the final compressed block in an entry.
    private static final int BLOCK_FLAG_LAST = 0x40;

    /// Extra match length selected by large distance slots.
    private static final byte[] DISTANCE_LENGTH_BONUS = {
            0, 0, 0, 0, 0, 0, 0, 1, 1, 1,
            1, 1, 2, 2, 2, 2, 2, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3
    };

    /// The main literal, control, and length decoder.
    private final Rar5HuffmanDecoder mainDecoder = new Rar5HuffmanDecoder();

    /// The new-match distance decoder.
    private final Rar5HuffmanDecoder distanceDecoder = new Rar5HuffmanDecoder();

    /// The repeated-distance length decoder.
    private final Rar5HuffmanDecoder lengthDecoder = new Rar5HuffmanDecoder();

    /// The optional low-distance alignment decoder.
    private final Rar5HuffmanDecoder alignmentDecoder = new Rar5HuffmanDecoder();

    /// The four most recently used one-based match distances.
    private final long[] repetitions = new long[REPETITION_COUNT];

    /// The raw LZ dictionary retained across solid entries.
    private byte[] dictionary = new byte[0];

    /// The active dictionary ring size.
    private int dictionarySize;

    /// The next dictionary ring position to overwrite.
    private int dictionaryPosition;

    /// The total number of raw bytes produced by the current solid sequence.
    private long totalRawSize;

    /// The most recently decoded match length.
    private int lastLength;

    /// Whether the current Huffman tables can be reused by a following block.
    private boolean tablesAvailable;

    /// Whether low distance bits use the alignment Huffman decoder.
    private boolean huffmanAlignment;

    /// Whether a dictionary sequence has been initialized.
    private boolean initialized;

    /// Creates an empty RAR5 LZ decoder.
    Rar5LzDecoder() {
        resetModel();
    }

    /// Decodes one entry while preserving state when it is a valid solid continuation.
    void decode(
            InputStream input,
            OutputStream output,
            int requestedDictionarySize,
            boolean version7,
            boolean solid,
            long unpackedSize
    ) throws IOException {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (requestedDictionarySize <= 0 || unpackedSize < 0L) {
            throw new IOException("Invalid RAR5 decompression properties");
        }

        prepareDictionary(requestedDictionarySize, solid);
        Rar5OutputPipeline pipeline = new Rar5OutputPipeline(output, totalRawSize, unpackedSize);
        Rar5BitInput bits = new Rar5BitInput(input);

        boolean lastBlock;
        do {
            bits.clearBlockEnd();
            bits.alignToByte();
            Block block = readBlockHeader(bits);
            bits.setBlockEndBit(block.endBit());

            if (block.newTables()) {
                readTables(bits, version7);
            } else if (!tablesAvailable && bits.remainingBlockBits() != 0L) {
                throw new IOException("RAR5 compressed block reuses unavailable Huffman tables");
            }

            while (!bits.atBlockEnd()) {
                decodeSymbol(bits, pipeline);
            }
            lastBlock = block.last();
        } while (!lastBlock);

        bits.clearBlockEnd();
        bits.alignToByte();
        pipeline.finish();
        initialized = true;
    }

    /// Invalidates all dictionary and entropy state after a skipped or failed entry.
    void invalidate() {
        dictionaryPosition = 0;
        totalRawSize = 0L;
        initialized = false;
        resetModel();
    }

    /// Releases the potentially large dictionary immediately.
    void release() {
        dictionary = new byte[0];
        dictionarySize = 0;
        invalidate();
    }

    /// Allocates or retains the dictionary required by one entry.
    private void prepareDictionary(int requestedSize, boolean solid) throws IOException {
        if (solid && initialized) {
            if (requestedSize > dictionarySize) {
                throw new IOException("RAR5 solid entry requires a larger dictionary than its predecessor");
            }
            return;
        }

        if (dictionary.length < requestedSize) {
            dictionary = new byte[requestedSize];
        } else {
            Arrays.fill(dictionary, 0, requestedSize, (byte) 0);
        }
        dictionarySize = requestedSize;
        dictionaryPosition = 0;
        totalRawSize = 0L;
        resetModel();
    }

    /// Resets repetition distances and Huffman state for a new non-solid sequence.
    private void resetModel() {
        Arrays.fill(repetitions, -1L);
        lastLength = 0;
        tablesAvailable = false;
        huffmanAlignment = false;
        mainDecoder.reset();
        distanceDecoder.reset();
        lengthDecoder.reset();
        alignmentDecoder.reset();
    }

    /// Reads and validates one byte-aligned compressed-block header.
    private static Block readBlockHeader(Rar5BitInput bits) throws IOException {
        int flags = bits.readAlignedByte();
        int checksum = bits.readAlignedByte() ^ flags;
        int additionalSizeBytes = flags >>> 3 & 0x03;
        if (additionalSizeBytes == 3) {
            throw new IOException("Invalid RAR5 compressed-block size encoding");
        }

        long blockSize = 0L;
        for (int index = 0; index <= additionalSizeBytes; index++) {
            int value = bits.readAlignedByte();
            checksum ^= value;
            blockSize |= (long) value << (index * 8);
        }
        if (checksum != BLOCK_HEADER_CHECKSUM) {
            throw new IOException("RAR5 compressed-block header checksum mismatch");
        }
        if (blockSize == 0L) {
            throw new IOException("RAR5 compressed block has an empty physical payload");
        }

        int finalByteBits = (flags & 0x07) + 1;
        long payloadBits;
        long endBit;
        try {
            payloadBits = Math.addExact(Math.multiplyExact(blockSize - 1L, 8L), finalByteBits);
            endBit = Math.addExact(bits.positionBits(), payloadBits);
        } catch (ArithmeticException exception) {
            throw new IOException("RAR5 compressed-block size overflows", exception);
        }
        return new Block(
                endBit,
                (flags & BLOCK_FLAG_NEW_TABLES) != 0,
                (flags & BLOCK_FLAG_LAST) != 0
        );
    }

    /// Reads the canonical code lengths for all RAR5 entropy alphabets.
    private void readTables(Rar5BitInput bits, boolean version7) throws IOException {
        tablesAvailable = false;
        byte[] levelLengths = new byte[LEVEL_ALPHABET_SIZE];
        for (int index = 0; index < levelLengths.length;) {
            int length = bits.readBits(4);
            if (length == 15) {
                int zeroExtension = bits.readBits(4);
                if (zeroExtension != 0) {
                    int end = Math.min(levelLengths.length, index + zeroExtension + 2);
                    Arrays.fill(levelLengths, index, end, (byte) 0);
                    index = end;
                    continue;
                }
            }
            levelLengths[index++] = (byte) length;
        }

        Rar5HuffmanDecoder levelDecoder = new Rar5HuffmanDecoder();
        levelDecoder.build(levelLengths, 0, levelLengths.length);

        int distanceAlphabetSize = version7
                ? DISTANCE_ALPHABET_SIZE_V7
                : DISTANCE_ALPHABET_SIZE_V6;
        int totalLengthCount = MAIN_ALPHABET_SIZE
                + distanceAlphabetSize
                + ALIGNMENT_ALPHABET_SIZE
                + LENGTH_ALPHABET_SIZE;
        byte[] lengths = new byte[totalLengthCount];
        for (int index = 0; index < lengths.length;) {
            int symbol = levelDecoder.decode(bits);
            if (symbol < 16) {
                lengths[index++] = (byte) symbol;
                continue;
            }

            int extraBits = (symbol & 1) == 0 ? 3 : 7;
            int repeatCount = (extraBits == 3 ? 3 : 11) + bits.readBits(extraBits);
            int end = Math.min(lengths.length, index + repeatCount);
            byte repeatedLength = 0;
            if (symbol < 18) {
                if (index == 0) {
                    throw new IOException("RAR5 Huffman table starts with a repeat symbol");
                }
                repeatedLength = lengths[index - 1];
            }
            Arrays.fill(lengths, index, end, repeatedLength);
            index = end;
        }

        int mainOffset = 0;
        int distanceOffset = mainOffset + MAIN_ALPHABET_SIZE;
        int alignmentOffset = distanceOffset + distanceAlphabetSize;
        int lengthOffset = alignmentOffset + ALIGNMENT_ALPHABET_SIZE;
        mainDecoder.build(lengths, mainOffset, MAIN_ALPHABET_SIZE);
        distanceDecoder.build(lengths, distanceOffset, distanceAlphabetSize);
        lengthDecoder.build(lengths, lengthOffset, LENGTH_ALPHABET_SIZE);

        huffmanAlignment = false;
        for (int index = 0; index < ALIGNMENT_ALPHABET_SIZE; index++) {
            if ((lengths[alignmentOffset + index] & 0xff) != ALIGNMENT_BITS) {
                alignmentDecoder.build(lengths, alignmentOffset, ALIGNMENT_ALPHABET_SIZE);
                huffmanAlignment = true;
                break;
            }
        }
        if (!huffmanAlignment) {
            alignmentDecoder.reset();
        }
        tablesAvailable = true;
    }

    /// Decodes one literal, match, or filter control symbol.
    private void decodeSymbol(Rar5BitInput bits, Rar5OutputPipeline pipeline) throws IOException {
        int symbol = mainDecoder.decode(bits);
        if (symbol < LITERAL_COUNT) {
            emitRaw(symbol, pipeline);
            return;
        }
        if (symbol == FILTER_SYMBOL) {
            registerFilter(bits, pipeline);
            return;
        }
        if (symbol == REPEAT_LAST_SYMBOL) {
            if (lastLength == 0 || repetitions[0] <= 0L) {
                throw new IOException("RAR5 stream repeats an unavailable match");
            }
            copyMatch(repetitions[0], lastLength, pipeline);
            return;
        }
        if (symbol < NEW_MATCH_SYMBOL) {
            int repetitionIndex = symbol - REPEAT_DISTANCE_SYMBOL;
            long distance = moveRepetitionToFront(repetitionIndex);
            int length = decodeLength(bits, lengthDecoder.decode(bits));
            lastLength = length;
            copyMatch(distance, length, pipeline);
            return;
        }

        int length = decodeLength(bits, symbol - NEW_MATCH_SYMBOL);
        int distanceSlot = distanceDecoder.decode(bits);
        DecodedDistance decodedDistance = decodeDistance(bits, distanceSlot, length);
        length = decodedDistance.length();
        long distance = decodedDistance.distance();
        repetitions[3] = repetitions[2];
        repetitions[2] = repetitions[1];
        repetitions[1] = repetitions[0];
        repetitions[0] = distance;
        lastLength = length;
        copyMatch(distance, length, pipeline);
    }

    /// Decodes one match length slot and applies the mandatory two-byte minimum.
    private static int decodeLength(Rar5BitInput bits, int slot) throws IOException {
        if (slot < 0 || slot >= LENGTH_ALPHABET_SIZE) {
            throw new IOException("Invalid RAR5 match-length slot");
        }
        int value = slot;
        if (slot >= 8) {
            int extraBits = (slot >>> 2) - 1;
            value = ((4 | slot & 3) << extraBits) + bits.readBits(extraBits);
        }
        return value + 2;
    }

    /// Decodes one distance slot and its distance-dependent match-length bonus.
    private DecodedDistance decodeDistance(Rar5BitInput bits, int slot, int length) throws IOException {
        if (slot < 0 || slot >= DISTANCE_ALPHABET_SIZE_V7) {
            throw new IOException("Invalid RAR5 distance slot");
        }

        long distanceCode = slot;
        if (slot >= 4) {
            int extraBits = (slot - 2) >>> 1;
            if (extraBits > 30) {
                throw new IOException("RAR5 distance exceeds the supported dictionary range");
            }
            distanceCode = (long) (2 | slot & 1) << extraBits;
            if (extraBits < ALIGNMENT_BITS) {
                distanceCode += bits.readBits(extraBits);
            } else {
                length += DISTANCE_LENGTH_BONUS[extraBits] & 0xff;
                if (huffmanAlignment) {
                    int highBits = bits.readBits(extraBits - ALIGNMENT_BITS);
                    int lowBits = alignmentDecoder.decode(bits);
                    distanceCode += (long) highBits << ALIGNMENT_BITS | lowBits;
                } else {
                    distanceCode += Integer.toUnsignedLong(bits.readBits(extraBits));
                }
            }
        }
        return new DecodedDistance(distanceCode + 1L, length);
    }

    /// Moves one recent distance to the front of the repetition queue.
    private long moveRepetitionToFront(int index) throws IOException {
        if (index < 0 || index >= repetitions.length || repetitions[index] <= 0L) {
            throw new IOException("RAR5 stream references an unavailable repeated distance");
        }
        long distance = repetitions[index];
        for (int current = index; current > 0; current--) {
            repetitions[current] = repetitions[current - 1];
        }
        repetitions[0] = distance;
        return distance;
    }

    /// Reads and registers one non-overlapping post-processing filter.
    private static void registerFilter(Rar5BitInput bits, Rar5OutputPipeline pipeline) throws IOException {
        long startOffset = readFilterInteger(bits);
        long size = readFilterInteger(bits);
        int type = bits.readBits(3);
        int channels = type == Rar5OutputPipeline.FILTER_DELTA ? bits.readBits(5) + 1 : 0;
        pipeline.registerFilter(startOffset, size, type, channels);
    }

    /// Reads a one-to-four-byte little-endian integer used by filter descriptors.
    private static long readFilterInteger(Rar5BitInput bits) throws IOException {
        int byteCount = bits.readBits(2) + 1;
        long value = 0L;
        for (int index = 0; index < byteCount; index++) {
            value |= (long) bits.readBits(8) << (index * 8);
        }
        return value;
    }

    /// Copies one potentially overlapping LZ match from the retained dictionary.
    private void copyMatch(long distance, int length, Rar5OutputPipeline pipeline) throws IOException {
        long availableHistory = Math.min(totalRawSize, dictionarySize);
        if (distance <= 0L || distance > availableHistory) {
            throw new IOException("RAR5 match distance exceeds available dictionary history");
        }
        int sourcePosition = dictionaryPosition - (int) distance;
        if (sourcePosition < 0) {
            sourcePosition += dictionarySize;
        }
        for (int index = 0; index < length; index++) {
            int value = dictionary[sourcePosition] & 0xff;
            sourcePosition++;
            if (sourcePosition == dictionarySize) {
                sourcePosition = 0;
            }
            emitRaw(value, pipeline);
        }
    }

    /// Appends one raw byte to the output pipeline and solid dictionary.
    private void emitRaw(int value, Rar5OutputPipeline pipeline) throws IOException {
        pipeline.accept(value);
        dictionary[dictionaryPosition++] = (byte) value;
        if (dictionaryPosition == dictionarySize) {
            dictionaryPosition = 0;
        }
        totalRawSize++;
    }

    /// Describes one validated compressed-block boundary and its table flags.
    ///
    /// @param endBit exclusive absolute payload bit position
    /// @param newTables whether the payload begins with new Huffman tables
    /// @param last whether this is the final compressed block
    @NotNullByDefault
    private record Block(long endBit, boolean newTables, boolean last) {
        /// Validates one compressed-block descriptor.
        private Block {
            if (endBit < 0L) {
                throw new IllegalArgumentException("Invalid RAR5 block boundary");
            }
        }
    }

    /// Describes one decoded one-based distance and adjusted match length.
    ///
    /// @param distance one-based dictionary distance
    /// @param length match length including any distance bonus
    @NotNullByDefault
    private record DecodedDistance(long distance, int length) {
        /// Validates one decoded match descriptor.
        private DecodedDistance {
            if (distance <= 0L || length < 2) {
                throw new IllegalArgumentException("Invalid RAR5 match descriptor");
            }
        }
    }
}
