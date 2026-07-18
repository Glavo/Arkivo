// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes one immutable, size-preserving preprocessing filter placed before XZ's terminal LZMA2 filter.
///
/// Filters are listed in encoding order. Decoding applies their inverse transforms in reverse order after LZMA2
/// decompression.
@NotNullByDefault
public sealed interface XZFilter permits XZBCJFilter, XZDeltaFilter {
}
