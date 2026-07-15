// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/// Verifies the public API examples presented in the repository README.
@NotNullByDefault
final class ReadmeExamplesTest {
    /// Verifies the documented allocating buffer compression round trip.
    @Test
    void compressesAndDecompressesBuffer() throws IOException {
        byte[] input = "Arkivo buffer example".getBytes(StandardCharsets.UTF_8);

        CompressionCodec codec = Objects.requireNonNull(CompressionCodecs.find("zstd"));
        ByteBuffer compressed = codec.compress(ByteBuffer.wrap(input));
        ByteBuffer decoded = codec.decompress(compressed, input.length);

        byte[] output = new byte[decoded.remaining()];
        decoded.get(output);
        assertArrayEquals(input, output);
    }

    /// Verifies the documented writable ZIP and detected archive file-system flow.
    @Test
    void createsAndReadsZipFileSystem(@TempDir Path temporaryDirectory) throws IOException {
        Path archive = temporaryDirectory.resolve("example.zip");
        Map<String, ?> writable = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive, writable)) {
            Files.createDirectories(fileSystem.getPath("/docs"));
            Files.writeString(fileSystem.getPath("/docs/readme.txt"), "Hello from Arkivo");
        }

        try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
            String text = Files.readString(fileSystem.getPath("/docs/readme.txt"));
            assertEquals("Hello from Arkivo", text);
        }
    }
}
