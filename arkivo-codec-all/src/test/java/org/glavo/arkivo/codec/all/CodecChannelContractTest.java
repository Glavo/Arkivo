// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionFeature;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the first-class channel contract across all installed codec providers.
@NotNullByDefault
final class CodecChannelContractTest {
    /// Verifies channel transfer, counters, endpoint ownership, and capability consistency.
    @Test
    void roundTripsEveryBidirectionalCodecThroughChannels() throws IOException {
        byte[] input = ("Arkivo channel codec contract: " + "0123456789".repeat(64))
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertEquals(
                    codec.capabilities().supports(CompressionFeature.COMPRESSION),
                    codec.canCompress(),
                    codec.name()
            );
            assertEquals(
                    codec.capabilities().supports(CompressionFeature.DECOMPRESSION),
                    codec.canDecompress(),
                    codec.name()
            );
            if (!codec.canCompress() || !codec.canDecompress()) {
                continue;
            }

            ByteArrayOutputStream compressedBytes = new ByteArrayOutputStream();
            ReadableByteChannel uncompressedSource = Channels.newChannel(new ByteArrayInputStream(input));
            WritableByteChannel compressedTarget = Channels.newChannel(compressedBytes);
            CodecTransferResult compression = codec.compress(uncompressedSource, compressedTarget);

            assertEquals(input.length, compression.inputBytes(), codec.name());
            assertEquals(compressedBytes.size(), compression.outputBytes(), codec.name());
            assertTrue(uncompressedSource.isOpen(), codec.name());
            assertTrue(compressedTarget.isOpen(), codec.name());

            ByteArrayOutputStream decodedBytes = new ByteArrayOutputStream();
            ReadableByteChannel compressedSource = Channels.newChannel(
                    new ByteArrayInputStream(compressedBytes.toByteArray())
            );
            WritableByteChannel decodedTarget = Channels.newChannel(decodedBytes);
            CodecTransferResult decompression = codec.decompress(compressedSource, decodedTarget);

            assertEquals(input.length, decompression.outputBytes(), codec.name());
            assertTrue(compressedSource.isOpen(), codec.name());
            assertTrue(decodedTarget.isOpen(), codec.name());
            assertArrayEquals(input, decodedBytes.toByteArray(), codec.name());
        }
    }
}
