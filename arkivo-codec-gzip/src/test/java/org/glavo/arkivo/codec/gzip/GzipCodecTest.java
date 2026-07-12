// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
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

/// Tests gzip codec behavior.
@NotNullByDefault
public final class GzipCodecTest {
    /// Verifies that gzip compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        GzipCodec codec = new GzipCodec();
        byte[] input = "hello gzip".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(GzipCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the gzip codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(GzipCodec.class, Objects.requireNonNull(CompressionCodecs.find(GzipCodec.NAME)).getClass());
    }

    /// Verifies gzip metadata and signature matching.
    @Test
    public void metadata() {
        GzipCodec codec = new GzipCodec();
        assertEquals(java.util.List.of("gz", "gzip"), codec.fileExtensions());
        assertEquals(true, codec.matches(ByteBuffer.wrap(new byte[]{0x1f, (byte) 0x8b, 0x08})));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{0x1f})));
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
