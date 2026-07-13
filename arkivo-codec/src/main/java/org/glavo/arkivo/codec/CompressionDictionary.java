// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Stores immutable raw dictionary content and its optional format-specific identifier.
@NotNullByDefault
public final class CompressionDictionary {
    /// The sentinel used when no dictionary identifier is known.
    public static final long UNKNOWN_ID = -1L;

    /// The raw dictionary bytes.
    private final byte @Unmodifiable [] bytes;

    /// The dictionary identifier, or `UNKNOWN_ID` when unavailable.
    private final long id;

    /// Creates an immutable dictionary.
    private CompressionDictionary(byte[] bytes, long id) {
        if (id < UNKNOWN_ID) {
            throw new IllegalArgumentException("id must be non-negative or UNKNOWN_ID");
        }
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.id = id;
    }

    /// Creates a dictionary without a known identifier.
    public static CompressionDictionary of(byte[] bytes) {
        return new CompressionDictionary(bytes, UNKNOWN_ID);
    }

    /// Creates a dictionary with a non-negative format-specific identifier.
    public static CompressionDictionary of(byte[] bytes, long id) {
        return new CompressionDictionary(bytes, id);
    }

    /// Copies the remaining bytes from a buffer without changing its position.
    public static CompressionDictionary of(ByteBuffer buffer) {
        return of(buffer, UNKNOWN_ID);
    }

    /// Copies the remaining bytes and records a non-negative format-specific identifier.
    ///
    /// The source buffer's position and limit are not changed.
    public static CompressionDictionary of(ByteBuffer buffer, long id) {
        Objects.requireNonNull(buffer, "buffer");
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return new CompressionDictionary(bytes, id);
    }

    /// Returns a copy of the raw dictionary bytes.
    public byte[] bytes() {
        return bytes.clone();
    }

    /// Returns a read-only buffer view of the raw dictionary bytes.
    ///
    /// Each returned view has independent position and limit state and starts at position zero.
    public @UnmodifiableView ByteBuffer buffer() {
        return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
    }

    /// Returns the dictionary identifier, or `UNKNOWN_ID` when unavailable.
    public long id() {
        return id;
    }

    /// Returns the number of raw dictionary bytes.
    public int size() {
        return bytes.length;
    }
}
