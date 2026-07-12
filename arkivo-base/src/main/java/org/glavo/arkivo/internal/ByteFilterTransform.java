// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;

/// Transforms a contiguous prefix of buffered bytes for one stateful preprocessing filter.
@NotNullByDefault
public interface ByteFilterTransform {
    /// Transforms bytes in place and returns the contiguous prefix that is ready for downstream consumption.
    int transform(byte[] buffer, int offset, int length);
}
