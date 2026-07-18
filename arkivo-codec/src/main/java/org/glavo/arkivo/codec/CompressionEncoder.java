// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally encodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. Successful operations advance the source position by the bytes consumed and the
/// target position by the bytes produced; they do not change either limit or byte order. Buffer positions are the
/// authoritative record of progress, including when an operation throws after making partial progress. Every target
/// must be writable, and a two-buffer operation requires different source and target instances. These are caller
/// preconditions: implementations are not required to reject a read-only target or the same source and target instance
/// before making progress.
///
/// Submit source with [#encode(ByteBuffer, ByteBuffer)] until it reports that more input is needed, then call
/// [#finish(ByteBuffer)] after the final source byte. Once finishing starts, repeat only `finish` until it returns
/// [CodecOutcome#FINISHED]. [#reset()] abandons or reinitializes a stream for reuse; [#close()] releases the engine and
/// never emits an implicit trailer.
///
/// A complete buffer-driven encoding loop, with `source` holding all input and `sink` a blocking channel, is:
///
/// ```java
/// try (CompressionEncoder encoder = codec.newEncoder()) {
///     ByteBuffer output = ByteBuffer.allocate(8192);
///     while (source.hasRemaining()) {
///         if (encoder.encode(source, output) == CodecOutcome.NEEDS_OUTPUT) {
///             output.flip();
///             while (output.hasRemaining()) {
///                 sink.write(output);
///             }
///             output.compact();
///         }
///     }
///     while (true) {
///         CodecOutcome outcome = encoder.finish(output);
///         output.flip();
///         while (output.hasRemaining()) {
///             sink.write(output);
///         }
///         output.compact();
///         if (outcome == CodecOutcome.FINISHED) {
///             break;
///         }
///     }
/// }
/// ```
@NotNullByDefault
public interface CompressionEncoder extends AutoCloseable {
    /// Consumes uncompressed source bytes and produces encoded bytes until either buffer prevents further progress.
    ///
    /// A `NEEDS_INPUT` result means the source position reached its limit. A `NEEDS_OUTPUT` result means the target
    /// position reached its limit while the encoder still has work to perform; supply target space and call this method
    /// again with the source in its resulting state.
    ///
    /// @param source the uncompressed input buffer
    /// @param target the distinct writable encoded-output buffer
    /// @return `CodecOutcome.NEEDS_INPUT` when all source bytes were consumed, or
    /// `CodecOutcome.NEEDS_OUTPUT` when the target was filled first
    /// @throws IOException if the source cannot be encoded
    CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Finishes the current encoding and emits its remaining payload and trailer.
    ///
    /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`. The target position is
    /// advanced by emitted bytes and its limit is unchanged. No further source can be submitted until [#reset()].
    ///
    /// @param target the writable encoded-output buffer
    /// @return `CodecOutcome.FINISHED` when finalization completed, or
    /// `CodecOutcome.NEEDS_OUTPUT` otherwise
    /// @throws IOException if final payload or trailer generation fails
    CodecOutcome finish(ByteBuffer target) throws IOException;

    /// Abandons the current encoding and restores the encoder's original immutable configuration.
    ///
    /// No pending payload, flush boundary, or trailer is emitted.
    void reset();

    /// Releases encoder resources without implicitly finishing the current encoding.
    ///
    /// The encoder cannot be reused after this method returns.
    @Override
    void close();

    /// Encodes a format that can expose a decodable boundary without ending the active encoding.
    @NotNullByDefault
    interface Flushable extends CompressionEncoder {
        /// Emits all currently pending output to a decodable boundary without ending the encoding.
        ///
        /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`. After `FLUSHED`,
        /// source may again be submitted to the same encoding.
        ///
        /// @param target the writable encoded-output buffer
        /// @return `CodecOutcome.FLUSHED` when flushing completed, or `CodecOutcome.NEEDS_OUTPUT` otherwise
        /// @throws IOException if the decodable boundary cannot be generated
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
        /// Callers repeat this operation with fresh target space after `CodecOutcome.NEEDS_OUTPUT`. After
        /// `BOUNDARY_REACHED`, a subsequent [#encode(ByteBuffer, ByteBuffer)] begins the following frame.
        ///
        /// @param target the writable encoded-output buffer
        /// @return `CodecOutcome.BOUNDARY_REACHED` when the frame boundary completed, or
        /// `CodecOutcome.NEEDS_OUTPUT` otherwise
        /// @throws IOException if the current frame cannot be finalized
        CodecOutcome finishFrame(ByteBuffer target) throws IOException;
    }

    /// Encodes independently terminated frames and supports nonterminal flushing within each active frame.
    @NotNullByDefault
    interface FlushableFramed extends Framed, Flushable {
    }
}
