// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.Adler32;

/// Stores immutable zlib preset-dictionary bytes and their derived Adler-32 identifier.
///
/// Factory methods copy the supplied remaining bytes, so later source-array or source-buffer changes cannot affect the
/// dictionary. [#bytes()] returns a copy and [#buffer()] returns a new read-only view. Instances are therefore safe for
/// concurrent use.
@NotNullByDefault
public final class ZlibDictionary implements CompressionDictionary {
    /// Immutable preset-dictionary content.
    private final RawCompressionDictionary content;

    /// Adler-32 identifier written to zlib stream headers.
    private final long adler32;

    /// Creates a zlib dictionary from immutable raw content.
    private ZlibDictionary(RawCompressionDictionary content) {
        this.content = Objects.requireNonNull(content, "content");
        Adler32 checksum = new Adler32();
        checksum.update(content.bytes());
        this.adler32 = checksum.getValue();
    }

    /// Creates a zlib dictionary by copying the supplied bytes.
    ///
    /// @param bytes the complete preset-dictionary content
    /// @return an immutable dictionary independent of `bytes`
    public static ZlibDictionary of(byte[] bytes) {
        return new ZlibDictionary(RawCompressionDictionary.of(bytes));
    }

    /// Creates a zlib dictionary by copying the buffer's remaining bytes without changing its state.
    ///
    /// @param buffer the source whose remaining bytes form the dictionary
    /// @return an immutable dictionary independent of `buffer`
    public static ZlibDictionary of(ByteBuffer buffer) {
        return new ZlibDictionary(RawCompressionDictionary.of(buffer));
    }

    /// Returns a copy of the preset-dictionary bytes.
    ///
    /// @return a new array containing the complete dictionary
    public byte[] bytes() {
        return content.bytes();
    }

    /// Returns an independent read-only view of the dictionary bytes at position zero.
    ///
    /// @return a new read-only buffer whose remaining bytes are the complete dictionary
    public @UnmodifiableView ByteBuffer buffer() {
        return content.buffer();
    }

    /// Returns the number of preset-dictionary bytes.
    ///
    /// @return the dictionary length
    public int size() {
        return content.size();
    }

    /// Returns the unsigned 32-bit Adler-32 dictionary identifier.
    ///
    /// @return the identifier in the range zero through `0xffffffff`
    public long adler32() {
        return adler32;
    }
}
