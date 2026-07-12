// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

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

    /// Returns a copy of the raw dictionary bytes.
    public byte[] bytes() {
        return bytes.clone();
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
