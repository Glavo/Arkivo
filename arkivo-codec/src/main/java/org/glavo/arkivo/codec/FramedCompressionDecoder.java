// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Decodes a format whose independently terminated frames may be concatenated and decoded after reset.
@NotNullByDefault
public interface FramedCompressionDecoder extends CompressionDecoder {
}
