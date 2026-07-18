// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.lz4.internal.LZ4DictionarySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Stores immutable LZ4 prefix-history bytes and an optional frame dictionary identifier.
///
/// LZ4 match offsets can address at most the final 65,535 bytes of a dictionary. Construction therefore copies only
/// that effective suffix, even when a larger dictionary source is supplied.
@NotNullByDefault
public final class LZ4Dictionary implements CompressionDictionary {
    /// Sentinel returned when the dictionary identifier is passed out of band.
    public static final long NO_DICTIONARY_ID = -1L;

    /// Maximum prefix-history size addressable by an LZ4 match offset.
    public static final int MAXIMUM_CONTENT_SIZE = 65_535;

    /// Immutable effective prefix-history bytes.
    private final RawCompressionDictionary content;

    /// Unsigned frame dictionary identifier, or `NO_DICTIONARY_ID` when omitted.
    private final long dictionaryId;

    /// Copies and canonicalizes one dictionary suffix with optional identification metadata.
    private LZ4Dictionary(ByteBuffer bytes, long dictionaryId) {
        Objects.requireNonNull(bytes, "bytes");
        if (dictionaryId < NO_DICTIONARY_ID || dictionaryId > 0xffff_ffffL) {
            throw new IllegalArgumentException("dictionaryId must be an unsigned 32-bit value or NO_DICTIONARY_ID");
        }
        ByteBuffer effective = bytes.slice();
        if (effective.remaining() > MAXIMUM_CONTENT_SIZE) {
            effective.position(effective.limit() - MAXIMUM_CONTENT_SIZE);
        }
        content = RawCompressionDictionary.of(effective);
        this.dictionaryId = dictionaryId;
    }

    /// Creates an out-of-band dictionary by copying the effective suffix of the supplied bytes.
    public static LZ4Dictionary rawContent(byte[] bytes) {
        return new LZ4Dictionary(ByteBuffer.wrap(Objects.requireNonNull(bytes, "bytes")), NO_DICTIONARY_ID);
    }

    /// Creates an out-of-band dictionary without changing the supplied buffer state.
    public static LZ4Dictionary rawContent(ByteBuffer bytes) {
        return new LZ4Dictionary(bytes, NO_DICTIONARY_ID);
    }

    /// Creates an identified dictionary by copying the effective suffix of the supplied bytes.
    public static LZ4Dictionary identified(long dictionaryId, byte[] bytes) {
        return new LZ4Dictionary(ByteBuffer.wrap(Objects.requireNonNull(bytes, "bytes")), dictionaryId);
    }

    /// Creates an identified dictionary without changing the supplied buffer state.
    public static LZ4Dictionary identified(long dictionaryId, ByteBuffer bytes) {
        return new LZ4Dictionary(bytes, dictionaryId);
    }

    /// Creates a dictionary whose identifier is the zero-seed xxHash-32 of its effective content.
    ///
    /// This derivation is an Arkivo convenience convention, not a requirement of the LZ4 frame specification.
    public static LZ4Dictionary identifiedByContent(byte[] bytes) {
        return identifiedByContent(ByteBuffer.wrap(Objects.requireNonNull(bytes, "bytes")));
    }

    /// Creates a content-identified dictionary without changing the supplied buffer state.
    ///
    /// This derivation is an Arkivo convenience convention, not a requirement of the LZ4 frame specification.
    public static LZ4Dictionary identifiedByContent(ByteBuffer bytes) {
        LZ4Dictionary dictionary = rawContent(bytes);
        return new LZ4Dictionary(
                dictionary.buffer(),
                LZ4DictionarySupport.contentIdentifier(dictionary.bytes())
        );
    }

    /// Returns a copy of the effective prefix-history bytes.
    public byte[] bytes() {
        return content.bytes();
    }

    /// Returns an independent read-only view of the effective prefix-history bytes.
    public @UnmodifiableView ByteBuffer buffer() {
        return content.buffer();
    }

    /// Returns the number of effective prefix-history bytes.
    public int size() {
        return content.size();
    }

    /// Returns whether this dictionary carries an identifier for the frame descriptor.
    public boolean hasDictionaryId() {
        return dictionaryId != NO_DICTIONARY_ID;
    }

    /// Returns the unsigned frame identifier, or `NO_DICTIONARY_ID` when it is supplied out of band.
    public long dictionaryId() {
        return dictionaryId;
    }
}
