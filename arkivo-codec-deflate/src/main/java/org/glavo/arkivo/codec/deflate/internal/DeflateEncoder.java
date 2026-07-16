// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.RawCompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Exposes the shared pure Java Deflate encoder under the raw Deflate module's internal type.
@NotNullByDefault
public final class DeflateEncoder implements CompressionEncoder.Flushable {
    /// Shared format-parameterized encoder implementation.
    private final DeflateEncoderEngine engine;

    /// Creates a raw Deflate encoder with immutable stream configuration.
    ///
    /// @param compressionLevel bounded match-search level from zero through nine
    /// @param dictionary preset dictionary, or null
    /// @param strategy compression strategy
    public DeflateEncoder(
            int compressionLevel,
            @Nullable RawCompressionDictionary dictionary,
            CompressionStrategy strategy
    ) {
        this.engine = new DeflateEncoderEngine(
                DeflateEncoderEngine.Format.DEFLATE,
                compressionLevel,
                dictionary != null ? dictionary.bytes() : null,
                strategy
        );
    }

    /// Encodes source bytes until the source or target is exhausted.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        return engine.encode(source, target);
    }

    /// Flushes pending raw Deflate output without ending the stream.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        return engine.flush(target);
    }

    /// Finishes the raw Deflate stream without releasing encoder-owned state.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        return engine.finish(target);
    }

    /// Abandons the current stream and restores the configured initial history.
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