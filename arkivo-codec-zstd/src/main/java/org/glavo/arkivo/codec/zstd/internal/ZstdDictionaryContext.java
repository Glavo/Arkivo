// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;

/// Holds validated Zstandard dictionary content and optional entropy tables.
@NotNullByDefault
final class ZstdDictionaryContext {
    /// A context representing no dictionary.
    static final ZstdDictionaryContext NONE = new ZstdDictionaryContext(
            ZstdDictionary.NO_DICTIONARY_ID,
            new byte[0],
            null,
            null,
            null,
            null,
            null,
            1,
            4,
            8
    );

    /// Formatted dictionary identifier, or `ZstdDictionary.NO_DICTIONARY_ID` for raw content.
    private final long id;

    /// Raw history content.
    private final byte @Unmodifiable [] content;

    /// Initial Huffman table, or null for raw dictionaries.
    private final @Nullable ZstdEntropy.HuffmanTable huffmanTable;

    /// Initial Huffman encoding table, or null for raw dictionaries.
    private final @Nullable ZstdLiteralEncoder.HuffmanEncoding huffmanEncoding;

    /// Initial offset-code FSE table, or null for raw dictionaries.
    private final @Nullable ZstdEntropy.FseTable offsetTable;

    /// Initial match-length FSE table, or null for raw dictionaries.
    private final @Nullable ZstdEntropy.FseTable matchLengthTable;

    /// Initial literal-length FSE table, or null for raw dictionaries.
    private final @Nullable ZstdEntropy.FseTable literalLengthTable;

    /// Initial most-recent offset.
    private final int repeatedOffset1;

    /// Initial second-most-recent offset.
    private final int repeatedOffset2;

    /// Initial third-most-recent offset.
    private final int repeatedOffset3;

    /// Creates a dictionary context.
    private ZstdDictionaryContext(
            long id,
            byte[] content,
            @Nullable ZstdEntropy.HuffmanTable huffmanTable,
            @Nullable ZstdLiteralEncoder.HuffmanEncoding huffmanEncoding,
            @Nullable ZstdEntropy.FseTable offsetTable,
            @Nullable ZstdEntropy.FseTable matchLengthTable,
            @Nullable ZstdEntropy.FseTable literalLengthTable,
            int repeatedOffset1,
            int repeatedOffset2,
            int repeatedOffset3
    ) {
        this.id = id;
        this.content = content;
        this.huffmanTable = huffmanTable;
        this.huffmanEncoding = huffmanEncoding;
        this.offsetTable = offsetTable;
        this.matchLengthTable = matchLengthTable;
        this.literalLengthTable = literalLengthTable;
        this.repeatedOffset1 = repeatedOffset1;
        this.repeatedOffset2 = repeatedOffset2;
        this.repeatedOffset3 = repeatedOffset3;
    }

    /// Parses configured dictionary bytes.
    static ZstdDictionaryContext parse(@Nullable ZstdDictionary dictionary) throws IOException {
        if (dictionary == null) {
            return NONE;
        }
        byte[] bytes = dictionary.bytes();
        if (bytes.length < 8) {
            throw new IOException("Zstandard dictionaries must contain at least eight bytes");
        }
        if (!dictionary.isFullDictionary()) {
            return new ZstdDictionaryContext(
                    ZstdDictionary.NO_DICTIONARY_ID,
                    bytes,
                    null,
                    null,
                    null,
                    null,
                    null,
                    1,
                    4,
                    8
            );
        }

        long id = dictionary.dictionaryId();
        int offset = 8;
        ZstdEntropy.HuffmanParseResult huffman = ZstdEntropy.readHuffmanTable(bytes, offset, bytes.length);
        offset += huffman.bytesRead();
        ZstdEntropy.FseParseResult offsets = ZstdEntropy.readFseTable(bytes, offset, bytes.length, 31, 8);
        offset += offsets.bytesRead();
        ZstdEntropy.FseParseResult matches = ZstdEntropy.readFseTable(bytes, offset, bytes.length, 52, 9);
        offset += matches.bytesRead();
        ZstdEntropy.FseParseResult literals = ZstdEntropy.readFseTable(bytes, offset, bytes.length, 35, 9);
        offset += literals.bytesRead();
        if (bytes.length - offset < 12) {
            throw new IOException("Truncated Zstandard dictionary entropy section");
        }
        long repeat1 = readUnsignedInt(bytes, offset);
        long repeat2 = readUnsignedInt(bytes, offset + 4);
        long repeat3 = readUnsignedInt(bytes, offset + 8);
        offset += 12;
        int contentSize = bytes.length - offset;
        if (repeat1 == 0L || repeat2 == 0L || repeat3 == 0L
                || repeat1 > contentSize || repeat2 > contentSize || repeat3 > contentSize) {
            throw new IOException("Invalid Zstandard dictionary repeated offsets");
        }
        return new ZstdDictionaryContext(
                id,
                Arrays.copyOfRange(bytes, offset, bytes.length),
                huffman.table(),
                ZstdLiteralEncoder.fromWeights(
                        huffman.weights(),
                        huffman.symbolCount(),
                        huffman.tableLog()
                ),
                offsets.table(),
                matches.table(),
                literals.table(),
                (int) repeat1,
                (int) repeat2,
                (int) repeat3
        );
    }

    /// Returns the dictionary identifier.
    long id() {
        return id;
    }

    /// Returns the immutable dictionary history.
    byte @Unmodifiable [] content() {
        return content;
    }

    /// Returns the initial Huffman table, or null when unavailable.
    @Nullable ZstdEntropy.HuffmanTable huffmanTable() {
        return huffmanTable;
    }

    /// Returns the initial Huffman encoding table, or null when unavailable.
    @Nullable ZstdLiteralEncoder.HuffmanEncoding huffmanEncoding() {
        return huffmanEncoding;
    }

    /// Returns the initial offset table, or null when unavailable.
    @Nullable ZstdEntropy.FseTable offsetTable() {
        return offsetTable;
    }

    /// Returns the initial match-length table, or null when unavailable.
    @Nullable ZstdEntropy.FseTable matchLengthTable() {
        return matchLengthTable;
    }

    /// Returns the initial literal-length table, or null when unavailable.
    @Nullable ZstdEntropy.FseTable literalLengthTable() {
        return literalLengthTable;
    }

    /// Returns the initial most-recent offset.
    int repeatedOffset1() {
        return repeatedOffset1;
    }

    /// Returns the initial second-most-recent offset.
    int repeatedOffset2() {
        return repeatedOffset2;
    }

    /// Returns the initial third-most-recent offset.
    int repeatedOffset3() {
        return repeatedOffset3;
    }

    /// Reads one unsigned little-endian 32-bit integer.
    private static long readUnsignedInt(byte[] source, int offset) {
        return Integer.toUnsignedLong(ByteArrayAccess.readIntLittleEndian(source, offset));
    }
}
