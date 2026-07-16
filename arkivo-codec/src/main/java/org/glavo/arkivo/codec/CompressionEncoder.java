// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally encodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. Buffer positions are the authoritative record of bytes consumed and produced.
@NotNullByDefault
public interface CompressionEncoder extends AutoCloseable {
    /// Consumes uncompressed source bytes and produces encoded bytes until either buffer prevents further progress.
    ///
    /// @return `CodecOutcome.NEEDS_INPUT` when all source bytes were consumed, or
    /// `CodecOutcome.NEEDS_OUTPUT` when the target was filled first
    CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Finishes the current encoding and emits its remaining payload and trailer.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
    ///
    /// @return `CodecOutcome.FINISHED` when finalization completed, or
    /// `CodecOutcome.NEEDS_OUTPUT` otherwise
    CodecOutcome finish(ByteBuffer target) throws IOException;

    /// Abandons the current encoding and restores the encoder's original immutable configuration.
    void reset();

    /// Releases encoder resources without implicitly finishing the current encoding.
    @Override
    void close();
}
