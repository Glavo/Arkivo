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
    public static ZlibDictionary of(byte[] bytes) {
        return new ZlibDictionary(RawCompressionDictionary.of(bytes));
    }

    /// Creates a zlib dictionary by copying the buffer's remaining bytes without changing its state.
    public static ZlibDictionary of(ByteBuffer buffer) {
        return new ZlibDictionary(RawCompressionDictionary.of(buffer));
    }

    /// Returns a copy of the preset-dictionary bytes.
    public byte[] bytes() {
        return content.bytes();
    }

    /// Returns an independent read-only view of the dictionary bytes at position zero.
    public @UnmodifiableView ByteBuffer buffer() {
        return content.buffer();
    }

    /// Returns the number of preset-dictionary bytes.
    public int size() {
        return content.size();
    }

    /// Returns the unsigned 32-bit Adler-32 dictionary identifier.
    public long adler32() {
        return adler32;
    }
}
