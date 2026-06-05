// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.lzma;

import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests LZMA codec behavior.
@NotNullByDefault
public final class LzmaCodecTest {
    /// Verifies that LZMA compression round-trips bytes.
    @Test
    public void roundTrip() throws IOException {
        LzmaCodec codec = new LzmaCodec();
        byte[] input = "hello lzma".getBytes(StandardCharsets.UTF_8);

        assertEquals(true, codec instanceof CompressionCodec);
        assertEquals(LzmaCodec.NAME, codec.name());
        assertEquals(true, codec.canCompress());
        assertEquals(true, codec.canDecompress());
        assertArrayEquals(input, roundTrip(codec, input));
    }

    /// Verifies that the LZMA codec can be discovered through service loading.
    @Test
    public void findInstalledCodec() {
        assertEquals(LzmaCodec.class, Objects.requireNonNull(CompressionCodecs.find(LzmaCodec.NAME)).getClass());
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
