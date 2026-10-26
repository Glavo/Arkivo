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
    static final long CONTIGUOUS_LIMIT = 16L * 1024L * 1024L;

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

    /// Discards all available history while retaining allocated storage for subsequent appends.
    void reset() {
        position = 0L;
        size = 0L;
    }

    /// Returns the logical window capacity.
    long capacity() {
        return capacity;
    }

    /// Returns the number of prior bytes currently available.
    long size() {
        return size;
    }

    /// Copies the first `length` bytes of a block into history, splitting at ring and segment boundaries.
    void append(byte[] block, int length) throws IOException {
        Objects.checkFromIndexSize(0, length, block.length);
        if (length == 0) return;
        if (capacity == 0L) {
            throw new IOException("Zstandard frame with a zero window produced output");
        }
        for (int offset = 0; offset < length;) {
            int copied = (int) Math.min(length - offset, capacity - position);
            if (contiguous != null) {
                System.arraycopy(block, offset, contiguous, (int) position, copied);
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
                int segmentOffset = (int) position & SEGMENT_MASK;
                copied = Math.min(copied, SEGMENT_SIZE - segmentOffset);
                System.arraycopy(block, offset, segment, segmentOffset, copied);
            }
            offset += copied;
            position += copied;
            if (position == capacity) position = 0L;
            size += Math.min(copied, capacity - size);
        }
    }

    /// Copies prior bytes without changing history; the range must end at or before the write position.
    void copy(long distance, byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (distance <= 0L || distance > size || length > distance) {
            throw new IOException("Zstandard match offset exceeds available history");
        }
        long sourcePosition = position - distance;
        if (sourcePosition < 0L) {
            sourcePosition += capacity;
        }
        while (length > 0) {
            int copied = (int) Math.min(length, capacity - sourcePosition);
            if (contiguous != null) {
                System.arraycopy(contiguous, (int) sourcePosition, target, offset, copied);
            } else {
                Map<Long, byte[]> storage = Objects.requireNonNull(segments);
                byte[] segment = storage.get(sourcePosition >>> SEGMENT_SHIFT);
                if (segment == null) {
                    throw new IOException("Zstandard history segment is unavailable");
                }
                int segmentOffset = (int) sourcePosition & SEGMENT_MASK;
                copied = Math.min(copied, SEGMENT_SIZE - segmentOffset);
                System.arraycopy(segment, segmentOffset, target, offset, copied);
            }
            offset += copied;
            length -= copied;
            sourcePosition += copied;
            if (sourcePosition == capacity) sourcePosition = 0L;
        }
    }
}
