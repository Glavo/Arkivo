// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Encodes a format that can expose a decodable boundary without ending the active encoding.
@NotNullByDefault
public interface FlushableCompressionEncoder extends CompressionEncoder {
    /// Emits all currently pending output to a decodable boundary without ending the encoding.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
    ///
    /// @return `CodecOutcome.FLUSHED` when flushing completed, or `CodecOutcome.NEEDS_OUTPUT` otherwise
    CodecOutcome flush(ByteBuffer target) throws IOException;
}
