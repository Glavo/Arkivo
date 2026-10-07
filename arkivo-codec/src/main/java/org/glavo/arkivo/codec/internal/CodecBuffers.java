// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Objects;

/// Copies staged codec bytes between buffers without allocating an intermediate view.
@NotNullByDefault
public final class CodecBuffers {
    /// Prevents instantiation.
    private CodecBuffers() {
    }

    /// Copies as many remaining source bytes as fit in the target.
    ///
    /// On success, both positions advance by the returned count. Limits and byte orders are unchanged.
    /// If either buffer has no remaining bytes, this method returns zero without attempting a write,
    /// including when the target is read-only. Neither buffer is retained.
    ///
    /// @param source the bytes to copy
    /// @param target the destination
    /// @return the smaller of the buffers' initial remaining byte counts
    /// @throws IllegalArgumentException if the buffers are the same object and the count is nonzero
    /// @throws ReadOnlyBufferException if the target is read-only and the count is nonzero
    /// @throws NullPointerException if either buffer is null
    public static int transfer(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        int count = Math.min(source.remaining(), target.remaining());
        if (count == 0) {
            return 0;
        }
        int originalLimit = source.limit();
        source.limit(source.position() + count);
        try {
            target.put(source);
        } finally {
            source.limit(originalLimit);
        }
        return count;
    }
}
