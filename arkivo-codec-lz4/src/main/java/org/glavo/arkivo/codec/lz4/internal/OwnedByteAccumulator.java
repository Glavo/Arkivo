// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Collects a bounded byte sequence without retaining caller-owned buffers.
@NotNullByDefault
final class OwnedByteAccumulator {
    /// Preferred initial allocation size.
    private static final int INITIAL_CAPACITY = 8192;

    /// Maximum accepted byte count.
    private final int maximumLength;

    /// Owned storage containing the collected prefix.
    private byte[] bytes = new byte[0];

    /// Number of collected bytes.
    private int size;

    /// Creates an empty accumulator with an inclusive byte-count bound.
    OwnedByteAccumulator(int maximumLength) {
        if (maximumLength < 0) {
            throw new IllegalArgumentException("maximumLength must not be negative");
        }
        this.maximumLength = maximumLength;
    }

    /// Copies every remaining source byte into owned storage.
    void append(ByteBuffer source, String overflowMessage) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(overflowMessage, "overflowMessage");
        int additional = source.remaining();
        if (additional > maximumLength - size) {
            throw new IOException(overflowMessage);
        }
        ensureCapacity(size + additional);
        source.get(bytes, size, additional);
        size += additional;
    }

    /// Returns the number of collected bytes.
    int size() {
        return size;
    }

    /// Transfers the collected bytes into an exact owned array and releases retained capacity.
    byte[] takeBytes() {
        byte[] result = size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        bytes = new byte[0];
        size = 0;
        return result;
    }

    /// Discards collected content and releases retained capacity.
    void clear() {
        bytes = new byte[0];
        size = 0;
    }

    /// Expands owned storage to contain the requested total length.
    private void ensureCapacity(int requiredLength) {
        if (requiredLength <= bytes.length) {
            return;
        }
        int capacity = Math.min(maximumLength, Math.max(INITIAL_CAPACITY, bytes.length));
        if (capacity == 0) {
            capacity = requiredLength;
        }
        while (capacity < requiredLength) {
            int grown = capacity + (capacity >>> 1) + 1;
            capacity = Math.min(maximumLength, Math.max(grown, requiredLength));
        }
        bytes = Arrays.copyOf(bytes, capacity);
    }
}
