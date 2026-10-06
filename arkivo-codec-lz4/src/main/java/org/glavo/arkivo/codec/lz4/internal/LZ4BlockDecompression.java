// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Implements bounded one-shot LZ4 block decompression with optional prefix history.
@NotNullByDefault
final class LZ4BlockDecompression {
    /// Maximum representable match distance.
    private static final int MAXIMUM_OFFSET = 65_535;

    /// Preferred initial decoded-output allocation.
    private static final int INITIAL_CAPACITY = 8192;

    /// Maximum initial estimate before decoded lengths require further growth.
    private static final int MAXIMUM_INITIAL_CAPACITY = 8 * 1024 * 1024;

    /// Empty prefix history.
    private static final byte[] EMPTY_DICTIONARY = new byte[0];

    /// Creates no instances.
    private LZ4BlockDecompression() {
    }

    /// Decodes one complete block without prefix history.
    static Result decompress(
            byte[] compressed,
            int maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) throws IOException {
        return decompress(
                compressed,
                EMPTY_DICTIONARY,
                maximumOutputSize,
                maximumWindowSize,
                maximumMemorySize
        );
    }

    /// Decodes one complete block with optional prefix history.
    static Result decompress(
            byte[] compressed,
            byte[] dictionary,
            int maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) throws IOException {
        return decompress(compressed, compressed.length, dictionary, dictionary.length, new byte[0],
                maximumOutputSize, maximumWindowSize, maximumMemorySize);
    }

    /// Decodes the meaningful input and dictionary prefixes into reusable owned output storage.
    static Result decompress(
            byte[] compressed,
            int compressedLength,
            byte[] dictionary,
            int dictionarySize,
            byte[] reusableOutput,
            int maximumOutputSize,
            long maximumWindowSize,
            long maximumMemorySize
    ) throws IOException {
        Objects.requireNonNull(compressed, "compressed");
        Objects.requireNonNull(dictionary, "dictionary");
        Objects.checkFromIndexSize(0, compressedLength, compressed.length);
        Objects.checkFromIndexSize(0, dictionarySize, dictionary.length);
        if (maximumOutputSize < 0) {
            throw new IllegalArgumentException("maximumOutputSize must not be negative");
        }
        if (compressedLength == 0) {
            throw new IOException("Empty input is not an LZ4 block");
        }

        int dictionaryLength = Math.min(dictionarySize, MAXIMUM_OFFSET);
        int dictionaryOffset = dictionarySize - dictionaryLength;
        int initialCapacity = reusableOutput.length;
        if (initialCapacity == 0) {
            // A modest input-based estimate avoids repeated growth for mostly literal blocks.
            initialCapacity = (int) Math.min(maximumOutputSize,
                    Math.min(MAXIMUM_INITIAL_CAPACITY, Math.max(INITIAL_CAPACITY, (long) compressedLength * 2)));
            if (maximumMemorySize >= 0L
                    && (long) compressed.length + dictionary.length + initialCapacity > maximumMemorySize) {
                initialCapacity = Math.min(maximumOutputSize, INITIAL_CAPACITY);
            }
        }
        requireMemory(maximumMemorySize, compressed.length, dictionary.length, initialCapacity);
        byte[] output = reusableOutput.length == 0 ? new byte[initialCapacity] : reusableOutput;
        int outputSize = 0;
        int inputPosition = 0;
        boolean decodedMatch = false;

        while (inputPosition < compressedLength) {
            int token = Byte.toUnsignedInt(compressed[inputPosition++]);
            int literalLength = token >>> 4;
            if (literalLength == 15) {
                int extension;
                do {
                    if (inputPosition >= compressedLength) {
                        throw new IOException("Truncated LZ4 literal length");
                    }
                    extension = Byte.toUnsignedInt(compressed[inputPosition++]);
                    literalLength = checkedLength(literalLength, extension, maximumOutputSize);
                } while (extension == 255);
            }
            if (literalLength > compressedLength - inputPosition) {
                throw new IOException("Truncated LZ4 literal bytes");
            }
            int requiredOutput = checkedOutputSize(outputSize, literalLength, maximumOutputSize);
            output = ensureCapacity(
                    output,
                    requiredOutput,
                    maximumOutputSize,
                    compressed.length,
                    dictionary.length,
                    maximumMemorySize
            );
            System.arraycopy(compressed, inputPosition, output, outputSize, literalLength);
            inputPosition += literalLength;
            outputSize = requiredOutput;

            int matchCode = token & 15;
            if (inputPosition == compressedLength) {
                if (matchCode != 0) {
                    throw new IOException("Final LZ4 sequence declares a missing match");
                }
                if (decodedMatch && literalLength < 5) {
                    throw new IOException("Final LZ4 sequence contains fewer than five literals");
                }
                return new Result(output, outputSize);
            }
            if (compressedLength - inputPosition < 2) {
                throw new IOException("Truncated LZ4 match offset");
            }
            int matchOffset = Short.toUnsignedInt(
                    ByteArrayAccess.readShortLittleEndian(compressed, inputPosition)
            );
            inputPosition += 2;
            if (matchOffset == 0 || matchOffset > outputSize + dictionaryLength) {
                throw new IOException("Invalid LZ4 match offset: " + matchOffset);
            }
            CompressionDecoderSupport.requireWindowSize(maximumWindowSize, matchOffset);

            int matchLength = matchCode + 4;
            if (matchCode == 15) {
                int extension;
                do {
                    if (inputPosition >= compressedLength) {
                        throw new IOException("Truncated LZ4 match length");
                    }
                    extension = Byte.toUnsignedInt(compressed[inputPosition++]);
                    matchLength = checkedLength(matchLength, extension, maximumOutputSize);
                } while (extension == 255);
            }
            requiredOutput = checkedOutputSize(outputSize, matchLength, maximumOutputSize);
            output = ensureCapacity(
                    output,
                    requiredOutput,
                    maximumOutputSize,
                    compressed.length,
                    dictionary.length,
                    maximumMemorySize
            );
            copyMatch(output, outputSize, matchOffset, matchLength, dictionary, dictionaryOffset + dictionaryLength);
            outputSize = requiredOutput;
            decodedMatch = true;
            if (inputPosition == compressedLength) {
                throw new IOException("LZ4 block is missing its final literal sequence");
            }
        }
        throw new IOException("LZ4 block is missing its final literal sequence");
    }

    /// Copies prefix history followed by an overlapping match without reading bytes not yet produced.
    private static void copyMatch(byte[] output, int position, int distance, int length,
                                  byte[] dictionary, int dictionaryEnd) {
        int source = position - distance;
        if (source < 0) {
            int copied = Math.min(length, -source);
            System.arraycopy(dictionary, dictionaryEnd + source, output, position, copied);
            position += copied;
            source += copied;
            length -= copied;
        }
        if (length == 0) return;
        if (distance == 1) {
            Arrays.fill(output, position, position + length, output[source]);
            return;
        }
        int copied = Math.min(length, distance);
        System.arraycopy(output, source, output, position, copied);
        while (copied < length) {
            int extension = Math.min(copied, length - copied);
            System.arraycopy(output, position, output, position + copied, extension);
            copied += extension;
        }
    }

    /// Adds one parsed length component without exceeding the decoded-size bound.
    private static int checkedLength(int current, int additional, int maximumOutputSize) throws IOException {
        if (additional > maximumOutputSize - current) {
            throw outputLimit(maximumOutputSize);
        }
        return current + additional;
    }

    /// Adds one decoded range without exceeding the decoded-size bound.
    private static int checkedOutputSize(int current, int additional, int maximumOutputSize) throws IOException {
        if (additional > maximumOutputSize - current) {
            throw outputLimit(maximumOutputSize);
        }
        return current + additional;
    }

    /// Returns a stable failure for a raw block larger than its configured bound.
    private static IOException outputLimit(int maximumOutputSize) {
        return new IOException(
                "Decoded LZ4 block exceeds the configured maximum of " + maximumOutputSize + " bytes"
        );
    }

    /// Expands decoded storage while enforcing the configured working-memory limit.
    private static byte[] ensureCapacity(
            byte[] output,
            int requiredLength,
            int maximumOutputSize,
            int compressedLength,
            int dictionaryLength,
            long maximumMemorySize
    ) throws DecompressionMemoryLimitException {
        if (requiredLength <= output.length) {
            return output;
        }
        int capacity = Math.max(1, output.length);
        while (capacity < requiredLength) {
            capacity = (int) Math.min(maximumOutputSize, Math.max((long) capacity * 2, requiredLength));
        }
        if (maximumMemorySize >= 0L) {
            long available = maximumMemorySize - compressedLength - dictionaryLength;
            if (available >= requiredLength) capacity = (int) Math.min(capacity, available);
        }
        requireMemory(maximumMemorySize, compressedLength, dictionaryLength, capacity);
        return Arrays.copyOf(output, capacity);
    }

    /// Enforces the memory occupied by compressed input, prefix history, and decoded storage.
    private static void requireMemory(
            long maximumMemorySize,
            int compressedLength,
            int dictionaryLength,
            int outputCapacity
    ) throws DecompressionMemoryLimitException {
        long required = (long) compressedLength + dictionaryLength + outputCapacity;
        if (maximumMemorySize >= 0L && required > maximumMemorySize) {
            throw new DecompressionMemoryLimitException(maximumMemorySize, required);
        }
    }

    /// Holds decoded storage together with the meaningful prefix length.
    ///
    /// @param bytes  owned decoded storage
    /// @param length number of meaningful decoded bytes
    @NotNullByDefault
    record Result(byte[] bytes, int length) {
        /// Validates the decoded storage range.
        Result {
            Objects.requireNonNull(bytes, "bytes");
            if (length < 0 || length > bytes.length) {
                throw new IllegalArgumentException("length is outside decoded storage");
            }
        }
    }
}
