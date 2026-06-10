// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic ZIP Arkivo file system behavior.
@NotNullByDefault
public final class ZipArkivoFileSystemTest {
    /// Verifies that a ZIP file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = Path.of("sample.zip");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
            assertEquals(ZipArkivoFileSystemProvider.instance(), fileSystem.provider());
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            assertEquals(true, fileSystem.isOpen());
            assertEquals(true, fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("zip"));
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("owner"));
            assertEquals(true, fileSystem.supportedFileAttributeViews().contains("posix"));
        }
    }

    /// Verifies that ZIP file systems are read-only.
    @Test
    public void zipFileSystemIsReadOnly() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            assertEquals(true, fileSystem.isReadOnly());
        }
    }

    /// Verifies that ZIP file systems expose synthesized owner and group principal lookup.
    @Test
    public void userPrincipalLookupService() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            UserPrincipalLookupService lookupService = fileSystem.getUserPrincipalLookupService();
            UserPrincipal owner = lookupService.lookupPrincipalByName("owner");
            GroupPrincipal group = lookupService.lookupPrincipalByGroupName("group");

            assertEquals("owner", owner.getName());
            assertEquals("group", group.getName());
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByName("missing")
            );
            assertThrows(
                    UserPrincipalNotFoundException.class,
                    () -> lookupService.lookupPrincipalByGroupName("missing")
            );
        }
    }

    /// Verifies that a streaming ZIP writer can write entries in storage order.
    @Test
    public void streamingWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/hello.txt");
                try (var output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("dir/empty.txt");
                writer.endEntry();
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

    /// Verifies that a streaming ZIP writer exposes pending entry attribute views.
    @Test
    public void streamingWriterEntryAttributeView() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-attrs-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("dir/hello.txt");
                BasicFileAttributeView basicView = writer.attributeView(BasicFileAttributeView.class);
                ZipArkivoEntryAttributeView zipView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                PosixFileAttributeView posixView = writer.attributeView(PosixFileAttributeView.class);
                assertNotNull(basicView);
                assertNotNull(zipView);
                assertNotNull(posixView);
                assertEquals("posix", posixView.name());
                assertThrows(UnsupportedOperationException.class, () -> zipView.setOwner(posixView.getOwner()));
                assertThrows(UnsupportedOperationException.class, () -> zipView.setGroup(posixView.readAttributes().group()));
                posixView.setOwner(posixView.getOwner());
                posixView.setGroup(posixView.readAttributes().group());
                assertThrows(UserPrincipalNotFoundException.class, () -> posixView.setOwner(() -> "missing"));
                assertThrows(UserPrincipalNotFoundException.class, () -> posixView.setGroup(() -> "missing"));

                zipView.setMethod(ZipMethod.deflated());
                Set<PosixFilePermission> permissions = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ
                );
                zipView.setPermissions(permissions);
                ZipArkivoEntryAttributes attributes = zipView.readAttributes();
                assertEquals("dir/hello.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(permissions, attributes.permissions());

                try (var output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                assertThrows(IllegalStateException.class, () -> zipView.setMethod(ZipMethod.stored()));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                PosixFileAttributes attributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), PosixFileAttributes.class);
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ
                ), attributes.permissions());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer persists ZIP metadata for stored entries.
    @Test
    public void streamingWriterStoredEntryMetadata() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-stored-");
        byte[] content = "stored-content".getBytes(StandardCharsets.UTF_8);
        byte[] localExtraData = new byte[]{1, 2, 3};
        byte[] centralExtraData = new byte[]{4, 5};
        byte[] rawComment = new byte[]{6, 7, 8};
        FileTime lastModifiedTime = FileTime.fromMillis(1_893_456_000_000L);
        long crc32 = crc32(content);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("meta");
                ZipArkivoEntryAttributeView directoryView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(directoryView);
                directoryView.setTimes(lastModifiedTime, null, null);
                directoryView.setRawComment(rawComment);
                writer.endEntry();

                writer.beginFile("meta/stored.bin");
                ZipArkivoEntryAttributeView fileView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(fileView);
                fileView.setMethod(ZipMethod.stored());
                fileView.setTimes(lastModifiedTime, null, null);
                fileView.setUncompressedSizeAndCrc32(content.length, crc32);
                fileView.setInternalAttributes(1);
                fileView.setExternalAttributes(0x20L);
                fileView.setLocalExtraData(localExtraData);
                fileView.setCentralDirectoryExtraData(centralExtraData);
                fileView.setRawComment(rawComment);
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }

                writer.beginSymbolicLink("meta/link", "stored.bin");
                writer.endEntry();
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                ZipArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta"), ZipArkivoEntryAttributes.class);
                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(ZipMethod.stored(), directoryAttributes.method());
                assertArrayEquals(rawComment, directoryAttributes.rawComment());
                assertEquals(lastModifiedTime, directoryAttributes.lastModifiedTime());

                ZipArkivoEntryAttributes fileAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta/stored.bin"), ZipArkivoEntryAttributes.class);
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/meta/stored.bin")));
                assertEquals(ZipMethod.stored(), fileAttributes.method());
                assertEquals(content.length, fileAttributes.compressedSize());
                assertEquals(content.length, fileAttributes.size());
                assertEquals(crc32, fileAttributes.crc32());
                assertEquals(1, fileAttributes.internalAttributes());
                assertEquals(0x20L, fileAttributes.externalAttributes());
                assertArrayEquals(localExtraData, fileAttributes.localExtraData());
                assertArrayEquals(centralExtraData, fileAttributes.centralDirectoryExtraData());
                assertArrayEquals(rawComment, fileAttributes.rawComment());
                assertEquals(lastModifiedTime, fileAttributes.lastModifiedTime());

                ZipArkivoEntryAttributes linkAttributes =
                        Files.readAttributes(fileSystem.getPath("/meta/link"), ZipArkivoEntryAttributes.class);
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals("stored.bin", Files.readString(fileSystem.getPath("/meta/link"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that default traditional ZIP encryption is applied to file entries.
    @Test
    public void streamingWriterDefaultTraditionalEncryption() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-encrypted-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted deflated content".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginDirectory("secure");
                writer.endEntry();

                writer.beginFile("secure/message.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                assertEquals(ZipEncryption.traditional(), view.readAttributes().encryption());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path directory = fileSystem.getPath("/secure");
                Path file = fileSystem.getPath("/secure/message.txt");
                ZipArkivoEntryAttributes directoryAttributes =
                        Files.readAttributes(directory, ZipArkivoEntryAttributes.class);
                ZipArkivoEntryAttributes fileAttributes =
                        Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipEncryption.none(), directoryAttributes.encryption());
                assertEquals(ZipEncryption.traditional(), fileAttributes.encryption());
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), "wrong".getBytes(StandardCharsets.UTF_8))
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                writer.beginFile("stored.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                view.setEncryption(ZipEncryption.traditional());
                view.setUncompressedSizeAndCrc32(content.length, crc32);
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/stored.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipEncryption.traditional(), attributes.encryption());
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.winZipAes256()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginFile("secure/aes.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                assertEquals(ZipEncryption.winZipAes256(), view.readAttributes().encryption());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/secure/aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/secure/aes.txt")));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secure/aes.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }

            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.winZipAes256()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginFile("empty-password-aes.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/empty-password-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("empty-password-aes.txt", attributes.path());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                writer.beginFile("stored-aes.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                view.setEncryption(ZipEncryption.winZipAes128());
                view.setUncompressedSizeAndCrc32(content.length, crc32);
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/stored-aes.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipEncryption.winZipAes128(), attributes.encryption());
                assertEquals(content.length + 20L, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32, attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored-aes.bin", attributes.path());
                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipEncryption.winZipAes128(), attributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                writer.beginFile("stored-aes-descriptor.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                view.setEncryption(ZipEncryption.winZipAes192());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/stored-aes-descriptor.bin");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipEncryption.winZipAes192(), attributes.encryption());
                assertEquals(content.length + 24L, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored-aes-descriptor.bin", attributes.path());
                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipEncryption.winZipAes192(), attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }

            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
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
                Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
        )) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-stored-descriptor.bin", attributes.path());
            assertEquals(ZipMethod.stored(), attributes.method());
            assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
                Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
        )) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-stored-descriptor.bin", attributes.path());
            assertEquals(ZipMethod.stored(), attributes.method());
            assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that encrypted streaming ZIP writes require a password.
    @Test
    public void streamingWriterTraditionalEncryptionRequiresPassword() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-encrypted-no-password-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setEncryption(ZipEncryption.traditional());
                assertThrows(IOException.class, writer::openOutputStream);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a ZIP file system can create a new archive through NIO write operations.
    @Test
    public void fileSystemCreateWritesArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-");

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(
                                 archivePath,
                                 Map.of(
                                         ArkivoFileSystem.OPEN_OPTIONS.key(),
                                         Set.of(
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING,
                                                 StandardOpenOption.WRITE
                                         )
                                 )
                         )) {
                assertEquals(false, fileSystem.isReadOnly());
                Files.createDirectory(fileSystem.getPath("/dir"));
                Files.writeString(fileSystem.getPath("/dir/hello.txt"), "hello", StandardCharsets.UTF_8);
                try (SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/dir/channel.bin"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                )) {
                    assertEquals(0, channel.position());
                    assertEquals(7, channel.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
                    assertEquals(7, channel.position());
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(true, Files.isDirectory(fileSystem.getPath("/dir")));
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                assertEquals("channel", Files.readString(fileSystem.getPath("/dir/channel.bin"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that open options accept array values.
    @Test
    public void fileSystemOpenOptionsAcceptArrayValues() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-array-");

        try {
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(
                                 archivePath,
                                 Map.of(
                                         ArkivoFileSystem.OPEN_OPTIONS.key(),
                                         new StandardOpenOption[]{
                                                 StandardOpenOption.CREATE_NEW,
                                                 StandardOpenOption.WRITE
                                         }
                                 )
                         )) {
                Files.writeString(fileSystem.getPath("/hello.txt"), "hello", StandardCharsets.UTF_8);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that unsafe write open options are rejected.
    @Test
    public void fileSystemOpenOptionsRejectUnsafeWriteValues() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-invalid-");

        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ZipArkivoFileSystem.open(
                            archivePath,
                            Map.of(
                                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                            )
                    )
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that read open options allow provider-specific non-write options.
    @Test
    public void fileSystemReadOpenOptionsAcceptProviderOptions() {
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromEnvironment(Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.<OpenOption>of(StandardOpenOption.READ, TestOpenOption.DIRECT)
        ));

        assertEquals(false, config.archiveWritable());
        assertEquals(Set.<OpenOption>of(StandardOpenOption.READ, TestOpenOption.DIRECT), config.openOptions());
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

    /// Verifies that malformed UTF-8 streaming entry names are rejected.
    @Test
    public void streamingReaderRejectsMalformedUtf8Name() throws IOException {
        int utf8Flag = 1 << 11;
        byte[] archive = streamingStoredArchiveWithRawName(new byte[]{(byte) 0xc3, 0x28}, utf8Flag);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("Failed to decode ZIP entry name"));
        }
    }

    /// Verifies that streaming ZIP entries must have a decoded path.
    @Test
    public void streamingReaderRejectsEmptyName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName(new byte[0], 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry is missing a path"));
        }
    }

    /// Verifies that `.` is not enough to form a streaming ZIP entry path.
    @Test
    public void streamingReaderRejectsDotOnlyName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName(new byte[]{'.'}, 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry is missing a path"));
        }
    }

    /// Verifies that streaming ZIP entry paths cannot contain parent-directory segments.
    @Test
    public void streamingReaderRejectsParentSegmentName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName("../evil.txt".getBytes(StandardCharsets.UTF_8), 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry path must not contain .."));
        }
    }

    /// Verifies that streaming ZIP entry paths must be relative.
    @Test
    public void streamingReaderRejectsAbsoluteName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName("/evil.txt".getBytes(StandardCharsets.UTF_8), 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry path must be relative"));
        }
    }

    /// Verifies that streaming ZIP entry paths cannot contain drive roots.
    @Test
    public void streamingReaderRejectsDriveRootName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName("C:/evil.txt".getBytes(StandardCharsets.UTF_8), 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry path must be relative"));
        }
    }

    /// Verifies that backslash-separated parent-directory segments are rejected by the streaming ZIP reader.
    @Test
    public void streamingReaderRejectsBackslashParentSegmentName() throws IOException {
        byte[] archive = streamingStoredArchiveWithRawName("..\\evil.txt".getBytes(StandardCharsets.UTF_8), 0);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            IOException exception = assertThrows(IOException.class, reader::next);
            assertEquals(true, exception.getMessage().contains("ZIP entry path must not contain .."));
        }
    }

    /// Verifies that the streaming ZIP reader can read stored entries written with data descriptors.
    @Test
    public void streamingReaderStoredDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("stored-descriptor-");
        byte[] content = contentWithDataDescriptorSignature();

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("stored.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored.txt", attributes.path());
                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64.txt", attributes.path());
            assertEquals(ZipMethod.deflated(), attributes.method());
            assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that ZIP64 extra fields do not force ZIP64 data descriptors for small streaming entries.
    @Test
    public void streamingReaderZip64ExtraWithZip32DataDescriptor() throws IOException {
        byte[] firstContent = "zip32 descriptor with zip64 extra".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "next entry".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64ExtraWithZip32DataDescriptorArchive(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-extra.txt", firstAttributes.path());
            assertEquals(ZipMethod.deflated(), firstAttributes.method());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(firstContent, input.readAllBytes());
            }

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.stored(), secondAttributes.method());
            assertEquals(secondContent.length, secondAttributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that the streaming ZIP reader can read ZIP64 sizes from a local header extra field.
    @Test
    public void streamingReaderZip64StoredLocalSizes() throws IOException {
        byte[] content = "zip64 local sizes".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64StoredLocalSizesArchive(content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-stored.txt", attributes.path());
            assertEquals(ZipMethod.stored(), attributes.method());
            assertEquals(content.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that mismatched deflated data descriptors are rejected.
    @Test
    public void streamingReaderDeflatedDataDescriptorMismatchIsRejected() throws IOException {
        Path archivePath = createTemporaryArchivePath("deflated-descriptor-mismatch-");
        byte[] content = "deflated descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("deflated.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(tampered))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginFile("deflated.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(deflatedContent);
                }

                writer.beginFile("stored.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(storedContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes deflatedAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", deflatedAttributes.path());
                assertEquals(ZipMethod.deflated(), deflatedAttributes.method());
                assertEquals(ZipEncryption.traditional(), deflatedAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, deflatedAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(deflatedContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes storedAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("stored.txt", storedAttributes.path());
                assertEquals(ZipMethod.stored(), storedAttributes.method());
                assertEquals(ZipEncryption.traditional(), storedAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, storedAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(storedContent, input.readAllBytes());
                }

                assertEquals(false, reader.next());
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginFile("deflated.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflated.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(ZipEncryption.traditional(), attributes.encryption());
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                Path file = fileSystem.getPath("/aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("aes.txt", attributes.path());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                assertEquals(content.length, attributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/aes.txt"))
                );
                assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
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

    /// Verifies that WinZip AES placeholder entries without AES metadata are rejected as unsupported encryption.
    @Test
    public void malformedWinZipAesEncryptionIsRejected() throws IOException {
        byte[] archive = malformedWinZipAesArchive();
        Path archivePath = createTemporaryArchiveContent(archive);
        ZipEncryption unknownAes = ZipEncryption.of("winzip-aes-unknown");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bad-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(unknownAes, attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("bad-aes.txt", attributes.path());
                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(unknownAes, attributes.encryption());
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

                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(ZipEncryption.none(), attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP compression method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("unencrypted-aes.txt", attributes.path());
                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(ZipEncryption.none(), attributes.encryption());
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
        ZipEncryption unknownAes = ZipEncryption.of("winzip-aes-unknown");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bad-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(unknownAes, attributes.encryption());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("Unsupported ZIP encryption method"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);

                assertEquals("bad-aes.txt", attributes.path());
                assertEquals(ZipMethod.of(99), attributes.method());
                assertEquals(unknownAes, attributes.encryption());
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

    /// Verifies basic ZIP path operations.
    @Test
    public void paths() throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(Path.of("sample.zip"))) {
            Path path = fileSystem.getPath("/a/b/../c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals("/a/c.txt", path.normalize().toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
        }
    }

    /// Verifies that preamble bytes can be read from an archive path.
    @Test
    public void preambleFromArchivePath() throws IOException {
        byte[] preamble = new byte[]{1, 2, 3, 4};
        Path archivePath = createTemporaryArchive(preamble);

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
    public void preambleFromVolumeSource() throws IOException {
        byte[] preamble = new byte[]{5, 6, 7};
        Path archivePath = createTemporaryArchive(preamble);

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

    /// Verifies that preamble detection handles ZIP offsets that already include the preamble size.
    @Test
    public void preambleFromArchivePathWithAdjustedZipOffsets() throws IOException {
        byte[] preamble = new byte[]{9, 8, 7, 6, 5};
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithPreambleAndAdjustedOffsets(preamble));

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

    /// Verifies that seekable ZIP entries must have a decoded path.
    @Test
    public void rejectsEmptyCentralDirectoryEntryName() throws IOException {
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(new byte[0]));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/a"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry is missing a path"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP entry paths ignore `.` and repeated separators.
    @Test
    public void normalizesCentralDirectoryEntryName() throws IOException {
        byte[] name = "dir//./hello.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dir/hello.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals("dir//./hello.txt", attributes.path());
                assertArrayEquals(name, attributes.rawPath());
                assertArrayEquals(new byte[0], Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that duplicate seekable ZIP entry paths are rejected.
    @Test
    public void rejectsDuplicateCentralDirectoryEntryName() throws IOException {
        byte[] name = "duplicate.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(twoEntryZipWithRawNames(name, name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/duplicate.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("Duplicate ZIP entry path"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that duplicate normalized seekable ZIP entry paths are rejected.
    @Test
    public void rejectsDuplicateNormalizedCentralDirectoryEntryName() throws IOException {
        byte[] firstName = "dir/hello.txt".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "dir//./hello.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(twoEntryZipWithRawNames(firstName, secondName));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("Duplicate ZIP entry path"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP entry paths cannot contain parent-directory segments.
    @Test
    public void rejectsParentSegmentCentralDirectoryEntryName() throws IOException {
        byte[] name = "../evil.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/evil.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry path must not contain .."));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP entry paths must be relative.
    @Test
    public void rejectsAbsoluteCentralDirectoryEntryName() throws IOException {
        byte[] name = "/evil.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/evil.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry path must be relative"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP entry paths cannot contain drive roots.
    @Test
    public void rejectsDriveRootCentralDirectoryEntryName() throws IOException {
        byte[] name = "C:/evil.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/C:/evil.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry path must be relative"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that backslash-separated parent-directory segments are rejected in seekable ZIP entries.
    @Test
    public void rejectsBackslashParentSegmentCentralDirectoryEntryName() throws IOException {
        byte[] name = "..\\evil.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawName(name));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/evil.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry path must not contain .."));
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
                assertEquals("zip", Files.getFileStore(file).type());

                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(5L, attributes.size());
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(true, attributes.compressedSize() > 0);

                Map<String, Object> namedAttributes = Files.readAttributes(file, "zip:size,compressedSize,method");
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.deflated(), namedAttributes.get("method"));
                assertEquals(true, ((Long) namedAttributes.get("compressedSize")) > 0);

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

    /// Verifies that a split ZIP archive can be indexed and read through a volume source.
    @Test
    public void readSplitZipFromVolumeSource() throws IOException {
        Path firstVolume = createTemporaryArchivePath("split-zip-");
        Path secondVolume = firstVolume.getParent().resolve("sample.z02");
        byte[][] volumes = splitZipArchive();
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
                assertEquals(ZipMethod.stored(), attributes.method());
                assertEquals("split", Files.readString(file, StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that a ZIP file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = createDeflatedZipArchive();
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI entryUri = URI.create(fileSystemUri + "!/dir/hello.txt");

        try {
            try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                Path entry = provider.getPath(entryUri);
                assertEquals(entryUri, entry.toUri());
                assertEquals("hello", Files.readString(entry, StandardCharsets.UTF_8));
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> provider.newFileSystem(fileSystemUri, Map.of())
                );
            }
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that closing a ZIP file system closes an owned volume source.
    @Test
    public void closeVolumeSource() throws IOException {
        TestVolumeSource volumes = new TestVolumeSource();
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes);

        fileSystem.close();

        assertEquals(false, fileSystem.isOpen());
        assertEquals(true, volumes.closed);
        assertThrows(IllegalStateException.class, () -> fileSystem.getPath("/"));
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

    /// Returns a minimal empty ZIP archive with the given preamble bytes.
    private static byte[] emptyZipWithPreamble(byte[] preamble) {
        ByteBuffer buffer = ByteBuffer.allocate(preamble.length + 22).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(preamble);
        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Returns a minimal ZIP archive whose offsets include the preamble size.
    private static byte[] singleEntryZipWithPreambleAndAdjustedOffsets(byte[] preamble) {
        byte[] name = new byte[]{'a'};
        int localHeaderOffset = preamble.length;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderOffset + localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(
                preamble.length + localHeaderSize + centralDirectorySize + 22
        ).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(preamble);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);

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

    /// Returns a minimal seekable ZIP archive with one stored entry using a raw name.
    private static byte[] singleEntryZipWithRawName(byte[] name) {
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(localHeaderSize + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);

        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put(name);

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

    /// Returns a minimal seekable ZIP archive with two stored entries using raw names.
    private static byte[] twoEntryZipWithRawNames(byte[] firstName, byte[] secondName) {
        int firstLocalHeaderOffset = 0;
        int firstLocalHeaderSize = 30 + firstName.length;
        int secondLocalHeaderOffset = firstLocalHeaderOffset + firstLocalHeaderSize;
        int secondLocalHeaderSize = 30 + secondName.length;
        int centralDirectoryOffset = secondLocalHeaderOffset + secondLocalHeaderSize;
        int centralDirectorySize = 46 + firstName.length + 46 + secondName.length;

        ByteBuffer buffer = ByteBuffer.allocate(centralDirectoryOffset + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeStoredLocalHeader(buffer, firstName);
        writeStoredLocalHeader(buffer, secondName);
        writeStoredCentralDirectoryEntry(buffer, firstName, firstLocalHeaderOffset);
        writeStoredCentralDirectoryEntry(buffer, secondName, secondLocalHeaderOffset);

        buffer.putInt(0x06054b50);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 2);
        buffer.putShort((short) 2);
        buffer.putInt(centralDirectorySize);
        buffer.putInt(centralDirectoryOffset);
        buffer.putShort((short) 0);
        return buffer.array();
    }

    /// Writes a minimal stored local header with no content.
    private static void writeStoredLocalHeader(ByteBuffer buffer, byte[] name) {
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
    }

    /// Writes a minimal stored central directory entry with no content.
    private static void writeStoredCentralDirectoryEntry(ByteBuffer buffer, byte[] name, int localHeaderOffset) {
        buffer.putInt(0x02014b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(localHeaderOffset);
        buffer.put(name);
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

    /// Returns a minimal streaming stored ZIP archive with a raw entry name.
    private static byte[] streamingStoredArchiveWithRawName(byte[] name, int flags) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) flags);
        buffer.putShort((short) ZipMethod.STORED_ID);
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
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
        buffer.putShort((short) ZipMethod.DEFLATED_ID);
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
        buffer.putShort((short) ZipMethod.DEFLATED_ID);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED_ID);
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
        byte[] name = "aes.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData();
        byte[] encryptedBody = winZipAesEncryptedBody(password, deflateRaw(content));
        int encryptedSize = encryptedBody.length;
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
        buffer.putShort((short) 1);
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
        buffer.putShort((short) 1);
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
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED_ID);
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
        return winZipAesExtraData(vendorVersion, ZipMethod.DEFLATED_ID);
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

    /// Returns a two-volume ZIP archive with one stored file.
    private static byte[][] splitZipArchive() {
        byte[] name = "hello.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "split".getBytes(StandardCharsets.UTF_8);
        CRC32 crc32 = new CRC32();
        crc32.update(content);

        int localHeaderSize = 30 + name.length;
        ByteBuffer firstVolume = ByteBuffer.allocate(localHeaderSize + content.length).order(ByteOrder.LITTLE_ENDIAN);
        firstVolume.putInt(0x04034b50);
        firstVolume.putShort((short) 20);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putShort((short) 0);
        firstVolume.putInt((int) crc32.getValue());
        firstVolume.putInt(content.length);
        firstVolume.putInt(content.length);
        firstVolume.putShort((short) name.length);
        firstVolume.putShort((short) 0);
        firstVolume.put(name);
        firstVolume.put(content);

        int centralDirectorySize = 46 + name.length;
        ByteBuffer secondVolume = ByteBuffer.allocate(centralDirectorySize + 22).order(ByteOrder.LITTLE_ENDIAN);
        secondVolume.putInt(0x02014b50);
        secondVolume.putShort((short) 20);
        secondVolume.putShort((short) 20);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putInt((int) crc32.getValue());
        secondVolume.putInt(content.length);
        secondVolume.putInt(content.length);
        secondVolume.putShort((short) name.length);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putShort((short) 0);
        secondVolume.putInt(0);
        secondVolume.putInt(0);
        secondVolume.put(name);

        secondVolume.putInt(0x06054b50);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putShort((short) 1);
        secondVolume.putInt(centralDirectorySize);
        secondVolume.putInt(0);
        secondVolume.putShort((short) 0);

        return new byte[][]{firstVolume.array(), secondVolume.array()};
    }

    /// Returns the unsigned ZIP CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Creates a temporary archive file under the module build directory.
    private static Path createTemporaryArchive(byte[] preamble) throws IOException {
        return createTemporaryArchiveContent(emptyZipWithPreamble(preamble));
    }

    /// Creates a temporary archive file with the given content under the module build directory.
    private static Path createTemporaryArchiveContent(byte[] content) throws IOException {
        Path archivePath = createTemporaryArchivePath("preamble-");
        Files.write(archivePath, content);
        return archivePath;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-zip-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sfx.zip");
    }

    /// Creates a temporary deflated ZIP archive under the module build directory.
    private static Path createDeflatedZipArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("real-zip-");
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
            writer.beginDirectory("dir");
            writer.endEntry();
            writer.beginFile("dir/hello.txt");
            try (var output = writer.openOutputStream()) {
                output.write("hello".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archivePath;
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
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

    /// Test volume source that records close calls.
    @NotNullByDefault
    private static final class TestVolumeSource implements ArkivoVolumeSource {
        /// Whether this source has been closed.
        private boolean closed;

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closed = true;
        }
    }

    /// Open option used to emulate provider-specific options in tests.
    private enum TestOpenOption implements OpenOption {
        /// Provider-specific direct I/O marker.
        DIRECT
    }
}
