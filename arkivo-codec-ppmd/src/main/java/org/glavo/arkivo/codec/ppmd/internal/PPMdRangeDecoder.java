// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Supplies arithmetic intervals to the shared PPMd Variant H model.
@NotNullByDefault
public interface PPMdRangeDecoder {
    /// Returns the current cumulative-frequency position for the given scale.
    int currentCount(int scale) throws IOException;

    /// Commits the selected half-open cumulative-frequency interval.
    void decode(int lowCount, int highCount) throws IOException;

    /// Decodes a binary interval whose zero branch occupies `zeroSize` units of `scale`.
    default boolean decodeBit(int zeroSize, int scale) throws IOException {
        if (currentCount(scale) < zeroSize) {
            decode(0, zeroSize);
            return false;
        }
        decode(zeroSize, scale);
        return true;
    }
}
