// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Reads compressed bytes from a backing channel and exposes decoded bytes.
@NotNullByDefault
public interface DecompressingReadableByteChannel extends ReadableByteChannel {
    /// Decodes bytes into the target and reports progress and end-of-input state.
    default CodecResult decode(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        long inputBefore = inputBytes();
        long outputBefore = outputBytes();
        int read = read(target);
        CodecResult.Status status = read < 0 ? CodecResult.Status.END_OF_INPUT : CodecResult.Status.ACTIVE;
        return new CodecResult(inputBytes() - inputBefore, outputBytes() - outputBefore, status);
    }

    /// Returns the number of compressed bytes logically consumed by the decoder.
    long inputBytes();

    /// Returns the number of compressed bytes obtained from the backing source, including buffered read-ahead.
    ///
    /// The returned value is never less than inputBytes(). Decoders without observable read-ahead return inputBytes().
    default long sourceBytes() {
        return inputBytes();
    }

    /// Returns a read-only view of source bytes obtained but not logically consumed.
    ///
    /// The remaining byte count equals sourceBytes() minus inputBytes(). The view's position and limit are independent,
    /// but its content is valid only until the decoder performs another read or decode operation.
    default @UnmodifiableView ByteBuffer unconsumedInput() {
        return ByteBuffer.allocate(0).asReadOnlyBuffer();
    }

    /// Returns the number of uncompressed bytes returned to callers.
    long outputBytes();

    /// Releases decoder resources and closes an owned source.
    ///
    /// The decoder is no longer open after release is attempted. If owned-source closure fails, a later `close()` retries
    /// only source closure and does not release decoder state again.
    @Override
    void close() throws IOException;

    /// Reads concatenated frames and can stop explicitly at each frame boundary.
    @NotNullByDefault
    interface Framed extends DecompressingReadableByteChannel {
        /// Decodes without beginning a following frame after the current frame completes.
        CodecResult decodeFrame(ByteBuffer target) throws IOException;
    }
}
