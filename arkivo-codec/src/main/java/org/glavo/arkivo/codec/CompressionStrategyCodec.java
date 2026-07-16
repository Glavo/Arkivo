// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an immutable codec configuration with a selectable generic compression strategy.
@NotNullByDefault
public interface CompressionStrategyCodec extends CompressionCodec {
    /// Returns the configured compression strategy.
    CompressionStrategy compressionStrategy();

    /// Returns an immutable codec configured with the requested compression strategy.
    CompressionStrategyCodec withCompressionStrategy(CompressionStrategy compressionStrategy);
}
