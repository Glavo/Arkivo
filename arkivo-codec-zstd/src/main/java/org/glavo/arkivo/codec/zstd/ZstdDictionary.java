// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Stores immutable Zstandard dictionary bytes together with their required interpretation.
@NotNullByDefault
public final class ZstdDictionary implements CompressionDictionary {
    /// The Zstandard formatted-dictionary magic value.
    public static final long FORMATTED_DICTIONARY_MAGIC = 0xec30_a437L;

    /// The value returned when dictionary content has no formatted dictionary identifier.
    public static final long NO_DICTIONARY_ID = 0L;

    /// Immutable dictionary bytes.
    private final RawCompressionDictionary content;

    /// Requested interpretation of the dictionary bytes.
    private final ContentType contentType;

    /// Formatted dictionary identifier, or `NO_DICTIONARY_ID` for raw content.
    private final long dictionaryId;

    /// Creates one immutable Zstandard dictionary and validates its interpretation metadata.
    private ZstdDictionary(RawCompressionDictionary content, ContentType contentType) {
        this.content = Objects.requireNonNull(content, "content");
        this.contentType = Objects.requireNonNull(contentType, "contentType");
        if (content.size() < 8) {
            throw new IllegalArgumentException("Zstandard dictionaries must contain at least eight bytes");
        }

        byte[] bytes = content.bytes();
        boolean hasMagic = readUnsignedInt(bytes, 0) == FORMATTED_DICTIONARY_MAGIC;
        if (contentType == ContentType.FULL_DICTIONARY && !hasMagic) {
            throw new IllegalArgumentException("Full Zstandard dictionaries must start with the dictionary magic");
        }
        boolean fullDictionary = contentType != ContentType.RAW_CONTENT && hasMagic;
        if (fullDictionary) {
            long id = readUnsignedInt(bytes, 4);
            if (id == NO_DICTIONARY_ID) {
                throw new IllegalArgumentException("Formatted Zstandard dictionaries must have a nonzero identifier");
            }
            this.dictionaryId = id;
        } else {
            this.dictionaryId = NO_DICTIONARY_ID;
        }
    }

    /// Creates a dictionary whose full-versus-raw interpretation is detected from its magic value.
    public static ZstdDictionary of(byte[] bytes) {
        return new ZstdDictionary(RawCompressionDictionary.of(bytes), ContentType.AUTO);
    }

    /// Creates a dictionary whose full-versus-raw interpretation is detected without changing the buffer state.
    public static ZstdDictionary of(ByteBuffer buffer) {
        return new ZstdDictionary(RawCompressionDictionary.of(buffer), ContentType.AUTO);
    }

    /// Creates a dictionary that is always interpreted as raw history content.
    public static ZstdDictionary rawContent(byte[] bytes) {
        return new ZstdDictionary(RawCompressionDictionary.of(bytes), ContentType.RAW_CONTENT);
    }

    /// Creates a raw-content dictionary without changing the source buffer state.
    public static ZstdDictionary rawContent(ByteBuffer buffer) {
        return new ZstdDictionary(RawCompressionDictionary.of(buffer), ContentType.RAW_CONTENT);
    }

    /// Creates a dictionary that must use the standard formatted-dictionary representation.
    public static ZstdDictionary fullDictionary(byte[] bytes) {
        return new ZstdDictionary(RawCompressionDictionary.of(bytes), ContentType.FULL_DICTIONARY);
    }

    /// Creates a full dictionary without changing the source buffer state.
    public static ZstdDictionary fullDictionary(ByteBuffer buffer) {
        return new ZstdDictionary(RawCompressionDictionary.of(buffer), ContentType.FULL_DICTIONARY);
    }

    /// Returns a copy of the complete dictionary representation.
    public byte[] bytes() {
        return content.bytes();
    }

    /// Returns an independent read-only view of the dictionary representation at position zero.
    public @UnmodifiableView ByteBuffer buffer() {
        return content.buffer();
    }

    /// Returns the number of bytes in the complete dictionary representation.
    public int size() {
        return content.size();
    }

    /// Returns the requested dictionary-content interpretation.
    public ContentType contentType() {
        return contentType;
    }

    /// Returns whether these bytes are interpreted as a full formatted dictionary.
    public boolean isFullDictionary() {
        return dictionaryId != NO_DICTIONARY_ID;
    }

    /// Returns the unsigned 32-bit dictionary identifier, or `NO_DICTIONARY_ID` for raw content.
    public long dictionaryId() {
        return dictionaryId;
    }

    /// Reads one unsigned little-endian 32-bit integer.
    private static long readUnsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(bytes[offset])
                        | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                        | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                        | Byte.toUnsignedInt(bytes[offset + 3]) << 24
        );
    }

    /// Selects how Zstandard interprets dictionary bytes.
    @NotNullByDefault
    public enum ContentType {
        /// Detects full dictionaries by their standard magic value and otherwise uses raw content.
        AUTO,

        /// Treats every byte as raw history content, even when the bytes begin with the dictionary magic.
        RAW_CONTENT,

        /// Requires the bytes to be interpreted using the standard full-dictionary representation.
        FULL_DICTIONARY
    }
}
