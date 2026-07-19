// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStoredContent;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR file systems exploit indexed Zstandard frames without staging contiguous entry bodies.
@NotNullByDefault
public final class TarZstdSeekableFileSystemTest {
    /// Verifies creation emits a seek table and random entry reads use source-backed decoded slices.
    @Test
    public void createsAndReadsLazySeekableTarZstd() throws IOException {
        byte[] expected = patternedBytes(2 * 1024 * 1024 + 73_819);
        Path archive = Files.createTempFile("arkivo-seekable-tar-", ".tar.zst");
        RejectingEditStorage storage = new RejectingEditStorage();
        try {
            Files.delete(archive);
            TarArchiveOptions.Create createOptions = TarArchiveOptions.CREATE_DEFAULTS
                    .withCompression(ZstdCodec.DEFAULT);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archive, createOptions)) {
                Files.write(fileSystem.getPath("/large.bin"), expected);
                Files.writeString(fileSystem.getPath("/tail.txt"), "tail");
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(archive, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }
            assertTrue(index.frameCount() >= 3);

            TarArchiveOptions.Read readOptions = TarArchiveOptions.READ_DEFAULTS.withCommon(
                    ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive, readOptions)) {
                Path entry = fileSystem.getPath("/large.bin");
                try (SeekableByteChannel channel = Files.newByteChannel(entry, StandardOpenOption.READ)) {
                    int offset = 1024 * 1024 + 12_345;
                    channel.position(offset);
                    ByteBuffer actual = ByteBuffer.allocate(8192);
                    assertEquals(actual.capacity(), channel.read(actual));
                    assertArrayEquals(
                            java.util.Arrays.copyOfRange(expected, offset, offset + actual.capacity()),
                            actual.array()
                    );
                }
                assertEquals("tail", Files.readString(fileSystem.getPath("/tail.txt")));
            }
            assertEquals(0, storage.createCount());
            assertEquals(1, storage.closeCount());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies complete-rewrite updates can read unchanged lazy bodies and publish another seekable encoding.
    @Test
    public void updatesSeekableTarZstd() throws IOException {
        byte[] expected = patternedBytes(1024 * 1024 + 91_337);
        Path archive = Files.createTempFile("arkivo-seekable-tar-update-", ".tar.zst");
        try {
            Files.delete(archive);
            TarArchiveOptions.Create createOptions = TarArchiveOptions.CREATE_DEFAULTS
                    .withCompression(ZstdCodec.DEFAULT);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archive, createOptions)) {
                Files.write(fileSystem.getPath("/unchanged.bin"), expected);
                Files.writeString(fileSystem.getPath("/value.txt"), "before");
            }

            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archive)) {
                Files.writeString(
                        fileSystem.getPath("/value.txt"),
                        "after",
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            }

            try (SeekableByteChannel source = Files.newByteChannel(archive, StandardOpenOption.READ)) {
                assertNotNull(ZstdCodec.DEFAULT.readIndex(source));
            }
            RejectingEditStorage storage = new RejectingEditStorage();
            TarArchiveOptions.Read readOptions = TarArchiveOptions.READ_DEFAULTS.withCommon(
                    ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive, readOptions)) {
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/unchanged.bin")));
                assertEquals("after", Files.readString(fileSystem.getPath("/value.txt")));
            }
            assertEquals(0, storage.createCount());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies archive indexing skips an unneeded body frame and defers its decoding failure until body access.
    @Test
    public void skipsUnneededFramesDuringIndexing() throws IOException {
        byte[] expected = patternedBytes(2 * 1024 * 1024 + 8192);
        Path archive = Files.createTempFile("arkivo-seekable-tar-skipped-frame-", ".tar.zst");
        try {
            Files.delete(archive);
            TarArchiveOptions.Create createOptions = TarArchiveOptions.CREATE_DEFAULTS
                    .withCompression(ZstdCodec.DEFAULT);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.create(archive, createOptions)) {
                Files.write(fileSystem.getPath("/large.bin"), expected);
                Files.writeString(fileSystem.getPath("/tail.txt"), "tail");
            }

            CompressionCodec.Seekable.Index index;
            try (SeekableByteChannel source = Files.newByteChannel(archive, StandardOpenOption.READ)) {
                index = ZstdCodec.DEFAULT.readIndex(source);
                assertNotNull(index);
            }
            assertTrue(index.frameCount() >= 3);
            byte[] encoded = Files.readAllBytes(archive);
            encoded[Math.toIntExact(index.frameCompressedOffset(1))] = 0;
            Files.write(archive, encoded, StandardOpenOption.TRUNCATE_EXISTING);

            RejectingEditStorage storage = new RejectingEditStorage();
            TarArchiveOptions.Read readOptions = TarArchiveOptions.READ_DEFAULTS
                    .withCompression(ZstdCodec.DEFAULT)
                    .withCommon(ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage));
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive, readOptions)) {
                assertEquals("tail", Files.readString(fileSystem.getPath("/tail.txt")));
                try (SeekableByteChannel entry = Files.newByteChannel(
                        fileSystem.getPath("/large.bin"),
                        StandardOpenOption.READ
                )) {
                    entry.position(index.frameUncompressedOffset(1) - 512L);
                    assertThrows(IOException.class, () -> entry.read(ByteBuffer.allocate(1)));
                }
            }
            assertEquals(0, storage.createCount());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies ordinary single-frame Zstandard TAR files retain the staging fallback.
    @Test
    public void stagesOrdinaryTarZstdWithoutSeekTable() throws IOException {
        ByteArrayOutputStream tarBytes = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(tarBytes)) {
            var entry = writer.beginFile("value.txt");
            try (OutputStream output = entry.openOutputStream()) {
                output.write("ordinary".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        ByteBuffer encoded = ZstdCodec.DEFAULT.compress(ByteBuffer.wrap(tarBytes.toByteArray()));
        byte[] encodedBytes = new byte[encoded.remaining()];
        encoded.get(encodedBytes);

        Path archive = Files.createTempFile("arkivo-ordinary-tar-", ".tar.zst");
        CountingEditStorage storage = new CountingEditStorage();
        try {
            Files.write(archive, encodedBytes, StandardOpenOption.TRUNCATE_EXISTING);
            TarArchiveOptions.Read options = TarArchiveOptions.READ_DEFAULTS.withCommon(
                    ArchiveReadOptions.DEFAULT.withEditStorageFactory(() -> storage)
            );
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archive, options)) {
                assertEquals("ordinary", Files.readString(fileSystem.getPath("/value.txt")));
            }
            assertEquals(1, storage.createCount());
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Creates deterministic data spanning several default seekable frames.
    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 13 + index / 29);
        }
        return bytes;
    }

    /// Rejects body staging so a successful open proves indexed slices were selected.
    @NotNullByDefault
    private static final class RejectingEditStorage implements ArkivoEditStorage {
        /// The number of attempted content allocations.
        private int createCount;

        /// The number of storage close calls.
        private int closeCount;

        /// Rejects every staged-content allocation.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createCount++;
            throw new IOException("Seekable TAR entry body was unexpectedly staged: " + path);
        }

        /// Records storage closure.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of attempted content allocations.
        private int createCount() {
            return createCount;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Counts staging allocations while delegating their storage to memory.
    @NotNullByDefault
    private static final class CountingEditStorage implements ArkivoEditStorage {
        /// The memory-backed storage delegate.
        private final ArkivoEditStorage delegate = ArkivoEditStorage.memory();

        /// The number of staged content allocations.
        private int createCount;

        /// Creates one counted staged content object.
        @Override
        public ArkivoStoredContent createContent(String path, long expectedSize) throws IOException {
            createCount++;
            return delegate.createContent(path, expectedSize);
        }

        /// Closes the delegated storage.
        @Override
        public void close() throws IOException {
            delegate.close();
        }

        /// Returns the staged allocation count.
        private int createCount() {
            return createCount;
        }
    }
}
