// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.delta;

import org.glavo.arkivo.codec.transform.ByteTransform;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Applies the byte-wise Delta filter used by XZ and 7z with a history distance from 1 through 256.
@NotNullByDefault
public final class DeltaTransform implements ByteTransform {
    /// Whether this transform converts original bytes into differences.
    private final boolean encoder;

    /// The circular original-byte history.
    private final byte[] history;

    /// The next history position to replace.
    private int historyPosition;

    /// Creates a Delta transform with the requested direction and history distance.
    public DeltaTransform(ByteTransform.Direction direction, int distance) {
        Objects.requireNonNull(direction, "direction");
        if (distance < 1 || distance > 256) {
            throw new IllegalArgumentException("Delta distance must be between 1 and 256");
        }
        this.encoder = direction == ByteTransform.Direction.ENCODE;
        this.history = new byte[distance];
    }

    /// Transforms every supplied byte in place.
    @Override
    public int transform(byte[] buffer, int offset, int length) {
        int end = offset + length;
        for (int index = offset; index < end; index++) {
            byte current = buffer[index];
            byte previous = history[historyPosition];
            byte original;
            if (encoder) {
                original = current;
                buffer[index] = (byte) (current - previous);
            } else {
                original = (byte) (current + previous);
                buffer[index] = original;
            }
            history[historyPosition] = original;
            historyPosition++;
            if (historyPosition == history.length) {
                historyPosition = 0;
            }
        }
        return length;
    }
}
