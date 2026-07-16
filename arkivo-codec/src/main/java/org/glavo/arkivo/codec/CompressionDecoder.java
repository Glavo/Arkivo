// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally decodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. A completed encoding leaves trailing bytes unconsumed when its boundary is
/// detectable from the compressed data.
@NotNullByDefault
public interface CompressionDecoder extends AutoCloseable {
    /// Decodes compressed source bytes while allowing additional source bytes in a later operation.
    default CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decode(source, target, false);
    }

    /// Decodes compressed source bytes until input, output space, a dictionary, or the encoding boundary stops progress.
    ///
    /// When `endOfInput` is true, exhausting the source before the encoding completes is an error. Buffer positions
    /// are the authoritative record of bytes consumed and produced.
    ///
    /// @return the actionable reason this operation returned
    CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException;

    /// Abandons the current encoding and restores the decoder's original immutable configuration.
    void reset();

    /// Releases decoder resources without consuming additional input.
    @Override
    void close();
}
