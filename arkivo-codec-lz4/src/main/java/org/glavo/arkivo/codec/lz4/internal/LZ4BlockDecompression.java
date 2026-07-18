// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
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
        Objects.requireNonNull(compressed, "compressed");
        Objects.requireNonNull(dictionary, "dictionary");
        if (maximumOutputSize < 0) {
            throw new IllegalArgumentException("maximumOutputSize must not be negative");
        }
        if (compressed.length == 0) {
            throw new IOException("Empty input is not an LZ4 block");
        }

        int dictionaryLength = Math.min(dictionary.length, MAXIMUM_OFFSET);
        int dictionaryOffset = dictionary.length - dictionaryLength;
        int initialCapacity = Math.min(maximumOutputSize, INITIAL_CAPACITY);
        requireMemory(maximumMemorySize, compressed.length, dictionaryLength, initialCapacity);
        byte[] output = new byte[initialCapacity];
        int outputSize = 0;
        int inputPosition = 0;
        boolean decodedMatch = false;

        while (inputPosition < compressed.length) {
            int token = Byte.toUnsignedInt(compressed[inputPosition++]);
            int literalLength = token >>> 4;
            if (literalLength == 15) {
                int extension;
                do {
                    if (inputPosition >= compressed.length) {
                        throw new IOException("Truncated LZ4 literal length");
                    }
                    extension = Byte.toUnsignedInt(compressed[inputPosition++]);
                    literalLength = checkedLength(literalLength, extension, maximumOutputSize);
                } while (extension == 255);
            }
            if (literalLength > compressed.length - inputPosition) {
                throw new IOException("Truncated LZ4 literal bytes");
            }
            int requiredOutput = checkedOutputSize(outputSize, literalLength, maximumOutputSize);
            output = ensureCapacity(
                    output,
                    requiredOutput,
                    maximumOutputSize,
                    compressed.length,
                    dictionaryLength,
                    maximumMemorySize
            );
            System.arraycopy(compressed, inputPosition, output, outputSize, literalLength);
            inputPosition += literalLength;
            outputSize = requiredOutput;

            int matchCode = token & 15;
            if (inputPosition == compressed.length) {
                if (matchCode != 0) {
                    throw new IOException("Final LZ4 sequence declares a missing match");
                }
                if (decodedMatch && literalLength < 5) {
                    throw new IOException("Final LZ4 sequence contains fewer than five literals");
                }
                return new Result(output, outputSize);
            }
            if (compressed.length - inputPosition < 2) {
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
                    if (inputPosition >= compressed.length) {
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
                    dictionaryLength,
                    maximumMemorySize
            );
            for (int index = 0; index < matchLength; index++) {
                int sourcePosition = outputSize - matchOffset + index;
                output[outputSize + index] = sourcePosition < 0
                        ? dictionary[dictionaryOffset + dictionaryLength + sourcePosition]
                        : output[sourcePosition];
            }
            outputSize = requiredOutput;
            decodedMatch = true;
            if (inputPosition == compressed.length) {
                throw new IOException("LZ4 block is missing its final literal sequence");
            }
        }
        throw new IOException("LZ4 block is missing its final literal sequence");
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
            int grown = capacity + (capacity >>> 1) + 1;
            capacity = Math.min(maximumOutputSize, Math.max(grown, requiredLength));
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
