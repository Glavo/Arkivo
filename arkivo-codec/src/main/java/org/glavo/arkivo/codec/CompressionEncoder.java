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

    /// Encodes a format that can expose a decodable boundary without ending the active encoding.
    @NotNullByDefault
    interface Flushable extends CompressionEncoder {
        /// Emits all currently pending output to a decodable boundary without ending the encoding.
        ///
        /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
        ///
        /// @return `CodecOutcome.FLUSHED` when flushing completed, or `CodecOutcome.NEEDS_OUTPUT` otherwise
        CodecOutcome flush(ByteBuffer target) throws IOException;
    }

    /// Incrementally encodes a sequence of independently terminated compression frames.
    ///
    /// Completing a frame preserves immutable encoder configuration and leaves the encoder ready to accept source bytes
    /// for a following frame. The following frame may be initialized lazily. Calling terminal `finish` before accepting
    /// more source bytes must end the complete encoding without emitting an additional empty frame.
    @NotNullByDefault
    interface Framed extends CompressionEncoder {
        /// Finishes the current frame without finishing the complete encoding session.
        ///
        /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`.
        ///
        /// @return `CodecOutcome.BOUNDARY_REACHED` when the frame boundary completed, or
        /// `CodecOutcome.NEEDS_OUTPUT` otherwise
        CodecOutcome finishFrame(ByteBuffer target) throws IOException;
    }

    /// Encodes independently terminated frames and supports nonterminal flushing within each active frame.
    @NotNullByDefault
    interface FlushableFramed extends Framed, Flushable {
    }
}
