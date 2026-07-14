// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/// Stores a sliding Zstandard history window without allocating unused large windows eagerly.
@NotNullByDefault
final class ZstdHistory {
    /// Largest window represented by one contiguous array.
    private static final long CONTIGUOUS_LIMIT = 16L * 1024L * 1024L;

    /// Shift for one lazily allocated segment.
    private static final int SEGMENT_SHIFT = 20;

    /// Number of bytes in one lazy segment.
    private static final int SEGMENT_SIZE = 1 << SEGMENT_SHIFT;

    /// Mask selecting an offset within one segment.
    private static final int SEGMENT_MASK = SEGMENT_SIZE - 1;

    /// Logical history capacity.
    private final long capacity;

    /// Contiguous storage for ordinary windows, or null for large windows.
    private final byte @Nullable [] contiguous;

    /// Lazily allocated storage for large windows, or null for ordinary windows.
    private final @Nullable Map<Long, byte[]> segments;

    /// Next logical write position.
    private long position;

    /// Number of bytes currently available.
    private long size;

    /// Creates a history window.
    ZstdHistory(long capacity) throws IOException {
        if (capacity < 0L) {
            throw new IllegalArgumentException("capacity must not be negative");
        }
        this.capacity = capacity;
        try {
            if (capacity <= CONTIGUOUS_LIMIT) {
                contiguous = new byte[(int) Math.max(1L, capacity)];
                segments = null;
            } else {
                contiguous = null;
                segments = new HashMap<>();
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("Unable to allocate Zstandard history window", error);
        }
    }

    /// Returns the logical window capacity.
    long capacity() {
        return capacity;
    }

    /// Returns the number of prior bytes currently available.
    long size() {
        return size;
    }

    /// Appends one byte to the sliding window.
    void append(byte value) throws IOException {
        if (capacity == 0L) {
            throw new IOException("Zstandard frame with a zero window produced output");
        }
        if (contiguous != null) {
            contiguous[(int) position] = value;
        } else {
            long segmentIndex = position >>> SEGMENT_SHIFT;
            Map<Long, byte[]> storage = Objects.requireNonNull(segments);
            byte[] segment = storage.get(segmentIndex);
            if (segment == null) {
                try {
                    segment = new byte[SEGMENT_SIZE];
                    storage.put(segmentIndex, segment);
                } catch (OutOfMemoryError error) {
                    throw new IOException("Unable to grow the Zstandard history window", error);
                }
            }
            segment[(int) position & SEGMENT_MASK] = value;
        }
        position++;
        if (position == capacity) {
            position = 0L;
        }
        if (size < capacity) {
            size++;
        }
    }

    /// Reads a byte at the requested positive distance behind the write position.
    byte get(long distance) throws IOException {
        if (distance <= 0L || distance > size) {
            throw new IOException("Zstandard match offset exceeds available history");
        }
        long sourcePosition = position - distance;
        if (sourcePosition < 0L) {
            sourcePosition += capacity;
        }
        if (contiguous != null) {
            return contiguous[(int) sourcePosition];
        }
        Map<Long, byte[]> storage = Objects.requireNonNull(segments);
        byte[] segment = storage.get(sourcePosition >>> SEGMENT_SHIFT);
        if (segment == null) {
            throw new IOException("Zstandard history segment is unavailable");
        }
        return segment[(int) sourcePosition & SEGMENT_MASK];
    }
}
