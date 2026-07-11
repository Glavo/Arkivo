// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoFormats;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies runtime discovery through the all-in-one aggregate module.
@NotNullByDefault
final class AllAggregationTest {
    /// Verifies that all aggregated archive and codec providers are visible.
    @Test
    void discoversAllAggregatedProviders() {
        Set<String> archiveNames = ArkivoFormats.installed()
                .stream()
                .map(ArkivoFormat::name)
                .collect(Collectors.toUnmodifiableSet());
        Set<String> codecNames = CompressionCodecs.installed()
                .stream()
                .map(CompressionCodec::name)
                .collect(Collectors.toUnmodifiableSet());

        assertEquals(Set.of("7z", "ar", "rar", "tar", "zip"), archiveNames);
        assertEquals(Set.of("bzip2", "deflate", "gzip", "lzma", "xz", "zlib", "zstd"), codecNames);
    }

    /// Verifies ByteBuffer round trips through every aggregated compression codec.
    @Test
    void roundTripsBuffersThroughAllAggregatedCodecs() throws IOException {
        byte[] expected = "Arkivo ByteBuffer codec round trip\n"
                .repeat(256)
                .getBytes(StandardCharsets.UTF_8);

        for (CompressionCodec codec : CompressionCodecs.installed()) {
            assertTrue(codec.canCompressBuffers(), codec.name());
            assertTrue(codec.canDecompressBuffers(), codec.name());

            ByteBuffer source = ByteBuffer.allocateDirect(expected.length + 4);
            source.position(2);
            source.put(expected);
            source.flip();
            source.position(2);

            ByteBuffer compressed = ByteBuffer.allocateDirect(expected.length * 2 + 4096);
            int compressedStart = 3;
            compressed.position(compressedStart);
            codec.compress(source, compressed);
            assertFalse(source.hasRemaining(), codec.name());

            compressed.limit(compressed.position());
            compressed.position(compressedStart);
            ByteBuffer decoded = ByteBuffer.allocateDirect(expected.length + 4);
            int decodedStart = 2;
            decoded.position(decodedStart);
            decoded.limit(decodedStart + expected.length);
            codec.decompress(compressed, decoded);

            assertFalse(compressed.hasRemaining(), codec.name());
            assertEquals(decoded.limit(), decoded.position(), codec.name());
            assertArrayEquals(expected, bufferBytes(decoded, decodedStart, decoded.position()), codec.name());
        }
    }

    /// Returns bytes from the given absolute buffer range without changing its state.
    private static byte[] bufferBytes(ByteBuffer buffer, int start, int end) {
        ByteBuffer view = buffer.duplicate();
        view.position(start);
        view.limit(end);
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        return bytes;
    }
}
