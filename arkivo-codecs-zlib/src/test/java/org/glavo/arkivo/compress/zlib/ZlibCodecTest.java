// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.zlib;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
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

/// Tests zlib codec behavior.
@NotNullByDefault
public final class ZlibCodecTest {
    /// Verifies that zlib compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        ZlibCodec codec = new ZlibCodec();
        byte[] input = "hello zlib".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(ZlibCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the zlib codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(ZlibCodec.class, Objects.requireNonNull(CompressionCodecs.find(ZlibCodec.NAME)).getClass());
    }

    /// Verifies zlib header matching.
    @Test
    public void metadata() {
        ZlibCodec codec = new ZlibCodec();
        assertEquals(true, codec.matches(ByteBuffer.wrap(new byte[]{0x78, (byte) 0x9c})));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{0x78, 0x00})));
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
