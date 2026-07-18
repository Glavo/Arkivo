// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Stores immutable raw history bytes without format-specific metadata or interpretation rules.
@NotNullByDefault
public final class RawCompressionDictionary implements CompressionDictionary {
    /// The immutable raw history bytes.
    private final byte @Unmodifiable [] bytes;

    /// Copies raw history bytes into an immutable dictionary.
    private RawCompressionDictionary(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
    }

    /// Creates a raw dictionary by copying the supplied bytes.
    ///
    /// @param bytes the raw history bytes to copy
    /// @return an immutable raw dictionary
    public static RawCompressionDictionary of(byte[] bytes) {
        return new RawCompressionDictionary(bytes);
    }

    /// Creates a raw dictionary by copying the buffer's remaining bytes without changing its state.
    ///
    /// @param buffer the source buffer, read from its current position to its limit without modification
    /// @return an immutable raw dictionary
    public static RawCompressionDictionary of(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return new RawCompressionDictionary(bytes);
    }

    /// Returns a copy of the raw history bytes.
    ///
    /// @return a new mutable copy
    public byte[] bytes() {
        return bytes.clone();
    }

    /// Returns an independent read-only view of the raw history bytes at position zero.
    ///
    /// @return a new read-only buffer view
    public @UnmodifiableView ByteBuffer buffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /// Returns the number of raw history bytes.
    ///
    /// @return the dictionary size in bytes
    public int size() {
        return bytes.length;
    }
}
