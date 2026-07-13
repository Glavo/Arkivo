// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.DecompressionLimitException;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the public raw PPMd7 codec contract.
@NotNullByDefault
final class PPMdCodecTest {
    /// Verifies service discovery and the decompression-only capability surface.
    @Test
    void exposesDecompressionCapabilities() {
        PPMdCodec codec = new PPMdCodec();

        assertFalse(codec.canCompress());
        assertTrue(codec.canDecompress());
        assertTrue(codec.capabilities().supports(CompressionFeature.DIRECT_BYTE_BUFFER));
        assertNotNull(CompressionCodecs.find(PPMdCodec.NAME));
        assertNotNull(CompressionCodecs.find("ppmd7"));
    }

    /// Verifies required model options, output limits, counters, and source ownership.
    @Test
    void validatesRawParametersAndHonorsOwnership() throws IOException {
        PPMdCodec codec = new PPMdCodec();
        ReadableByteChannel missingOptions = Channels.newChannel(new ByteArrayInputStream(new byte[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.openDecoder(missingOptions, CodecOptions.EMPTY, ChannelOwnership.RETAIN)
        );

        CodecOptions limited = CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 4L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 1L << 20)
                .set(PPMdCodecOptions.DECODED_SIZE, 1L)
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, 0L)
                .build();
        assertThrows(
                DecompressionLimitException.class,
                () -> codec.openDecoder(
                        Channels.newChannel(new ByteArrayInputStream(new byte[0])),
                        limited,
                        ChannelOwnership.RETAIN
                )
        );

        ReadableByteChannel source = Channels.newChannel(new ByteArrayInputStream(new byte[5]));
        try (CompressionDecoder decoder = codec.openDecoder(
                source,
                options(0L),
                ChannelOwnership.CLOSE
        )) {
            assertEquals(-1, decoder.read(ByteBuffer.allocateDirect(1)));
            assertEquals(5L, decoder.inputBytes());
            assertEquals(0L, decoder.outputBytes());
        }
        assertFalse(source.isOpen());
    }

    /// Creates valid raw PPMd7 options for the given exact decoded size.
    private static CodecOptions options(long decodedSize) {
        return CodecOptions.builder()
                .set(PPMdCodecOptions.MAXIMUM_ORDER, 4L)
                .set(PPMdCodecOptions.MEMORY_SIZE, 1L << 20)
                .set(PPMdCodecOptions.DECODED_SIZE, decodedSize)
                .build();
    }
}
