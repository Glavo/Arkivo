// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies channel context progress, ownership, transfer, and option contracts.
@NotNullByDefault
final class CompressionChannelContextTest {
    /// The identity codec used to isolate generic channel behavior.
    private static final CompressionCodec CODEC = new IdentityCodec();

    /// Verifies explicit encoder directives and retained target ownership.
    @Test
    void encodesIncrementallyAndRetainsTargetByDefault() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(bytes);
        CompressionEncoder encoder = CODEC.openEncoder(target);

        CodecResult flushed = encoder.encode(ByteBuffer.wrap(new byte[]{1, 2, 3}), EncodeDirective.FLUSH);
        CodecResult finished = encoder.encode(ByteBuffer.wrap(new byte[]{4}), EncodeDirective.END_FRAME);

        assertEquals(new CodecResult(3, 3, CodecStatus.FLUSHED), flushed);
        assertEquals(new CodecResult(1, 1, CodecStatus.FRAME_FINISHED), finished);
        assertEquals(4, encoder.inputBytes());
        assertEquals(4, encoder.outputBytes());
        assertFalse(encoder.isOpen());
        assertTrue(target.isOpen());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes.toByteArray());
    }

    /// Verifies decoder progress and retained source ownership.
    @Test
    void decodesIncrementallyAndRetainsSourceByDefault() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{5, 6, 7}));
        ByteBuffer output = ByteBuffer.allocate(3);
        CompressionDecoder decoder = CODEC.openDecoder(source);

        CodecResult decoded = decoder.decode(output);
        CodecResult ended = decoder.decode(ByteBuffer.allocate(1));
        decoder.close();

        assertEquals(new CodecResult(3, 3, CodecStatus.ACTIVE), decoded);
        assertEquals(new CodecResult(0, 0, CodecStatus.END_OF_INPUT), ended);
        assertTrue(source.isOpen());
        assertArrayEquals(new byte[]{5, 6, 7}, output.array());
    }

    /// Verifies explicit channel ownership closes backing channels.
    @Test
    void closesOwnedBackingChannels() throws IOException {
        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());
        try (CompressionEncoder encoder = CODEC.openEncoder(
                target,
                CodecOptions.EMPTY,
                ChannelOwnership.CLOSE
        )) {
            encoder.encode(ByteBuffer.wrap(new byte[]{1}), EncodeDirective.END_FRAME);
        }
        assertFalse(target.isOpen());

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{1}));
        try (CompressionDecoder decoder = CODEC.openDecoder(
                source,
                CodecOptions.EMPTY,
                ChannelOwnership.CLOSE
        )) {
            decoder.decode(ByteBuffer.allocate(1));
        }
        assertFalse(source.isOpen());
    }

    /// Verifies blocking channel transfers report counts and retain both endpoints.
    @Test
    void transfersBetweenChannelsWithoutTakingOwnership() throws IOException {
        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[]{8, 9, 10}));
        ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
        WritableByteChannel target = Channels.newChannel(compressedBytes);

        CodecTransferResult compressed = CODEC.compress(source, target);

        assertEquals(new CodecTransferResult(3, 3), compressed);
        assertTrue(source.isOpen());
        assertTrue(target.isOpen());

        ReadableByteChannel encoded = Channels.newChannel(
                new ByteArrayInputStream(compressedBytes.toByteArray())
        );
        ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
        WritableByteChannel decoded = Channels.newChannel(decodedBytes);
        CodecTransferResult decompressed = CODEC.decompress(encoded, decoded);

        assertEquals(new CodecTransferResult(3, 3), decompressed);
        assertTrue(encoded.isOpen());
        assertTrue(decoded.isOpen());
        assertArrayEquals(new byte[]{8, 9, 10}, decodedBytes.toByteArray());
    }

    /// Verifies unsupported typed options fail before a context is opened.
    @Test
    void rejectsUnsupportedOptions() {
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, new CompressionLevel(3))
                .build();
        WritableByteChannel target = Channels.newChannel(new ByteArrayOutputStream());

        assertThrows(
                UnsupportedOperationException.class,
                () -> CODEC.openEncoder(target, options, ChannelOwnership.RETAIN)
        );
        assertTrue(target.isOpen());
    }

    /// Implements identity coding through the stream-provider compatibility SPI.
    @NotNullByDefault
    private static final class IdentityCodec implements CompressionCodec {
        /// The identity codec capabilities.
        private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
                CompressionFeature.COMPRESSION,
                CompressionFeature.DECOMPRESSION,
                CompressionFeature.ONE_SHOT_COMPRESSION,
                CompressionFeature.ONE_SHOT_DECOMPRESSION,
                CompressionFeature.FLUSH
        ));

        /// Creates an identity codec.
        private IdentityCodec() {
        }

        /// Returns the identity codec name.
        @Override
        public String name() {
            return "identity";
        }

        /// Returns identity codec capabilities.
        @Override
        public CompressionCapabilities capabilities() {
            return CAPABILITIES;
        }

        /// Opens an identity encoder.
        @Override
        public CompressionEncoder openEncoder(
                WritableByteChannel target,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.compressionOptions(), "identity compression");
            return StreamCodecAdapters.openEncoder(target, ownership, output -> output);
        }

        /// Opens an identity decoder.
        @Override
        public CompressionDecoder openDecoder(
                ReadableByteChannel source,
                CodecOptions options,
                ChannelOwnership ownership
        ) throws IOException {
            options.requireSupported(CAPABILITIES.decompressionOptions(), "identity decompression");
            return StreamCodecAdapters.openDecoder(source, ownership, input -> input);
        }
    }
}
