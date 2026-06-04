// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.bzip2;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests BZip2 codec registration and round-trip behavior.
@NotNullByDefault
public final class Bzip2CodecTest {
    /// Creates a BZip2 codec test instance.
    public Bzip2CodecTest() {
    }

    /// Verifies BZip2 compression and decompression preserve input bytes.
    @Test
    public void roundTrip() throws Exception {
        byte[] input = "hello bzip2".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(input, roundTrip(new Bzip2Codec(), input));
    }

    /// Verifies the codec reports its stable name and supported operations.
    @Test
    public void properties() {
        CompressionCodec codec = new Bzip2Codec();
        assertEquals(Bzip2Codec.NAME, codec.name());
        assertTrue(codec.canCompress());
        assertTrue(codec.canDecompress());
    }

    /// Verifies the codec is discoverable through `ServiceLoader`.
    @Test
    public void serviceLoading() {
        assertEquals(Bzip2Codec.class, Objects.requireNonNull(CompressionCodecs.find(Bzip2Codec.NAME)).getClass());
    }

    /// Compresses and decompresses input bytes with the given codec.
    private static byte[] roundTrip(CompressionCodec codec, byte[] input) throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (java.io.OutputStream output = codec.compressTo(compressed)) {
            output.write(input);
        }

        ByteArrayOutputStream decompressed = new ByteArrayOutputStream();
        try (java.io.InputStream inputStream = codec.decompressFrom(new ByteArrayInputStream(compressed.toByteArray()))) {
            inputStream.transferTo(decompressed);
        }
        return decompressed.toByteArray();
    }
}
