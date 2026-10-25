// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.Objects;

/// Implements bounded one-shot LZ4 block compression with optional prefix history.
@NotNullByDefault
final class LZ4BlockCompression {
    /// Hash width for a 64 KiB table of match positions.
    private static final int HASH_BITS = 14;

    /// Number of failed probes before the search stride increases by one byte.
    private static final int PROBES_PER_STRIDE = 64;

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
    /// Neither input array is modified or retained.
    static byte[] compress(byte[] source, byte[] dictionary) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(dictionary, "dictionary");

        if (source.length < REQUIRED_LAST_MATCH_DISTANCE) {
            byte[] encoded = new byte[1 + source.length];
            writeFinalLiterals(encoded, 0, source, 0, source.length);
            return encoded;
        }

        int dictionaryLength = Math.min(dictionary.length, MAXIMUM_OFFSET);
        byte[] input = source;
        if (dictionaryLength != 0) {
            int dictionaryOffset = dictionary.length - dictionaryLength;
            input = new byte[Math.addExact(dictionaryLength, source.length)];
            System.arraycopy(dictionary, dictionaryOffset, input, 0, dictionaryLength);
            System.arraycopy(source, 0, input, dictionaryLength, source.length);
        }

        int[] hashTable = new int[1 << HASH_BITS];
        Arrays.fill(hashTable, -1);
        int inputEnd = input.length;
        for (int position = 0; position + MINIMUM_MATCH_LENGTH <= dictionaryLength; position++) {
            hashTable[hash(ByteArrayAccess.readIntLittleEndian(input, position))] = position;
        }

        byte[] encoded = new byte[Math.toIntExact(maxCompressedLength(source.length))];
        int outputPosition = 0;
        int anchor = dictionaryLength;
        int position = dictionaryLength;
        int searchLimit = inputEnd - REQUIRED_LAST_MATCH_DISTANCE;
        int matchLimit = inputEnd - REQUIRED_LAST_LITERALS;
        int searchAttempts = PROBES_PER_STRIDE;

        while (position <= searchLimit) {
            int sequence = ByteArrayAccess.readIntLittleEndian(input, position);
            int hash = hash(sequence);
            int candidate = hashTable[hash];
            hashTable[hash] = position;
            int offset = position - candidate;
            if (candidate < 0
                    || offset <= 0
                    || offset > MAXIMUM_OFFSET
                    || ByteArrayAccess.readIntLittleEndian(input, candidate) != sequence) {
                int step = searchAttempts++ / PROBES_PER_STRIDE;
                if (step > searchLimit - position) break;
                position += step;
                continue;
            }

            // Recover matching bytes skipped during the search, without crossing the last sequence.
            while (position > anchor && candidate > 0 && input[position - 1] == input[candidate - 1]) {
                position--;
                candidate--;
            }
            int matchEnd = position + MINIMUM_MATCH_LENGTH;
            // Compare equal-length ranges, excluding the mandatory final literals from the match.
            int mismatch = Arrays.mismatch(input, candidate + MINIMUM_MATCH_LENGTH, matchLimit - offset,
                    input, matchEnd, matchLimit);
            matchEnd = mismatch < 0 ? matchLimit : matchEnd + mismatch;
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

            // Keep boundary matches discoverable without indexing every byte of a long repetition.
            for (int indexed = matchEnd - 2; indexed < matchEnd; indexed++) {
                hashTable[hash(ByteArrayAccess.readIntLittleEndian(input, indexed))] = indexed;
            }
            position = matchEnd;
            anchor = matchEnd;
            searchAttempts = PROBES_PER_STRIDE;
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
        ByteArrayAccess.writeShortLittleEndian(output, outputPosition, (short) matchOffset);
        outputPosition += Short.BYTES;
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

    /// Maps a four-byte sequence to a match-table index.
    private static int hash(int sequence) {
        return sequence * -1_640_531_535 >>> (Integer.SIZE - HASH_BITS);
    }
}
