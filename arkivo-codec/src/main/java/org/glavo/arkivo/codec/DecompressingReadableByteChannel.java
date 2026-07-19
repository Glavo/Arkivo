// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads compressed bytes from a backing channel and exposes decoded bytes.
///
/// Contexts are stateful and not safe for concurrent data operations. A read may block while obtaining compressed input
/// and advances the target position by the positive count it returns; an empty target returns zero without reading the
/// source. Arkivo-provided contexts fail with `IOException` when a nonempty operation cannot make codec or transport
/// progress.
///
/// Closing always releases decoder state. A borrowed backing source remains open; an owned source is closed. Closing does
/// not drain compressed input or verify that the current encoding is complete.
///
/// Contexts created by [CompressionCodec]'s default channel factories and
/// [org.glavo.arkivo.codec.internal.CodecChannelAdapters] implement [InterruptibleChannel] exactly when their backing source
/// does. For such a context, interrupting a thread during decoding closes the context and source and reports
/// [ClosedByInterruptException]. Calling `close()` from another thread during an operation similarly closes the source
/// and makes that operation report [AsynchronousCloseException]. These terminal cancellations may close even a borrowed
/// source. An idle `close()` follows the configured ownership policy.
@NotNullByDefault
public interface DecompressingReadableByteChannel extends ReadableByteChannel {
    /// Decodes bytes into the target and reports progress and end-of-input state.
    ///
    /// The target position is advanced by `result.outputBytes()`. Input and output counts in the result cover only this
    /// call; the channel counter methods remain cumulative. `END_OF_INPUT` may accompany final output bytes.
    ///
    /// @param target the destination for decoded bytes
    /// @return this call's progress and active or end-of-input status
    /// @throws IOException if compressed input cannot be read or decoded
    default CodecResult decode(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        long inputBefore = inputBytes();
        long outputBefore = outputBytes();
        int read = read(target);
        CodecResult.Status status = read < 0 ? CodecResult.Status.END_OF_INPUT : CodecResult.Status.ACTIVE;
        return new CodecResult(inputBytes() - inputBefore, outputBytes() - outputBefore, status);
    }

    /// Returns the cumulative number of compressed bytes logically consumed by the decoder.
    ///
    /// @return the cumulative logically consumed compressed byte count
    long inputBytes();

    /// Returns the cumulative number of compressed bytes obtained from the backing source, including buffered read-ahead.
    ///
    /// The returned value is never less than inputBytes(). Decoders without observable read-ahead return inputBytes().
    ///
    /// @return the cumulative compressed byte count obtained from the backing source
    default long sourceBytes() {
        return inputBytes();
    }

    /// Returns a read-only view of source bytes obtained but not logically consumed.
    ///
    /// The remaining byte count equals sourceBytes() minus inputBytes(). The view's position and limit are independent,
    /// but its content is valid only until the decoder performs another read or decode operation.
    ///
    /// @return a read-only transient view of buffered compressed read-ahead
    default @UnmodifiableView ByteBuffer unconsumedInput() {
        return ByteBuffer.allocate(0).asReadOnlyBuffer();
    }

    /// Returns the cumulative number of uncompressed bytes returned to callers.
    ///
    /// @return the cumulative decoded output byte count
    long outputBytes();

    /// Releases decoder resources and closes an owned source.
    ///
    /// The decoder is no longer open after release is attempted. If owned-source closure fails, a later `close()` retries
    /// only source closure and does not release decoder state again.
    ///
    /// @throws IOException if decoder release or owned-source closure fails
    @Override
    void close() throws IOException;

    /// Reads concatenated frames and can stop explicitly at each frame boundary.
    @NotNullByDefault
    interface Framed extends DecompressingReadableByteChannel {
        /// Decodes without beginning a following frame after the current frame completes.
        ///
        /// `FRAME_FINISHED` identifies the boundary even when the call also produced bytes. A later call begins the next
        /// frame if compressed input remains; `END_OF_INPUT` reports that no following frame is available. The target
        /// position and per-call counters follow [DecompressingReadableByteChannel#decode(ByteBuffer)].
        ///
        /// @param target the destination for decoded bytes
        /// @return this call's progress and active, frame-finished, or end-of-input status
        /// @throws IOException if compressed input cannot be read or decoded
        CodecResult decodeFrame(ByteBuffer target) throws IOException;
    }
}
