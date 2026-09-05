// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotLinkException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.containsBytes;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.createTemporaryArchivePath;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.deleteTemporaryArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.singleStoredZipArchive;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.tamperFirstDataDescriptorCrc;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.updateSourceZip;
import static org.glavo.arkivo.archive.zip.ZipTestArchiveFixtures.writeStoredZipEntry;
import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.bzip2;
import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.deflate64StoredBlock;
import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.lzma;
import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.xz;
import static org.glavo.arkivo.archive.zip.ZipCompressionTestFixtures.zstandard;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests end-to-end ZIP reading, writing, updating, encryption, and volume handling.
@NotNullByDefault
public final class ZipArchiveIntegrationTest {
    /// The ZIP LZMA general purpose flag indicating an EOS marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The ZIP version needed to extract Deflate64 entries.
    private static final int DEFLATE64_VERSION_NEEDED = 21;

    /// The ZIP version needed to extract LZMA entries.
    private static final int LZMA_VERSION_NEEDED = 63;

    /// Returns ZIP read options using a fixed password.
    private static ZipArchiveOptions.Read readOptions(byte[] password) {
        return ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
    }

    /// Returns ZIP creation options using a fixed password and no default encryption.
    private static ZipArchiveOptions.Create createOptions(byte[] password) {
        return ZipArchiveOptions.CREATE_DEFAULTS.withPasswordProvider(ArkivoPasswordProvider.fixed(password));
    }

    /// Returns ZIP creation options using a fixed password and the requested default encryption.
    private static ZipArchiveOptions.Create createOptions(byte[] password, ZipEncryption encryption) {
        return createOptions(password).withDefaultEncryption(encryption);
    }

    /// Returns ZIP update options using format-independent update settings and no ZIP-specific overrides.
    private static ZipArchiveOptions.Update updateOptions(ArchiveUpdateOptions common) {
        return ZipArchiveOptions.UPDATE_DEFAULTS.withCommon(common);
    }

    /// Returns ZIP update options using format-independent update settings and a fixed password.
    private static ZipArchiveOptions.Update updateOptions(ArchiveUpdateOptions common, byte[] password) {
        return updateOptions(common).withPasswordProvider(ArkivoPasswordProvider.fixed(password));
    }

    /// Verifies that a streaming ZIP writer can write entries in storage order.
    @Test
    public void streamingWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var directoryEntry = writer.beginDirectory("dir");
                directoryEntry.close();
                var contentEntry = writer.beginFile("dir/hello.txt");
                try (var output = contentEntry.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                var emptyEntry = writer.beginFile("dir/empty.txt");
                emptyEntry.close();
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/dir/empty.txt")));
                PosixFileAttributes posixAttributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), PosixFileAttributes.class);
                ZipArkivoEntryAttributes zipAttributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), ZipArkivoEntryAttributes.class);
                assertEquals(true, posixAttributes.isRegularFile());
                assertEquals("owner", posixAttributes.owner().getName());
                assertEquals("owner", zipAttributes.owner().getName());
                assertEquals(true, posixAttributes.permissions().contains(PosixFilePermission.OWNER_READ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a ZIP update can append entries to an existing archive.
    @Test
    public void fileSystemUpdateAppends() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-append-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var originalEntry = writer.beginFile("before.txt");
                try (OutputStream output = originalEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/after.txt"), "after", StandardCharsets.UTF_8);
                assertThrows(
                        FileAlreadyExistsException.class,
                        () -> Files.writeString(
                                fileSystem.getPath("/before.txt"),
                                "replacement",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW
                        )
                );
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("before", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertEquals("after", Files.readString(fileSystem.getPath("/after.txt"), StandardCharsets.UTF_8));
            }
            assertEquals(
                    Map.of("before.txt", "before", "after.txt", "after"),
                    readSequentialTextEntries(archivePath)
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer emits interoperable Deflate64 entries.
    @Test
    public void streamingWriterDeflate64Entry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-deflate64-");
        byte[] content = "deflate64 writer content ".repeat(4_096).getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var deflate64Entry = writer.beginFile("deflate64.txt");
                ZipArkivoEntryAttributeView view = deflate64Entry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.DEFLATE64);
                try (var output = deflate64Entry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/deflate64.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.DEFLATE64, attributes.compressionMethod());
                assertEquals(DEFLATE64_VERSION_NEEDED, attributes.versionNeededToExtract());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            try (var zipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                    .setPath(archivePath)
                    .get()) {
                var entry = Objects.requireNonNull(zipFile.getEntry("deflate64.txt"));
                assertEquals(ZipMethod.DEFLATE64.id(), entry.getMethod());
                try (InputStream input = zipFile.getInputStream(entry)) {
                    assertArrayEquals(content, input.readAllBytes());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies Deflate64 completion before WinZip AES authentication and the following entry.
    @Test
    public void streamingWriterAesDeflate64Entry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-aes-deflate64-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "AES Deflate64 writer content ".repeat(1_024).getBytes(StandardCharsets.UTF_8);
        byte[] after = "after AES Deflate64".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password)
            )) {
                var encryptedEntry = writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView view = encryptedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.DEFLATE64);
                view.setEncryption(ZipEncryption.WINZIP_AES_256);
                try (var output = encryptedEntry.openOutputStream()) {
                    output.write(content);
                }

                var followingEntry = writer.beginFile("after.txt");
                try (var output = followingEntry.openOutputStream()) {
                    output.write(after);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, readOptions(password))) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
                assertArrayEquals(after, Files.readAllBytes(fileSystem.getPath("/after.txt")));
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        fileSystem.getPath("/secret.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertEquals(ZipMethod.DEFLATE64, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies closing a partially consumed Deflate descriptor entry preserves the following entry.
    @Test
    public void streamingReaderDrainsDeflatedDataDescriptorEntriesOnClose() throws IOException {
        byte[] password = "deflate close secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "partially consumed Deflate descriptor content".repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte[] after = "after partially consumed Deflate".getBytes(StandardCharsets.UTF_8);

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.NONE,
                ZipEncryption.ZIP_CRYPTO,
                ZipEncryption.WINZIP_AES_256
        }) {
            byte[] archive = streamingDeflatedDataDescriptorArchive(encryption, password, content, after);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(Byte.toUnsignedInt(content[0]), input.read());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies an invalid Deflate descriptor does not consume the following entry.
    @Test
    public void streamingReaderRejectsInvalidDeflatedDataDescriptorsWithoutLosingFollowingEntry() throws IOException {
        byte[] password = "deflate mismatch secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "Deflate descriptor CRC mismatch".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] after = "after invalid Deflate descriptor".getBytes(StandardCharsets.UTF_8);

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.NONE,
                ZipEncryption.ZIP_CRYPTO,
                ZipEncryption.WINZIP_AES_256
        }) {
            byte[] archive = tamperFirstDataDescriptorCrc(
                    streamingDeflatedDataDescriptorArchive(encryption, password, content, after)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                InputStream input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor"));
                input.close();

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream afterInput = reader.openInputStream()) {
                    assertArrayEquals(after, afterInput.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies closing a partially consumed LZMA descriptor entry preserves the following entry.
    @Test
    public void streamingReaderDrainsLzmaDataDescriptorEntriesOnClose() throws IOException {
        byte[] password = "lzma close secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "partially consumed LZMA descriptor content".repeat(128)
                .getBytes(StandardCharsets.UTF_8);
        byte[] after = "after partially consumed LZMA".getBytes(StandardCharsets.UTF_8);

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.NONE,
                ZipEncryption.ZIP_CRYPTO,
                ZipEncryption.WINZIP_AES_256
        }) {
            byte[] archive = streamingLzmaDataDescriptorArchive(encryption, password, content, after);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                assertEquals(encryption, reader.readAttributes(ZipArkivoEntryAttributes.class).encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(Byte.toUnsignedInt(content[0]), input.read());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies an invalid LZMA descriptor does not consume the following entry.
    @Test
    public void streamingReaderRejectsInvalidLzmaDataDescriptorsWithoutLosingFollowingEntry() throws IOException {
        byte[] password = "lzma mismatch secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "LZMA descriptor CRC mismatch".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] after = "after invalid LZMA descriptor".getBytes(StandardCharsets.UTF_8);

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.NONE,
                ZipEncryption.ZIP_CRYPTO,
                ZipEncryption.WINZIP_AES_256
        }) {
            byte[] archive = tamperFirstDataDescriptorCrc(
                    streamingLzmaDataDescriptorArchive(encryption, password, content, after)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                InputStream input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor"));
                input.close();

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream afterInput = reader.openInputStream()) {
                    assertArrayEquals(after, afterInput.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies that default traditional ZIP encryption is applied to file entries.
    @Test
    public void streamingWriterDefaultTraditionalEncryption() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-encrypted-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted deflated content".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password, ZipEncryption.ZIP_CRYPTO)
            )) {
                var secureDirectory = writer.beginDirectory("secure");
                secureDirectory.close();

                var messageEntry = writer.beginFile("secure/message.txt");
                ZipArkivoEntryAttributeView view = messageEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                assertEquals(ZipEncryption.ZIP_CRYPTO, view.readAttributes().encryption());
                try (var output = messageEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path directory = fileSystem.getPath("/secure");
                Path file = fileSystem.getPath("/secure/message.txt");
                ZipArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(directory, ZipArkivoEntryAttributes.class);
                ZipArkivoEntryAttributes fileAttributes =
                        Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipEncryption.NONE, directoryAttributes.encryption());
                assertEquals(ZipEncryption.ZIP_CRYPTO, fileAttributes.encryption());
                assertEquals(true, (fileAttributes.generalPurposeFlags() & 1) != 0);
                assertEquals(content.length, fileAttributes.size());
                assertEquals(true, fileAttributes.compressedSize() > content.length);
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/secure/message.txt")));
            }
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions("wrong".getBytes(StandardCharsets.UTF_8))
            )) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/secure/message.txt")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that stored entries can explicitly request traditional ZIP encryption.
    @Test
    public void streamingWriterStoredTraditionalEncryption() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-stored-encrypted-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted stored content".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password)
            )) {
                var storedEntry = writer.beginFile("stored.bin");
                ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                view.setEncryption(ZipEncryption.ZIP_CRYPTO);
                view.setUncompressedSizeAndCrc32(content.length, crc32);
                try (var output = storedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/stored.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, attributes.encryption());
                assertEquals(content.length + 12L, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32, attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that default WinZip AES encryption is applied to file entries.
    @Test
    public void streamingWriterDefaultWinZipAesEncryption() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-aes-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "AES encrypted deflated content".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password, ZipEncryption.WINZIP_AES_256)
            )) {
                var encryptedEntry = writer.beginFile("secure/aes.txt");
                ZipArkivoEntryAttributeView view = encryptedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                assertEquals(ZipEncryption.WINZIP_AES_256, view.readAttributes().encryption());
                try (var output = encryptedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/secure/aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/secure/aes.txt")));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secure/aes.txt", attributes.path());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }

            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES entries can use an empty password.
    @Test
    public void streamingWriterWinZipAesEmptyPassword() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-aes-empty-password-");
        byte[] password = new byte[0];
        byte[] content = "AES empty password content".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password, ZipEncryption.WINZIP_AES_256)
            )) {
                var encryptedEntry = writer.beginFile("empty-password-aes.txt");
                try (var output = encryptedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/empty-password-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("empty-password-aes.txt", attributes.path());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that stored entries can explicitly request WinZip AES encryption.
    @Test
    public void streamingWriterStoredWinZipAesEncryption() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-stored-aes-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "AES encrypted stored content".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password)
            )) {
                var storedEntry = writer.beginFile("stored-aes.bin");
                ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                view.setEncryption(ZipEncryption.WINZIP_AES_128);
                view.setUncompressedSizeAndCrc32(content.length, crc32);
                try (var output = storedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/stored-aes.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_128, attributes.encryption());
                assertEquals(content.length + 20L, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32, attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored-aes.bin", attributes.path());
                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_128, attributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that stored WinZip AES entries can be read with data descriptors.
    @Test
    public void streamingWriterStoredWinZipAesEncryptionDataDescriptor() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-stored-aes-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "AES stored descriptor content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password)
            )) {
                var storedEntry = writer.beginFile("stored-aes-descriptor.bin");
                ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                view.setEncryption(ZipEncryption.WINZIP_AES_192);
                try (var output = storedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/stored-aes-descriptor.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_192, attributes.encryption());
                assertEquals(content.length + 24L, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored-aes-descriptor.bin", attributes.path());
                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_192, attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }

            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that raw encrypted WinZip AES stored data can contain a descriptor signature.
    @Test
    public void streamingReaderStoredWinZipAesDescriptorIgnoresCiphertextSignature() throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = contentWithAesCiphertextDescriptorSignature(password);
        byte[] archive = winZipAesStoredDataDescriptorArchive(password, content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                readOptions(password)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-stored-descriptor.bin", attributes.path());
            assertEquals(ZipMethod.STORED, attributes.compressionMethod());
            assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that raw encrypted WinZip AES stored data cannot end an entry by matching descriptor sizes alone.
    @Test
    public void streamingReaderStoredWinZipAesDescriptorChecksCiphertextAuthenticationCandidate() throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = contentWithAesCiphertextDescriptorSizeCandidate(password);
        byte[] archive = winZipAesStoredDataDescriptorArchive(password, content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                readOptions(password)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-stored-descriptor.bin", attributes.path());
            assertEquals(ZipMethod.STORED, attributes.compressionMethod());
            assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that closing a failed WinZip AES data-descriptor entry leaves the stream at the next entry.
    @Test
    public void streamingReaderCloseAfterWinZipAesAuthenticationFailureConsumesDescriptor() throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] firstContent = "tampered AES stored descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after AES failure".getBytes(StandardCharsets.UTF_8);
        byte[] archive = winZipAesDeflatedDataDescriptorArchiveWithFollowingStoredEntry(
                password,
                firstContent,
                secondContent
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                readOptions(password)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-deflated-descriptor.txt", firstAttributes.path());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            firstInput.close();

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that WinZip AES ZIP64 stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterWinZipAesZip64StoredDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] firstContent = "AES ZIP64 stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after AES ZIP64 stored mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = winZipAesZip64StoredDataDescriptorCrcMismatchWithStoredEntry(
                password,
                firstContent,
                secondContent
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                new ByteArrayInputStream(archive),
                readOptions(password)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-zip64-stored-descriptor-crc.bin", firstAttributes.path());
            assertEquals(ZipMethod.STORED, firstAttributes.compressionMethod());
            assertEquals(ZipEncryption.WINZIP_AES_256, firstAttributes.encryption());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("WinZip AES data descriptor does not match"));
            firstInput.close();

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that a ZIP file system can create a new archive through NIO write operations.
    @Test
    public void fileSystemCreateWritesArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archivePath)) {
                assertEquals(false, fileSystem.isReadOnly());
                Path missing = fileSystem.getPath("/missing.txt");
                Path directory = fileSystem.getPath("/dir");
                Path file = fileSystem.getPath("/dir/hello.txt");
                Path link = fileSystem.getPath("/dir/link");
                assertEquals(false, Files.exists(missing));
                assertEquals(true, Files.notExists(missing));
                assertEquals(false, Files.exists(directory));
                Files.createDirectory(directory);
                assertEquals(true, Files.exists(directory));
                assertEquals(true, Files.isDirectory(directory));
                Files.writeString(file, "hello", StandardCharsets.UTF_8);
                assertEquals(true, Files.exists(file));
                Files.createSymbolicLink(link, Path.of("hello.txt"));
                assertEquals(true, Files.exists(link));
                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/dir/channel.bin"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                )) {
                    assertEquals(0, channel.position());
                    assertEquals(7, channel.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
                    assertEquals(7, channel.position());
                }
                PosixFileAttributes directoryAttributes = Files.readAttributes(directory, PosixFileAttributes.class);
                ZipArkivoEntryAttributes fileAttributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                ZipArkivoEntryAttributeView fileAttributeView =
                        Files.getFileAttributeView(file, ZipArkivoEntryAttributeView.class);
                Map<String, Object> namedAttributes = Files.readAttributes(
                        file,
                        "zip:size,compressedSize,compressionMethodId,compressionMethod"
                );
                ArrayList<String> rootChildren = new ArrayList<>();
                ArrayList<String> directoryChildren = new ArrayList<>();

                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(5L, fileAttributes.size());
                assertEquals(ZipMethod.DEFLATED, fileAttributes.compressionMethod());
                assertEquals(false, linkAttributes.isRegularFile());
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(fileSystem.getPath("hello.txt"), Files.readSymbolicLink(link));
                assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(file));
                assertNotNull(fileAttributeView);
                assertEquals(5L, fileAttributeView.readAttributes().size());
                assertEquals(
                        Set.of("size", "compressedSize", "compressionMethodId", "compressionMethod"),
                        namedAttributes.keySet()
                );
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.DEFLATED.id(), namedAttributes.get("compressionMethodId"));
                assertEquals(ZipMethod.DEFLATED, namedAttributes.get("compressionMethod"));

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                    for (Path child : stream) {
                        directoryChildren.add(child.toString());
                    }
                }
                assertEquals(List.of("/dir"), rootChildren);
                assertEquals(List.of("/dir/channel.bin", "/dir/hello.txt", "/dir/link"), directoryChildren);

                Files.createSymbolicLink(fileSystem.getPath("/dir-link"), Path.of("dir"));
                Files.createSymbolicLink(
                        fileSystem.getPath("/absolute-link"),
                        fileSystem.getPath("/dir/hello.txt")
                );
                Files.createSymbolicLink(fileSystem.getPath("/cycle-a"), Path.of("cycle-b"));
                Files.createSymbolicLink(fileSystem.getPath("/cycle-b"), Path.of("cycle-a"));
                assertSymbolicLinkIdentity(fileSystem, false);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(true, Files.isDirectory(fileSystem.getPath("/dir")));
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                assertEquals("channel", Files.readString(fileSystem.getPath("/dir/channel.bin"), StandardCharsets.UTF_8));
                Path link = fileSystem.getPath("/dir/link");
                ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(false, linkAttributes.isRegularFile());
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(0, linkAttributes.generalPurposeFlags() & (1 << 3));
                assertEquals("hello", Files.readString(link, StandardCharsets.UTF_8));
                assertEquals(fileSystem.getPath("hello.txt"), Files.readSymbolicLink(link));
                assertSymbolicLinkIdentity(fileSystem, true);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                assertSymbolicLinkIdentity(fileSystem, true);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies real-path resolution and file identity through persisted ZIP symbolic links.
    ///
    /// @param contentReadable whether completed entry bodies are readable in the current file-system mode
    private static void assertSymbolicLinkIdentity(
            ZipArkivoFileSystem fileSystem,
            boolean contentReadable
    ) throws IOException {
        Path file = fileSystem.getPath("/dir/hello.txt");
        Path link = fileSystem.getPath("/dir/link");

        assertEquals(file, file.toRealPath());
        assertEquals(file, link.toRealPath());
        assertEquals(link, link.toRealPath(LinkOption.NOFOLLOW_LINKS));
        assertEquals(file, fileSystem.getPath("/dir-link/link").toRealPath());
        assertEquals(file, fileSystem.getPath("/absolute-link").toRealPath());
        assertEquals(file, fileSystem.getPath("dir/../dir/link").toRealPath());
        if (contentReadable) {
            assertEquals("hello", Files.readString(link, StandardCharsets.UTF_8));
            try (SeekableByteChannel channel = Files.newByteChannel(link, StandardOpenOption.READ)) {
                assertEquals(5L, channel.size());
            }
        } else {
            assertThrows(IOException.class, () -> Files.readString(link, StandardCharsets.UTF_8));
        }
        assertEquals(true, Files.readAttributes(link, BasicFileAttributes.class).isRegularFile());
        assertEquals(true, Files.readAttributes(
                link,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        ).isSymbolicLink());
        BasicFileAttributeView followedBasicView = Files.getFileAttributeView(
                link,
                BasicFileAttributeView.class
        );
        BasicFileAttributeView linkBasicView = Files.getFileAttributeView(
                link,
                BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        assertNotNull(followedBasicView);
        assertNotNull(linkBasicView);
        assertEquals(true, followedBasicView.readAttributes().isRegularFile());
        assertEquals(true, linkBasicView.readAttributes().isSymbolicLink());
        ZipArkivoEntryAttributeView followedZipView = Files.getFileAttributeView(
                link,
                ZipArkivoEntryAttributeView.class
        );
        ZipArkivoEntryAttributeView linkZipView = Files.getFileAttributeView(
                link,
                ZipArkivoEntryAttributeView.class,
                LinkOption.NOFOLLOW_LINKS
        );
        assertNotNull(followedZipView);
        assertNotNull(linkZipView);
        assertEquals("dir/hello.txt", followedZipView.readAttributes().path());
        assertEquals("dir/link", linkZipView.readAttributes().path());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/dir-link"))) {
            ArrayList<String> children = new ArrayList<>();
            for (Path child : stream) {
                children.add(child.toString());
            }
            assertEquals(Set.of("/dir/channel.bin", "/dir/hello.txt", "/dir/link"), Set.copyOf(children));
        }
        assertEquals(true, Files.isSameFile(file, link));
        assertEquals(true, Files.isSameFile(file, fileSystem.getPath("/dir-link/hello.txt")));
        assertEquals(false, Files.isSameFile(file, fileSystem.getPath("/dir/channel.bin")));
        assertEquals(false, Files.isSameFile(file, Path.of("foreign")));
        assertThrows(FileSystemLoopException.class, () -> fileSystem.getPath("/cycle-a").toRealPath());
        assertThrows(NoSuchFileException.class, () -> fileSystem.getPath("/missing.txt").toRealPath());
        assertThrows(
                NoSuchFileException.class,
                () -> Files.isSameFile(fileSystem.getPath("/missing.txt"), fileSystem.getPath("/missing.txt"))
        );
    }

    /// Verifies that ZIP entries can be copied into a writable ZIP file system target.
    @Test
    public void fileSystemCreateCopiesEntryIntoWritableArchive() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-copy-source-");
        Path targetPath = sourcePath.getParent().resolve("copy-target.zip");
        FileTime lastModifiedTime = FileTime.fromMillis(1_893_456_000_000L);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(sourcePath)) {
                var helloEntry = writer.beginFile("hello.txt");
                ZipArkivoEntryAttributeView attributeView = helloEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(attributeView);
                attributeView.setTimes(lastModifiedTime, null, null);
                try (OutputStream output = helloEntry.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem sourceFileSystem = ZipArkivoFileSystem.open(sourcePath);
                 ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.create(targetPath)) {
                Path target = targetFileSystem.getPath("/copied.txt");
                assertEquals(false, Files.exists(target));
                Files.copy(
                        sourceFileSystem.getPath("/hello.txt"),
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                assertEquals(true, Files.exists(target));
            }

            try (ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals(
                        "hello",
                        Files.readString(targetFileSystem.getPath("/copied.txt"), StandardCharsets.UTF_8)
                );
                assertEquals(
                        lastModifiedTime,
                        Files.getLastModifiedTime(targetFileSystem.getPath("/copied.txt"))
                );
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that ZIP copy follows symbolic links unless `NOFOLLOW_LINKS` is requested.
    @Test
    public void fileSystemCopySymbolicLinkIntoWritableArchive() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-copy-link-source-");
        Path targetPath = sourcePath.getParent().resolve("copy-link-target.zip");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(sourcePath)) {
                var directoryEntry = writer.beginDirectory("dir");
                directoryEntry.close();
                var targetEntry = writer.beginFile("dir/target.txt");
                try (OutputStream output = targetEntry.openOutputStream()) {
                    output.write("target".getBytes(StandardCharsets.UTF_8));
                }
                var linkEntry = writer.beginSymbolicLink("dir/link", "target.txt");
                linkEntry.close();
            }

            try (ZipArkivoFileSystem sourceFileSystem = ZipArkivoFileSystem.open(sourcePath);
                 ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.create(targetPath)) {
                Path sourceLink = sourceFileSystem.getPath("/dir/link");
                Files.copy(sourceLink, targetFileSystem.getPath("/followed.txt"));
                Files.copy(sourceLink, targetFileSystem.getPath("/link"), LinkOption.NOFOLLOW_LINKS);
            }

            try (ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.open(targetPath)) {
                Path copiedLink = targetFileSystem.getPath("/link");
                assertEquals(
                        "target",
                        Files.readString(targetFileSystem.getPath("/followed.txt"), StandardCharsets.UTF_8)
                );
                assertEquals(true, Files.readAttributes(
                        copiedLink,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                ).isSymbolicLink());
                assertThrows(
                        NoSuchFileException.class,
                        () -> Files.readString(copiedLink, StandardCharsets.UTF_8)
                );
                assertEquals(targetFileSystem.getPath("target.txt"), Files.readSymbolicLink(copiedLink));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that writable ZIP file system symbolic links inherit the default encryption setting.
    @Test
    public void fileSystemCreateWritesEncryptedSymbolicLink() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-encrypted-link-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(
                    archivePath,
                    createOptions(password, ZipEncryption.ZIP_CRYPTO)
            )) {
                Files.createSymbolicLink(fileSystem.getPath("/link"), Path.of("target.txt"));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path link = fileSystem.getPath("/link");
                ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(ZipEncryption.ZIP_CRYPTO, linkAttributes.encryption());
                assertEquals(0, linkAttributes.generalPurposeFlags() & (1 << 3));
                assertThrows(NoSuchFileException.class, () -> Files.readString(link, StandardCharsets.UTF_8));
                assertEquals(fileSystem.getPath("target.txt"), Files.readSymbolicLink(link));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a writable ZIP file system stores initial POSIX permissions in external attributes.
    @Test
    public void fileSystemCreateWritesInitialPosixPermissions() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-posix-");
        Set<PosixFilePermission> directoryPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE
        );
        Set<PosixFilePermission> filePermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ
        );
        Set<PosixFilePermission> linkPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        );

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archivePath)) {
                Files.createDirectory(
                        fileSystem.getPath("/bin"),
                        PosixFilePermissions.asFileAttribute(directoryPermissions)
                );
                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/bin/tool.sh"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(filePermissions)
                )) {
                    assertEquals(2, channel.write(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8))));
                }
                Files.createSymbolicLink(
                        fileSystem.getPath("/bin/latest"),
                        Path.of("tool.sh"),
                        PosixFilePermissions.asFileAttribute(linkPermissions)
                );
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path directory = fileSystem.getPath("/bin");
                Path file = fileSystem.getPath("/bin/tool.sh");
                Path link = fileSystem.getPath("/bin/latest");
                PosixFileAttributes directoryAttributes = Files.readAttributes(directory, PosixFileAttributes.class);
                PosixFileAttributes fileAttributes = Files.readAttributes(file, PosixFileAttributes.class);
                PosixFileAttributes linkAttributes = Files.readAttributes(
                        link,
                        PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );

                assertEquals(directoryPermissions, directoryAttributes.permissions());
                assertEquals(filePermissions, fileAttributes.permissions());
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(linkPermissions, linkAttributes.permissions());
                assertEquals(fileSystem.getPath("tool.sh"), Files.readSymbolicLink(link));
                assertEquals("ok", Files.readString(file, StandardCharsets.UTF_8));
                assertEquals("ok", Files.readString(link, StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that streaming ZIP writes can publish assembled bytes through a fixed commit target.
    @Test
    public void fileSystemCreateWritesArchiveToCommitTarget() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-create-commit-source-");
        Path targetPath = sourcePath.getParent().resolve("target.zip");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(targetPath)) {
                Path committed = fileSystem.getPath("/committed.txt");
                Files.writeString(committed, "committed", StandardCharsets.UTF_8);
                var fileStore = Files.getFileStore(committed);
                assertWritableZipFileStoreAttributeViews(fileStore, false);
                assertEquals(fileStore.name(), fileStore.getAttribute("name"));
                assertEquals(fileStore.type(), fileStore.getAttribute("type"));
                assertEquals(Boolean.valueOf(false), fileStore.getAttribute("readOnly"));
                assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("basic:totalSpace"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("zip:type"));
            }

            assertEquals(false, Files.exists(sourcePath));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals("committed", Files.readString(fileSystem.getPath("/committed.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that append mode can publish an archive copy with new entries through a fixed commit target.
    @Test
    public void fileSystemAppendWritesArchiveToCommitTarget() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-append-commit-source-");
        Path targetPath = sourcePath.getParent().resolve("append-target.zip");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(sourcePath)) {
                var beforeEntry = writer.beginFile("before.txt");
                try (OutputStream output = beforeEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    sourcePath,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            )) {
                Files.writeString(fileSystem.getPath("/after.txt"), "after", StandardCharsets.UTF_8);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(sourcePath)) {
                assertEquals("before", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertThrows(
                        NoSuchFileException.class,
                        () -> Files.readString(fileSystem.getPath("/after.txt"), StandardCharsets.UTF_8)
                );
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals("before", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertEquals("after", Files.readString(fileSystem.getPath("/after.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that append mode can replace an existing ZIP file entry with a new central directory record.
    @Test
    public void fileSystemAppendReplacesExistingEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-append-replace-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var beforeEntry = writer.beginFile("before.txt");
                try (OutputStream output = beforeEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                var keepEntry = writer.beginFile("keep.txt");
                try (OutputStream output = keepEntry.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/before.txt"), "after", StandardCharsets.UTF_8);
                assertThrows(
                        FileAlreadyExistsException.class,
                        () -> Files.writeString(
                                fileSystem.getPath("/keep.txt"),
                                "ignored",
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE
                        )
                );
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("after", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that append replacement can publish the changed archive through a fixed commit target.
    @Test
    public void fileSystemAppendReplacesExistingEntryToCommitTarget() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-append-replace-commit-source-");
        Path targetPath = sourcePath.getParent().resolve("append-replace-target.zip");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(sourcePath)) {
                var beforeEntry = writer.beginFile("before.txt");
                try (OutputStream output = beforeEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                var keepEntry = writer.beginFile("keep.txt");
                try (OutputStream output = keepEntry.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    sourcePath,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            )) {
                Files.writeString(fileSystem.getPath("/before.txt"), "after", StandardCharsets.UTF_8);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(sourcePath)) {
                assertEquals("before", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            }
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals("after", Files.readString(fileSystem.getPath("/before.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that append mode can delete existing entries from the final central directory view.
    @Test
    public void fileSystemAppendDeletesExistingEntries() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-append-delete-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var directoryEntry = writer.beginDirectory("dir");
                directoryEntry.close();
                var childEntry = writer.beginFile("dir/child.txt");
                try (OutputStream output = childEntry.openOutputStream()) {
                    output.write("child".getBytes(StandardCharsets.UTF_8));
                }
                var emptyDirectoryEntry = writer.beginDirectory("empty");
                emptyDirectoryEntry.close();
                var removeEntry = writer.beginFile("remove.txt");
                try (OutputStream output = removeEntry.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                var recreateEntry = writer.beginFile("recreate.txt");
                try (OutputStream output = recreateEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                assertThrows(DirectoryNotEmptyException.class, () -> Files.delete(fileSystem.getPath("/dir")));
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.delete(fileSystem.getPath("/empty"));
                Files.delete(fileSystem.getPath("/recreate.txt"));
                Files.writeString(
                        fileSystem.getPath("/recreate.txt"),
                        "after",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE
                );
                assertThrows(NoSuchFileException.class, () -> Files.delete(fileSystem.getPath("/missing.txt")));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("child", Files.readString(fileSystem.getPath("/dir/child.txt"), StandardCharsets.UTF_8));
                assertEquals("after", Files.readString(fileSystem.getPath("/recreate.txt"), StandardCharsets.UTF_8));
                assertThrows(NoSuchFileException.class, () -> Files.readString(
                        fileSystem.getPath("/remove.txt"),
                        StandardCharsets.UTF_8
                ));
                assertThrows(NoSuchFileException.class, () -> Files.readAttributes(
                        fileSystem.getPath("/empty"),
                        ZipArkivoEntryAttributes.class
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies complete-rewrite moves preserve entry bodies, metadata, links, and local-record names.
    @Test
    public void fileSystemUpdateMovesExistingAndWrittenEntries() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-move-");
        Path foreignTarget = archivePath.getParent().resolve("foreign-move-target");
        byte[] extraData = extraField(0x7171, new byte[]{1, 2, 3, 4});
        byte[] rawComment = new byte[]{5, 6, 7};
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var directoryEntry = writer.beginDirectory("dir");
                directoryEntry.close();
                var childEntry = writer.beginFile("dir/child.txt");
                ZipArkivoEntryAttributeView childView = childEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(childView);
                childView.setCentralDirectoryExtraData(extraData);
                childView.setRawComment(rawComment);
                try (OutputStream output = childEntry.openOutputStream()) {
                    output.write("child".getBytes(StandardCharsets.UTF_8));
                }
                var linkEntry = writer.beginSymbolicLink("dir/link", "child.txt");
                linkEntry.close();
                var targetEntry = writer.beginFile("target.txt");
                try (OutputStream output = targetEntry.openOutputStream()) {
                    output.write("old-target".getBytes(StandardCharsets.UTF_8));
                }
                var replacementEntry = writer.beginFile("replacement.txt");
                try (OutputStream output = replacementEntry.openOutputStream()) {
                    output.write("new-target".getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] originalCompressedChild = compressedEntryPayload(archivePath, "dir/child.txt");

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Path directory = fileSystem.getPath("/dir");
                Path movedDirectory = fileSystem.getPath("/renamed-目录");
                Files.move(directory, movedDirectory, StandardCopyOption.ATOMIC_MOVE);
                assertEquals("child", Files.readString(
                        movedDirectory.resolve("child.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals(
                        "child.txt",
                        Files.readSymbolicLink(movedDirectory.resolve("link")).toString()
                );
                assertEquals(false, Files.exists(directory, LinkOption.NOFOLLOW_LINKS));

                Files.move(
                        fileSystem.getPath("/replacement.txt"),
                        fileSystem.getPath("/target.txt"),
                        StandardCopyOption.REPLACE_EXISTING
                );
                assertEquals("new-target", Files.readString(
                        fileSystem.getPath("/target.txt"),
                        StandardCharsets.UTF_8
                ));

                Path written = fileSystem.getPath("/written.txt");
                Files.writeString(written, "written", StandardCharsets.UTF_8);
                Path movedWritten = fileSystem.getPath("/moved-written.txt");
                Files.move(written, movedWritten);
                assertEquals("written", Files.readString(movedWritten, StandardCharsets.UTF_8));

                assertThrows(
                        FileSystemException.class,
                        () -> Files.move(movedDirectory, movedDirectory.resolve("nested"))
                );
                assertThrows(
                        FileAlreadyExistsException.class,
                        () -> Files.move(movedDirectory.resolve("link"), fileSystem.getPath("/target.txt"))
                );
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> Files.move(movedWritten, fileSystem.getPath("/ignored.txt"), LinkOption.NOFOLLOW_LINKS)
                );
                assertThrows(
                        ProviderMismatchException.class,
                        () -> fileSystem.provider().move(movedWritten, foreignTarget)
                );
                Path crossFileSystemSource = fileSystem.getPath("/cross-file-system.txt");
                Files.writeString(crossFileSystemSource, "cross", StandardCharsets.UTF_8);
                Files.move(crossFileSystemSource, foreignTarget);
                assertEquals("cross", Files.readString(foreignTarget, StandardCharsets.UTF_8));
                assertEquals(false, Files.exists(crossFileSystemSource));
                Files.move(movedWritten, movedWritten, StandardCopyOption.ATOMIC_MOVE);

                assertEquals("child", Files.readString(
                        movedDirectory.resolve("child.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals("written", Files.readString(movedWritten, StandardCharsets.UTF_8));
                assertEquals(false, Files.exists(fileSystem.getPath("/ignored.txt")));
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        movedDirectory.resolve("child.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertArrayEquals(extraData, attributes.centralDirectoryExtraData());
                assertArrayEquals(rawComment, attributes.rawComment());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path movedDirectory = fileSystem.getPath("/renamed-目录");
                assertEquals("child", Files.readString(
                        movedDirectory.resolve("child.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals(
                        "child.txt",
                        Files.readSymbolicLink(movedDirectory.resolve("link")).toString()
                );
                assertEquals("new-target", Files.readString(
                        fileSystem.getPath("/target.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals("written", Files.readString(
                        fileSystem.getPath("/moved-written.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals(false, Files.exists(fileSystem.getPath("/dir"), LinkOption.NOFOLLOW_LINKS));
                assertEquals(false, Files.exists(fileSystem.getPath("/replacement.txt")));
                assertEquals(false, Files.exists(fileSystem.getPath("/written.txt")));
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        movedDirectory.resolve("child.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertArrayEquals(extraData, attributes.centralDirectoryExtraData());
                assertArrayEquals(rawComment, attributes.rawComment());
            }

            Map<String, String> sequentialEntries = readSequentialTextEntries(archivePath);
            assertEquals("child", sequentialEntries.get("renamed-目录/child.txt"));
            assertEquals("child.txt", sequentialEntries.get("renamed-目录/link"));
            assertEquals("new-target", sequentialEntries.get("target.txt"));
            assertEquals("written", sequentialEntries.get("moved-written.txt"));
            assertEquals(false, sequentialEntries.containsKey("dir/child.txt"));
            assertEquals(false, sequentialEntries.containsKey("replacement.txt"));
            assertEquals(false, sequentialEntries.containsKey("cross-file-system.txt"));
            assertArrayEquals(
                    originalCompressedChild,
                    compressedEntryPayload(archivePath, "renamed-目录/child.txt")
            );
        } finally {
            Files.deleteIfExists(foreignTarget);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies complete-rewrite updates persist mutable ZIP metadata without rewriting entry payloads.
    @Test
    public void fileSystemUpdatePersistsEntryMetadata() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-metadata-");
        FileTime existingTime = FileTime.fromMillis(1_900_000_000_000L);
        FileTime writtenTime = FileTime.fromMillis(1_910_000_000_000L);
        FileTime encryptedTime = FileTime.fromMillis(1_800_000_000_000L);
        FileTime rejectedEncryptedTime = FileTime.fromMillis(encryptedTime.toMillis() + 7_200_000L);
        byte[] password = "metadata password".getBytes(StandardCharsets.UTF_8);
        byte[] existingComment = "updated existing".getBytes(StandardCharsets.UTF_8);
        byte[] writtenComment = "updated written".getBytes(StandardCharsets.UTF_8);
        byte[] linkComment = "updated link".getBytes(StandardCharsets.UTF_8);
        Set<PosixFilePermission> permissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        );
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password, ZipEncryption.ZIP_CRYPTO)
            )) {
                var directoryEntry = writer.beginDirectory("dir");
                directoryEntry.close();
                var existingEntry = writer.beginFile("dir/existing.txt");
                ZipArkivoEntryAttributeView existingWriteView = existingEntry.attributeView(
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(existingWriteView);
                existingWriteView.setEncryption(ZipEncryption.NONE);
                try (OutputStream output = existingEntry.openOutputStream()) {
                    output.write("existing payload".getBytes(StandardCharsets.UTF_8));
                }
                var linkEntry = writer.beginSymbolicLink("dir/link", "existing.txt");
                linkEntry.close();
                var encryptedEntry = writer.beginFile("dir/encrypted.txt");
                ZipArkivoEntryAttributeView encryptedWriteView = encryptedEntry.attributeView(
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(encryptedWriteView);
                encryptedWriteView.setTimes(encryptedTime, null, null);
                try (OutputStream output = encryptedEntry.openOutputStream()) {
                    output.write("encrypted payload".getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] originalCompressedPayload = compressedEntryPayload(archivePath, "dir/existing.txt");
            byte[] originalEncryptedPayload = compressedEntryPayload(archivePath, "dir/encrypted.txt");

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    archivePath,
                    updateOptions(ArchiveUpdateOptions.DEFAULT, password)
            )) {
                Path existing = fileSystem.getPath("/dir/existing.txt");
                Path directory = fileSystem.getPath("/dir");
                Files.setLastModifiedTime(existing, existingTime);
                Files.setAttribute(existing, "zip:rawComment", existingComment);
                ZipArkivoEntryAttributeView existingView = Files.getFileAttributeView(
                        existing,
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(existingView);
                existingView.setPermissions(permissions);
                Path link = fileSystem.getPath("/dir/link");
                ZipArkivoEntryAttributeView followedLinkView = Files.getFileAttributeView(
                        link,
                        ZipArkivoEntryAttributeView.class
                );
                ZipArkivoEntryAttributeView linkView = Files.getFileAttributeView(
                        link,
                        ZipArkivoEntryAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertNotNull(followedLinkView);
                assertNotNull(linkView);
                followedLinkView.setInternalAttributes(7);
                linkView.setRawComment(linkComment);
                PosixFileAttributeView posixView = Files.getFileAttributeView(
                        existing,
                        PosixFileAttributeView.class
                );
                assertNotNull(posixView);
                posixView.setOwner(posixView.getOwner());
                posixView.setGroup(posixView.readAttributes().group());

                ZipArkivoEntryAttributes beforeRejectedChanges = existingView.readAttributes();
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> existingView.setMethod(ZipMethod.STORED)
                );
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> existingView.setTimes(null, existingTime, null)
                );
                assertThrows(
                        IllegalArgumentException.class,
                        () -> existingView.setInternalAttributes(0x1_0000)
                );
                assertEquals(
                        beforeRejectedChanges.compressionMethod(),
                        existingView.readAttributes().compressionMethod()
                );
                assertEquals(7, existingView.readAttributes().internalAttributes());
                assertArrayEquals(linkComment, linkView.readAttributes().rawComment());

                Path encrypted = fileSystem.getPath("/dir/encrypted.txt");
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> Files.setLastModifiedTime(encrypted, rejectedEncryptedTime)
                );
                assertEquals(encryptedTime, Files.getLastModifiedTime(encrypted));

                long directoryExternalAttributes = 0x41ed_0010L;
                Files.setAttribute(directory, "zip:externalAttributes", directoryExternalAttributes);
                assertEquals(
                        directoryExternalAttributes,
                        Files.readAttributes(directory, ZipArkivoEntryAttributes.class).externalAttributes()
                );

                Path written = fileSystem.getPath("/dir/written.txt");
                Files.writeString(written, "written payload", StandardCharsets.UTF_8);
                ZipArkivoEntryAttributeView writtenView = Files.getFileAttributeView(
                        written,
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(writtenView);
                writtenView.setTimes(writtenTime, null, null);
                writtenView.setPermissions(permissions);
                writtenView.setInternalAttributes(9);
                writtenView.setRawComment(writtenComment);

                ZipArkivoEntryAttributes existingAttributes = existingView.readAttributes();
                assertEquals(existingTime, existingAttributes.lastModifiedTime());
                assertEquals(permissions, existingAttributes.permissions());
                assertEquals(7, existingAttributes.internalAttributes());
                assertArrayEquals(existingComment, existingAttributes.rawComment());
                assertArrayEquals(linkComment, Files.readAttributes(
                        fileSystem.getPath("/dir/link"),
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                ).rawComment());
                ZipArkivoEntryAttributes writtenAttributes = writtenView.readAttributes();
                assertEquals(writtenTime, writtenAttributes.lastModifiedTime());
                assertEquals(permissions, writtenAttributes.permissions());
                assertEquals(9, writtenAttributes.internalAttributes());
                assertArrayEquals(writtenComment, writtenAttributes.rawComment());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                ZipArkivoEntryAttributes existingAttributes = Files.readAttributes(
                        fileSystem.getPath("/dir/existing.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertEquals(existingTime, existingAttributes.lastModifiedTime());
                assertEquals(permissions, existingAttributes.permissions());
                assertEquals(7, existingAttributes.internalAttributes());
                assertArrayEquals(existingComment, existingAttributes.rawComment());

                ZipArkivoEntryAttributes writtenAttributes = Files.readAttributes(
                        fileSystem.getPath("/dir/written.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertEquals(writtenTime, writtenAttributes.lastModifiedTime());
                assertEquals(permissions, writtenAttributes.permissions());
                assertEquals(9, writtenAttributes.internalAttributes());
                assertArrayEquals(writtenComment, writtenAttributes.rawComment());
                assertEquals(
                        0x41ed_0010L,
                        Files.readAttributes(
                                fileSystem.getPath("/dir"),
                                ZipArkivoEntryAttributes.class
                        ).externalAttributes()
                );
                assertEquals(encryptedTime, Files.getLastModifiedTime(fileSystem.getPath("/dir/encrypted.txt")));
                assertEquals(
                        "encrypted payload",
                        Files.readString(fileSystem.getPath("/dir/encrypted.txt"), StandardCharsets.UTF_8)
                );
            }

            assertArrayEquals(
                    originalCompressedPayload,
                    compressedEntryPayload(archivePath, "dir/existing.txt")
            );
            assertArrayEquals(
                    originalEncryptedPayload,
                    compressedEntryPayload(archivePath, "dir/encrypted.txt")
            );
            assertLocalAndCentralTimestampMatch(archivePath, "dir/existing.txt");
            assertLocalAndCentralTimestampMatch(archivePath, "dir/written.txt");
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that read/write update mode can add, replace, and delete ZIP entries.
    @Test
    public void fileSystemUpdateAddsReplacesAndDeletesEntries() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-");
        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var replaceEntry = writer.beginFile("replace.txt");
                try (OutputStream output = replaceEntry.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                var removeEntry = writer.beginFile("remove.txt");
                try (OutputStream output = removeEntry.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                var keepEntry = writer.beginFile("keep.txt");
                try (OutputStream output = keepEntry.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                assertEquals(false, fileSystem.isReadOnly());
                assertEquals("before", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
                Files.writeString(fileSystem.getPath("/replace.txt"), "after", StandardCharsets.UTF_8);
                assertEquals("after", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                Files.delete(fileSystem.getPath("/remove.txt"));
                assertThrows(NoSuchFileException.class, () -> Files.readAllBytes(fileSystem.getPath("/remove.txt")));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
                assertEquals("after", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertThrows(NoSuchFileException.class, () -> Files.readString(
                        fileSystem.getPath("/remove.txt"),
                        StandardCharsets.UTF_8
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies complete-rewrite channels provide random-access NIO mutation semantics.
    @Test
    public void fileSystemUpdateSupportsRandomAccessEntryChannels() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-random-");
        byte[] removedBody = "random-update-original-secret".getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
                writeStoredZipEntry(output, "patch.txt", "abcdef".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "append.txt", "left".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "truncate.txt", "lengthy".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "replace.txt", removedBody, null, null);
                writeStoredZipEntry(output, "conflict.txt", "stable".getBytes(StandardCharsets.UTF_8), null, null);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Path patch = fileSystem.getPath("/patch.txt");
                try (SeekableByteChannel channel = Files.newByteChannel(
                        patch,
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                )) {
                    ByteBuffer prefix = ByteBuffer.allocate(2);
                    assertEquals(2, channel.read(prefix));
                    assertArrayEquals("ab".getBytes(StandardCharsets.UTF_8), prefix.array());
                    channel.position(2L);
                    assertEquals(2, channel.write(ByteBuffer.wrap("XY".getBytes(StandardCharsets.UTF_8))));
                }
                assertEquals("abXYef", Files.readString(patch, StandardCharsets.UTF_8));

                Path append = fileSystem.getPath("/append.txt");
                try (SeekableByteChannel channel = Files.newByteChannel(
                        append,
                        Set.of(StandardOpenOption.APPEND)
                )) {
                    channel.position(0L);
                    channel.write(ByteBuffer.wrap("right".getBytes(StandardCharsets.UTF_8)));
                }
                assertEquals("leftright", Files.readString(append, StandardCharsets.UTF_8));

                Path truncate = fileSystem.getPath("/truncate.txt");
                try (SeekableByteChannel channel = Files.newByteChannel(
                        truncate,
                        Set.of(StandardOpenOption.WRITE)
                )) {
                    channel.truncate(3L);
                }
                assertEquals("len", Files.readString(truncate, StandardCharsets.UTF_8));

                Path created = fileSystem.getPath("/created.txt");
                try (SeekableByteChannel channel = Files.newByteChannel(
                        created,
                        Set.of(
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.CREATE_NEW
                        )
                )) {
                    channel.write(ByteBuffer.wrap("created".getBytes(StandardCharsets.UTF_8)));
                    channel.position(0L);
                    ByteBuffer content = ByteBuffer.allocate(7);
                    assertEquals(7, channel.read(content));
                    assertArrayEquals("created".getBytes(StandardCharsets.UTF_8), content.array());
                }
                assertEquals("created", Files.readString(created, StandardCharsets.UTF_8));

                assertThrows(FileAlreadyExistsException.class, () -> {
                    try (SeekableByteChannel ignored = Files.newByteChannel(
                            patch,
                            Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW)
                    )) {
                        throw new AssertionError("CREATE_NEW unexpectedly opened an existing ZIP entry");
                    }
                });
                assertThrows(NoSuchFileException.class, () -> {
                    try (SeekableByteChannel ignored = Files.newByteChannel(
                            fileSystem.getPath("/missing.txt"),
                            Set.of(StandardOpenOption.WRITE)
                    )) {
                        throw new AssertionError("WRITE unexpectedly opened a missing ZIP entry");
                    }
                });

                Path conflict = fileSystem.getPath("/conflict.txt");
                try (SeekableByteChannel ignored = Files.newByteChannel(
                        conflict,
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                )) {
                    assertThrows(IOException.class, () -> Files.delete(fileSystem.getPath("/append.txt")));
                }
                assertEquals("stable", Files.readString(conflict, StandardCharsets.UTF_8));

                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/replace.txt"),
                        Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                )) {
                    channel.write(ByteBuffer.wrap("new".getBytes(StandardCharsets.UTF_8)));
                }
                assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("abXYef", Files.readString(fileSystem.getPath("/patch.txt"), StandardCharsets.UTF_8));
                assertEquals("leftright", Files.readString(fileSystem.getPath("/append.txt"), StandardCharsets.UTF_8));
                assertEquals("len", Files.readString(fileSystem.getPath("/truncate.txt"), StandardCharsets.UTF_8));
                assertEquals("created", Files.readString(fileSystem.getPath("/created.txt"), StandardCharsets.UTF_8));
                assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
            }
            assertEquals(false, containsBytes(Files.readAllBytes(archivePath), removedBody));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that update mode physically removes deleted, replaced, and transient local records.
    @Test
    public void fileSystemUpdateFullyRemovesLocalRecords() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-compact-");
        byte[] removedContent = "removed-local-record-secret".getBytes(StandardCharsets.UTF_8);
        byte[] replacedContent = "replaced-local-record-secret".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
                writeStoredZipEntry(output, "replace.txt", replacedContent, null, null);
                writeStoredZipEntry(output, "remove.txt", removedContent, null, null);
                writeStoredZipEntry(output, "keep.txt", "keep".getBytes(StandardCharsets.UTF_8), null, null);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/replace.txt"), "after", StandardCharsets.UTF_8);
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.writeString(fileSystem.getPath("/transient.txt"), "transient", StandardCharsets.UTF_8);
                Files.delete(fileSystem.getPath("/transient.txt"));
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            assertEquals(
                    Map.of(
                            "replace.txt", "after",
                            "keep.txt", "keep",
                            "added.txt", "added"
                    ),
                    readSequentialTextEntries(archivePath)
            );
            byte[] archive = Files.readAllBytes(archivePath);
            assertEquals(false, containsBytes(archive, removedContent));
            assertEquals(false, containsBytes(archive, replacedContent));
            assertEquals(false, containsBytes(archive, "remove.txt".getBytes(StandardCharsets.UTF_8)));
            assertEquals(false, containsBytes(archive, "transient.txt".getBytes(StandardCharsets.UTF_8)));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that update mode removes orphan records left before a later appended central directory.
    @Test
    public void fileSystemUpdateRemovesPriorAppendResidue() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-residue-");
        byte[] obsoleteContent = "obsolete-prefix-record-secret".getBytes(StandardCharsets.UTF_8);

        try {
            byte[] obsoleteArchive = singleStoredZipArchive("obsolete.txt", obsoleteContent);
            byte[] currentArchive = singleStoredZipArchive(
                    "keep.txt",
                    "keep".getBytes(StandardCharsets.UTF_8)
            );
            Files.write(archivePath, appendStandaloneZip(obsoleteArchive, currentArchive));

            assertEquals(Map.of("obsolete.txt", "obsolete-prefix-record-secret"), readSequentialTextEntries(archivePath));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertThrows(NoSuchFileException.class, () -> Files.readAllBytes(fileSystem.getPath("/obsolete.txt")));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            assertEquals(
                    Map.of("keep.txt", "keep", "added.txt", "added"),
                    readSequentialTextEntries(archivePath)
            );
            byte[] rewrittenArchive = Files.readAllBytes(archivePath);
            assertEquals(false, containsBytes(rewrittenArchive, obsoleteContent));
            assertEquals(false, containsBytes(
                    rewrittenArchive,
                    "obsolete.txt".getBytes(StandardCharsets.UTF_8)
            ));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a full update rewrite preserves preamble bytes and existing ZIP metadata.
    @Test
    public void fileSystemUpdatePreservesPreambleAndZipMetadata() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-update-preamble-");
        byte[] preamble = new byte[]{1, 3, 5, 7, 9};
        byte[] extraData = new byte[]{0x34, 0x12, 0x02, 0x00, 0x55, 0x66};
        String entryComment = "entry-comment";
        String archiveComment = "archive-comment";

        try {
            ByteArrayOutputStream zipBody = new ByteArrayOutputStream();
            try (ZipOutputStream output = new ZipOutputStream(zipBody, StandardCharsets.UTF_8)) {
                output.setComment(archiveComment);
                writeStoredZipEntry(
                        output,
                        "keep.txt",
                        "keep".getBytes(StandardCharsets.UTF_8),
                        extraData,
                        entryComment
                );
                writeStoredZipEntry(
                        output,
                        "remove.txt",
                        "remove".getBytes(StandardCharsets.UTF_8),
                        null,
                        null
                );
            }
            ByteArrayOutputStream sourceArchive = new ByteArrayOutputStream();
            sourceArchive.write(preamble);
            zipBody.writeTo(sourceArchive);
            Files.write(archivePath, sourceArchive.toByteArray());

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archivePath)) {
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            byte[] rewrittenArchive = Files.readAllBytes(archivePath);
            assertArrayEquals(preamble, Arrays.copyOf(rewrittenArchive, preamble.length));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
                ZipArkivoEntryAttributes attributes =
                        Files.readAttributes(fileSystem.getPath("/keep.txt"), ZipArkivoEntryAttributes.class);
                assertArrayEquals(extraData, attributes.localExtraData());
                assertArrayEquals(extraData, attributes.centralDirectoryExtraData());
                assertEquals(entryComment, attributes.comment());
            }
            try (ZipFile zipFile = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8)) {
                assertEquals(archiveComment, zipFile.getComment());
                assertEquals(entryComment, zipFile.getEntry("keep.txt").getComment());
                assertArrayEquals(extraData, zipFile.getEntry("keep.txt").getExtra());
                assertNull(zipFile.getEntry("remove.txt"));
                assertNotNull(zipFile.getEntry("added.txt"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that append deletion can publish the changed archive through a fixed commit target.
    @Test
    public void fileSystemAppendDeletesExistingEntryToCommitTarget() throws IOException {
        Path sourcePath = createTemporaryArchivePath("fs-append-delete-commit-source-");
        Path targetPath = sourcePath.getParent().resolve("append-delete-target.zip");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(sourcePath)) {
                var removeEntry = writer.beginFile("remove.txt");
                try (OutputStream output = removeEntry.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                var keepEntry = writer.beginFile("keep.txt");
                try (OutputStream output = keepEntry.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(
                    sourcePath,
                    updateOptions(ArchiveUpdateOptions.DEFAULT.withCommitTarget(ArkivoCommitTarget.writeTo(targetPath)))
            )) {
                Files.delete(fileSystem.getPath("/remove.txt"));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(sourcePath)) {
                assertEquals("remove", Files.readString(fileSystem.getPath("/remove.txt"), StandardCharsets.UTF_8));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            }
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(targetPath)) {
                assertThrows(NoSuchFileException.class, () -> Files.readString(
                        fileSystem.getPath("/remove.txt"),
                        StandardCharsets.UTF_8
                ));
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            deleteTemporaryArchive(sourcePath);
        }
    }

    /// Verifies that atomic commit targets leave the source path unchanged until close commits the archive.
    @Test
    public void fileSystemCreateAtomicallyReplacesSourceOnClose() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-atomic-");
        Path directory = archivePath.getParent();
        byte[] original = "not yet replaced".getBytes(StandardCharsets.UTF_8);

        try {
            Files.write(archivePath, original);
            ZipArkivoFileSystem fileSystem = ZipArkivoFileSystemProvider.instance().newFileSystem(
                    archivePath,
                    Map.of(
                            "arkivo.openOptions",
                            Set.of(
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            ),
                            "arkivo.commitTarget",
                            ArkivoCommitTarget.atomicReplace(directory)
                    )
            );
            try (fileSystem) {
                Files.writeString(fileSystem.getPath("/replacement.txt"), "replacement", StandardCharsets.UTF_8);
                assertArrayEquals(original, Files.readAllBytes(archivePath));
            }

            try (ZipArkivoFileSystem reopenedFileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(
                        "replacement",
                        Files.readString(reopenedFileSystem.getPath("/replacement.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP reader can read entries from an input stream.
    @Test
    public void streamingReaderFromInputStream() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                ArrayList<String> visited = new ArrayList<>();
                while (reader.next()) {
                    ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                    PosixFileAttributes posixAttributes = reader.readAttributes(PosixFileAttributes.class);
                    assertEquals(attributes.isDirectory(), posixAttributes.isDirectory());
                    assertEquals("owner", attributes.owner().getName());
                    assertEquals("owner", posixAttributes.owner().getName());
                    visited.add(attributes.path());
                    if (attributes.isDirectory()) {
                        assertEquals("dir/", attributes.path());
                    } else {
                        assertEquals("dir/hello.txt", attributes.path());
                        try (var input = reader.openInputStream()) {
                            assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                        }
                    }
                }
                assertEquals(List.of("dir/", "dir/hello.txt"), visited);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that malformed streaming local extra field lengths are rejected.
    @Test
    public void streamingReaderRejectsMalformedLocalExtraFieldLength() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawNameAndExtraData(
                "extra.txt".getBytes(StandardCharsets.UTF_8),
                0,
                malformedExtraField()
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
        }
    }

    /// Verifies that stored streaming ZIP entry data must match known local header metadata.
    @Test
    public void streamingReaderStoredKnownSizeMismatchIsRejected() throws IOException {
        byte[] content = "stored known size mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithContent(
                "stored.txt".getBytes(StandardCharsets.UTF_8),
                content,
                crc32(content) ^ 1L,
                content.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("stored.txt", attributes.path());
            IOException exception = assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP entry data does not match local header"));
        }
    }

    /// Verifies that deflated streaming ZIP entry data must match known local header metadata.
    @Test
    public void streamingReaderDeflatedKnownSizeMismatchIsRejected() throws IOException {
        byte[] content = "deflated known size mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingDeflatedArchiveWithContent(
                "deflated.txt".getBytes(StandardCharsets.UTF_8),
                content,
                crc32(content),
                content.length + 1L
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deflated.txt", attributes.path());
            IOException exception = assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP entry data does not match local header"));
        }
    }

    /// Verifies Deflate64 data descriptors with and without signatures preserve the following entry.
    @Test
    public void streamingReaderReadsDeflate64DataDescriptorEntries() throws IOException {
        byte[] firstName = "deflate64-descriptor.txt".getBytes(StandardCharsets.UTF_8);
        byte[] firstContent = "Deflate64 descriptor content".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after Deflate64 descriptor".getBytes(StandardCharsets.UTF_8);

        for (boolean signedDescriptor : new boolean[]{true, false}) {
            byte[] archive = streamingDeflate64DataDescriptorArchive(
                    firstName,
                    firstContent,
                    signedDescriptor,
                    secondName,
                    secondContent
            );
            try (ZipArkivoStreamingReader reader =
                         ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflate64-descriptor.txt", attributes.path());
                assertEquals(ZipMethod.DEFLATE64, attributes.compressionMethod());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(firstContent, input.readAllBytes());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                assertEquals("after.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(secondContent, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies closing a partial Deflate64 descriptor entry drains to the following local header.
    @Test
    public void streamingReaderDrainsDeflate64DataDescriptorEntryOnClose() throws IOException {
        byte[] firstContent = "partially consumed Deflate64 descriptor".repeat(256)
                .getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "entry after partial close".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingDeflate64DataDescriptorArchive(
                "partial.txt".getBytes(StandardCharsets.UTF_8),
                firstContent,
                true,
                "after.txt".getBytes(StandardCharsets.UTF_8),
                secondContent
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertEquals(Byte.toUnsignedInt(firstContent[0]), input.read());
            }
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies encrypted Deflate64 descriptor entries preserve authentication and following-entry boundaries.
    @Test
    public void streamingReaderReadsEncryptedDeflate64DataDescriptorEntries() throws IOException {
        byte[] password = "deflate64 secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted Deflate64 descriptor content".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] after = "after encrypted Deflate64".getBytes(StandardCharsets.UTF_8);

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.ZIP_CRYPTO,
                ZipEncryption.WINZIP_AES_256
        }) {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, createOptions(password))) {
                var secretEntry = writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView view = secretEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.DEFLATE64);
                view.setEncryption(encryption);
                try (OutputStream output = secretEntry.openOutputStream()) {
                    output.write(content);
                }

                var followingEntry = writer.beginFile("after.txt");
                view = followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                try (OutputStream output = followingEntry.openOutputStream()) {
                    output.write(after);
                }
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive.toByteArray()),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.DEFLATE64, attributes.compressionMethod());
                assertEquals(encryption, attributes.encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies corrupt and truncated Deflate64 data descriptors fail before parsing another record.
    @Test
    public void streamingReaderRejectsInvalidDeflate64DataDescriptors() throws IOException {
        byte[] name = "invalid-deflate64.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "invalid Deflate64 descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate64StoredBlock(content);
        byte[] archive = streamingDeflate64DataDescriptorArchive(
                name,
                content,
                true,
                "after.txt".getBytes(StandardCharsets.UTF_8),
                new byte[]{1}
        );
        int descriptorOffset = 30 + name.length + compressed.length;

        byte[] corrupt = archive.clone();
        corrupt[descriptorOffset + Integer.BYTES] ^= 1;
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(corrupt))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            IOException exception = assertThrows(IOException.class, () -> {
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("data descriptor"));
        }

        byte[] truncated = Arrays.copyOf(archive, descriptorOffset + Integer.BYTES + Integer.BYTES);
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(truncated))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            assertThrows(IOException.class, () -> {
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
        }
    }

    /// Verifies that deflated streaming ZIP entries must consume the declared compressed size.
    @Test
    public void streamingReaderDeflatedCompressedSizeMismatchIsRejected() throws IOException {
        byte[] content = "deflated compressed size mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateRaw(content);
        byte[] archive = streamingDeflatedArchiveWithContent(
                "deflated-size.txt".getBytes(StandardCharsets.UTF_8),
                compressed,
                crc32(content),
                compressed.length + 1L,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deflated-size.txt", attributes.path());
            IOException exception = assertThrows(IOException.class, () -> {
                try (var input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP entry data does not match local header"));
        }
    }

    /// Verifies that a deflated entry may contain padding inside its declared compressed-size boundary.
    @Test
    public void streamingReaderAcceptsDeflatedPaddingAndDrainsDeclaredBody() throws IOException {
        byte[] firstContent = "padded deflate body".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingDeflatedArchiveWithPaddedBodyAndStoredEntry(
                "deflated-padding.txt".getBytes(StandardCharsets.UTF_8),
                firstContent,
                1024,
                "after.txt".getBytes(StandardCharsets.UTF_8),
                secondContent
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deflated-padding.txt", firstAttributes.path());
            try (var firstInput = reader.openInputStream()) {
                assertArrayEquals(firstContent, firstInput.readAllBytes());
            }

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that the streaming ZIP reader can read stored entries written with data descriptors.
    @Test
    public void streamingReaderStoredDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stored-descriptor-");
        byte[] content = contentWithDataDescriptorSignature();

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var storedEntry = writer.beginFile("stored.txt");
                ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                try (var output = storedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored.txt", attributes.path());
                assertEquals(ZipMethod.STORED, attributes.compressionMethod());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that advancing drains a directory entry and its declared data descriptor.
    @Test
    public void streamingReaderDrainsDirectoryDataDescriptor() throws IOException {
        byte[] content = "after directory descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingDirectoryDataDescriptorWithStoredEntry(content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes directory = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("directory/", directory.path());
            assertEquals(true, directory.isDirectory());

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes file = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", file.path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that a matching signed descriptor is accepted when the local header omitted its descriptor flag.
    @Test
    public void streamingReaderAcceptsMatchingUndeclaredDataDescriptor() throws IOException {
        byte[] content = "undeclared descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithUndeclaredDataDescriptor(content, crc32(content));

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that an undeclared signed descriptor cannot override validated local-header metadata.
    @Test
    public void streamingReaderRejectsMismatchedUndeclaredDataDescriptor() throws IOException {
        byte[] content = "bad undeclared descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithUndeclaredDataDescriptor(content, crc32(content) ^ 1L);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Undeclared ZIP data descriptor"));
        }
    }

    /// Verifies that stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterStoredDataDescriptorCrcMismatchConsumesDescriptor() throws IOException {
        byte[] firstContent = "stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after stored descriptor mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredDataDescriptorCrcMismatchWithStoredEntry(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("stored-descriptor-crc.txt", firstAttributes.path());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            firstInput.close();

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that encrypted stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterTraditionalStoredDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("encrypted-stored-descriptor-crc-mismatch-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] firstContent = "encrypted stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after encrypted stored mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, createOptions(password, ZipEncryption.ZIP_CRYPTO))) {
                var encryptedEntry = writer.beginFile("encrypted-stored-descriptor-crc.txt");
                ZipArkivoEntryAttributeView firstView = encryptedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(firstView);
                firstView.setMethod(ZipMethod.STORED);
                try (var output = encryptedEntry.openOutputStream()) {
                    output.write(firstContent);
                }

                var followingEntry = writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView secondView = followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(secondView);
                secondView.setMethod(ZipMethod.STORED);
                try (var output = followingEntry.openOutputStream()) {
                    output.write(secondContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("encrypted-stored-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.STORED, firstAttributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::close);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(secondContent, secondInput.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read ZIP64 data descriptors.
    @Test
    public void streamingReaderZip64DeflatedDataDescriptor() throws IOException {
        byte[] content = "zip64 descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64DeflatedDataDescriptorArchive(content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64.txt", attributes.path());
            assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that ZIP64 stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterZip64StoredDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        byte[] firstContent = "zip64 stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after zip64 stored mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64StoredDataDescriptorCrcMismatchWithStoredEntry(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-stored-descriptor-crc.txt", firstAttributes.path());
            assertEquals(ZipMethod.STORED, firstAttributes.compressionMethod());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            firstInput.close();

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that ZIP64 extra fields do not force ZIP64 data descriptors for small streaming entries.
    @Test
    public void streamingReaderZip64ExtraWithZip32DataDescriptor() throws IOException {
        byte[] firstContent = "zip32 descriptor with zip64 extra".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "next entry".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64ExtraWithZip32DataDescriptorArchive(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-extra.txt", firstAttributes.path());
            assertEquals(ZipMethod.DEFLATED, firstAttributes.compressionMethod());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(firstContent, input.readAllBytes());
            }

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.STORED, secondAttributes.compressionMethod());
            assertEquals(secondContent.length, secondAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that the streaming ZIP reader can read ZIP64 sizes from a local header extra field.
    @Test
    public void streamingReaderZip64StoredLocalSizes() throws IOException {
        byte[] content = "zip64 local sizes".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64StoredLocalSizesArchive(content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-stored.txt", attributes.path());
            assertEquals(ZipMethod.STORED, attributes.compressionMethod());
            assertEquals(content.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies that mismatched deflated data descriptors are rejected.
    @Test
    public void streamingReaderDeflatedDataDescriptorMismatchIsRejected() throws IOException {
        Path archivePath = createTemporaryArchivePath("deflated-descriptor-mismatch-");
        byte[] content = "deflated descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                var deflatedEntry = writer.beginFile("deflated.txt");
                try (var output = deflatedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(tampered))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", attributes.path());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read traditionally encrypted entries with data descriptors.
    @Test
    public void streamingReaderTraditionalEncryptionDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("encrypted-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] deflatedContent = "encrypted deflated descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] storedContent = contentWithDataDescriptorSignature();

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, createOptions(password, ZipEncryption.ZIP_CRYPTO))) {
                var deflatedEntry = writer.beginFile("deflated.txt");
                try (var output = deflatedEntry.openOutputStream()) {
                    output.write(deflatedContent);
                }

                var storedEntry = writer.beginFile("stored.txt");
                ZipArkivoEntryAttributeView view = storedEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.STORED);
                try (var output = storedEntry.openOutputStream()) {
                    output.write(storedContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes deflatedAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", deflatedAttributes.path());
                assertEquals(ZipMethod.DEFLATED, deflatedAttributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, deflatedAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, deflatedAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(deflatedContent, input.readAllBytes());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes storedAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored.txt", storedAttributes.path());
                assertEquals(ZipMethod.STORED, storedAttributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, storedAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, storedAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(storedContent, input.readAllBytes());
                }

                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a password verification failure on a known-size encrypted entry does not consume the following entry.
    @Test
    public void streamingReaderTraditionalPasswordFailureSkipsKnownSizeEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("encrypted-password-failure-known-size-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] secretContent = "encrypted known-size content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after password failure".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    createOptions(password)
            )) {
                var secretEntry = writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView secretView = secretEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(secretView);
                secretView.setMethod(ZipMethod.STORED);
                secretView.setEncryption(ZipEncryption.ZIP_CRYPTO);
                secretView.setUncompressedSizeAndCrc32(secretContent.length, crc32(secretContent));
                try (var output = secretEntry.openOutputStream()) {
                    output.write(secretContent);
                }

                var followingEntry = writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.STORED);
                afterView.setUncompressedSizeAndCrc32(afterContent.length, crc32(afterContent));
                try (var output = followingEntry.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            ByteBuffer localHeader = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(0x04034b50, localHeader.getInt(0));
            int nameLength = Short.toUnsignedInt(localHeader.getShort(26));
            int extraLength = Short.toUnsignedInt(localHeader.getShort(28));
            int encryptionHeaderVerificationOffset = 30 + nameLength + extraLength + 12 - 1;
            archive[encryptionHeaderVerificationOffset] ^= 1;

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes secretAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret.txt", secretAttributes.path());
                assertEquals(ZipMethod.STORED, secretAttributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, secretAttributes.encryption());

                IOException exception = assertThrows(IOException.class, reader::openInputStream);
                assertEquals(true, exception.getMessage().contains("password verification failed"));

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.STORED, afterAttributes.compressionMethod());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that mismatched traditionally encrypted deflated descriptors are rejected.
    @Test
    public void streamingReaderTraditionalDeflatedDataDescriptorMismatchIsRejected() throws IOException {
        Path archivePath = createTemporaryArchivePath("encrypted-deflated-descriptor-mismatch-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted deflated descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, createOptions(password, ZipEncryption.ZIP_CRYPTO))) {
                var deflatedEntry = writer.beginFile("deflated.txt");
                try (var output = deflatedEntry.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", attributes.path());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(ZipEncryption.ZIP_CRYPTO, attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES entries can be identified and read.
    @Test
    public void winZipAesRead() throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "WinZip AES encrypted content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = winZipAesArchive(password, content);
        Path archivePath = createTemporaryArchiveContent(archive);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                Path file = fileSystem.getPath("/aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertEquals(content.length, attributes.size());
                assertEquals(true, attributes.compressedSize() > content.length);
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/aes.txt")));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("aes.txt", attributes.path());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertEquals(content.length, attributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies known-size WinZip AES entries for every additional compressed method through the streaming reader.
    @Test
    public void streamingReaderReadsKnownSizeWinZipAesCompressedMethods() throws IOException {
        byte[] password = "known size AES password".getBytes(StandardCharsets.UTF_8);
        byte[] content = "known size AES compressed content".getBytes(StandardCharsets.UTF_8);
        ZipMethod[] methods = {
                ZipMethod.BZIP2,
                ZipMethod.LZMA,
                ZipMethod.XZ,
                ZipMethod.DEFLATE64,
                ZipMethod.ZSTANDARD
        };
        byte[][] compressedBodies = {
                bzip2(content),
                lzma(content),
                xz(content),
                deflate64StoredBlock(content),
                zstandard(content)
        };

        for (int index = 0; index < methods.length; index++) {
            ZipMethod method = methods[index];
            byte[] archive = winZipAesArchive(password, content, method, compressedBodies[index]);

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(method, attributes.compressionMethod());
                assertEquals(ZipEncryption.WINZIP_AES_256, attributes.encryption());
                assertEquals(content.length, attributes.size());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        }
    }

    /// Verifies that WinZip AES authentication failures are rejected.
    @Test
    public void winZipAesAuthenticationFailureIsRejected() throws IOException {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "WinZip AES authenticated content".getBytes(StandardCharsets.UTF_8);
        byte[] archive = tamperWinZipAesAuthentication(winZipAesArchive(password, content));
        Path archivePath = createTemporaryArchiveContent(archive);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    readOptions(password)
            )) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/aes.txt"))
                );
                assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    readOptions(password)
            )) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP WinZip AES metadata must match between local and central headers.
    @Test
    public void mismatchedLocalWinZipAesExtraDataIsRejected() throws IOException {
        Path archivePath = createTemporaryArchiveContent(winZipAesArchiveWithMismatchedLocalExtra());

        try {
            IOException exception = assertThrows(IOException.class, () -> {
                try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                    Files.readAttributes(fileSystem.getPath("/aes-mismatch.txt"), ZipArkivoEntryAttributes.class);
                }
            });
            assertEquals(true, exception.getMessage().contains(
                    "ZIP local header WinZip AES extra field does not match central directory"
            ));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES placeholder entries without AES metadata are rejected as unsupported encryption.
    @Test
    public void malformedWinZipAesEncryptionIsRejected() throws IOException {
        byte[] archive = malformedWinZipAesArchive();
        Path archivePath = createTemporaryArchiveContent(archive);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bad-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertNull(attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("bad-aes.txt", attributes.path());
                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertNull(attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that method-99 entries without the encrypted flag are not treated as readable WinZip AES entries.
    @Test
    public void unencryptedWinZipAesMethodIsRejected() throws IOException {
        byte[] archive = unencryptedWinZipAesMethodArchive();
        Path archivePath = createTemporaryArchiveContent(archive);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/unencrypted-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP compression method"));
                IOException streamException = assertThrows(IOException.class, () -> Files.newInputStream(file));
                assertEquals(true, streamException.getMessage().contains("Unsupported ZIP compression method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("unencrypted-aes.txt", attributes.path());
                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertEquals(ZipEncryption.NONE, attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP compression method"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES entries with unsupported vendor versions are rejected as unsupported encryption.
    @Test
    public void invalidWinZipAesVendorVersionIsRejected() throws IOException {
        byte[] archive = malformedWinZipAesArchive(winZipAesExtraData(3));
        Path archivePath = createTemporaryArchiveContent(archive);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bad-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertNull(attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("bad-aes.txt", attributes.path());
                assertEquals(99, attributes.compressionMethodId());
                assertNull(attributes.compressionMethod());
                assertNull(attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> {
                    try (var input = reader.openInputStream()) {
                        input.readAllBytes();
                    }
                });
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry names must match central directory entry names.
    @Test
    public void rejectsMismatchedLocalEntryName() throws IOException {
        byte[] localName = "local.txt".getBytes(StandardCharsets.UTF_8);
        byte[] centralName = "entry.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawLocalAndCentralNames(
                localName,
                centralName
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/entry.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header name does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP entry comments are decoded with the configured fallback encoding.
    @Test
    public void decodesEntryCommentWithFallbackEncoding() throws IOException {
        byte[] name = "comment.txt".getBytes(StandardCharsets.UTF_8);
        byte[] rawComment = "M\u00fcnchen".getBytes(Charset.forName("IBM437"));
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameExtraAndComment(
                name,
                0,
                new byte[0],
                rawComment
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/comment.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals("M\u00fcnchen", attributes.comment());
                assertArrayEquals(rawComment, attributes.rawComment());

                Map<String, Object> namedAttributes = Files.readAttributes(file, "zip:comment,rawComment");
                assertEquals(Set.of("comment", "rawComment"), namedAttributes.keySet());
                assertEquals("M\u00fcnchen", namedAttributes.get("comment"));
                assertArrayEquals(rawComment, (byte[]) namedAttributes.get("rawComment"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a valid Info-ZIP Unicode Comment Extra Field overrides fallback comment decoding.
    @Test
    public void decodesUnicodeCommentExtraFieldBeforeFallback() throws IOException {
        byte[] name = "unicode-comment.txt".getBytes(StandardCharsets.UTF_8);
        byte[] rawComment = "legacy".getBytes(Charset.forName("IBM437"));
        byte[] centralExtraData = unicodeExtraField(0x6375, rawComment, "Gr\u00fc\u00dfe");
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameExtraAndComment(
                name,
                0,
                centralExtraData,
                rawComment
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                ZipArkivoEntryAttributes attributes =
                        Files.readAttributes(fileSystem.getPath("/unicode-comment.txt"), ZipArkivoEntryAttributes.class);

                assertEquals("Gr\u00fc\u00dfe", attributes.comment());
                assertArrayEquals(rawComment, attributes.rawComment());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that malformed central directory extra field lengths are rejected.
    @Test
    public void rejectsMalformedCentralDirectoryExtraFieldLength() throws IOException {
        byte[] name = "central-extra.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawExtraData(
                name,
                new byte[0],
                malformedExtraField()
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/central-extra.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that malformed seekable local header extra field lengths are rejected.
    @Test
    public void rejectsMalformedLocalExtraFieldLength() throws IOException {
        byte[] name = "local-extra.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawExtraData(
                name,
                malformedExtraField(),
                new byte[0]
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/local-extra.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains("Invalid ZIP extra field length"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry flags must match central directory flags.
    @Test
    public void rejectsMismatchedLocalEntryFlags() throws IOException {
        byte[] name = "flags.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                1 << 11,
                ZipMethod.STORED.id(),
                ZipMethod.STORED.id()
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/flags.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header flags do not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies a local data-descriptor flag requires a descriptor even when the central flag is clear.
    @Test
    public void rejectsMissingDataDescriptorDeclaredOnlyByLocalHeader() throws IOException {
        byte[] name = "descriptor-flags.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                1 << 3,
                0,
                ZipMethod.STORED.id(),
                ZipMethod.STORED.id()
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/descriptor-flags.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains("ZIP data descriptor does not match"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry methods must match central directory methods.
    @Test
    public void rejectsMismatchedLocalEntryMethod() throws IOException {
        byte[] name = "method.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.DEFLATED.id(),
                ZipMethod.STORED.id()
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/method.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header method does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry CRC-32 values must match central directory values.
    @Test
    public void rejectsMismatchedLocalEntryCrc32() throws IOException {
        byte[] name = "crc.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.STORED.id(),
                ZipMethod.STORED.id(),
                1,
                0,
                0,
                0,
                0,
                0
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/crc.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header CRC-32 does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry compressed sizes must match central directory sizes.
    @Test
    public void rejectsMismatchedLocalEntryCompressedSize() throws IOException {
        byte[] name = "compressed.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.STORED.id(),
                ZipMethod.STORED.id(),
                0,
                0,
                1,
                0,
                0,
                0
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/compressed.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header compressed size does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP64 compressed sizes must match central directory sizes.
    @Test
    public void rejectsMismatchedLocalZip64CompressedSize() throws IOException {
        byte[] name = "zip64-compressed.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithLocalZip64Sizes(name, 0L, 1L, 0L, 0L));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/zip64-compressed.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header compressed size does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP entry uncompressed sizes must match central directory sizes.
    @Test
    public void rejectsMismatchedLocalEntryUncompressedSize() throws IOException {
        byte[] name = "uncompressed.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.STORED.id(),
                ZipMethod.STORED.id(),
                0,
                0,
                0,
                0,
                1,
                0
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/uncompressed.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header uncompressed size does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that local ZIP64 uncompressed sizes must match central directory sizes.
    @Test
    public void rejectsMismatchedLocalZip64UncompressedSize() throws IOException {
        byte[] name = "zip64-uncompressed.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithLocalZip64Sizes(name, 1L, 0L, 0L, 0L));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(
                                fileSystem.getPath("/zip64-uncompressed.txt"),
                                ZipArkivoEntryAttributes.class
                        )
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP local header uncompressed size does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that decoded seekable channels stage sizes beyond array limits and still validate actual content.
    @Test
    public void validatesOversizedDecodedSeekableEntryWithoutArrayLimit() throws IOException {
        byte[] name = "huge-deflated.txt".getBytes(StandardCharsets.UTF_8);
        long uncompressedSize = (long) Integer.MAX_VALUE + 1L;
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.DEFLATED.id(),
                ZipMethod.DEFLATED.id(),
                0,
                0,
                0,
                0,
                uncompressedSize,
                uncompressedSize
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.newByteChannel(fileSystem.getPath("/huge-deflated.txt"))
                );
                assertEquals("Unexpected end of raw deflate stream", exception.getMessage());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that every supported non-Deflate compressed method is available through the input-stream API.
    @Test
    public void readsEverySupportedCompressedMethodThroughInputStream() throws IOException {
        byte[] content = "compressed input stream contract".getBytes(StandardCharsets.UTF_8);
        byte[] zstandard = zstandard(content);
        String[] names = {
                "deflate64.txt",
                "bzip2.txt",
                "zstandard.txt",
                "deprecated-zstandard.txt",
                "lzma.txt",
                "xz.txt"
        };
        ZipMethod[] methods = {
                ZipMethod.DEFLATE64,
                ZipMethod.BZIP2,
                ZipMethod.ZSTANDARD,
                ZipMethod.DEPRECATED_ZSTANDARD,
                ZipMethod.LZMA,
                ZipMethod.XZ
        };
        byte[][] compressedBodies = {
                deflate64StoredBlock(content),
                bzip2(content),
                zstandard,
                zstandard,
                lzma(content),
                xz(content)
        };
        int[] flags = {0, 0, 0, 0, LZMA_EOS_MARKER_FLAG, 0};

        for (int index = 0; index < methods.length; index++) {
            byte[] name = names[index].getBytes(StandardCharsets.UTF_8);
            Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                    name,
                    compressedBodies[index],
                    methods[index].id(),
                    flags[index],
                    crc32(content),
                    compressedBodies[index].length,
                    content.length
            ));

            try {
                try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath);
                     InputStream input = Files.newInputStream(
                             fileSystem.getPath("/" + names[index]),
                             StandardOpenOption.READ
                     )) {
                    assertArrayEquals(content, input.readAllBytes());
                }
            } finally {
                deleteTemporaryArchive(archivePath);
            }
        }
    }

    /// Verifies that decoded seekable ZIP channels validate inflated entry sizes against actual data.
    @Test
    public void rejectsSeekableDeflatedEntryUncompressedSizeMismatch() throws IOException {
        byte[] name = "deflated-size.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "deflated data".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateRaw(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.DEFLATED.id(),
                crc32(content),
                compressed.length,
                content.length + 1L
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.newByteChannel(fileSystem.getPath("/deflated-size.txt"))
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP entry data does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that regular ZIP entries can be read through NIO file operations.
    @Test
    public void readDeflatedZipEntries() throws IOException {
        Path archivePath = createDeflatedZipArchive();
        Path copyTarget = archivePath.getParent().resolve("copied.txt");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dir/hello.txt");

                assertEquals("hello", Files.readString(file, StandardCharsets.UTF_8));
                assertEquals(file, file.toRealPath());
                assertEquals(
                        URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri() + "!/dir/hello.txt"),
                        file.toUri()
                );
                try (var input = Files.newInputStream(file)) {
                    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }
                assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
                assertEquals(false, fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
                assertEquals(true, fileSystem.getPathMatcher("regex:.*/hello\\.txt").matches(file));
                var fileStore = Files.getFileStore(file);
                assertEquals("zip", fileStore.type());
                assertEquals(fileStore.name(), fileStore.getAttribute("name"));
                assertEquals(fileStore.type(), fileStore.getAttribute("type"));
                assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
                assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
                assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
                assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("zip:type"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));

                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(5L, attributes.size());
                assertEquals(ZipMethod.DEFLATED, attributes.compressionMethod());
                assertEquals(true, attributes.compressedSize() > 0);

                Map<String, Object> namedAttributes = Files.readAttributes(
                        file,
                        "zip:size,compressedSize,compressionMethodId,compressionMethod"
                );
                assertEquals(
                        Set.of("size", "compressedSize", "compressionMethodId", "compressionMethod"),
                        namedAttributes.keySet()
                );
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.DEFLATED.id(), namedAttributes.get("compressionMethodId"));
                assertEquals(ZipMethod.DEFLATED, namedAttributes.get("compressionMethod"));
                assertEquals(true, ((Long) namedAttributes.get("compressedSize")) > 0);

                Map<String, Object> ownerAttributes = Files.readAttributes(file, "owner:owner");
                assertEquals(Set.of("owner"), ownerAttributes.keySet());
                assertEquals(attributes.owner(), ownerAttributes.get("owner"));
                assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(file, "owner:size"));

                Map<String, Object> posixAttributes =
                        Files.readAttributes(file, "posix:size,owner,group,permissions");
                assertEquals(Set.of("size", "owner", "group", "permissions"), posixAttributes.keySet());
                assertEquals(5L, posixAttributes.get("size"));
                assertEquals(attributes.owner(), posixAttributes.get("owner"));
                assertEquals(attributes.group(), posixAttributes.get("group"));
                assertEquals(attributes.permissions(), posixAttributes.get("permissions"));

                ArrayList<String> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/dir"))) {
                    for (Path child : stream) {
                        children.add(child.toString());
                    }
                }
                assertEquals(List.of("/dir/hello.txt"), children);

                Files.copy(file, copyTarget);
                assertEquals("hello", Files.readString(copyTarget, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(copyTarget);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP64 end records can locate the central directory.
    @Test
    public void readZip64CentralDirectory() throws IOException {
        Path archivePath = createTemporaryArchiveContent(zip64CentralDirectoryArchive());

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/a");

                assertEquals("z", Files.readString(file, StandardCharsets.UTF_8));
                assertEquals(1L, Files.size(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP64 central directory offsets must fit in Java offsets.
    @Test
    public void rejectsOversizedZip64CentralDirectoryOffset() throws IOException {
        Path archivePath = createTemporaryArchiveContent(zip64CentralDirectoryArchive(Long.MIN_VALUE));

        try {
            IOException exception = assertThrows(IOException.class, () -> {
                try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                    Files.readAllBytes(fileSystem.getPath("/a"));
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP64 central directory offset is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP64 central directories too large to buffer are rejected as I/O errors.
    @Test
    public void rejectsOversizedZip64CentralDirectorySize() {
        IOException exception = assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(new OversizedCentralDirectoryVolumeSource())) {
                Files.readAllBytes(fileSystem.getPath("/missing.txt"));
            }
        });
        assertEquals(true, exception.getMessage().contains("ZIP central directory is too large to index"));
    }

    /// Verifies that unusable ZIP64 locator offsets fall back to scanning for the ZIP64 end record.
    @Test
    public void ignoresOverflowingStoredZip64EndOffset() {
        IOException exception = assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(new OversizedCentralDirectoryVolumeSource(Long.MAX_VALUE))) {
                Files.readAllBytes(fileSystem.getPath("/missing.txt"));
            }
        });
        assertEquals(true, exception.getMessage().contains("ZIP central directory is too large to index"));
    }

    /// Verifies that ZIP64 entry offsets must fit in Java offsets.
    @Test
    public void rejectsOversizedZip64EntryLocalHeaderOffset() throws IOException {
        Path archivePath = createTemporaryArchiveContent(zip64EntryWithOversizedLocalHeaderOffsetArchive());

        try {
            IOException exception = assertThrows(IOException.class, () -> {
                try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                    Files.readAllBytes(fileSystem.getPath("/zip64-offset.txt"));
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP64 extended information value is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that adjusted ZIP64 entry offsets must not overflow physical storage offsets.
    @Test
    public void rejectsOverflowingAdjustedZip64EntryLocalHeaderOffset() throws IOException {
        Path archivePath = createTemporaryArchiveContent(adjustedZip64EntryWithOverflowingLocalHeaderOffsetArchive());

        try {
            IOException exception = assertThrows(IOException.class, () -> {
                try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                    Files.readAllBytes(fileSystem.getPath("/adjusted-zip64-offset.txt"));
                }
            });
            assertEquals(true, exception.getMessage().contains("ZIP local header offset is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP local header variable-data offsets must not overflow.
    @Test
    public void rejectsOverflowingZip64LocalHeaderDataOffset() {
        IOException exception = assertThrows(IOException.class, () -> {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(new OverflowingLocalHeaderDataOffsetVolumeSource())) {
                Files.readAllBytes(fileSystem.getPath("/x"));
            }
        });
        assertEquals(
                true,
                exception.getMessage().contains("ZIP local extra data offset is too large"),
                exception.getMessage()
        );
    }

    /// Asserts that the preamble channel exposes exactly the expected preamble bytes.
    private static void assertPreambleContent(byte[] expected, ZipArkivoFileSystem fileSystem) throws IOException {
        try (SeekableByteChannel channel = fileSystem.openPreambleChannel()) {
            assertEquals(expected.length, channel.size());
            ByteBuffer buffer = ByteBuffer.allocate(expected.length);
            assertEquals(expected.length, channel.read(buffer));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertArrayEquals(expected, buffer.array());
        }
    }

    /// Reads all sequential ZIP entries as UTF-8 text through the JDK local-record reader.
    private static Map<String, String> readSequentialTextEntries(Path archivePath) throws IOException {
        LinkedHashMap<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream input = new ZipInputStream(
                Files.newInputStream(archivePath),
                StandardCharsets.UTF_8
        )) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String previous = entries.put(
                        entry.getName(),
                        new String(input.readAllBytes(), StandardCharsets.UTF_8)
                );
                if (previous != null) {
                    throw new IOException("Duplicate sequential ZIP entry: " + entry.getName());
                }
                input.closeEntry();
            }
        }
        return Map.copyOf(entries);
    }

    /// Returns the exact compressed payload bytes for one non-ZIP64 test entry.
    private static byte[] compressedEntryPayload(Path archivePath, String expectedName) throws IOException {
        byte[] archive = Files.readAllBytes(archivePath);
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = -1;
        for (int offset = archive.length - 22; offset >= 0; offset--) {
            if (buffer.getInt(offset) == 0x06054b50
                    && offset + 22 + Short.toUnsignedInt(buffer.getShort(offset + 20)) == archive.length) {
                endOffset = offset;
                break;
            }
        }
        if (endOffset < 0) {
            throw new IOException("Test ZIP end record not found");
        }

        int centralDirectoryOffset = buffer.getInt(endOffset + 16);
        int centralDirectorySize = buffer.getInt(endOffset + 12);
        int centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize;
        for (int offset = centralDirectoryOffset; offset < centralDirectoryEnd; ) {
            if (buffer.getInt(offset) != 0x02014b50) {
                throw new IOException("Test ZIP central directory entry not found");
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            String name = new String(
                    archive,
                    offset + 46,
                    nameLength,
                    StandardCharsets.UTF_8
            );
            if (expectedName.equals(name)) {
                int compressedSize = Math.toIntExact(Integer.toUnsignedLong(buffer.getInt(offset + 20)));
                int localHeaderOffset = Math.toIntExact(Integer.toUnsignedLong(buffer.getInt(offset + 42)));
                if (buffer.getInt(localHeaderOffset) != 0x04034b50) {
                    throw new IOException("Test ZIP local header not found");
                }
                int localNameLength = Short.toUnsignedInt(buffer.getShort(localHeaderOffset + 26));
                int localExtraLength = Short.toUnsignedInt(buffer.getShort(localHeaderOffset + 28));
                int dataOffset = localHeaderOffset + 30 + localNameLength + localExtraLength;
                return Arrays.copyOfRange(archive, dataOffset, dataOffset + compressedSize);
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new NoSuchFileException(expectedName);
    }

    /// Asserts that one non-ZIP64 test entry stores matching local and central DOS timestamps.
    private static void assertLocalAndCentralTimestampMatch(Path archivePath, String expectedName) throws IOException {
        byte[] archive = Files.readAllBytes(archivePath);
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = -1;
        for (int offset = archive.length - 22; offset >= 0; offset--) {
            if (buffer.getInt(offset) == 0x06054b50
                    && offset + 22 + Short.toUnsignedInt(buffer.getShort(offset + 20)) == archive.length) {
                endOffset = offset;
                break;
            }
        }
        if (endOffset < 0) {
            throw new IOException("Test ZIP end record not found");
        }

        int centralDirectoryOffset = buffer.getInt(endOffset + 16);
        int centralDirectorySize = buffer.getInt(endOffset + 12);
        int centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize;
        for (int offset = centralDirectoryOffset; offset < centralDirectoryEnd; ) {
            if (buffer.getInt(offset) != 0x02014b50) {
                throw new IOException("Test ZIP central directory entry not found");
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            String name = new String(archive, offset + 46, nameLength, StandardCharsets.UTF_8);
            if (expectedName.equals(name)) {
                int localHeaderOffset = Math.toIntExact(Integer.toUnsignedLong(buffer.getInt(offset + 42)));
                if (buffer.getInt(localHeaderOffset) != 0x04034b50) {
                    throw new IOException("Test ZIP local header not found");
                }
                assertEquals(
                        Short.toUnsignedInt(buffer.getShort(offset + 12)),
                        Short.toUnsignedInt(buffer.getShort(localHeaderOffset + 10))
                );
                assertEquals(
                        Short.toUnsignedInt(buffer.getShort(offset + 14)),
                        Short.toUnsignedInt(buffer.getShort(localHeaderOffset + 12))
                );
                return;
            }
            offset += 46 + nameLength + extraLength + commentLength;
        }
        throw new NoSuchFileException(expectedName);
    }

    /// Appends a standalone ZIP and adjusts its central offsets to the combined physical archive.
    private static byte[] appendStandaloneZip(byte[] prefix, byte[] appendedArchive) throws IOException {
        byte[] adjustedArchive = appendedArchive.clone();
        ByteBuffer buffer = ByteBuffer.wrap(adjustedArchive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = adjustedArchive.length - 22;
        if (endOffset < 0 || buffer.getInt(endOffset) != 0x06054b50) {
            throw new IOException("Test ZIP end record not found");
        }
        int centralDirectorySize = buffer.getInt(endOffset + 12);
        int centralDirectoryOffset = buffer.getInt(endOffset + 16);
        int centralDirectoryEnd = centralDirectoryOffset + centralDirectorySize;
        for (int offset = centralDirectoryOffset; offset < centralDirectoryEnd; ) {
            if (buffer.getInt(offset) != 0x02014b50) {
                throw new IOException("Test ZIP central directory entry not found");
            }
            int nameLength = Short.toUnsignedInt(buffer.getShort(offset + 28));
            int extraLength = Short.toUnsignedInt(buffer.getShort(offset + 30));
            int commentLength = Short.toUnsignedInt(buffer.getShort(offset + 32));
            long localHeaderOffset = Integer.toUnsignedLong(buffer.getInt(offset + 42));
            buffer.putInt(offset + 42, Math.toIntExact(prefix.length + localHeaderOffset));
            offset += 46 + nameLength + extraLength + commentLength;
        }
        buffer.putInt(endOffset + 16, Math.addExact(prefix.length, centralDirectoryOffset));

        byte[] combined = Arrays.copyOf(prefix, prefix.length + adjustedArchive.length);
        System.arraycopy(adjustedArchive, 0, combined, prefix.length, adjustedArchive.length);
        return combined;
    }

    /// Asserts that a read-only channel reports closed state consistently after close.
    private static void assertClosedReadOnlyChannel(SeekableByteChannel channel) throws IOException {
        assertEquals(true, channel.isOpen());
        assertThrows(NonWritableChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(NonWritableChannelException.class, () -> channel.truncate(0));

        channel.close();

        assertEquals(false, channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        channel.close();
    }

    /// Verifies common writable ZIP file store attribute view declarations.
    private static void assertWritableZipFileStoreAttributeViews(FileStore fileStore, boolean readOnly) {
        assertEquals("zip", fileStore.name());
        assertEquals("zip", fileStore.type());
        assertEquals(readOnly, fileStore.isReadOnly());
        assertEquals(true, fileStore.supportsFileAttributeView(BasicFileAttributeView.class));
        assertEquals(true, fileStore.supportsFileAttributeView(ZipArkivoEntryAttributeView.class));
        assertEquals(false, fileStore.supportsFileAttributeView(PosixFileAttributeView.class));
        assertEquals(true, fileStore.supportsFileAttributeView("basic"));
        assertEquals(true, fileStore.supportsFileAttributeView("zip"));
        assertEquals(false, fileStore.supportsFileAttributeView("owner"));
        assertEquals(false, fileStore.supportsFileAttributeView("posix"));
    }

    /// Returns a minimal seekable ZIP archive with mismatched local and central directory names.
    private static byte[] singleEntryZipWithRawLocalAndCentralNames(byte[] localName, byte[] centralName) {
        int localHeaderSize = 30 + localName.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + centralName.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeStoredLocalHeader(buffer, localName);
        writeStoredCentralDirectoryEntry(buffer, centralName, 0);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal seekable ZIP archive with configurable local and central metadata.
    private static byte[] singleEntryZipWithRawNameAndLocalCentralMetadata(
            byte[] name,
            int localFlags,
            int centralFlags,
            int localMethod,
            int centralMethod
    ) {
        return singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                localFlags,
                centralFlags,
                localMethod,
                centralMethod,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }

    /// Returns a minimal seekable ZIP archive with configurable local and central data metadata.
    private static byte[] singleEntryZipWithRawNameAndLocalCentralMetadata(
            byte[] name,
            int localFlags,
            int centralFlags,
            int localMethod,
            int centralMethod,
            long localCrc32,
            long centralCrc32,
            long localCompressedSize,
            long centralCompressedSize,
            long localUncompressedSize,
            long centralUncompressedSize
    ) {
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(
                buffer,
                name,
                localFlags,
                localMethod,
                localCrc32,
                localCompressedSize,
                localUncompressedSize
        );
        writeCentralDirectoryEntry(
                buffer,
                name,
                centralFlags,
                centralMethod,
                0,
                centralCrc32,
                centralCompressedSize,
                centralUncompressedSize
        );

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal seekable ZIP archive with one entry body.
    private static byte[] singleEntryZipWithEntryBody(
            byte[] name,
            byte[] body,
            int method,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        return singleEntryZipWithEntryBody(name, body, method, 0, crc32, compressedSize, uncompressedSize);
    }

    /// Returns a minimal seekable ZIP archive with one entry body and configurable flags.
    private static byte[] singleEntryZipWithEntryBody(
            byte[] name,
            byte[] body,
            int method,
            int flags,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + body.length;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(centralDirectoryOffset + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, flags, method, crc32, compressedSize, uncompressedSize);
        buffer.put(body);
        writeCentralDirectoryEntry(
                buffer,
                name,
                flags,
                method,
                localHeaderOffset,
                crc32,
                compressedSize,
                uncompressedSize
        );

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal seekable ZIP archive with configurable local and central extra field data.
    private static byte[] singleEntryZipWithRawExtraData(
            byte[] name,
            byte[] localExtraData,
            byte[] centralExtraData
    ) {
        int localHeaderSize = 30 + name.length + localExtraData.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length + centralExtraData.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, 0, ZipMethod.STORED.id(), 0, 0, 0, localExtraData);
        writeCentralDirectoryEntry(buffer, name, 0, ZipMethod.STORED.id(), 0, 0, 0, 0, centralExtraData);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal seekable ZIP archive with central directory extra data and an entry comment.
    private static byte[] singleEntryZipWithRawNameExtraAndComment(
            byte[] name,
            int flags,
            byte[] centralExtraData,
            byte[] comment
    ) {
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length + centralExtraData.length + comment.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, flags, ZipMethod.STORED.id());
        writeCentralDirectoryEntry(
                buffer,
                name,
                flags,
                ZipMethod.STORED.id(),
                0,
                0,
                0,
                0,
                centralExtraData,
                comment
        );

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal seekable ZIP archive with local ZIP64 sizes and central directory sizes.
    private static byte[] singleEntryZipWithLocalZip64Sizes(
            byte[] name,
            long localUncompressedSize,
            long localCompressedSize,
            long centralUncompressedSize,
            long centralCompressedSize
    ) {
        byte[] localExtra = zip64ExtendedInformationExtra(localCompressedSize, localUncompressedSize);
        int localHeaderSize = 30 + name.length + localExtra.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) name.length);
        buffer.putShort((short) localExtra.length);
        buffer.put(name);
        buffer.put(localExtra);

        writeCentralDirectoryEntry(
                buffer,
                name,
                0,
                ZipMethod.STORED.id(),
                0,
                0,
                centralCompressedSize,
                centralUncompressedSize
        );

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Writes a minimal stored local header with no content.
    private static void writeStoredLocalHeader(ByteBuffer buffer, byte[] name) {
        writeLocalHeader(buffer, name, 0, ZipMethod.STORED.id());
    }

    /// Returns the ZIP version needed to extract field for the method.
    private static int zipVersionNeeded(int method) {
        return method == ZipMethod.LZMA.id() ? LZMA_VERSION_NEEDED : 20;
    }

    /// Writes a minimal local header with no content.
    private static void writeLocalHeader(ByteBuffer buffer, byte[] name, int flags, int method) {
        writeLocalHeader(buffer, name, flags, method, 0, 0, 0);
    }

    /// Writes a minimal local header with configurable data metadata.
    private static void writeLocalHeader(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        writeLocalHeader(buffer, name, flags, method, crc32, compressedSize, uncompressedSize, new byte[0]);
    }

    /// Writes a minimal local header with configurable data metadata and extra field data.
    private static void writeLocalHeader(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            byte[] extraData
    ) {
        buffer.putInt(0x04034b50);
        buffer.putShort((short) zipVersionNeeded(method));
        buffer.putShort((short) flags);
        buffer.putShort((short) method);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32);
        buffer.putInt((int) compressedSize);
        buffer.putInt((int) uncompressedSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) extraData.length);
        buffer.put(name);
        buffer.put(extraData);
    }

    /// Writes a minimal stored central directory entry with no content.
    private static void writeStoredCentralDirectoryEntry(ByteBuffer buffer, byte[] name, int localHeaderOffset) {
        writeCentralDirectoryEntry(buffer, name, 0, ZipMethod.STORED.id(), localHeaderOffset);
    }

    /// Writes a minimal central directory entry with no content.
    private static void writeCentralDirectoryEntry(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            int localHeaderOffset
    ) {
        writeCentralDirectoryEntry(buffer, name, flags, method, localHeaderOffset, 0, 0, 0);
    }

    /// Writes a minimal central directory entry with configurable data metadata.
    private static void writeCentralDirectoryEntry(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            int localHeaderOffset,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        writeCentralDirectoryEntry(
                buffer,
                name,
                flags,
                method,
                localHeaderOffset,
                crc32,
                compressedSize,
                uncompressedSize,
                new byte[0]
        );
    }

    /// Writes a minimal central directory entry with configurable data metadata and extra field data.
    private static void writeCentralDirectoryEntry(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            int localHeaderOffset,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            byte[] extraData
    ) {
        writeCentralDirectoryEntry(
                buffer,
                name,
                flags,
                method,
                localHeaderOffset,
                crc32,
                compressedSize,
                uncompressedSize,
                extraData,
                new byte[0]
        );
    }

    /// Writes a minimal central directory entry with configurable data metadata, extra field data, and comment.
    private static void writeCentralDirectoryEntry(
            ByteBuffer buffer,
            byte[] name,
            int flags,
            int method,
            int localHeaderOffset,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            byte[] extraData,
            byte[] comment
    ) {
        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) zipVersionNeeded(method));
        buffer.putShort((short) flags);
        buffer.putShort((short) method);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32);
        buffer.putInt((int) compressedSize);
        buffer.putInt((int) uncompressedSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) extraData.length);
        buffer.putShort((short) comment.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
        buffer.put(extraData);
        buffer.put(comment);
    }

    /// Returns stored content containing the data descriptor signature byte sequence.
    private static byte[] contentWithDataDescriptorSignature() {
        return new byte[]{
                's', 't', 'o', 'r', 'e', 'd', ' ',
                0x50, 0x4b, 0x07, 0x08,
                ' ', 'n', 'o', 't', ' ', 'a', ' ',
                'd', 'e', 's', 'c', 'r', 'i', 'p', 't', 'o', 'r'
        };
    }

    /// Returns a streaming archive containing a descriptor-backed directory followed by one stored file.
    private static byte[] streamingDirectoryDataDescriptorWithStoredEntry(byte[] content) {
        byte[] directoryName = "directory/".getBytes(StandardCharsets.UTF_8);
        byte[] fileName = "after.txt".getBytes(StandardCharsets.UTF_8);
        long fileCrc32 = crc32(content);
        int directoryHeaderSize = 30 + directoryName.length;
        int fileHeaderSize = 30 + fileName.length;

        ByteBuffer buffer = ByteBuffer.allocate(directoryHeaderSize + 16 + fileHeaderSize + content.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, directoryName, 1 << 3 | 1 << 11, ZipMethod.STORED.id(), 0, 0, 0);
        buffer.putInt(0x08074b50);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        writeLocalHeader(
                buffer,
                fileName,
                1 << 11,
                ZipMethod.STORED.id(),
                fileCrc32,
                content.length,
                content.length
        );
        buffer.put(content);
        return buffer.array();
    }

    /// Returns a complete stored ZIP whose local header omits the following signed descriptor flag.
    private static byte[] streamingStoredArchiveWithUndeclaredDataDescriptor(byte[] content, long descriptorCrc32) {
        byte[] name = "undeclared.txt".getBytes(StandardCharsets.UTF_8);
        long contentCrc32 = crc32(content);
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + content.length + 16;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(centralDirectoryOffset + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(
                buffer,
                name,
                1 << 11,
                ZipMethod.STORED.id(),
                contentCrc32,
                content.length,
                content.length
        );
        buffer.put(content);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) descriptorCrc32);
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        writeCentralDirectoryEntry(
                buffer,
                name,
                1 << 3 | 1 << 11,
                ZipMethod.STORED.id(),
                0,
                contentCrc32,
                content.length,
                content.length
        );
        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal streaming stored ZIP archive with a raw entry name and extra field data.
    private static byte[] streamingStoredArchiveWithRawNameAndExtraData(byte[] name, int flags, byte[] extraData) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + extraData.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) flags);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) extraData.length);
        buffer.put(name);
        buffer.put(extraData);
        return buffer.array();
    }

    /// Returns a minimal streaming stored ZIP archive with content and local header metadata.
    private static byte[] streamingStoredArchiveWithContent(
            byte[] name,
            byte[] content,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32);
        buffer.putInt((int) compressedSize);
        buffer.putInt((int) uncompressedSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);
        return buffer.array();
    }

    /// Returns a minimal streaming deflated ZIP archive with content and local header metadata.
    private static byte[] streamingDeflatedArchiveWithContent(
            byte[] name,
            byte[] content,
            long crc32,
            long uncompressedSize
    ) throws IOException {
        byte[] compressed = deflateRaw(content);
        return streamingDeflatedArchiveWithContent(name, compressed, crc32, compressed.length, uncompressedSize);
    }

    /// Returns a minimal streaming deflated ZIP archive with compressed data and local header metadata.
    private static byte[] streamingDeflatedArchiveWithContent(
            byte[] name,
            byte[] compressed,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.DEFLATED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32);
        buffer.putInt((int) compressedSize);
        buffer.putInt((int) uncompressedSize);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(compressed);
        return buffer.array();
    }

    /// Returns a writer-produced Deflate descriptor entry followed by one stored entry.
    private static byte[] streamingDeflatedDataDescriptorArchive(
            ZipEncryption encryption,
            byte[] password,
            byte[] content,
            byte[] after
    ) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, createOptions(password))) {
            var deflatedEntry = writer.beginFile("deflated.txt");
            ZipArkivoEntryAttributeView view = deflatedEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.DEFLATED);
            view.setEncryption(encryption);
            try (OutputStream output = deflatedEntry.openOutputStream()) {
                output.write(content);
            }

            var followingEntry = writer.beginFile("after.txt");
            view = followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            try (OutputStream output = followingEntry.openOutputStream()) {
                output.write(after);
            }
        }
        return archive.toByteArray();
    }

    /// Returns a writer-produced LZMA descriptor entry followed by one stored entry.
    private static byte[] streamingLzmaDataDescriptorArchive(
            ZipEncryption encryption,
            byte[] password,
            byte[] content,
            byte[] after
    ) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, createOptions(password))) {
            var lzmaEntry = writer.beginFile("lzma.txt");
            ZipArkivoEntryAttributeView view = lzmaEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.LZMA);
            view.setEncryption(encryption);
            try (OutputStream output = lzmaEntry.openOutputStream()) {
                output.write(content);
            }

            var followingEntry = writer.beginFile("after.txt");
            view = followingEntry.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.STORED);
            try (OutputStream output = followingEntry.openOutputStream()) {
                output.write(after);
            }
        }
        return archive.toByteArray();
    }

    /// Returns a Deflate64 descriptor entry followed by one known-size stored entry.
    private static byte[] streamingDeflate64DataDescriptorArchive(
            byte[] firstName,
            byte[] firstContent,
            boolean signedDescriptor,
            byte[] secondName,
            byte[] secondContent
    ) {
        byte[] compressed = deflate64StoredBlock(firstContent);
        int descriptorSize = Integer.BYTES * (signedDescriptor ? 4 : 3);
        ByteBuffer buffer = ByteBuffer.allocate(
                30 + firstName.length + compressed.length + descriptorSize
                        + 30 + secondName.length + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) DEFLATE64_VERSION_NEEDED);
        buffer.putShort((short) (1 << 3));
        buffer.putShort((short) ZipMethod.DEFLATE64.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) 0);
        buffer.put(firstName);
        buffer.put(compressed);
        if (signedDescriptor) {
            buffer.putInt(0x08074b50);
        }
        buffer.putInt((int) crc32(firstContent));
        buffer.putInt(compressed.length);
        buffer.putInt(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32(secondContent));
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a streaming ZIP archive with a padded deflated entry followed by one stored entry.
    private static byte[] streamingDeflatedArchiveWithPaddedBodyAndStoredEntry(
            byte[] firstName,
            byte[] firstContent,
            int paddingSize,
            byte[] secondName,
            byte[] secondContent
    ) throws IOException {
        byte[] firstCompressed = deflateRaw(firstContent);
        byte[] firstBody = Arrays.copyOf(firstCompressed, firstCompressed.length + paddingSize);
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        int firstHeaderSize = 30 + firstName.length;
        int secondHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstHeaderSize + firstBody.length + secondHeaderSize + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.DEFLATED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) firstCrc32);
        buffer.putInt(firstBody.length);
        buffer.putInt(firstContent.length);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) 0);
        buffer.put(firstName);
        buffer.put(firstBody);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a stored data-descriptor entry with a bad CRC followed by one stored entry.
    private static byte[] streamingStoredDataDescriptorCrcMismatchWithStoredEntry(
            byte[] firstContent,
            byte[] secondContent
    ) {
        byte[] firstName = "stored-descriptor-crc.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        int firstHeaderSize = 30 + firstName.length;
        int secondHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstHeaderSize + firstContent.length + 16 + secondHeaderSize + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 3 | 1 << 11));
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) 0);
        buffer.put(firstName);
        buffer.put(firstContent);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) (firstCrc32 ^ 1L));
        buffer.putInt(firstContent.length);
        buffer.putInt(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a minimal streaming ZIP archive that stores a ZIP64 data descriptor.
    private static byte[] zip64DeflatedDataDescriptorArchive(byte[] content) throws IOException {
        byte[] name = "zip64.txt".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateRaw(content);
        long crc32 = crc32(content);
        byte[] zip64Extra = zip64ExtendedInformationExtra(compressed.length, content.length);
        int localHeaderSize = 30 + name.length + zip64Extra.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + compressed.length + 24)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 3 | 1 << 11));
        buffer.putShort((short) ZipMethod.DEFLATED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) name.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.put(name);
        buffer.put(zip64Extra);
        buffer.put(compressed);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) crc32);
        buffer.putLong(compressed.length);
        buffer.putLong(content.length);
        return buffer.array();
    }

    /// Returns a ZIP64 stored data-descriptor entry with a bad CRC followed by one stored entry.
    private static byte[] zip64StoredDataDescriptorCrcMismatchWithStoredEntry(
            byte[] firstContent,
            byte[] secondContent
    ) {
        byte[] firstName = "zip64-stored-descriptor-crc.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] zip64Extra = zip64ExtendedInformationExtra(firstContent.length, firstContent.length);
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        int firstHeaderSize = 30 + firstName.length + zip64Extra.length;
        int secondHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstHeaderSize + firstContent.length + 24 + secondHeaderSize + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 3 | 1 << 11));
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.put(firstName);
        buffer.put(zip64Extra);
        buffer.put(firstContent);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) (firstCrc32 ^ 1L));
        buffer.putLong(firstContent.length);
        buffer.putLong(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a streaming ZIP archive with a ZIP64 extra field and a ZIP32 data descriptor.
    private static byte[] zip64ExtraWithZip32DataDescriptorArchive(
            byte[] firstContent,
            byte[] secondContent
    ) throws IOException {
        byte[] firstName = "zip64-extra.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] firstCompressed = deflateRaw(firstContent);
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        byte[] zip64Extra = zip64ExtendedInformationExtra(firstCompressed.length, firstContent.length);
        int firstLocalHeaderSize = 30 + firstName.length + zip64Extra.length;
        int secondLocalHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstLocalHeaderSize
                        + firstCompressed.length
                        + 16
                        + secondLocalHeaderSize
                        + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 3 | 1 << 11));
        buffer.putShort((short) ZipMethod.DEFLATED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.put(firstName);
        buffer.put(zip64Extra);
        buffer.put(firstCompressed);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) firstCrc32);
        buffer.putInt(firstCompressed.length);
        buffer.putInt(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a minimal streaming ZIP archive that stores ZIP64 local header sizes.
    private static byte[] zip64StoredLocalSizesArchive(byte[] content) {
        byte[] name = "zip64-stored.txt".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content);
        byte[] zip64Extra = zip64ExtendedInformationExtra(content.length, content.length);
        int localHeaderSize = 30 + name.length + zip64Extra.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + content.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) name.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.put(name);
        buffer.put(zip64Extra);
        buffer.put(content);
        return buffer.array();
    }

    /// Returns ZIP64 extended information extra data containing sizes.
    private static byte[] zip64ExtendedInformationExtra(long compressedSize, long uncompressedSize) {
        ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x0001);
        buffer.putShort((short) 16);
        buffer.putLong(uncompressedSize);
        buffer.putLong(compressedSize);
        return buffer.array();
    }

    /// Returns a minimal streaming ZIP archive containing one WinZip AES-256 stored entry with a data descriptor.
    private static byte[] winZipAesStoredDataDescriptorArchive(byte[] password, byte[] content) throws IOException {
        byte[] name = "aes-stored-descriptor.bin".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED.id());
        byte[] encryptedBody = winZipAesEncryptedBody(password, content);
        long crc32 = crc32(content);
        int localHeaderSize = 30 + name.length + aesExtra.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + encryptedBody.length + 16)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 | 1 << 3 | 1 << 11));
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.put(name);
        buffer.put(aesExtra);
        buffer.put(encryptedBody);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) crc32);
        buffer.putInt(encryptedBody.length);
        buffer.putInt(content.length);
        return buffer.array();
    }

    /// Returns a WinZip AES ZIP64 stored entry with a bad descriptor CRC followed by one stored entry.
    private static byte[] winZipAesZip64StoredDataDescriptorCrcMismatchWithStoredEntry(
            byte[] password,
            byte[] firstContent,
            byte[] secondContent
    ) throws IOException {
        byte[] firstName = "aes-zip64-stored-descriptor-crc.bin".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED.id());
        byte[] encryptedBody = winZipAesEncryptedBody(password, firstContent);
        byte[] zip64Extra = zip64ExtendedInformationExtra(encryptedBody.length, firstContent.length);
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        int firstHeaderSize = 30 + firstName.length + aesExtra.length + zip64Extra.length;
        int secondHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstHeaderSize + encryptedBody.length + 24 + secondHeaderSize + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 | 1 << 3 | 1 << 11));
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) (aesExtra.length + zip64Extra.length));
        buffer.put(firstName);
        buffer.put(aesExtra);
        buffer.put(zip64Extra);
        buffer.put(encryptedBody);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) (firstCrc32 ^ 1L));
        buffer.putLong(encryptedBody.length);
        buffer.putLong(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns a streaming ZIP archive with a tampered WinZip AES deflated entry followed by one stored entry.
    private static byte[] winZipAesDeflatedDataDescriptorArchiveWithFollowingStoredEntry(
            byte[] password,
            byte[] firstContent,
            byte[] secondContent
    ) throws IOException {
        byte[] firstName = "aes-deflated-descriptor.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData();
        byte[] encryptedBody = winZipAesEncryptedBody(password, deflateRaw(firstContent));
        encryptedBody[encryptedBody.length - 1] ^= 1;
        long firstCrc32 = crc32(firstContent);
        long secondCrc32 = crc32(secondContent);
        int firstHeaderSize = 30 + firstName.length + aesExtra.length;
        int secondHeaderSize = 30 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                firstHeaderSize + encryptedBody.length + 16 + secondHeaderSize + secondContent.length
        ).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 | 1 << 3 | 1 << 11));
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) firstName.length);
        buffer.putShort((short) aesExtra.length);
        buffer.put(firstName);
        buffer.put(aesExtra);
        buffer.put(encryptedBody);
        buffer.putInt(0x08074b50);
        buffer.putInt((int) firstCrc32);
        buffer.putInt(encryptedBody.length);
        buffer.putInt(firstContent.length);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) secondCrc32);
        buffer.putInt(secondContent.length);
        buffer.putInt(secondContent.length);
        buffer.putShort((short) secondName.length);
        buffer.putShort((short) 0);
        buffer.put(secondName);
        buffer.put(secondContent);
        return buffer.array();
    }

    /// Returns content whose deterministic test AES ciphertext contains a data descriptor signature.
    private static byte[] contentWithAesCiphertextDescriptorSignature(byte[] password) throws IOException {
        byte[] content = "AES stored raw descriptor signature payload".getBytes(StandardCharsets.UTF_8);
        byte[] desiredCiphertext = new byte[]{0x50, 0x4b, 0x07, 0x08};
        return contentWithAesCiphertext(password, content, 10, desiredCiphertext);
    }

    /// Returns content whose deterministic test AES ciphertext contains a size-matching descriptor candidate.
    private static byte[] contentWithAesCiphertextDescriptorSizeCandidate(byte[] password) throws IOException {
        byte[] content = "AES stored raw descriptor size candidate payload".getBytes(StandardCharsets.UTF_8);
        ByteBuffer desiredCiphertext = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        desiredCiphertext.putInt(0x08074b50);
        desiredCiphertext.putInt(0);
        desiredCiphertext.putInt(28);
        desiredCiphertext.putInt(0);
        return contentWithAesCiphertext(password, content, 10, desiredCiphertext.array());
    }

    /// Returns content adjusted so deterministic test AES encryption yields desired ciphertext bytes.
    private static byte[] contentWithAesCiphertext(
            byte[] password,
            byte[] content,
            int ciphertextOffset,
            byte[] desiredCiphertext
    ) throws IOException {
        byte[] derivedKey = winZipAesDerivedKey(password, winZipAesTestSalt());

        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derivedKey, 0, 32, "AES"));
            byte[] nonceBlock = new byte[16];
            byte[] keyStream = new byte[content.length];
            int nonce = 1;
            for (int offset = 0; offset < content.length; offset += 16) {
                nonceBlock[0] = (byte) nonce;
                nonceBlock[1] = (byte) (nonce >>> 8);
                nonceBlock[2] = (byte) (nonce >>> 16);
                nonceBlock[3] = (byte) (nonce >>> 24);
                Arrays.fill(nonceBlock, 4, nonceBlock.length, (byte) 0);
                nonce++;
                byte[] keyStreamBlock = cipher.update(nonceBlock);
                System.arraycopy(
                        keyStreamBlock,
                        0,
                        keyStream,
                        offset,
                        Math.min(keyStreamBlock.length, keyStream.length - offset)
                );
            }
            for (int index = 0; index < desiredCiphertext.length; index++) {
                int contentIndex = ciphertextOffset + index;
                content[contentIndex] = (byte) (desiredCiphertext[index] ^ keyStream[contentIndex]);
            }
            return content;
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to create WinZip AES test content", exception);
        }
    }

    /// Returns a minimal ZIP archive containing one WinZip AES-256 entry.
    private static byte[] winZipAesArchive(byte[] password, byte[] content) throws IOException {
        return winZipAesArchive(password, content, ZipMethod.DEFLATED, deflateRaw(content));
    }

    /// Returns a minimal ZIP archive containing one known-size WinZip AES-256 compressed entry.
    private static byte[] winZipAesArchive(
            byte[] password,
            byte[] content,
            ZipMethod compressionMethod,
            byte[] compressedContent
    ) throws IOException {
        byte[] name = "aes.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData(2, compressionMethod.id());
        byte[] encryptedBody = winZipAesEncryptedBody(password, compressedContent);
        int encryptedSize = encryptedBody.length;
        int flags = 1 | (compressionMethod == ZipMethod.LZMA ? LZMA_EOS_MARKER_FLAG : 0);
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length + aesExtra.length;
        int centralDirectoryOffset = localHeaderSize + encryptedSize;
        int centralDirectorySize = 46 + name.length + aesExtra.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + encryptedSize
                        + centralDirectorySize
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) flags);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(encryptedSize);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.put(name);
        buffer.put(aesExtra);
        buffer.put(encryptedBody);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) flags);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(encryptedSize);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
        buffer.put(aesExtra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive whose local and central WinZip AES extra fields conflict.
    private static byte[] winZipAesArchiveWithMismatchedLocalExtra() {
        byte[] name = "aes-mismatch.txt".getBytes(StandardCharsets.UTF_8);
        byte[] localAesExtra = winZipAesExtraData(2, ZipMethod.STORED.id());
        byte[] centralAesExtra = winZipAesExtraData(2, ZipMethod.DEFLATED.id());
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length + localAesExtra.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length + centralAesExtra.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 1);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) localAesExtra.length);
        buffer.put(name);
        buffer.put(localAesExtra);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 1);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) centralAesExtra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
        buffer.put(centralAesExtra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive with an encrypted method-99 entry and no WinZip AES extra field.
    private static byte[] malformedWinZipAesArchive() {
        return malformedWinZipAesArchive(new byte[0]);
    }

    /// Returns a minimal ZIP archive with an encrypted method-99 entry and malformed WinZip AES metadata.
    private static byte[] malformedWinZipAesArchive(byte[] aesExtra) {
        byte[] name = "bad-aes.txt".getBytes(StandardCharsets.UTF_8);
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length + aesExtra.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length + aesExtra.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + centralDirectorySize
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 1);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.put(name);
        buffer.put(aesExtra);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 1);
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
        buffer.put(aesExtra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive with an unencrypted method-99 entry and valid WinZip AES metadata.
    private static byte[] unencryptedWinZipAesMethodArchive() {
        byte[] name = "unencrypted-aes.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED.id());
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length + aesExtra.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length + aesExtra.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + centralDirectorySize
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.put(name);
        buffer.put(aesExtra);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 99);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) aesExtra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
        buffer.put(aesExtra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a copy of a WinZip AES archive with the authentication code modified.
    private static byte[] tamperWinZipAesAuthentication(byte[] archive) {
        byte[] tampered = archive.clone();
        ByteBuffer localHeader = ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN);
        int encryptedSize = localHeader.getInt(18);
        int nameLength = Short.toUnsignedInt(localHeader.getShort(26));
        int extraLength = Short.toUnsignedInt(localHeader.getShort(28));
        int authenticationOffset = 30 + nameLength + extraLength + encryptedSize - 1;
        tampered[authenticationOffset] ^= 1;
        return tampered;
    }

    /// Returns a copy of a ZIP archive with the last data descriptor CRC-32 modified.
    private static byte[] tamperLastDataDescriptorCrc(byte[] archive) {
        byte[] tampered = archive.clone();
        ByteBuffer buffer = ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = tampered.length - 16; offset >= 0; offset--) {
            if (buffer.getInt(offset) == 0x08074b50) {
                tampered[offset + 4] ^= 1;
                return tampered;
            }
        }
        throw new AssertionError("data descriptor signature not found");
    }

    /// Returns raw deflate-compressed bytes for a ZIP deflated entry.
    private static byte[] deflateRaw(byte[] content) throws IOException {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            deflater.setInput(content);
            deflater.finish();
            byte[] buffer = new byte[64];
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /// Returns a WinZip AES-256 entry body for compressed content.
    private static byte[] winZipAesEncryptedBody(byte[] password, byte[] compressedContent) throws IOException {
        byte[] salt = winZipAesTestSalt();
        byte[] derivedKey = winZipAesDerivedKey(password, salt);
        byte[] encryptedContent = compressedContent.clone();

        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derivedKey, 0, 32, "AES"));
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(derivedKey, 32, 32, "HmacSHA1"));
            byte[] keyStream = new byte[16];
            byte[] nonceBlock = new byte[16];
            int nonce = 1;
            for (int offset = 0; offset < encryptedContent.length; offset += keyStream.length) {
                nonceBlock[0] = (byte) nonce;
                nonceBlock[1] = (byte) (nonce >>> 8);
                nonceBlock[2] = (byte) (nonce >>> 16);
                nonceBlock[3] = (byte) (nonce >>> 24);
                Arrays.fill(nonceBlock, 4, nonceBlock.length, (byte) 0);
                nonce++;
                keyStream = cipher.update(nonceBlock);
                int length = Math.min(keyStream.length, encryptedContent.length - offset);
                for (int index = 0; index < length; index++) {
                    encryptedContent[offset + index] ^= keyStream[index];
                }
                mac.update(encryptedContent, offset, length);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.write(salt);
            output.write(derivedKey, 64, 2);
            output.write(encryptedContent);
            output.write(mac.doFinal(), 0, 10);
            return output.toByteArray();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to create WinZip AES test body", exception);
        }
    }

    /// Returns the fixed WinZip AES salt used by test fixtures.
    private static byte[] winZipAesTestSalt() {
        return new byte[]{
                0x00, 0x01, 0x02, 0x03,
                0x04, 0x05, 0x06, 0x07,
                0x08, 0x09, 0x0a, 0x0b,
                0x0c, 0x0d, 0x0e, 0x0f
        };
    }

    /// Returns the WinZip AES-256 derived key for a test password and salt.
    private static byte[] winZipAesDerivedKey(byte[] password, byte[] salt) throws IOException {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            PBEKeySpec keySpec = new PBEKeySpec(
                    new String(password, StandardCharsets.ISO_8859_1).toCharArray(),
                    salt,
                    1000,
                    66 * 8
            );
            return factory.generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to derive WinZip AES test key", exception);
        }
    }

    /// Returns a WinZip AES extra field for AES-256 deflated content.
    private static byte[] winZipAesExtraData() {
        return winZipAesExtraData(2);
    }

    /// Returns a WinZip AES extra field with the given vendor version.
    private static byte[] winZipAesExtraData(int vendorVersion) {
        return winZipAesExtraData(vendorVersion, ZipMethod.DEFLATED.id());
    }

    /// Returns a WinZip AES extra field with the given vendor version and compression method.
    private static byte[] winZipAesExtraData(int vendorVersion, int compressionMethod) {
        ByteBuffer buffer = ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) 0x9901);
        buffer.putShort((short) 7);
        buffer.putShort((short) vendorVersion);
        buffer.putShort((short) 0x4541);
        buffer.put((byte) 3);
        buffer.putShort((short) compressionMethod);
        return buffer.array();
    }

    /// Returns a minimal ZIP64 archive whose EOCD stores central directory location through ZIP64 fields.
    private static byte[] zip64CentralDirectoryArchive() {
        return zip64CentralDirectoryArchive(0L, false);
    }

    /// Returns a minimal ZIP64 archive with an optional stored central directory offset override.
    private static byte[] zip64CentralDirectoryArchive(long storedCentralDirectoryOffsetOverride) {
        return zip64CentralDirectoryArchive(storedCentralDirectoryOffsetOverride, true);
    }

    /// Returns a minimal ZIP64 archive with configurable stored central directory offset.
    private static byte[] zip64CentralDirectoryArchive(
            long storedCentralDirectoryOffsetOverride,
            boolean overrideStoredCentralDirectoryOffset
    ) {
        byte[] name = new byte[]{'a'};
        byte[] content = new byte[]{'z'};
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + content.length;
        long storedCentralDirectoryOffset = overrideStoredCentralDirectoryOffset
                ? storedCentralDirectoryOffsetOverride
                : centralDirectoryOffset;
        int centralDirectorySize = 46 + name.length;
        int zip64EndOffset = centralDirectoryOffset + centralDirectorySize;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + content.length
                        + centralDirectorySize
                        + 56
                        + 20
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);

        buffer.putInt(0x06064b50);
        buffer.putLong(44);
        buffer.putShort((short) 45);
        buffer.putShort((short) 45);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putLong(1);
        buffer.putLong(1);
        buffer.putLong(centralDirectorySize);
        buffer.putLong(storedCentralDirectoryOffset);

        buffer.putInt(0x07064b50);
        buffer.putInt(0);
        buffer.putLong(zip64EndOffset);
        buffer.putInt(1);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0xffff);
        buffer.putShort((short) 0xffff);
        buffer.putInt(0xffffffff);
        buffer.putInt(0xffffffff);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive with an oversized ZIP64 entry local header offset.
    private static byte[] zip64EntryWithOversizedLocalHeaderOffsetArchive() {
        byte[] name = "zip64-offset.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[]{'x'};
        byte[] zip64Extra = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0x0001)
                .putShort((short) 8)
                .putLong(Long.MIN_VALUE)
                .array();
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + content.length;
        int centralDirectorySize = 46 + name.length + zip64Extra.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                localHeaderSize
                        + content.length
                        + centralDirectorySize
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.put(name);
        buffer.put(zip64Extra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a ZIP archive whose adjusted ZIP64 entry local header offset overflows.
    private static byte[] adjustedZip64EntryWithOverflowingLocalHeaderOffsetArchive() {
        byte[] preamble = new byte[]{0};
        byte[] name = "adjusted-zip64-offset.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[]{'x'};
        byte[] zip64Extra = ByteBuffer.allocate(12)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 0x0001)
                .putShort((short) 8)
                .putLong(Long.MAX_VALUE)
                .array();
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        int localHeaderOffset = preamble.length;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderOffset + localHeaderSize + content.length;
        int centralDirectorySize = 46 + name.length + zip64Extra.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                preamble.length
                        + localHeaderSize
                        + content.length
                        + centralDirectorySize
                        + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(preamble);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 45);
        buffer.putShort((short) 45);
        buffer.putShort((short) (1 << 11));
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32.getValue());
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) zip64Extra.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0xffffffff);
        buffer.put(name);
        buffer.put(zip64Extra);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset - preamble.length);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns the unsigned ZIP CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Returns malformed extra field data with an incomplete payload.
    private static byte[] malformedExtraField() {
        return new byte[]{0x01, 0x00, 0x02, 0x00, 0x00};
    }

    /// Returns an unknown ZIP extra field with the given identifier and payload.
    private static byte[] extraField(int fieldId, byte[] data) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short) fieldId);
        buffer.putShort((short) data.length);
        buffer.put(data);
        return buffer.array();
    }

    /// Returns an Info-ZIP Unicode Path or Comment Extra Field for a raw value.
    private static byte[] unicodeExtraField(int fieldId, byte[] rawValue, String value) {
        byte[] encodedValue = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer payload = ByteBuffer.allocate(1 + Integer.BYTES + encodedValue.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        payload.put((byte) 1);
        payload.putInt((int) crc32(rawValue));
        payload.put(encodedValue);
        return extraField(fieldId, payload.array());
    }

    /// Creates a temporary archive file with the given content under the module build directory.
    private static Path createTemporaryArchiveContent(byte[] content) throws IOException {
        Path archivePath = createTemporaryArchivePath("preamble-");
        Files.write(archivePath, content);
        return archivePath;
    }

    /// Creates a temporary deflated ZIP archive under the module build directory.
    private static Path createDeflatedZipArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("real-zip-");
        return ZipTestArchiveFixtures.writeDeflatedArchive(archivePath);
    }

    /// Volume source whose ZIP64 entry local header variable-data offset overflows.
    @NotNullByDefault
    private static final class OverflowingLocalHeaderDataOffsetVolumeSource implements ArkivoVolumeSource {
        /// The entry name.
        private static final byte @Unmodifiable [] NAME = new byte[]{'x'};

        /// The local header name length that overflows the variable-data offset.
        private static final int LOCAL_HEADER_NAME_LENGTH = 158;

        /// The ZIP64 local header offset extra field size.
        private static final int ZIP64_EXTRA_SIZE = 12;

        /// The central directory size.
        private static final int CENTRAL_DIRECTORY_SIZE = 46 + NAME.length + ZIP64_EXTRA_SIZE;

        /// The virtual archive size.
        private static final long SIZE = Long.MAX_VALUE;

        /// The absolute offset of the ZIP end record.
        private static final long END_RECORD_OFFSET = SIZE - 22L;

        /// The absolute offset of the ZIP64 end locator.
        private static final long ZIP64_LOCATOR_OFFSET = END_RECORD_OFFSET - 20L;

        /// The absolute offset of the ZIP64 end record.
        private static final long ZIP64_END_OFFSET = ZIP64_LOCATOR_OFFSET - 56L;

        /// The absolute offset of the central directory.
        private static final long CENTRAL_DIRECTORY_OFFSET = ZIP64_END_OFFSET - CENTRAL_DIRECTORY_SIZE;

        /// The declared local header offset that leaves no room for the local name.
        private static final long LOCAL_HEADER_OFFSET = CENTRAL_DIRECTORY_OFFSET - 30L;

        /// The central directory extra field containing the ZIP64 local header offset.
        private static final byte @Unmodifiable [] ZIP64_EXTRA = zip64LocalHeaderOffsetExtra();

        /// Opens the only sparse volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index != 0) {
                return null;
            }
            return new SparseByteChannel(
                    SIZE,
                    new SparseSegment(LOCAL_HEADER_OFFSET, localHeader()),
                    new SparseSegment(CENTRAL_DIRECTORY_OFFSET, centralDirectory()),
                    new SparseSegment(ZIP64_END_OFFSET, zip64EndRecord()),
                    new SparseSegment(ZIP64_LOCATOR_OFFSET, zip64EndLocator()),
                    new SparseSegment(END_RECORD_OFFSET, endRecord())
            );
        }

        /// Returns a local file header whose declared file name would overflow the storage offset.
        private static byte[] localHeader() {
            ByteBuffer buffer = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x04034b50);
            buffer.putShort((short) 45);
            buffer.putShort((short) (1 << 11));
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putShort((short) LOCAL_HEADER_NAME_LENGTH);
            buffer.putShort((short) 0);
            return buffer.array();
        }

        /// Returns a central directory that references the overflowing local header offset.
        private static byte[] centralDirectory() {
            ByteBuffer buffer = ByteBuffer.allocate(CENTRAL_DIRECTORY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x02014b50);
            buffer.putShort((short) 45);
            buffer.putShort((short) 45);
            buffer.putShort((short) (1 << 11));
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putShort((short) NAME.length);
            buffer.putShort((short) ZIP64_EXTRA.length);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putInt(0);
            buffer.putInt(0xffffffff);
            buffer.put(NAME);
            buffer.put(ZIP64_EXTRA);
            return buffer.array();
        }

        /// Returns a ZIP64 end record for the sparse central directory.
        private static byte[] zip64EndRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x06064b50);
            buffer.putLong(44);
            buffer.putShort((short) 45);
            buffer.putShort((short) 45);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putLong(1);
            buffer.putLong(1);
            buffer.putLong(CENTRAL_DIRECTORY_SIZE);
            buffer.putLong(CENTRAL_DIRECTORY_OFFSET);
            return buffer.array();
        }

        /// Returns a ZIP64 end locator for the sparse archive.
        private static byte[] zip64EndLocator() {
            ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x07064b50);
            buffer.putInt(0);
            buffer.putLong(ZIP64_END_OFFSET);
            buffer.putInt(1);
            return buffer.array();
        }

        /// Returns the regular ZIP end record that points to ZIP64 metadata.
        private static byte[] endRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x06054b50);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0xffff);
            buffer.putShort((short) 0xffff);
            buffer.putInt(0xffffffff);
            buffer.putInt(0xffffffff);
            buffer.putShort((short) 0);
            return buffer.array();
        }

        /// Returns a ZIP64 extra field containing only the local header offset.
        private static byte[] zip64LocalHeaderOffsetExtra() {
            ByteBuffer buffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putShort((short) 0x0001);
            buffer.putShort((short) 8);
            buffer.putLong(LOCAL_HEADER_OFFSET);
            return buffer.array();
        }
    }

    /// Volume source that exposes a sparse ZIP64 ending with an oversized central directory.
    @NotNullByDefault
    private static final class OversizedCentralDirectoryVolumeSource implements ArkivoVolumeSource {
        /// The oversized central directory size declared by the ZIP64 end record.
        private static final long CENTRAL_DIRECTORY_SIZE = (long) Integer.MAX_VALUE + 1L;

        /// The stored central directory offset declared by the ZIP64 end record.
        private static final long CENTRAL_DIRECTORY_OFFSET = 1024L;

        /// The absolute offset of the ZIP64 end record.
        private static final long ZIP64_END_OFFSET = CENTRAL_DIRECTORY_OFFSET + CENTRAL_DIRECTORY_SIZE;

        /// The absolute offset of the ZIP64 end locator.
        private static final long ZIP64_LOCATOR_OFFSET = ZIP64_END_OFFSET + 56L;

        /// The absolute offset of the ZIP end record.
        private static final long END_RECORD_OFFSET = ZIP64_LOCATOR_OFFSET + 20L;

        /// The virtual archive size.
        private static final long SIZE = END_RECORD_OFFSET + 22L;

        /// The ZIP64 end offset stored in the locator.
        private final long storedZip64EndOffset;

        /// Creates a source with a valid stored ZIP64 end offset.
        private OversizedCentralDirectoryVolumeSource() {
            this(ZIP64_END_OFFSET);
        }

        /// Creates a source with the given stored ZIP64 end offset.
        private OversizedCentralDirectoryVolumeSource(long storedZip64EndOffset) {
            this.storedZip64EndOffset = storedZip64EndOffset;
        }

        /// Opens the only sparse volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index != 0) {
                return null;
            }
            return new SparseByteChannel(
                    SIZE,
                    new SparseSegment(ZIP64_END_OFFSET, zip64EndRecord()),
                    new SparseSegment(ZIP64_LOCATOR_OFFSET, zip64EndLocator()),
                    new SparseSegment(END_RECORD_OFFSET, endRecord())
            );
        }

        /// Returns a ZIP64 end record declaring an oversized central directory.
        private static byte[] zip64EndRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x06064b50);
            buffer.putLong(44);
            buffer.putShort((short) 45);
            buffer.putShort((short) 45);
            buffer.putInt(0);
            buffer.putInt(0);
            buffer.putLong(0);
            buffer.putLong(0);
            buffer.putLong(CENTRAL_DIRECTORY_SIZE);
            buffer.putLong(CENTRAL_DIRECTORY_OFFSET);
            return buffer.array();
        }

        /// Returns a ZIP64 end locator pointing at the sparse ZIP64 end record.
        private byte[] zip64EndLocator() {
            ByteBuffer buffer = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x07064b50);
            buffer.putInt(0);
            buffer.putLong(storedZip64EndOffset);
            buffer.putInt(1);
            return buffer.array();
        }

        /// Returns a ZIP end record that delegates location data to ZIP64.
        private static byte[] endRecord() {
            ByteBuffer buffer = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putInt(0x06054b50);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0);
            buffer.putShort((short) 0xffff);
            buffer.putShort((short) 0xffff);
            buffer.putInt(0xffffffff);
            buffer.putInt(0xffffffff);
            buffer.putShort((short) 0);
            return buffer.array();
        }
    }

    /// Stores bytes at an absolute sparse channel offset.
    @NotNullByDefault
    private static final class SparseSegment {
        /// The absolute segment offset.
        private final long offset;

        /// The segment bytes.
        private final byte @Unmodifiable [] bytes;

        /// Creates a sparse segment.
        private SparseSegment(long offset, byte[] bytes) {
            if (offset < 0) {
                throw new IllegalArgumentException("offset must not be negative");
            }
            this.offset = offset;
            this.bytes = bytes.clone();
        }

        /// Returns whether this segment contains the given absolute position.
        private boolean contains(long position) {
            return offset <= position && position - offset < bytes.length;
        }

        /// Returns the byte at the given absolute position.
        private byte byteAt(long position) {
            return bytes[(int) (position - offset)];
        }
    }

    /// Sparse read-only channel used to emulate very large ZIP files in tests.
    @NotNullByDefault
    private static final class SparseByteChannel implements SeekableByteChannel {
        /// The virtual channel size.
        private final long size;

        /// The populated sparse segments.
        private final SparseSegment @Unmodifiable [] segments;

        /// The current channel position.
        private long position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a sparse channel with the given size and populated segments.
        private SparseByteChannel(long size, SparseSegment... segments) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            this.size = size;
            this.segments = segments.clone();
        }

        /// Reads bytes from the sparse channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= size) {
                return -1;
            }

            int count = 0;
            while (destination.hasRemaining() && position < size) {
                destination.put(byteAt(position));
                position++;
                count++;
            }
            return count;
        }

        /// Always rejects writes.
        @Override
        public int write(ByteBuffer source) {
            throw new NonWritableChannelException();
        }

        /// Returns the current channel position.
        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        /// Sets the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            position = newPosition;
            return this;
        }

        /// Returns the virtual channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return size;
        }

        /// Always rejects truncation.
        @Override
        public SeekableByteChannel truncate(long newSize) {
            throw new NonWritableChannelException();
        }

        /// Returns whether this channel is open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }

        /// Returns the byte at an absolute sparse position.
        private byte byteAt(long position) {
            for (SparseSegment segment : segments) {
                if (segment.contains(position)) {
                    return segment.byteAt(position);
                }
            }
            return 0;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
