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
    /// Processes all remaining source bytes while keeping the active encoding open.
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
    void finish() throws IOException;

    /// Returns the number of uncompressed bytes accepted by this encoder.
    long inputBytes();

    /// Returns the number of compressed bytes written to the backing channel.
    long outputBytes();

    /// Finishes this encoder and retries incomplete owned-target closure when necessary.
    @Override
    void close() throws IOException;

    /// Writes a format that can flush pending output without ending the active encoding.
    @NotNullByDefault
    interface Flushable extends CompressingWritableByteChannel {
        /// Processes all source bytes and flushes pending output to a decodable boundary.
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
        void flush() throws IOException;
    }

    /// Writes independently terminated frames while retaining the channel between frames.
    @NotNullByDefault
    interface Framed extends CompressingWritableByteChannel {
        /// Processes all source bytes and finishes the active frame.
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
        void finishFrame() throws IOException;
    }

    /// Writes independently terminated frames and supports nonterminal flushing within each frame.
    @NotNullByDefault
    interface FlushableFramed extends Flushable, Framed {
    }
}
