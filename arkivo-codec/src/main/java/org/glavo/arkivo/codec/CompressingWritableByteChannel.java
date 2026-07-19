// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Accepts uncompressed bytes and writes encoded bytes to a backing channel.
///
/// Contexts are stateful and not safe for concurrent data operations. A write is a blocking codec operation: on success
/// it advances the source position by the returned count, and Arkivo-provided contexts consume every remaining source
/// byte. A nonempty operation that cannot make transport or codec progress fails with `IOException` instead of
/// busy-waiting.
///
/// Closing or finishing always releases the encoder. A borrowed backing channel remains open; an owned channel is closed
/// after final output is written. Finalization failure still makes this channel closed for writes, while an owned-target
/// close failure can be retried by calling [#finish()] or [#close()] again.
///
/// Contexts created by [CompressionCodec]'s default channel factories and
/// [org.glavo.arkivo.codec.internal.CodecChannelAdapters] implement [InterruptibleChannel] exactly when their backing target
/// does. For such a context, interrupting a thread during an encoding operation closes the context and target and reports
/// [ClosedByInterruptException]. Calling `close()` from another thread during an operation similarly closes the target
/// and makes that operation report [AsynchronousCloseException]. These terminal cancellations release encoder state
/// without emitting remaining final output and may therefore close even a borrowed target. An idle `close()` remains
/// graceful and follows the configured ownership policy.
@NotNullByDefault
public interface CompressingWritableByteChannel extends WritableByteChannel {
    /// Processes all remaining source bytes while keeping the active encoding open.
    ///
    /// On success the source position equals its original limit. If this method throws, its position and the byte
    /// counters report any progress completed before the failure.
    ///
    /// @param source the uncompressed bytes consumed from its current position to its limit
    /// @return this call's input and output progress with {@link CodecResult.Status#ACTIVE}
    /// @throws IOException if encoding or backing-channel output fails or makes no progress
    default CodecResult encode(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        long inputBefore = inputBytes();
        long outputBefore = outputBytes();
        while (source.hasRemaining()) {
            if (write(source) == 0) {
                throw new IOException("Compression encoder made no progress");
            }
        }
        return new CodecResult(
                inputBytes() - inputBefore,
                outputBytes() - outputBefore,
                CodecResult.Status.ACTIVE
        );
    }

    /// Finishes the complete encoding and releases encoder resources without necessarily closing the backing channel.
    ///
    /// The encoder is no longer open after finalization is attempted, even when this method throws. If closing an owned
    /// target fails, a later `finish()` or `close()` retries only target closure and does not emit the frame trailer again.
    ///
    /// @throws IOException if final output cannot be encoded or written, or an owned target cannot be closed
    void finish() throws IOException;

    /// Returns the cumulative number of uncompressed bytes accepted by this encoder.
    ///
    /// @return the cumulative uncompressed input byte count
    long inputBytes();

    /// Returns the cumulative number of compressed bytes written to the backing channel, including headers, flush data,
    /// frame boundaries, and trailers already emitted.
    ///
    /// @return the cumulative compressed output byte count
    long outputBytes();

    /// Finishes this encoder and retries incomplete owned-target closure when necessary.
    ///
    /// @throws IOException if finalization, output, or owned-target closure fails
    @Override
    void close() throws IOException;

    /// Writes a format that can flush pending output without ending the active encoding.
    @NotNullByDefault
    interface Flushable extends CompressingWritableByteChannel {
        /// Processes all source bytes and flushes pending output to a decodable boundary.
        ///
        /// The returned counts cover only this call, while [#inputBytes()] and [#outputBytes()] remain cumulative.
        ///
        /// @param source the uncompressed bytes consumed from its current position to its limit
        /// @return this call's progress with {@link CodecResult.Status#FLUSHED}
        /// @throws IOException if encoding, flushing, or backing-channel output fails or makes no progress
        default CodecResult flush(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            long inputBefore = inputBytes();
            long outputBefore = outputBytes();
            encode(source);
            flush();
            return new CodecResult(
                    inputBytes() - inputBefore,
                    outputBytes() - outputBefore,
                    CodecResult.Status.FLUSHED
            );
        }

        /// Flushes currently pending output to a decodable boundary without ending the active encoding.
        ///
        /// This method may block while the backing channel accepts generated bytes. It does not flush unrelated buffers
        /// maintained by the backing channel itself.
        ///
        /// @throws IOException if pending output cannot reach a decodable boundary or be written
        void flush() throws IOException;
    }

    /// Writes independently terminated frames while retaining the channel between frames.
    @NotNullByDefault
    interface Framed extends CompressingWritableByteChannel {
        /// Explicitly starts a frame after a completed frame boundary.
        ///
        /// The frame becomes active even if no source is subsequently written. A later `finishFrame` or terminal
        /// `finish` therefore emits an empty frame. Writing directly after a boundary instead starts a frame with
        /// [EncodingOptions#DEFAULT].
        ///
        /// @param options the parameters for the new frame
        /// @throws IOException if frame resources cannot be initialized or written
        /// @throws IllegalStateException if a frame is already active or the channel cannot start another frame
        void startFrame(EncodingOptions options) throws IOException;

        /// Explicitly starts a frame with default frame-scoped options.
        ///
        /// @throws IOException if frame resources cannot be initialized or written
        /// @throws IllegalStateException if a frame cannot be started in the current state
        default void startFrame() throws IOException {
            startFrame(EncodingOptions.DEFAULT);
        }

        /// Processes all source bytes and finishes the active frame.
        ///
        /// The returned counts cover only this call. A successful call leaves the channel open for another frame.
        ///
        /// @param source the uncompressed bytes consumed from its current position to its limit
        /// @return this call's progress with {@link CodecResult.Status#FRAME_FINISHED}
        /// @throws IOException if encoding, frame finalization, or backing-channel output fails or makes no progress
        default CodecResult finishFrame(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            long inputBefore = inputBytes();
            long outputBefore = outputBytes();
            encode(source);
            finishFrame();
            return new CodecResult(
                    inputBytes() - inputBefore,
                    outputBytes() - outputBefore,
                    CodecResult.Status.FRAME_FINISHED
            );
        }

        /// Finishes the active frame and leaves the channel ready for a following frame.
        ///
        /// Repeating this method before writing more source has no effect in Arkivo-provided contexts.
        ///
        /// @throws IOException if the frame trailer cannot be encoded or written
        void finishFrame() throws IOException;
    }

    /// Writes independently terminated frames and supports nonterminal flushing within each frame.
    @NotNullByDefault
    interface FlushableFramed extends Flushable, Framed {
    }
}
