// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.bzip2;

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

/// Tests BZip2 codec behavior.
@NotNullByDefault
public final class BZip2CodecTest {
    /// Verifies that BZip2 compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        BZip2Codec codec = new BZip2Codec();
        byte[] input = "hello bzip2".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(BZip2Codec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the BZip2 codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(BZip2Codec.class, Objects.requireNonNull(CompressionCodecs.find(BZip2Codec.NAME)).getClass());
        assertEquals(BZip2Codec.class, Objects.requireNonNull(CompressionCodecs.find("bz2")).getClass());
    }

    /// Verifies BZip2 metadata and signature matching.
    @Test
    public void metadata() {
        BZip2Codec codec = new BZip2Codec();
        assertEquals(java.util.List.of("bz2"), codec.aliases());
        assertEquals(java.util.List.of("bz2", "bzip2"), codec.fileExtensions());
        assertEquals(true, codec.matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h', '9', 0x31})));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h', '0'})));
        assertEquals(false, codec.matches(ByteBuffer.wrap(new byte[]{'B', 'Z', 'h'})));
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
