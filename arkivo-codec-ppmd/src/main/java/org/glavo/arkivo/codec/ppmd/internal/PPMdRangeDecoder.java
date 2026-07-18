// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Supplies arithmetic intervals to the shared PPMd Variant H model.
@NotNullByDefault
public interface PPMdRangeDecoder {
    /// Returns the current cumulative-frequency position for the given scale.
    ///
    /// @param scale the positive total frequency of the current context
    /// @return the selected cumulative count, from {@code 0} inclusive to {@code scale} exclusive
    /// @throws IOException if the scale or arithmetic state is invalid, or more compressed input is required
    int currentCount(int scale) throws IOException;

    /// Commits the selected half-open cumulative-frequency interval.
    ///
    /// @param lowCount the inclusive lower cumulative-frequency bound
    /// @param highCount the exclusive upper cumulative-frequency bound
    /// @throws IOException if the interval or arithmetic state is invalid, or more compressed input is required
    void decode(int lowCount, int highCount) throws IOException;

    /// Decodes a binary interval whose zero branch occupies `zeroSize` units of `scale`.
    ///
    /// @param zeroSize the positive frequency assigned to the zero branch, less than {@code scale}
    /// @param scale the positive total binary frequency
    /// @return {@code false} for the zero interval or {@code true} for the remaining interval
    /// @throws IOException if the interval or arithmetic state is invalid, or more compressed input is required
    default boolean decodeBit(int zeroSize, int scale) throws IOException {
        if (currentCount(scale) < zeroSize) {
            decode(0, zeroSize);
            return false;
        }
        decode(zeroSize, scale);
        return true;
    }
}
