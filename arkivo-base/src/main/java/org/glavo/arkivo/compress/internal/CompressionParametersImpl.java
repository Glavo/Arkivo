// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.internal;

import org.glavo.arkivo.compress.CompressionParameters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores immutable compression parameters.
@NotNullByDefault
public final class CompressionParametersImpl implements CompressionParameters {
    /// The shared default compression parameters.
    public static final CompressionParameters DEFAULT = new CompressionParametersImpl(
            DEFAULT_COMPRESSION_LEVEL,
            UNKNOWN_UNCOMPRESSED_SIZE,
            null
    );

    /// The requested compression level.
    private final int compressionLevel;

    /// The expected uncompressed size.
    private final long expectedUncompressedSize;

    /// The dictionary bytes, or `null` when no dictionary is configured.
    private final byte @Nullable [] dictionary;

    /// Creates immutable compression parameters.
    public CompressionParametersImpl(
            int compressionLevel,
            long expectedUncompressedSize,
            byte @Nullable [] dictionary
    ) {
        this.compressionLevel = compressionLevel;
        this.expectedUncompressedSize = expectedUncompressedSize;
        this.dictionary = dictionary != null ? dictionary.clone() : null;
    }

    /// Returns the requested compression level.
    @Override
    public int compressionLevel() {
        return compressionLevel;
    }

    /// Returns the expected uncompressed size.
    @Override
    public long expectedUncompressedSize() {
        return expectedUncompressedSize;
    }

    /// Returns a copy of the dictionary bytes, or `null` when no dictionary is configured.
    @Override
    public byte @Nullable [] dictionary() {
        return dictionary != null ? dictionary.clone() : null;
    }
}
