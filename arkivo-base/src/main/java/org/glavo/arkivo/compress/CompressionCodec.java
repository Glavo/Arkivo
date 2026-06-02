// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes a complete compression codec with encoder and decoder support.
@NotNullByDefault
public interface CompressionCodec extends CompressionFormat, CompressionEncoder, CompressionDecoder {
}
