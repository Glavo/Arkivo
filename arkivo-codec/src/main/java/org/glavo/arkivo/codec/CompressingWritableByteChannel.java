// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Accepts uncompressed bytes and writes encoded bytes to a backing channel.
@NotNullByDefault
public interface CompressingWritableByteChannel extends WritableByteChannel {
    /// Processes all remaining source bytes and applies the requested frame directive.
    default CodecResult encode(ByteBuffer source, EncodeDirective directive) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(directive, "directive");
        long inputBefore = inputBytes();
        long outputBefore = outputBytes();
        while (source.hasRemaining()) {
            if (write(source) == 0) {
                throw new IOException("Compression encoder made no progress");
            }
        }

        CodecStatus status;
        if (directive == EncodeDirective.FLUSH) {
            flush();
            status = CodecStatus.FLUSHED;
        } else if (directive == EncodeDirective.END_FRAME) {
            finishFrame();
            status = CodecStatus.FRAME_FINISHED;
        } else {
            status = CodecStatus.ACTIVE;
        }
        return new CodecResult(inputBytes() - inputBefore, outputBytes() - outputBefore, status);
    }

    /// Flushes currently pending output without ending the current frame.
    ///
    /// This operation throws UnsupportedOperationException when the engine is not a FlushableCompressionEncoder.
    void flush() throws IOException;

    /// Finishes the current frame.
    ///
    /// A FramedCompressionEncoder remains open and starts another frame lazily.
    /// Other encoders finish and release their resources.
    default void finishFrame() throws IOException {
        finish();
    }

    /// Finishes the active frame and releases encoder resources without necessarily closing the backing channel.
    ///
    /// The encoder is no longer open after finalization is attempted, even when this method throws. If closing an owned
    /// target fails, a later `finish()` or `close()` retries only target closure and does not emit the frame trailer again.
    void finish() throws IOException;

    /// Returns the number of uncompressed bytes accepted by this encoder.
    long inputBytes();

    /// Returns the number of compressed bytes written to the backing channel.
    long outputBytes();

    /// Finishes this encoder and retries incomplete owned-target closure when necessary.
    @Override
    void close() throws IOException;
}
