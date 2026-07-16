// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Provides the raw Deflate profile of the shared pure Java Deflate-family decoder.
@NotNullByDefault
public final class DeflateDecoder implements CompressionDecoder {
    /// Shared grammar engine configured for RFC 1951 semantics.
    private final DeflateDecoderEngine engine;

    /// Creates a raw Deflate decoder with an optional preset dictionary.
    ///
    /// @param dictionary preset history content, or null
    public DeflateDecoder(@Nullable RawCompressionDictionary dictionary) {
        engine = new DeflateDecoderEngine(
                DeflateDecoderEngine.Format.DEFLATE,
                dictionary != null ? dictionary.bytes() : null
        );
    }

    /// Decodes source bytes until input, output space, or the raw stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        return engine.decode(source, target, endOfInput);
    }

    /// Abandons the current stream and restores configured dictionary history.
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
