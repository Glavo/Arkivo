// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.internal.deflate.DeflateEncoderEngine;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Exposes the shared pure Java Deflate encoder under the Deflate64 module's internal type.
@NotNullByDefault
public final class Deflate64Encoder implements CompressionEncoder {
    /// Shared format-parameterized encoder implementation.
    private final DeflateEncoderEngine engine;

    /// Creates an encoder at the requested compression level from zero through nine.
    ///
    /// @param compressionLevel bounded match-search level
    public Deflate64Encoder(int compressionLevel) {
        this.engine = new DeflateEncoderEngine(
                DeflateEncoderEngine.Format.DEFLATE64,
                compressionLevel,
                null,
                CompressionStrategy.DEFAULT
        );
    }

    /// Encodes source bytes until the source or target is exhausted.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        return engine.encode(source, target);
    }

    /// Flushes pending Deflate64 output without ending the stream.
    @Override
    public CodecOutcome flush(ByteBuffer target) {
        return engine.flush(target);
    }

    /// Finishes the Deflate64 stream without releasing encoder-owned state.
    @Override
    public CodecOutcome finish(ByteBuffer target) {
        return engine.finish(target);
    }

    /// Abandons the current stream and restores a fresh Deflate64 session.
    @Override
    public void reset() {
        engine.reset();
    }

    /// Releases encoder-owned stream state without finishing pending data.
    @Override
    public void close() {
        engine.close();
    }
}