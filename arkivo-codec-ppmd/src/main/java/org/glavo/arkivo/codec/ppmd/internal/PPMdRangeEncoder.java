// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Accepts arithmetic intervals from the shared PPMd Variant H model.
@NotNullByDefault
interface PPMdRangeEncoder {
    /// Encodes one selected half-open cumulative-frequency interval.
    void encode(int lowCount, int highCount, int scale) throws IOException;

    /// Encodes a binary interval whose zero branch occupies the requested units of the scale.
    default void encodeBit(boolean one, int zeroSize, int scale) throws IOException {
        if (one) {
            encode(zeroSize, scale, scale);
        } else {
            encode(0, zeroSize, scale);
        }
    }

    /// Finishes the arithmetic representation and flushes its output.
    void finish() throws IOException;
}
