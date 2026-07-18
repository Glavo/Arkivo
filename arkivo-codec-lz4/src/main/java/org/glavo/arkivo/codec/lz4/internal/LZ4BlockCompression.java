// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/// Implements bounded one-shot LZ4 block compression with optional prefix history.
@NotNullByDefault
final class LZ4BlockCompression {
    /// Number of hash-table entries used by the greedy match finder.
    private static final int HASH_SIZE = 1 << 16;

    /// Maximum representable match distance.
    private static final int MAXIMUM_OFFSET = 65_535;

    /// Minimum representable match length.
    private static final int MINIMUM_MATCH_LENGTH = 4;

    /// Bytes that must remain literal after the final match.
    private static final int REQUIRED_LAST_LITERALS = 5;

    /// Minimum distance from the final match start to the decoded block end.
    private static final int REQUIRED_LAST_MATCH_DISTANCE = 12;

    /// Empty prefix history.
    private static final byte[] EMPTY_DICTIONARY = new byte[0];

    /// Creates no instances.
    private LZ4BlockCompression() {
    }

    /// Compresses a complete block without prefix history.
    static byte[] compress(byte[] source) {
        return compress(source, EMPTY_DICTIONARY);
    }

    /// Compresses a complete block using at most the final 65,535 dictionary bytes as prefix history.
    static byte[] compress(byte[] source, byte[] dictionary) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(dictionary, "dictionary");

        int dictionaryLength = Math.min(dictionary.length, MAXIMUM_OFFSET);
        int dictionaryOffset = dictionary.length - dictionaryLength;
        byte[] input = new byte[Math.addExact(dictionaryLength, source.length)];
        System.arraycopy(dictionary, dictionaryOffset, input, 0, dictionaryLength);
        System.arraycopy(source, 0, input, dictionaryLength, source.length);

        int[] hashTable = new int[HASH_SIZE];
        Arrays.fill(hashTable, -1);
        int inputEnd = input.length;
        for (int position = 0; position + MINIMUM_MATCH_LENGTH <= dictionaryLength; position++) {
            hashTable[hash(input, position)] = position;
        }

        byte[] encoded = new byte[Math.toIntExact(maxCompressedLength(source.length))];
        int outputPosition = 0;
        int anchor = dictionaryLength;
        int position = dictionaryLength;
        int searchLimit = inputEnd - REQUIRED_LAST_MATCH_DISTANCE;
        int matchLimit = inputEnd - REQUIRED_LAST_LITERALS;

        while (position <= searchLimit) {
            int hash = hash(input, position);
            int candidate = hashTable[hash];
            hashTable[hash] = position;
            int offset = position - candidate;
            if (candidate < 0
                    || offset <= 0
                    || offset > MAXIMUM_OFFSET
                    || !equalFourBytes(input, candidate, position)) {
                position++;
                continue;
            }

            int matchEnd = position + MINIMUM_MATCH_LENGTH;
            int candidatePosition = candidate + MINIMUM_MATCH_LENGTH;
            while (matchEnd < matchLimit && input[candidatePosition] == input[matchEnd]) {
                candidatePosition++;
                matchEnd++;
            }
            int matchLength = matchEnd - position;
            outputPosition = writeSequence(
                    encoded,
                    outputPosition,
                    input,
                    anchor,
                    position - anchor,
                    offset,
                    matchLength
            );

            for (int indexed = position + 1;
                 indexed < matchEnd && indexed + MINIMUM_MATCH_LENGTH <= inputEnd;
                 indexed++) {
                hashTable[hash(input, indexed)] = indexed;
            }
            position = matchEnd;
            anchor = matchEnd;
        }

        outputPosition = writeFinalLiterals(
                encoded,
                outputPosition,
                input,
                anchor,
                inputEnd - anchor
        );
        return Arrays.copyOf(encoded, outputPosition);
    }

    /// Returns the standard LZ4 maximum encoded block length.
    static long maxCompressedLength(long sourceLength) {
        if (sourceLength < 0L) {
            throw new IllegalArgumentException("sourceLength must not be negative");
        }
        return sourceLength + sourceLength / 255L + 16L;
    }

    /// Writes one literal-and-match sequence and returns the next output position.
    private static int writeSequence(
            byte[] output,
            int outputPosition,
            byte[] input,
            int literalOffset,
            int literalLength,
            int matchOffset,
            int matchLength
    ) {
        int tokenPosition = outputPosition++;
        int literalNibble = Math.min(literalLength, 15);
        int matchCode = matchLength - MINIMUM_MATCH_LENGTH;
        int matchNibble = Math.min(matchCode, 15);
        output[tokenPosition] = (byte) (literalNibble << 4 | matchNibble);

        if (literalLength >= 15) {
            outputPosition = writeLength(output, outputPosition, literalLength - 15);
        }
        System.arraycopy(input, literalOffset, output, outputPosition, literalLength);
        outputPosition += literalLength;
        output[outputPosition++] = (byte) matchOffset;
        output[outputPosition++] = (byte) (matchOffset >>> 8);
        if (matchCode >= 15) {
            outputPosition = writeLength(output, outputPosition, matchCode - 15);
        }
        return outputPosition;
    }

    /// Writes the mandatory final literal-only sequence.
    private static int writeFinalLiterals(
            byte[] output,
            int outputPosition,
            byte[] input,
            int literalOffset,
            int literalLength
    ) {
        output[outputPosition++] = (byte) (Math.min(literalLength, 15) << 4);
        if (literalLength >= 15) {
            outputPosition = writeLength(output, outputPosition, literalLength - 15);
        }
        System.arraycopy(input, literalOffset, output, outputPosition, literalLength);
        return outputPosition + literalLength;
    }

    /// Writes one LZ4 length extension including its terminating byte.
    private static int writeLength(byte[] output, int outputPosition, int length) {
        while (length >= 255) {
            output[outputPosition++] = (byte) 255;
            length -= 255;
        }
        output[outputPosition++] = (byte) length;
        return outputPosition;
    }

    /// Returns a 16-bit hash of four bytes starting at the supplied position.
    private static int hash(byte[] input, int position) {
        int value = Byte.toUnsignedInt(input[position])
                | Byte.toUnsignedInt(input[position + 1]) << 8
                | Byte.toUnsignedInt(input[position + 2]) << 16
                | Byte.toUnsignedInt(input[position + 3]) << 24;
        return value * -1_640_531_535 >>> 16;
    }

    /// Returns whether two four-byte ranges contain identical bytes.
    private static boolean equalFourBytes(byte[] input, int first, int second) {
        return input[first] == input[second]
                && input[first + 1] == input[second + 1]
                && input[first + 2] == input[second + 2]
                && input[first + 3] == input[second + 3];
    }
}
