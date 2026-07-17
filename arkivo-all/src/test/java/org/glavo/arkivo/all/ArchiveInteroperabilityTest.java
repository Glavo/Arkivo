// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.apache.commons.compress.archivers.ar.ArArchiveEntry;
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream;
import org.apache.commons.compress.archivers.ar.ArArchiveOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributeView;
import org.glavo.arkivo.archive.ar.ArArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributeView;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies bidirectional archive interoperability against an independent implementation.
///
/// Every fixture is generated in a temporary file during the test run so the repository does not carry binary samples.
@NotNullByDefault
final class ArchiveInteroperabilityTest {
    /// Content shared by independently generated and Arkivo-generated archives.
    private static final byte @Unmodifiable [] CONTENT = "Arkivo cross-implementation archive content\n"
            .repeat(32)
            .getBytes(StandardCharsets.UTF_8);

    /// A portable long member name that requires an AR long-name extension.
    private static final String AR_PATH = "long-member-name-produced-for-cross-implementation-validation.txt";

    /// A Unicode TAR path long enough to require extended path metadata.
    private static final String TAR_PATH = "directory/"
            + "long-path-segment-for-pax-interoperability-".repeat(3)
            + "\u8d44\u6599.txt";

    /// A Unicode ZIP and 7z path used to verify encoded-name interoperability.
    private static final String MODERN_PATH = "directory/\u8d44\u6599-interop.txt";

    /// The symbolic-link target used by TAR and ZIP archives.
    private static final String LINK_TARGET = "directory/target.txt";

    /// Verifies Arkivo reads a BSD-long-name AR archive produced by Commons Compress.
    @Test
    void readsIndependentlyGeneratedArArchive() throws IOException {
        Path archive = temporaryArchive(".a");
        try {
            try (ArArchiveOutputStream output = new ArArchiveOutputStream(Files.newOutputStream(archive))) {
                output.setLongFileMode(ArArchiveOutputStream.LONGFILE_BSD);
                ArArchiveEntry entry = new ArArchiveEntry(AR_PATH, CONTENT.length, 123, 456, 0100640, 1_700_000_000L);
                output.putArchiveEntry(entry);
                output.write(CONTENT);
                output.closeArchiveEntry();
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                Path file = fileSystem.getPath("/" + AR_PATH);
                ArArkivoEntryAttributes attributes = Files.readAttributes(file, ArArkivoEntryAttributes.class);
                assertEquals(AR_PATH, attributes.path());
                assertEquals(123L, attributes.userId());
                assertEquals(456L, attributes.groupId());
                assertEquals(0100640, attributes.mode());
                assertArrayEquals(CONTENT, Files.readAllBytes(file));
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Commons Compress reads Arkivo's BSD-long-name AR output and metadata.
    @Test
    void writesInteroperableArArchive() throws IOException {
        Path archive = temporaryArchive(".a");
        try {
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                    "ar",
                    Files.newOutputStream(archive)
            )) {
                var writerEntry110 = writer.beginFile(AR_PATH);
                ArArkivoEntryAttributeView view = Objects.requireNonNull(
                        writerEntry110.attributeView(ArArkivoEntryAttributeView.class)
                );
                view.setUserId(321L);
                view.setGroupId(654L);
                view.setMode(0100600);
                try (OutputStream body = writerEntry110.openOutputStream()) {
                    body.write(CONTENT);
                }
            }

            try (ArArchiveInputStream input = new ArArchiveInputStream(Files.newInputStream(archive))) {
                ArArchiveEntry entry = Objects.requireNonNull(input.getNextEntry());
                assertEquals(AR_PATH, entry.getName());
                assertEquals(321, entry.getUserId());
                assertEquals(654, entry.getGroupId());
                assertEquals(0100600, entry.getMode());
                assertArrayEquals(CONTENT, input.readAllBytes());
                assertNull(input.getNextEntry());
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Arkivo reads POSIX long names, metadata, directories, and links from independent TAR output.
    @Test
    void readsIndependentlyGeneratedTarArchive() throws IOException {
        Path archive = temporaryArchive(".tar");
        try {
            try (TarArchiveOutputStream output = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
                output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                output.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                output.setAddPaxHeadersForNonAsciiNames(true);

                TarArchiveEntry directory = new TarArchiveEntry("directory/");
                directory.setMode(0750);
                output.putArchiveEntry(directory);
                output.closeArchiveEntry();

                TarArchiveEntry file = new TarArchiveEntry(TAR_PATH);
                file.setSize(CONTENT.length);
                file.setMode(0640);
                file.setUserId(123L);
                file.setGroupId(456L);
                file.setUserName("interop-user");
                file.setGroupName("interop-group");
                output.putArchiveEntry(file);
                output.write(CONTENT);
                output.closeArchiveEntry();

                TarArchiveEntry link = new TarArchiveEntry("link", TarConstants.LF_SYMLINK);
                link.setLinkName(LINK_TARGET);
                link.setMode(0777);
                output.putArchiveEntry(link);
                output.closeArchiveEntry();
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                assertTrue(Files.isDirectory(fileSystem.getPath("/directory")));
                Path file = fileSystem.getPath("/" + TAR_PATH);
                TarArkivoEntryAttributes attributes = Files.readAttributes(file, TarArkivoEntryAttributes.class);
                assertEquals(TAR_PATH, attributes.path());
                assertEquals(0640, attributes.mode());
                assertEquals(123L, attributes.userId());
                assertEquals(456L, attributes.groupId());
                assertEquals("interop-user", attributes.userName());
                assertEquals("interop-group", attributes.groupName());
                assertArrayEquals(CONTENT, Files.readAllBytes(file));
                Path link = fileSystem.getPath("/link");
                assertTrue(Files.isSymbolicLink(link));
                assertEquals(LINK_TARGET, Files.readSymbolicLink(link).toString());
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Commons Compress reads Arkivo's PAX metadata, directories, and links.
    @Test
    void writesInteroperableTarArchive() throws IOException {
        Path archive = temporaryArchive(".tar");
        try {
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                    "tar",
                    Files.newOutputStream(archive)
            )) {
                var writerEntry198 = writer.beginDirectory("directory");
                writerEntry198.close();

                var writerEntry201 = writer.beginFile(TAR_PATH);
                TarArkivoEntryAttributeView view = Objects.requireNonNull(
                        writerEntry201.attributeView(TarArkivoEntryAttributeView.class)
                );
                view.setMode(0640);
                view.setUserId(321L);
                view.setGroupId(654L);
                view.setUserName("arkivo-user");
                view.setGroupName("arkivo-group");
                try (OutputStream body = writerEntry201.openOutputStream()) {
                    body.write(CONTENT);
                }

                var writerEntry214 = writer.beginSymbolicLink("link", LINK_TARGET);
                writerEntry214.close();
            }

            try (TarArchiveInputStream input = new TarArchiveInputStream(Files.newInputStream(archive))) {
                TarArchiveEntry directory = Objects.requireNonNull(input.getNextEntry());
                assertEquals("directory/", directory.getName());
                assertTrue(directory.isDirectory());

                TarArchiveEntry file = Objects.requireNonNull(input.getNextEntry());
                assertEquals(TAR_PATH, file.getName());
                assertEquals(0640, file.getMode());
                assertEquals(321L, file.getLongUserId());
                assertEquals(654L, file.getLongGroupId());
                assertEquals("arkivo-user", file.getUserName());
                assertEquals("arkivo-group", file.getGroupName());
                assertArrayEquals(CONTENT, input.readAllBytes());

                TarArchiveEntry link = Objects.requireNonNull(input.getNextEntry());
                assertEquals("link", link.getName());
                assertTrue(link.isSymbolicLink());
                assertEquals(LINK_TARGET, link.getLinkName());
                assertNull(input.getNextEntry());
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Arkivo reads UTF-8 names, Unix modes, and symbolic links from independent ZIP output.
    @Test
    void readsIndependentlyGeneratedZipArchive() throws IOException {
        Path archive = temporaryArchive(".zip");
        try {
            try (ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)) {
                output.setEncoding(StandardCharsets.UTF_8.name());

                ZipArchiveEntry directory = new ZipArchiveEntry("directory/");
                directory.setUnixMode(040750);
                output.putArchiveEntry(directory);
                output.closeArchiveEntry();

                ZipArchiveEntry file = new ZipArchiveEntry(MODERN_PATH);
                file.setMethod(ZipArchiveOutputStream.DEFLATED);
                file.setUnixMode(0100640);
                output.putArchiveEntry(file);
                output.write(CONTENT);
                output.closeArchiveEntry();

                ZipArchiveEntry link = new ZipArchiveEntry("link");
                link.setUnixMode(0120777);
                output.putArchiveEntry(link);
                output.write(LINK_TARGET.getBytes(StandardCharsets.UTF_8));
                output.closeArchiveEntry();
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                assertTrue(Files.isDirectory(fileSystem.getPath("/directory")));
                Path file = fileSystem.getPath("/" + MODERN_PATH);
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(MODERN_PATH, attributes.path());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertArrayEquals(CONTENT, Files.readAllBytes(file));
                Path link = fileSystem.getPath("/link");
                assertTrue(Files.isSymbolicLink(link));
                assertEquals(LINK_TARGET, Files.readSymbolicLink(link).toString());
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Commons Compress reads Arkivo's UTF-8 ZIP names and symbolic links.
    @Test
    void writesInteroperableZipArchive() throws IOException {
        Path archive = temporaryArchive(".zip");
        try {
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                    "zip",
                    Files.newOutputStream(archive)
            )) {
                var writerEntry295 = writer.beginDirectory("directory");
                writerEntry295.close();

                var writerEntry298 = writer.beginFile(MODERN_PATH);
                try (OutputStream body = writerEntry298.openOutputStream()) {
                    body.write(CONTENT);
                }

                var writerEntry303 = writer.beginSymbolicLink("link", LINK_TARGET);
                writerEntry303.close();
            }

            try (ZipFile zipFile = ZipFile.builder().setPath(archive).get()) {
                ZipArchiveEntry directory = Objects.requireNonNull(zipFile.getEntry("directory/"));
                assertTrue(directory.isDirectory());

                ZipArchiveEntry file = Objects.requireNonNull(zipFile.getEntry(MODERN_PATH));
                assertEquals(ZipArchiveOutputStream.DEFLATED, file.getMethod());
                try (InputStream input = zipFile.getInputStream(file)) {
                    assertArrayEquals(CONTENT, input.readAllBytes());
                }

                ZipArchiveEntry link = Objects.requireNonNull(zipFile.getEntry("link"));
                assertTrue(link.isUnixSymlink());
                try (InputStream input = zipFile.getInputStream(link)) {
                    assertEquals(LINK_TARGET, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Arkivo reads LZMA2-compressed 7z output from an independent implementation.
    @Test
    void readsIndependentlyGeneratedSevenZipArchive() throws IOException {
        Path archive = temporaryArchive(".7z");
        try {
            try (SevenZOutputFile output = new SevenZOutputFile(Files.newByteChannel(
                    archive,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            ))) {
                output.setContentCompression(SevenZMethod.LZMA2);

                SevenZArchiveEntry directory = new SevenZArchiveEntry();
                directory.setName("directory");
                directory.setDirectory(true);
                output.putArchiveEntry(directory);
                output.closeArchiveEntry();

                SevenZArchiveEntry file = new SevenZArchiveEntry();
                file.setName(MODERN_PATH);
                file.setDirectory(false);
                output.putArchiveEntry(file);
                output.write(CONTENT);
                output.closeArchiveEntry();
            }

            try (ArkivoFileSystem fileSystem = ArkivoFormats.openFileSystem(archive)) {
                assertTrue(Files.isDirectory(fileSystem.getPath("/directory")));
                Path file = fileSystem.getPath("/" + MODERN_PATH);
                SevenZipArkivoEntryAttributes attributes = Files.readAttributes(
                        file,
                        SevenZipArkivoEntryAttributes.class
                );
                assertEquals(MODERN_PATH, attributes.path());
                assertNotNull(attributes.coderGraph());
                assertArrayEquals(CONTENT, Files.readAllBytes(file));
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Verifies Commons Compress reads Arkivo's LZMA2-compressed 7z output.
    @Test
    void writesInteroperableSevenZipArchive() throws IOException {
        Path archive = temporaryArchive(".7z");
        try {
            try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                    "7z",
                    Files.newOutputStream(archive)
            )) {
                var writerEntry379 = writer.beginDirectory("directory");
                writerEntry379.close();

                var writerEntry382 = writer.beginFile(MODERN_PATH);
                try (OutputStream body = writerEntry382.openOutputStream()) {
                    body.write(CONTENT);
                }
            }

            try (SevenZFile sevenZFile = SevenZFile.builder().setPath(archive).get()) {
                SevenZArchiveEntry directory = Objects.requireNonNull(sevenZFile.getNextEntry());
                assertEquals("directory", directory.getName());
                assertTrue(directory.isDirectory());

                SevenZArchiveEntry file = Objects.requireNonNull(sevenZFile.getNextEntry());
                assertEquals(MODERN_PATH, file.getName());
                assertFalse(file.isDirectory());
                try (InputStream input = sevenZFile.getInputStream(file)) {
                    assertArrayEquals(CONTENT, input.readAllBytes());
                }
                assertNull(sevenZFile.getNextEntry());
            }
        } finally {
            Files.deleteIfExists(archive);
        }
    }

    /// Creates an empty temporary archive path with the requested suffix.
    private static Path temporaryArchive(String suffix) throws IOException {
        return Files.createTempFile("arkivo-interoperability-", suffix);
    }
}
