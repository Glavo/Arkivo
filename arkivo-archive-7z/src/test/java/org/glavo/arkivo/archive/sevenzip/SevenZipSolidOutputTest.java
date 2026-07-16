// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.glavo.arkivo.archive.ArchiveOptions;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests configurable solid-folder output across every 7z writer surface.
@NotNullByDefault
public final class SevenZipSolidOutputTest {
    /// Temporary test directory.
    @TempDir
    public Path temporaryDirectory;

    /// Verifies file-system output groups files by count and interoperates with Commons Compress.
    @Test
    public void fileSystemSolidFoldersRoundTripWithCommonsCompress() throws IOException {
        Path archive = temporaryDirectory.resolve("file-system-solid.7z");
        byte[] first = repeatedBytes(3_000, 11);
        byte[] second = repeatedBytes(2_000, 29);
        byte[] third = repeatedBytes(1_000, 47);
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("first.bin", first);
        expected.put("empty.bin", new byte[0]);
        expected.put("second.bin", second);
        expected.put("third.bin", third);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                archive,
                ArchiveOptions.fromEnvironment(writerEnvironment(2))
        )) {
            for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
                if ("empty.bin".equals(entry.getKey())) {
                    Files.createDirectory(fileSystem.getPath("/directory"));
                }
                Files.write(fileSystem.getPath("/" + entry.getKey()), entry.getValue());
            }
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            assertContents(fileSystem, expected);
            assertEquals(true, Files.isDirectory(fileSystem.getPath("/directory")));
            SevenZipArkivoEntryAttributes firstAttributes = attributes(fileSystem, "first.bin");
            SevenZipArkivoEntryAttributes secondAttributes = attributes(fileSystem, "second.bin");
            SevenZipArkivoEntryAttributes thirdAttributes = attributes(fileSystem, "third.bin");
            SevenZipCoderGraph firstGraph = Objects.requireNonNull(firstAttributes.coderGraph());
            SevenZipCoderGraph secondGraph = Objects.requireNonNull(secondAttributes.coderGraph());
            SevenZipCoderGraph thirdGraph = Objects.requireNonNull(thirdAttributes.coderGraph());
            assertEquals(firstGraph, secondGraph);
            assertEquals(first.length + second.length, firstGraph.finalUnpackSize());
            assertEquals(third.length, thirdGraph.finalUnpackSize());
            assertNotEquals(firstGraph, thirdGraph);

            assertEquals(true, firstAttributes.solid());
            assertEquals(true, secondAttributes.solid());
            assertEquals(false, thirdAttributes.solid());
            assertEquals(0, firstAttributes.substreamIndex());
            assertEquals(1, secondAttributes.substreamIndex());
            assertEquals(0, thirdAttributes.substreamIndex());
            assertEquals(2, firstAttributes.substreamCount());
            assertEquals(2, secondAttributes.substreamCount());
            assertEquals(1, thirdAttributes.substreamCount());
            assertEquals(0L, firstAttributes.decodedOffset());
            assertEquals(first.length, secondAttributes.decodedOffset());
            assertEquals(0L, thirdAttributes.decodedOffset());
            assertEquals(firstAttributes.dataOffset(), firstAttributes.packedStreams().get(0).offset());
            assertEquals(firstAttributes.dataOffset(), secondAttributes.dataOffset());
            assertEquals(firstAttributes.packedSize(), secondAttributes.packedSize());
            assertEquals(firstAttributes.packedStreams(), secondAttributes.packedStreams());
            assertEquals(
                    firstAttributes.packedStreams().stream().mapToLong(SevenZipPackedStream::size).sum(),
                    firstAttributes.packedSize()
            );
            assertEquals(firstAttributes.packedStreams().get(0).crc32(), firstAttributes.packedCrc32());
            assertEquals(crc32(first), firstAttributes.crc32());
            assertEquals(crc32(second), secondAttributes.crc32());
            assertEquals(crc32(third), thirdAttributes.crc32());
            assertEquals(true, thirdAttributes.dataOffset() > firstAttributes.dataOffset());
            assertThrows(UnsupportedOperationException.class, () -> firstAttributes.packedStreams().clear());

            SevenZipArkivoEntryAttributes emptyAttributes = attributes(fileSystem, "empty.bin");
            SevenZipArkivoEntryAttributes directoryAttributes = attributes(fileSystem, "directory");
            for (SevenZipArkivoEntryAttributes streamless : List.of(emptyAttributes, directoryAttributes)) {
                assertEquals(false, streamless.solid());
                assertEquals(SevenZipArkivoEntryAttributes.NO_SUBSTREAM_INDEX, streamless.substreamIndex());
                assertEquals(0, streamless.substreamCount());
                assertEquals(SevenZipArkivoEntryAttributes.NO_DATA_OFFSET, streamless.dataOffset());
                assertEquals(0L, streamless.decodedOffset());
                assertEquals(0L, streamless.packedSize());
                assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, streamless.packedCrc32());
                assertEquals(List.of(), streamless.packedStreams());
                assertEquals(SevenZipArkivoEntryAttributes.UNKNOWN_CRC32, streamless.crc32());
            }

            Map<String, Object> named = Files.readAttributes(
                    fileSystem.getPath("/second.bin"),
                    "7z:solid,substreamIndex,substreamCount,dataOffset,decodedOffset,packedSize,packedCrc32,packedStreams,crc32"
            );
            assertEquals(secondAttributes.solid(), named.get("solid"));
            assertEquals(secondAttributes.substreamIndex(), named.get("substreamIndex"));
            assertEquals(secondAttributes.substreamCount(), named.get("substreamCount"));
            assertEquals(secondAttributes.dataOffset(), named.get("dataOffset"));
            assertEquals(secondAttributes.decodedOffset(), named.get("decodedOffset"));
            assertEquals(secondAttributes.packedSize(), named.get("packedSize"));
            assertEquals(secondAttributes.packedCrc32(), named.get("packedCrc32"));
            assertEquals(secondAttributes.packedStreams(), named.get("packedStreams"));
            assertEquals(secondAttributes.crc32(), named.get("crc32"));
        }

        assertCommonsContents(archive, expected);
    }

    /// Verifies streaming entry coder changes create exact solid-folder boundaries.
    @Test
    public void streamingSolidFoldersRespectCoderBoundaries() throws IOException {
        Path archive = temporaryDirectory.resolve("streaming-solid.7z");
        byte[] first = repeatedBytes(1_500, 3);
        byte[] second = repeatedBytes(1_700, 5);
        byte[] third = repeatedBytes(1_900, 7);
        byte[] fourth = repeatedBytes(2_100, 9);
        byte[] fifth = repeatedBytes(2_300, 13);
        byte[] sixth = repeatedBytes(2_500, 15);

        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(
                archive,
                ArchiveOptions.fromEnvironment(writerEnvironment(10))
        )) {
            writeStreamingEntry(writer, "first.bin", first, null, null);
            writeStreamingEntry(writer, "second.bin", second, null, null);
            writeStreamingEntry(writer, "third.bin", third, SevenZipCompression.deflate(2), null);
            writeStreamingEntry(writer, "fourth.bin", fourth, SevenZipCompression.deflate(2), null);
            SevenZipFilterChain delta = SevenZipFilterChain.of(SevenZipFilter.delta(3));
            writeStreamingEntry(writer, "fifth.bin", fifth, null, delta);
            writeStreamingEntry(writer, "sixth.bin", sixth, null, delta);
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            SevenZipCoderGraph firstGraph = coderGraph(fileSystem, "first.bin");
            SevenZipCoderGraph secondGraph = coderGraph(fileSystem, "second.bin");
            SevenZipCoderGraph thirdGraph = coderGraph(fileSystem, "third.bin");
            SevenZipCoderGraph fourthGraph = coderGraph(fileSystem, "fourth.bin");
            SevenZipCoderGraph fifthGraph = coderGraph(fileSystem, "fifth.bin");
            SevenZipCoderGraph sixthGraph = coderGraph(fileSystem, "sixth.bin");
            assertEquals(firstGraph, secondGraph);
            assertEquals(first.length + second.length, firstGraph.finalUnpackSize());
            assertEquals(thirdGraph, fourthGraph);
            assertEquals(third.length + fourth.length, thirdGraph.finalUnpackSize());
            assertEquals(SevenZipCoderMethod.LZMA2, firstGraph.coders().get(0).method());
            assertEquals(SevenZipCoderMethod.DEFLATE, thirdGraph.coders().get(0).method());
            assertEquals(fifthGraph, sixthGraph);
            assertEquals(fifth.length + sixth.length, fifthGraph.finalUnpackSize());
            assertEquals(
                    List.of(SevenZipCoderMethod.LZMA2, SevenZipCoderMethod.DELTA),
                    fifthGraph.coders().stream().map(SevenZipCoder::method).toList()
            );
        }
    }

    /// Verifies Zstandard output encodes and decodes multiple file substreams in one solid folder.
    @Test
    public void zstandardSolidFolderRoundTrip() throws IOException {
        Path archive = temporaryDirectory.resolve("zstandard-solid.7z");
        byte[] first = repeatedBytes(4_096, 23);
        byte[] second = repeatedBytes(3_072, 41);
        byte[] third = repeatedBytes(2_048, 59);
        Map<String, Object> environment = new LinkedHashMap<>(writerEnvironment(3));
        environment.put(SevenZipArkivoFileSystem.COMPRESSION.key(), SevenZipCompression.zstandard());

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive, ArchiveOptions.fromEnvironment(environment))) {
            Files.write(fileSystem.getPath("/first.bin"), first);
            Files.write(fileSystem.getPath("/second.bin"), second);
            Files.write(fileSystem.getPath("/third.bin"), third);
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(first, Files.readAllBytes(fileSystem.getPath("/first.bin")));
            assertArrayEquals(second, Files.readAllBytes(fileSystem.getPath("/second.bin")));
            assertArrayEquals(third, Files.readAllBytes(fileSystem.getPath("/third.bin")));
            SevenZipCoderGraph graph = coderGraph(fileSystem, "first.bin");
            assertEquals(graph, coderGraph(fileSystem, "second.bin"));
            assertEquals(graph, coderGraph(fileSystem, "third.bin"));
            assertEquals(first.length + second.length + third.length, graph.finalUnpackSize());
            assertEquals(SevenZipCoderMethod.ZSTANDARD, graph.coders().get(0).method());
        }
    }

    /// Verifies complete-rewrite update mode re-encodes surviving entries into solid folders.
    @Test
    public void updateModeRewritesEntriesAsSolidFolders() throws IOException {
        Path archive = temporaryDirectory.resolve("updated-solid.7z");
        byte[] first = repeatedBytes(1_024, 17);
        byte[] second = repeatedBytes(2_048, 31);
        byte[] updatedSecond = repeatedBytes(3_072, 37);

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                archive,
                ArchiveOptions.fromEnvironment(writerEnvironment(1))
        )) {
            Files.write(fileSystem.getPath("/first.bin"), first);
            Files.write(fileSystem.getPath("/second.bin"), second);
        }

        Map<String, Object> updateEnvironment = new LinkedHashMap<>();
        updateEnvironment.put(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );
        updateEnvironment.put(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                SevenZipCompression.lzma2(SevenZipCompression.MIN_DICTIONARY_SIZE)
        );
        updateEnvironment.put(SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(), 2);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive, ArchiveOptions.fromEnvironment(updateEnvironment))) {
            Files.write(fileSystem.getPath("/second.bin"), updatedSecond);
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archive)) {
            assertArrayEquals(first, Files.readAllBytes(fileSystem.getPath("/first.bin")));
            assertArrayEquals(updatedSecond, Files.readAllBytes(fileSystem.getPath("/second.bin")));
            SevenZipCoderGraph graph = coderGraph(fileSystem, "first.bin");
            assertEquals(graph, coderGraph(fileSystem, "second.bin"));
            assertEquals(first.length + updatedSecond.length, graph.finalUnpackSize());
        }
    }

    /// Verifies solid output composes with entry encryption, encrypted headers, and split publication.
    @Test
    public void encryptedSplitSolidArchiveRoundTrip() throws IOException {
        Path firstVolume = temporaryDirectory.resolve("encrypted-solid.7z.001");
        byte[] password = "solid-password".getBytes(StandardCharsets.UTF_16LE);
        byte[] first = repeatedBytes(4_096, 53);
        byte[] second = repeatedBytes(4_096, 71);
        byte[] third = repeatedBytes(4_096, 89);

        Map<String, Object> environment = new LinkedHashMap<>(writerEnvironment(3));
        environment.put(SevenZipArkivoFileSystem.SPLIT_SIZE.key(), 192L);
        environment.put(
                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
        environment.put(SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key(), true);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume, ArchiveOptions.fromEnvironment(environment))) {
            Files.write(fileSystem.getPath("/first.bin"), first);
            Files.write(fileSystem.getPath("/second.bin"), second);
            Files.write(fileSystem.getPath("/third.bin"), third);
        }

        try (var paths = Files.list(temporaryDirectory)) {
            assertEquals(true, paths.filter(path -> path.getFileName().toString().contains(".7z.")).count() > 1L);
        }
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                firstVolume,
                ArchiveOptions.fromEnvironment(Map.of(
                        SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                        ArkivoPasswordProvider.fixed(password)
                ))
        )) {
            assertArrayEquals(first, Files.readAllBytes(fileSystem.getPath("/first.bin")));
            assertArrayEquals(second, Files.readAllBytes(fileSystem.getPath("/second.bin")));
            assertArrayEquals(third, Files.readAllBytes(fileSystem.getPath("/third.bin")));
            SevenZipCoderGraph graph = coderGraph(fileSystem, "first.bin");
            assertEquals(graph, coderGraph(fileSystem, "second.bin"));
            assertEquals(graph, coderGraph(fileSystem, "third.bin"));
            assertEquals(first.length + second.length + third.length, graph.finalUnpackSize());
            assertEquals(SevenZipCoderMethod.AES, graph.coders().get(0).method());
            assertEquals(SevenZipCoderMethod.LZMA2, graph.coders().get(1).method());
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /// Returns the standard forward-only writer environment with a solid file-count limit.
    private static Map<String, Object> writerEnvironment(int solidFileCount) {
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );
        environment.put(
                SevenZipArkivoFileSystem.COMPRESSION.key(),
                SevenZipCompression.lzma2(SevenZipCompression.MIN_DICTIONARY_SIZE)
        );
        environment.put(SevenZipArkivoFileSystem.SOLID_FILE_COUNT.key(), solidFileCount);
        return environment;
    }

    /// Writes one streaming file entry with optional compression and filter overrides.
    private static void writeStreamingEntry(
            SevenZipArkivoStreamingWriter writer,
            String name,
            byte[] content,
            @Nullable SevenZipCompression compression,
            @Nullable SevenZipFilterChain filters
    ) throws IOException {
        writer.beginFile(name);
        SevenZipArkivoEntryAttributeView attributes = Objects.requireNonNull(
                writer.attributeView(SevenZipArkivoEntryAttributeView.class)
        );
        if (compression != null) {
            attributes.setCompression(compression);
        }
        if (filters != null) {
            attributes.setFilters(filters);
        }
        try (OutputStream output = writer.openOutputStream()) {
            output.write(content);
        }
    }

    /// Returns the 7z-specific attributes for one entry.
    private static SevenZipArkivoEntryAttributes attributes(
            SevenZipArkivoFileSystem fileSystem,
            String name
    ) throws IOException {
        return Files.readAttributes(
                fileSystem.getPath("/" + name),
                SevenZipArkivoEntryAttributes.class
        );
    }

    /// Returns the coder graph for one non-empty entry.
    private static SevenZipCoderGraph coderGraph(SevenZipArkivoFileSystem fileSystem, String name) throws IOException {
        return Objects.requireNonNull(attributes(fileSystem, name).coderGraph());
    }

    /// Verifies all expected file contents through Arkivo.
    private static void assertContents(
            SevenZipArkivoFileSystem fileSystem,
            Map<String, byte[]> expected
    ) throws IOException {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(fileSystem.getPath("/" + entry.getKey())));
        }
    }

    /// Verifies all expected file contents through Commons Compress.
    private static void assertCommonsContents(Path archive, Map<String, byte[]> expected) throws IOException {
        try (SevenZFile sevenZFile = SevenZFile.builder().setPath(archive).get()) {
            int fileCount = 0;
            int directoryCount = 0;
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    assertEquals("directory", entry.getName());
                    directoryCount++;
                } else {
                    try (var input = sevenZFile.getInputStream(entry)) {
                        assertArrayEquals(expected.get(entry.getName()), input.readAllBytes());
                    }
                    fileCount++;
                }
            }
            assertEquals(expected.size(), fileCount);
            assertEquals(1, directoryCount);
        }
    }

    /// Returns the unsigned CRC-32 of decoded entry content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Creates deterministic incompressible-looking content.
    private static byte[] repeatedBytes(int size, int multiplier) {
        byte[] content = new byte[size];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * multiplier + index / 13);
        }
        return content;
    }
}
