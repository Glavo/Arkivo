// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally encodes one compression frame between caller-owned byte buffers.
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

    /// Emits all currently pending output to a decodable boundary without ending the frame.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
    ///
    /// @return `CodecOutcome.FLUSHED` when flushing completed, or `CodecOutcome.NEEDS_OUTPUT` otherwise
    CodecOutcome flush(ByteBuffer target) throws IOException;

    /// Finishes the current frame and emits its remaining payload and trailer.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
    ///
    /// @return `CodecOutcome.FRAME_FINISHED` when finalization completed, or
    /// `CodecOutcome.NEEDS_OUTPUT` otherwise
    CodecOutcome finishFrame(ByteBuffer target) throws IOException;

    /// Abandons the current frame and restores the encoder's original immutable configuration.
    void reset();

    /// Releases encoder resources without implicitly finishing the current frame.
    @Override
    void close();
}
