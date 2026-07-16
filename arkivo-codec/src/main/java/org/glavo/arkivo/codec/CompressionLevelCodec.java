// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes an immutable codec configuration with a selectable compression level.
@NotNullByDefault
public interface CompressionLevelCodec extends CompressionCodec {
    /// Returns the configured compression level.
    long compressionLevel();

    /// Returns the minimum supported compression level.
    long minimumCompressionLevel();

    /// Returns the maximum supported compression level.
    long maximumCompressionLevel();

    /// Returns the format implementation's default compression level.
    long defaultCompressionLevel();

    /// Returns an immutable codec configured with the requested compression level.
    CompressionLevelCodec withCompressionLevel(long compressionLevel);
}
