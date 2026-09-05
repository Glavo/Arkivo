// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.splitStoredArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.splitVolumePath;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests explicit and conventionally named split-volume ZIP inputs.
@NotNullByDefault
public final class ZipSplitInputIntegrationTest {
    /// Verifies that a split ZIP archive can be indexed and read through a volume source.
    @Test
    public void readsExplicitVolumeSource() throws IOException {
        Path firstVolume = createTemporaryArchivePath("split-zip-");
        Path secondVolume = firstVolume.getParent().resolve("sample.z02");
        byte[][] volumes = splitStoredArchive();
        Files.write(firstVolume, volumes[0]);
        Files.write(secondVolume, volumes[1]);

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(ArkivoVolumeSource.of(List.of(firstVolume, secondVolume)))) {
                Path file = fileSystem.getPath("/hello.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                ArrayList<String> rootChildren = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                assertEquals(List.of("/hello.txt"), rootChildren);
                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals("split", Files.readString(file, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies discovery and reading of conventionally named ZIP split volumes.
    @Test
    public void discoversAndReadsSplitVolumePaths() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-zip-path-");
        Path firstVolume = splitVolumePath(archivePath, 0);
        byte[][] volumes = splitStoredArchive();
        Files.write(firstVolume, volumes[0]);
        Files.write(archivePath, volumes[1]);

        try {
            List<Path> discoveredPaths = Objects.requireNonNull(
                    ZipArkivoFormat.instance().discoverVolumePaths(archivePath)
            );
            assertEquals(List.of(firstVolume, archivePath), discoveredPaths);
            assertThrows(UnsupportedOperationException.class, discoveredPaths::clear);

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archivePath)) {
                assertTrue(reader.next());
                assertEquals("hello.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals("split", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertFalse(reader.next());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("split", Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a complete archive ignores a stale conventionally named split companion.
    @Test
    public void completeArchiveIgnoresStaleSplitCompanion() throws IOException {
        Path archivePath = createTemporaryArchivePath("single-zip-with-stale-split-");
        Path staleVolume = splitVolumePath(archivePath, 0);

        try {
            Files.write(staleVolume, "stale".getBytes(StandardCharsets.UTF_8));
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var singleEntry = writer.beginFile("single.txt");
                try (OutputStream output = singleEntry.openOutputStream()) {
                    output.write("single".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("single", Files.readString(fileSystem.getPath("/single.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }
}
