// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Buffers a transform's committed prefix and unchanged lookahead suffix.
///
/// The output view's position records consumed bytes and its limit separates committed bytes from pending lookahead.
/// The input view covers the unused tail. The views are reused, so callers must not retain them across other buffer
/// operations. After a transform failure, the caller must discard this buffer; transformed bytes are not rolled back.
/// This buffer is not safe for concurrent use.
@NotNullByDefault
final class TransformBuffer {
    /// The maximum number of bytes held for transformation and lookahead.
    private static final int CAPACITY = 8192;

    /// The stateful in-place transform.
    private final ByteTransform transform;

    /// The shared storage for committed bytes, lookahead, and unused capacity, in that order.
    private final byte[] bytes = new byte[CAPACITY];

    /// The reusable view filled by an upstream read or caller write.
    private final ByteBuffer input = ByteBuffer.wrap(bytes);

    /// The reusable view consumed by a caller read or downstream write.
    private final ByteBuffer output = ByteBuffer.wrap(bytes).limit(0);

    /// The number of uncommitted bytes immediately following the output limit.
    private int pending;

    /// Creates an empty buffer for one transform sequence.
    TransformBuffer(ByteTransform transform) {
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    /// Returns the unused tail, reclaiming consumed space when the tail reaches capacity.
    ///
    /// The caller must consume all available output before requesting more input. Newly added bytes are recorded by
    /// advancing the returned view's position, then calling [#transform()].
    ByteBuffer input() {
        int end = output.limit() + pending;
        if (end == bytes.length) {
            int ready = output.remaining();
            System.arraycopy(bytes, output.position(), bytes, 0, ready + pending);
            output.position(0).limit(ready);
            end = ready + pending;
        }
        return input.clear().position(end);
    }

    /// Commits newly supplied input and transforms its complete prefix together with pending lookahead.
    ///
    /// @throws IOException if the transform returns an invalid count or retains a full buffer without a committed byte
    void transform() throws IOException {
        int start = output.limit();
        pending = input.position() - start;
        int transformed = transform.transform(bytes, start, pending);
        if (transformed < 0 || transformed > pending) {
            throw new IOException("Byte filter returned an invalid transformed byte count");
        }
        output.limit(start + transformed);
        pending -= transformed;
        if (!output.hasRemaining() && pending == bytes.length) {
            throw new IOException("Byte filter made no progress with a full buffer");
        }
    }

    /// Returns the committed prefix; advancing its position consumes bytes without copying the pending suffix.
    ByteBuffer output() {
        return output;
    }

    /// Makes the remaining lookahead available unchanged without invoking the transform again.
    void finish() {
        output.limit(output.limit() + pending);
        pending = 0;
    }
}
