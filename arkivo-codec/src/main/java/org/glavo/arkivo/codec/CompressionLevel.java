// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Selects an algorithm-specific compression level.
///
/// @param value the algorithm-specific level value
@NotNullByDefault
public record CompressionLevel(int value) {
}
