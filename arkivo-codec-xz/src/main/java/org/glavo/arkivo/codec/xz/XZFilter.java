// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one size-preserving preprocessing filter placed before XZ's terminal LZMA2 filter.
@NotNullByDefault
public sealed interface XZFilter permits XZBCJFilter, XZDeltaFilter {
}
