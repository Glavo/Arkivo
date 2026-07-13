// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.spi;

import org.glavo.arkivo.codec.CompressionStrategy;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.zip.Deflater;

/// Maps the public compression strategy abstraction to JDK Deflater constants for codec providers.
@NotNullByDefault
public final class DeflateStrategySupport {
    /// Creates no instances.
    private DeflateStrategySupport() {
    }

    /// Returns the JDK Deflater constant for the requested strategy.
    public static int toJdkValue(CompressionStrategy strategy) {
        return switch (Objects.requireNonNull(strategy, "strategy")) {
            case DEFAULT -> Deflater.DEFAULT_STRATEGY;
            case FILTERED -> Deflater.FILTERED;
            case HUFFMAN_ONLY -> Deflater.HUFFMAN_ONLY;
        };
    }
}
