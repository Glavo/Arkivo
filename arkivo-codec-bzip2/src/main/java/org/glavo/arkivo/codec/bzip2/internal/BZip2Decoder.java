// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally decodes one BZip2 frame without retaining caller-owned buffers.
///
/// Incomplete compressed blocks are retained only as owned bytes. The shared pure Java decoder parses each block
/// transactionally and returns any compressed read-ahead to the current caller buffer after a successful boundary.
@NotNullByDefault
public final class BZip2Decoder extends BZip2ChannelDecoder implements CompressionDecoder.Framed {
    /// Creates an empty BZip2 decoder with no attached transport.
    public BZip2Decoder() {
        super();
    }

    /// Decodes compressed bytes until input, output space, or the frame boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Finishes decoding after all source bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Implements decoding with the selected source-completion state.
    private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        return decodeBuffers(source, target, endOfInput);
    }

    /// Abandons the current frame and restores the initial decoder state.
    @Override
    public void reset() {
        resetBuffers();
    }

    /// Releases decoder-owned state without consuming additional input.
    @Override
    public void close() {
        closeBuffers();
    }
}
