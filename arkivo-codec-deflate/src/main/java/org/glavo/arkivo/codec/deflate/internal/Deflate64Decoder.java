// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Provides the Deflate64 profile of the shared pure Java Deflate-family decoder.
@NotNullByDefault
public final class Deflate64Decoder implements CompressionDecoder {
    /// Shared grammar engine configured for Deflate64 semantics.
    private final DeflateDecoderEngine engine = new DeflateDecoderEngine(
            DeflateDecoderEngine.Format.DEFLATE64,
            null
    );

    /// Creates a raw Deflate64 decoder.
    public Deflate64Decoder() {
    }

    /// Decodes source bytes until input, output space, or the Deflate64 stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return engine.decode(source, target, endOfInput);
    }

    /// Abandons the current stream and restores empty Deflate64 history.
    @Override
    public void reset() {
        engine.reset();
    }

    /// Releases decoder-owned Java state without consuming additional input.
    @Override
    public void close() {
        engine.close();
    }
}
