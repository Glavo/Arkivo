// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.zstd;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.glavo.arkivo.compress.CompressionParameters;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests Zstandard codec behavior.
@NotNullByDefault
public final class ZstdCodecTest {
    /// Verifies that Zstandard compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(ZstdCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the Zstandard codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(ZstdCodec.class, Objects.requireNonNull(CompressionCodecs.find(ZstdCodec.NAME)).getClass());
    }

    /// Verifies Zstandard metadata and ByteBuffer one-shot compression.
    @Test
    public void byteBufferRoundTrip() throws IOException {
        ZstdCodec codec = new ZstdCodec();
        byte[] input = "hello zstd bytebuffer".getBytes(StandardCharsets.UTF_8);
        ByteBuffer source = ByteBuffer.allocateDirect(input.length);
        source.put(input).flip();
        ByteBuffer compressed = ByteBuffer.allocateDirect((int) codec.maxCompressedSize(input.length));

        assertEquals(true, codec.canCompressBuffers());
        assertEquals(true, codec.canDecompressBuffers());
        codec.compress(source, compressed, CompressionParameters.builder().compressionLevel(1).build());
        compressed.flip();
        assertEquals(true, codec.matches(compressed.duplicate()));

        ByteBuffer decompressed = ByteBuffer.allocateDirect(input.length);
        codec.decompress(compressed, decompressed, CompressionParameters.defaults());
        decompressed.flip();

        byte[] output = new byte[decompressed.remaining()];
        decompressed.get(output);
        assertArrayEquals(input, output);
    }

    /// Compresses and decompresses the given bytes.
    private static byte[] roundTrip(CompressionCodec codec, byte[] input) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (OutputStream output = codec.compressTo(compressed)) {
            output.write(input);
        }

        try (InputStream inputStream = codec.decompressFrom(new ByteArrayInputStream(compressed.toByteArray()))) {
            return inputStream.readAllBytes();
        }
    }
}
