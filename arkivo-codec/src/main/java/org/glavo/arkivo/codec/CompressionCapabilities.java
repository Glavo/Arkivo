// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Objects;
import java.util.Set;

/// Describes operation modes and typed options supported by a compression codec.
///
/// @param features supported operation features
/// @param compressionOptions options accepted when opening an encoder
/// @param decompressionOptions options accepted when opening a decoder
/// @param compressionLevels supported compression-level range, or `null` when levels are unsupported
@NotNullByDefault
public record CompressionCapabilities(
        @Unmodifiable Set<CompressionFeature> features,
        @Unmodifiable Set<CodecOption<?>> compressionOptions,
        @Unmodifiable Set<CodecOption<?>> decompressionOptions,
        @Nullable CompressionLevelRange compressionLevels
) {
    /// Creates an immutable capability description.
    public CompressionCapabilities {
        features = Set.copyOf(Objects.requireNonNull(features, "features"));
        compressionOptions = Set.copyOf(Objects.requireNonNull(compressionOptions, "compressionOptions"));
        decompressionOptions = Set.copyOf(Objects.requireNonNull(decompressionOptions, "decompressionOptions"));
    }

    /// Creates capabilities without operation-specific options.
    public static CompressionCapabilities of(@Unmodifiable Set<CompressionFeature> features) {
        return new CompressionCapabilities(features, Set.of(), Set.of(), null);
    }

    /// Returns whether the codec supports the given feature.
    public boolean supports(CompressionFeature feature) {
        return features.contains(Objects.requireNonNull(feature, "feature"));
    }
}
