// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Deflate strategies across raw Deflate, gzip, and zlib encoders.
@NotNullByDefault
final class DeflateStrategyTest {
    /// Verifies strategy configuration remains immutable and observable on each concrete codec.
    @Test
    void exposesStrategyOnEveryDeflateContainer() {
        for (CompressionCodec<?> codec : codecs()) {
            assertSame(codec, withStrategy(codec, DeflateStrategy.DEFAULT));

            CompressionCodec<?> configured = withStrategy(codec, DeflateStrategy.FILTERED);
            assertEquals(DeflateStrategy.FILTERED, strategyOf(configured));
            assertEquals(DeflateStrategy.DEFAULT, strategyOf(codec));
        }
    }

    /// Verifies every strategy produces interoperable output for every Deflate container.
    @Test
    void appliesEveryStrategyToEveryDeflateContainer() throws IOException {
        byte @Unmodifiable [] input = (
                "Arkivo Deflate strategy repeated payload 0123456789abcdef;"
        ).repeat(4_096).getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec<?> codec : codecs()) {
            int defaultSize = 0;
            int huffmanOnlySize = 0;
            for (DeflateStrategy strategy : DeflateStrategy.values()) {
                CompressionCodec<?> configured = withStrategy(codec, strategy);
                ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
                configured.compress(
                        Channels.newChannel(new ByteArrayInputStream(input)),
                        Channels.newChannel(compressedBytes)
                );

                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                configured.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                assertArrayEquals(input, decodedBytes.toByteArray(), codec.format().name() + ": " + strategy);
                if (strategy == DeflateStrategy.DEFAULT) {
                    defaultSize = compressedBytes.size();
                } else if (strategy == DeflateStrategy.HUFFMAN_ONLY) {
                    huffmanOnlySize = compressedBytes.size();
                }
            }
            assertTrue(defaultSize > 0, codec.format().name());
            assertTrue(huffmanOnlySize > defaultSize, codec.format().name());
        }
    }

    /// Verifies non-default strategies can finish empty streams through every Deflate container.
    @Test
    void finishesEmptyStreamsWithNonDefaultStrategies() throws IOException {
        for (CompressionCodec<?> codec : codecs()) {
            for (DeflateStrategy strategy : List.of(DeflateStrategy.FILTERED, DeflateStrategy.HUFFMAN_ONLY)) {
                CompressionCodec<?> configured = withStrategy(codec, strategy);
                ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
                try (CompressingWritableByteChannel encoder = configured.newWritableByteChannel(
                        Channels.newChannel(compressedBytes),
                        EncodingOptions.DEFAULT,
                        ResourceOwnership.BORROWED
                )) {
                    if (strategy == DeflateStrategy.HUFFMAN_ONLY) {
                        ((CompressingWritableByteChannel.Flushable) encoder).flush();
                    }
                    encoder.finish();
                }

                ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
                configured.decompress(
                        Channels.newChannel(new ByteArrayInputStream(compressedBytes.toByteArray())),
                        Channels.newChannel(decodedBytes)
                );
                assertEquals(0, decodedBytes.size(), codec.format().name() + ": " + strategy);
            }
        }
    }

    /// Returns the default configurations for every Deflate container.
    private static @Unmodifiable List<CompressionCodec<?>> codecs() {
        return List.of(DeflateCodec.DEFAULT, GzipCodec.DEFAULT, ZlibCodec.DEFAULT);
    }

    /// Applies a Deflate strategy while preserving the concrete codec family.
    private static CompressionCodec<?> withStrategy(CompressionCodec<?> codec, DeflateStrategy strategy) {
        if (codec instanceof DeflateCodec deflateCodec) {
            return deflateCodec.withStrategy(strategy);
        }
        if (codec instanceof GzipCodec gzipCodec) {
            return gzipCodec.withStrategy(strategy);
        }
        if (codec instanceof ZlibCodec zlibCodec) {
            return zlibCodec.withStrategy(strategy);
        }
        throw new AssertionError("Unexpected Deflate codec: " + codec.getClass().getName());
    }

    /// Reads the Deflate strategy while preserving concrete codec APIs.
    private static DeflateStrategy strategyOf(CompressionCodec<?> codec) {
        if (codec instanceof DeflateCodec deflateCodec) {
            return deflateCodec.strategy();
        }
        if (codec instanceof GzipCodec gzipCodec) {
            return gzipCodec.strategy();
        }
        if (codec instanceof ZlibCodec zlibCodec) {
            return zlibCodec.strategy();
        }
        throw new AssertionError("Unexpected Deflate codec: " + codec.getClass().getName());
    }
}
