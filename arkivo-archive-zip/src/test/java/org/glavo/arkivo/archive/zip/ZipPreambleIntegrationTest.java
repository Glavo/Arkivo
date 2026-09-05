// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.emptyZipWithPreamble;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.singleEntryZipWithPreambleAndAdjustedOffsets;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests ZIP preamble discovery, access, and channel lifecycle behavior.
@NotNullByDefault
public final class ZipPreambleIntegrationTest {
    /// Verifies that preamble bytes can be read from an archive path.
    @Test
    public void readsPreambleFromArchivePath() throws IOException {
        byte[] preamble = new byte[]{1, 2, 3, 4};
        Path archivePath = writeArchive(emptyZipWithPreamble(preamble));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that preamble bytes can be read from a volume source.
    @Test
    public void readsPreambleFromVolumeSource() throws IOException {
        byte[] preamble = new byte[]{5, 6, 7};
        Path archivePath = writeArchive(emptyZipWithPreamble(preamble));

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(archivePath)))) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies preamble detection when stored ZIP offsets already include the preamble size.
    @Test
    public void readsPreambleWithAdjustedZipOffsets() throws IOException {
        byte[] preamble = new byte[]{9, 8, 7, 6, 5};
        Path archivePath = writeArchive(singleEntryZipWithPreambleAndAdjustedOffsets(preamble));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
                assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/a")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that streaming readers scan PK00 and executable-style preambles before the first local header.
    @Test
    public void streamingReaderScansRecognizedPreambles() throws IOException {
        byte[] content = "preamble content".getBytes(StandardCharsets.UTF_8);
        byte[] entryArchive = streamingArchive(content);

        for (byte[] preamble : List.of(
                new byte[]{'P', 'K', '0', '0'},
                "MZ executable preamble".getBytes(StandardCharsets.UTF_8),
                new byte[]{'P', 'K', '0', '0', 'n', 'o', 't', '-', 'a', '-', 'm', 'a', 'r', 'k', 'e', 'r'},
                new byte[]{'M', 'Z', 'P', 'K', 7, 8, 'n', 'o', 't', '-', 'a', '-', 'h', 'e', 'a', 'd', 'e', 'r'}
        )) {
            byte[] archive = Arrays.copyOf(preamble, preamble.length + entryArchive.length);
            System.arraycopy(entryArchive, 0, archive, preamble.length, entryArchive.length);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next());
                assertEquals("entry.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertFalse(reader.next());
            }
        }
    }

    /// Verifies that streaming preamble scanning remains bounded by the common metadata limit.
    @Test
    public void streamingReaderPreambleHonorsMetadataLimit() throws IOException {
        byte[] archive = "preamble without a ZIP record".getBytes(StandardCharsets.UTF_8);
        ZipArchiveOptions.Read options = ZipArchiveOptions.READ_DEFAULTS.withCommon(
                ZipArchiveOptions.READ_DEFAULTS.common().withLimits(
                        ArchiveReadLimits.builder().maximumMetadataSize(3L).build()
                )
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                options
        )) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertTrue(exception.getMessage().contains("Archive metadata size"));
        }
    }

    /// Verifies that a closed preamble channel consistently rejects channel operations.
    @Test
    public void closedPreambleChannelRejectsOperations() throws IOException {
        byte[] preamble = new byte[]{4, 3, 2, 1};
        Path archivePath = writeArchive(emptyZipWithPreamble(preamble));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                SeekableByteChannel channel = fileSystem.openPreambleChannel();

                assertEquals(preamble.length, channel.size());
                assertTrue(channel.isOpen());
                assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
                assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));

                channel.close();

                assertFalse(channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, channel::position);
                assertThrows(ClosedChannelException.class, channel::size);
                assertThrows(ClosedChannelException.class, () -> channel.truncate(0L));
                channel.close();
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that preamble setup preserves its primary failure when channel cleanup fails with I/O.
    @Test
    public void setupSuppressesArchiveChannelCloseFailure() throws IOException {
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(new CloseFailingArchiveVolumeSource(new byte[0], 1))) {
            IOException exception = assertThrows(IOException.class, fileSystem::openPreambleChannel);

            assertTrue(exception.getMessage().contains("ZIP end of central directory record not found"));
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        }
    }

    /// Verifies that preamble setup preserves its primary failure when channel cleanup fails at runtime.
    @Test
    public void setupSuppressesArchiveChannelRuntimeCloseFailure() throws IOException {
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(new CloseFailingArchiveVolumeSource(new byte[0], 1, true))) {
            IOException exception = assertThrows(IOException.class, fileSystem::openPreambleChannel);

            assertTrue(exception.getMessage().contains("ZIP end of central directory record not found"));
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        }
    }

    /// Writes the given archive bytes to a new temporary ZIP path.
    private static Path writeArchive(byte[] content) throws IOException {
        Path archivePath = createTemporaryArchivePath("preamble-");
        Files.write(archivePath, content);
        return archivePath;
    }

    /// Returns a streaming ZIP archive containing one regular entry.
    private static byte[] streamingArchive(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            try (var entryOutput = writer.beginFile("entry.txt").openOutputStream()) {
                entryOutput.write(content);
            }
        }
        return output.toByteArray();
    }

    /// Asserts that a preamble channel exposes exactly the expected bytes.
    private static void assertPreambleContent(byte[] expected, ZipArkivoFileSystem fileSystem) throws IOException {
        try (SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            assertEquals(expected.length, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            assertEquals(expected.length, channel.read(buffer));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertArrayEquals(expected, buffer.array());
        }
    }
}
