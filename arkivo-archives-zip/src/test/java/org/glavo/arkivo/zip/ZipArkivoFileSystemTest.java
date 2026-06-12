// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
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
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.ZipException;

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

    /// Verifies that streaming reader attributes fail as closed after the reader is closed.
    @Test
    public void streamingReaderReadAttributesAfterCloseIsRejectedAsClosed() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            ZipArkivoStreamingReader reader =
                    ZipArkivoStreamingReader.open(new ByteArrayInputStream(Files.readAllBytes(archivePath)));
            assertEquals(true, reader.next());

            reader.close();

            IOException exception = assertThrows(
                    IOException.class,
                    () -> reader.readAttributes(ZipArkivoEntryAttributes.class)
            );
            assertEquals(true, exception.getMessage().contains("closed"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer exposes pending entry attribute views.
    @Test
    public void streamingWriterEntryAttributeView() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-attrs-");
        byte[] rawComment = "pending comment".getBytes(StandardCharsets.UTF_8);

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
                zipView.setRawComment(rawComment);
                Set<PosixFilePermission> permissions = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ
                );
                zipView.setPermissions(permissions);
                IOException localExtraException =
                        assertThrows(IOException.class, () -> zipView.setLocalExtraData(malformedExtraField()));
                assertEquals(true, localExtraException.getMessage().contains("Invalid ZIP extra field length"));
                IOException centralExtraException = assertThrows(
                        IOException.class,
                        () -> zipView.setCentralDirectoryExtraData(malformedExtraField())
                );
                assertEquals(true, centralExtraException.getMessage().contains("Invalid ZIP extra field length"));
                ZipArkivoEntryAttributes attributes = zipView.readAttributes();
                assertEquals("dir/hello.txt", attributes.path());
                assertEquals("pending comment", attributes.comment());
                assertArrayEquals(rawComment, attributes.rawComment());
                assertEquals(true, (attributes.generalPurposeFlags() & (1 << 11)) != 0);
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(permissions, attributes.permissions());

                try (var output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
                assertThrows(IllegalStateException.class, () -> zipView.setMethod(ZipMethod.stored()));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("hello", Files.readString(fileSystem.getPath("/dir/hello.txt"), StandardCharsets.UTF_8));
                ZipArkivoEntryAttributes attributes =
                        Files.readAttributes(fileSystem.getPath("/dir/hello.txt"), ZipArkivoEntryAttributes.class);
                assertEquals("pending comment", attributes.comment());
                assertArrayEquals(rawComment, attributes.rawComment());
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
        byte[] localExtraData = extraField(0x7070, new byte[]{1, 2, 3});
        byte[] centralExtraData = extraField(0x7071, new byte[]{4, 5});
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
                Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
        )) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-deflated-descriptor.txt", firstAttributes.path());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            firstInput.close();

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.stored(), secondAttributes.method());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            assertEquals(false, reader.next());
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
                Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
        )) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("aes-zip64-stored-descriptor-crc.bin", firstAttributes.path());
            assertEquals(ZipMethod.stored(), firstAttributes.method());
            assertEquals(ZipEncryption.winZipAes256(), firstAttributes.encryption());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("WinZip AES data descriptor does not match"));
            firstInput.close();

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.stored(), secondAttributes.method());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
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
                IOException exception = assertThrows(IOException.class, writer::openOutputStream);
                assertEquals(true, exception.getMessage().contains("requires a password"));
            }
            assertArrayEquals(emptyZipWithPreamble(new byte[0]), Files.readAllBytes(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that encrypted in-memory streaming ZIP entries require a password before writing a header.
    @Test
    public void streamingWriterEncryptedSymbolicLinkRequiresPasswordBeforeHeader() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-encrypted-link-no-password-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginSymbolicLink("secret-link", "target");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setEncryption(ZipEncryption.winZipAes128());
                IOException exception = assertThrows(IOException.class, writer::endEntry);
                assertEquals(true, exception.getMessage().contains("requires a password"));
            }
            assertArrayEquals(emptyZipWithPreamble(new byte[0]), Files.readAllBytes(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that streaming writer channels remain closed after entry close validation fails.
    @Test
    public void streamingWriterChannelCloseFailureMarksWrapperClosed() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-channel-close-failure-");
        byte[] content = "bad size".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("bad-size.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
                var channel = writer.openChannel();

                assertEquals(content.length, channel.write(ByteBuffer.wrap(content)));
                IOException exception = assertThrows(IOException.class, channel::close);
                assertEquals(true, exception.getMessage().contains("configured size"));
                assertEquals(false, channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
                channel.close();
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that closing a streaming ZIP writer closes the target output when entry validation fails.
    @Test
    public void streamingWriterCloseFailureClosesOutput() throws IOException {
        CloseTrackingOutputStream archiveOutput = new CloseTrackingOutputStream(true);
        byte[] content = "bad writer close size".getBytes(StandardCharsets.UTF_8);

        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archiveOutput);
        writer.beginFile("bad-size.txt");
        ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
        var entryOutput = writer.openOutputStream();
        entryOutput.write(content);

        IOException exception = assertThrows(IOException.class, writer::close);
        assertEquals(true, exception.getMessage().contains("configured size"));
        assertEquals(true, archiveOutput.closed);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(true, exception.getSuppressed()[0].getMessage().contains("close failed"));
    }

    /// Verifies that close action failures do not mask streaming ZIP output close failures.
    @Test
    public void streamingCloseActionFailureIsSuppressedWhenOutputCloseFails() throws Exception {
        CloseTrackingOutputStream archiveOutput = new CloseTrackingOutputStream(true);
        StreamingZipArkivoFileSystemImpl fileSystem = new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS
        );
        setStreamingZipCloseAction(fileSystem, () -> {
            throw new IllegalStateException("close action failed");
        });

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("close failed", exception.getMessage());
        assertEquals(true, archiveOutput.closed);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies that close action failures are preserved after runtime streaming ZIP output close failures.
    @Test
    public void streamingCloseActionFailureIsSuppressedWhenOutputCloseFailsAtRuntime() throws Exception {
        RuntimeCloseFailingCloseTrackingOutputStream archiveOutput =
                new RuntimeCloseFailingCloseTrackingOutputStream();
        StreamingZipArkivoFileSystemImpl fileSystem = new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS
        );
        boolean[] closeActionRan = new boolean[1];
        setStreamingZipCloseAction(fileSystem, () -> {
            closeActionRan[0] = true;
            throw new IllegalStateException("close action failed");
        });

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertEquals("close failed", exception.getMessage());
        assertEquals(true, archiveOutput.closed());
        assertEquals(true, closeActionRan[0]);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
        fileSystem.close();
    }

    /// Verifies that close action failures are preserved after runtime entry close failures.
    @Test
    public void streamingCloseActionFailureIsSuppressedWhenEntryCloseFailsAtRuntime() throws Exception {
        RuntimeFailingCloseTrackingOutputStream archiveOutput = new RuntimeFailingCloseTrackingOutputStream();
        StreamingZipArkivoFileSystemImpl fileSystem = new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                archiveOutput,
                ZipArkivoFileSystemConfig.DEFAULTS
        );
        boolean[] closeActionRan = new boolean[1];
        setStreamingZipCloseAction(fileSystem, () -> {
            closeActionRan[0] = true;
            throw new IllegalStateException("close action failed");
        });

        OutputStream entryOutput = fileSystem.newOutputStream(fileSystem.getPath("/runtime.txt"));
        archiveOutput.failWrites();

        RuntimeException exception = assertThrows(RuntimeException.class, fileSystem::close);

        assertEquals("write failed", exception.getMessage());
        assertEquals(true, archiveOutput.closed());
        assertEquals(true, closeActionRan[0]);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, () -> entryOutput.write(1));
        fileSystem.close();
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

    /// Verifies that streaming ZIP write channels report closed state before read capability checks.
    @Test
    public void fileSystemWriteChannelReadAfterCloseIsRejectedAsClosed() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-write-channel-read-after-close-");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
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
                SeekableByteChannel channel = Files.newByteChannel(
                        fileSystem.getPath("/channel.bin"),
                        Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                );

                assertThrows(NonReadableChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
                assertEquals(7, channel.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
                channel.close();

                assertEquals(false, channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, channel::position);
                assertThrows(ClosedChannelException.class, channel::size);
                assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
                channel.close();
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

    /// Verifies that read-only ZIP entry channel and stream open options reject write access.
    @Test
    public void rejectsWritableEntryOpenOptions() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dir/hello.txt");

                try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate(5);
                    assertEquals(5, channel.read(buffer));
                    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), buffer.array());
                }
                try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
                    assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), input.readAllBytes());
                }

                assertThrows(
                        UnsupportedOperationException.class,
                        () -> Files.newByteChannel(file, StandardOpenOption.WRITE)
                );
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> Files.newInputStream(file, StandardOpenOption.APPEND)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that decoded seekable entry channels reject operations as closed after they are closed.
    @Test
    public void decodedEntryChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/dir/hello.txt"));

                ByteBuffer buffer = ByteBuffer.allocate(5);
                assertEquals(5, channel.read(buffer));
                assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), buffer.array());
                assertClosedReadOnlyChannel(channel);
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
            assertEquals(true, reader.next());
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

    /// Verifies that known-size entry close preserves validation failure when delegate close also fails.
    @Test
    public void knownSizeEntryCloseFailureSuppressesDelegateCloseFailure() throws Exception {
        byte[] content = "known size entry close failure".getBytes(StandardCharsets.UTF_8);
        InputStream input = newKnownSizeEntryInputStream(
                new CloseFailingInputStream(content),
                crc32(content) ^ 1L,
                content.length
        );

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals(true, exception.getMessage().contains("ZIP entry data does not match local header"));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime known-size entry drain failures still close the delegate stream.
    @Test
    public void knownSizeEntryRuntimeDrainFailureClosesDelegate() throws Exception {
        byte[] content = "known size runtime drain failure".getBytes(StandardCharsets.UTF_8);
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(content, 0);
        InputStream input = newKnownSizeEntryInputStream(delegate, crc32(content), content.length);

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that setup cleanup runtime failures do not replace the setup failure.
    @Test
    public void streamingEntrySetupFailureSuppressesRuntimeCloseFailure() throws Exception {
        IOException failure = new IOException("setup failed");

        closeEntryAfterFailedSetup(new RuntimeCloseFailingInputStream(new byte[0]), failure);

        assertEquals(1, failure.getSuppressed().length);
        assertEquals(IllegalStateException.class, failure.getSuppressed()[0].getClass());
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
    }

    /// Verifies that current entry close preserves drain failure when delegate close fails at runtime.
    @Test
    public void currentEntryCloseFailureSuppressesRuntimeDelegateCloseFailure() throws Exception {
        InputStream input = newCurrentEntryInputStream(new ReadAndRuntimeCloseFailingInputStream());

        IOException exception = assertThrows(IOException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(IllegalStateException.class, exception.getSuppressed()[0].getClass());
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime stored descriptor read failures finish the entry state.
    @Test
    public void storedDataDescriptorRuntimeDescriptorFailureMarksEntryFinished() throws Exception {
        InputStream input = newStoredDataDescriptorInputStream(
                new PushbackInputStream(new ReadFailingCloseTrackingInputStream(dataDescriptorSignature(), 4), 32),
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::read);

        assertEquals("read failed", exception.getMessage());
        assertEquals(-1, input.read());
        input.close();
    }

    /// Verifies that runtime encrypted stored descriptor read failures finish the entry state.
    @Test
    public void encryptedStoredDataDescriptorRuntimeDescriptorFailureMarksEntryFinished() throws Exception {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        InputStream input = newEncryptedStoredDataDescriptorInputStream(
                new PushbackInputStream(new ReadFailingCloseTrackingInputStream(dataDescriptorSignature(), 4), 32),
                newTraditionalDecryptor(password),
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::read);

        assertEquals("read failed", exception.getMessage());
        assertEquals(-1, input.read());
        input.close();
    }

    /// Verifies that deflated entry close preserves an invalid deflate failure when validation also fails.
    @Test
    public void deflatedEntryCloseFailureSuppressesValidationFailure() throws Exception {
        byte[] content = "deflated entry close failure".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflateRaw(content);
        compressed[0] = (byte) 0xff;
        InputStream input = newEntryInflaterInputStream(
                new ByteArrayInputStream(compressed),
                new Inflater(true),
                null,
                false,
                compressed.length,
                crc32(content),
                content.length
        );

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals(true, exception instanceof ZipException);
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(true, exception.getSuppressed()[0].getMessage().contains(
                "ZIP entry data does not match local header"
        ));
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime deflated entry drain failures still close the delegate stream.
    @Test
    public void deflatedEntryRuntimeDrainFailureClosesDelegate() throws Exception {
        byte[] content = "deflated runtime drain failure".getBytes(StandardCharsets.UTF_8);
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(content, 0);
        InputStream input = newEntryInflaterInputStream(
                delegate,
                new Inflater(true),
                null,
                false,
                ZipArkivoEntryAttributes.UNKNOWN_SIZE,
                ZipArkivoEntryAttributes.UNKNOWN_CRC32,
                ZipArkivoEntryAttributes.UNKNOWN_SIZE
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime WinZip AES descriptor drain failures still run finish cleanup.
    @Test
    public void aesDataDescriptorInflaterRuntimeDrainFailureRunsFinish() throws Exception {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(new byte[0], 0);
        InputStream input = newAesDataDescriptorInflaterInputStream(
                new PushbackInputStream(delegate, 32),
                newWinZipAesDecryptor(password),
                10,
                28,
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("read failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that AES authentication failure remains primary when descriptor reading fails at runtime.
    @Test
    public void aesDataDescriptorInflaterAuthenticationFailureSuppressesRuntimeDescriptorFailure() throws Exception {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "AES descriptor authentication failure".getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBody = winZipAesEncryptedBody(password, deflateRaw(content));
        byte[] encryptedContentAndAuthentication = Arrays.copyOfRange(
                encryptedBody,
                winZipAesTestSalt().length + 2,
                encryptedBody.length
        );
        encryptedContentAndAuthentication[encryptedContentAndAuthentication.length - 1] ^= 1;
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(
                encryptedContentAndAuthentication,
                encryptedContentAndAuthentication.length
        );
        InputStream input = newAesDataDescriptorInflaterInputStream(
                new PushbackInputStream(delegate, 32),
                newWinZipAesDecryptor(password),
                10,
                28,
                false
        );

        IOException exception = assertThrows(IOException.class, input::close);

        assertEquals("WinZip AES authentication failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(IllegalStateException.class, exception.getSuppressed()[0].getClass());
        assertEquals("read failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime encrypted descriptor drain failures still run finish cleanup.
    @Test
    public void encryptedDataDescriptorInflaterRuntimeDrainFailureRunsFinish() throws Exception {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(new byte[0], 0);
        InputStream input = newEncryptedDataDescriptorInflaterInputStream(
                new PushbackInputStream(delegate, 32),
                newTraditionalDecryptor(password),
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("read failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
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
            assertEquals(true, reader.next());
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
            assertEquals(true, reader.next());
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

    /// Verifies that closing a mismatched deflated entry drains to the declared compressed-size boundary.
    @Test
    public void streamingReaderCloseAfterDeflatedCompressedSizeMismatchDrainsDeclaredBody() throws IOException {
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
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deflated-padding.txt", firstAttributes.path());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("ZIP entry data does not match local header"));
            firstInput.close();

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            assertEquals(false, reader.next());
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

    /// Verifies that stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterStoredDataDescriptorCrcMismatchConsumesDescriptor() throws IOException {
        byte[] firstContent = "stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after stored descriptor mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredDataDescriptorCrcMismatchWithStoredEntry(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("stored-descriptor-crc.txt", firstAttributes.path());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            firstInput.close();

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.stored(), secondAttributes.method());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
            }
            assertEquals(false, reader.next());
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD.key(),
                password,
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, environment)) {
                writer.beginFile("encrypted-stored-descriptor-crc.txt");
                ZipArkivoEntryAttributeView firstView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(firstView);
                firstView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(firstContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView secondView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(secondView);
                secondView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(secondContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("encrypted-stored-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.stored(), firstAttributes.method());
                assertEquals(ZipEncryption.traditional(), firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::close);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                assertEquals(ZipEncryption.traditional(), secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(secondContent, secondInput.readAllBytes());
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

    /// Verifies that ZIP64 stored descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterZip64StoredDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        byte[] firstContent = "zip64 stored descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] secondContent = "after zip64 stored mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] archive = zip64StoredDataDescriptorCrcMismatchWithStoredEntry(firstContent, secondContent);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zip64-stored-descriptor-crc.txt", firstAttributes.path());
            assertEquals(ZipMethod.stored(), firstAttributes.method());
            var firstInput = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, firstInput::close);
            assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
            firstInput.close();

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", secondAttributes.path());
            assertEquals(ZipMethod.stored(), secondAttributes.method());
            try (var secondInput = reader.openInputStream()) {
                assertArrayEquals(secondContent, secondInput.readAllBytes());
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

    /// Verifies that reader close preserves entry validation failure when source close also fails.
    @Test
    public void streamingReaderClosePreservesEntryFailureWhenSourceCloseFails() throws IOException {
        Path archivePath = createTemporaryArchivePath("reader-close-source-close-failure-");
        byte[] content = "deflated descriptor close failure".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("deflated.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] tampered = tamperLastDataDescriptorCrc(Files.readAllBytes(archivePath));
            ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new CloseFailingInputStream(tampered));
            try {
                assertEquals(true, reader.next());
                var input = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, reader::close);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                assertEquals(1, exception.getSuppressed().length);
                assertEquals("close failed", exception.getSuppressed()[0].getMessage());
                assertThrows(IOException.class, input::read);
                reader.close();
            } finally {
                reader.close();
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that reader close still closes the source after a runtime entry close failure.
    @Test
    public void streamingReaderCloseClosesSourceAfterRuntimeEntryFailure() throws IOException {
        byte[] name = "stored.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "runtime close failure".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithContent(name, content, crc32(content), content.length, content.length);
        ReadFailingCloseTrackingInputStream source =
                new ReadFailingCloseTrackingInputStream(archive, 30 + name.length);

        ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
        assertEquals(true, reader.next());
        InputStream entryInput = reader.openInputStream();

        RuntimeException exception = assertThrows(RuntimeException.class, reader::close);
        assertEquals("read failed", exception.getMessage());
        assertEquals(true, source.closed());
        assertThrows(IOException.class, entryInput::read);
        reader.close();
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView secretView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(secretView);
                secretView.setMethod(ZipMethod.stored());
                secretView.setEncryption(ZipEncryption.traditional());
                secretView.setUncompressedSizeAndCrc32(secretContent.length, crc32(secretContent));
                try (var output = writer.openOutputStream()) {
                    output.write(secretContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                afterView.setUncompressedSizeAndCrc32(afterContent.length, crc32(afterContent));
                try (var output = writer.openOutputStream()) {
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
                    Map.of(ZipArkivoFileSystem.PASSWORD.key(), password)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secretAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret.txt", secretAttributes.path());
                assertEquals(ZipMethod.stored(), secretAttributes.method());
                assertEquals(ZipEncryption.traditional(), secretAttributes.encryption());

                IOException exception = assertThrows(IOException.class, reader::openInputStream);
                assertEquals(true, exception.getMessage().contains("password verification failed"));

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
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

    /// Verifies that preamble channels reject operations as closed after they are closed.
    @Test
    public void preambleChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        byte[] preamble = new byte[]{4, 3, 2, 1};
        Path archivePath = createTemporaryArchive(preamble);

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                SeekableByteChannel channel = fileSystem.openPreambleChannel();

                assertEquals(preamble.length, channel.size());
                assertClosedReadOnlyChannel(channel);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that preamble channel setup keeps its primary failure when cleanup close fails.
    @Test
    public void failedPreambleChannelSetupSuppressesArchiveCloseFailure() throws IOException {
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(new CloseFailingChannelVolumeSource(new byte[0], 1))) {
            IOException exception = assertThrows(IOException.class, fileSystem::openPreambleChannel);

            assertEquals(true, exception.getMessage().contains("ZIP end of central directory record not found"));
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        }
    }

    /// Verifies that preamble channel setup keeps its primary failure when cleanup close fails at runtime.
    @Test
    public void failedPreambleChannelSetupSuppressesArchiveRuntimeCloseFailure() throws IOException {
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(new CloseFailingChannelVolumeSource(new byte[0], 1, true))) {
            IOException exception = assertThrows(IOException.class, fileSystem::openPreambleChannel);

            assertEquals(true, exception.getMessage().contains("ZIP end of central directory record not found"));
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
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

    /// Verifies that a regular ZIP file entry cannot also be an indexed directory parent.
    @Test
    public void rejectsCentralDirectoryFileParentConflict() throws IOException {
        byte[] firstName = "dir".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "dir/file.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(twoEntryZipWithRawNames(firstName, secondName));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAttributes(fileSystem.getPath("/dir/file.txt"), ZipArkivoEntryAttributes.class)
                );
                assertEquals(true, exception.getMessage().contains("ZIP entry path conflicts with directory"));
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
        byte[] rawComment = "M\u00fcnchen".getBytes(ZipEntryNameEncoding.cp437());
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
        byte[] rawComment = "legacy".getBytes(ZipEntryNameEncoding.cp437());
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
                ZipMethod.STORED_ID,
                ZipMethod.STORED_ID
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

    /// Verifies that local ZIP entry methods must match central directory methods.
    @Test
    public void rejectsMismatchedLocalEntryMethod() throws IOException {
        byte[] name = "method.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.DEFLATED_ID,
                ZipMethod.STORED_ID
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
                ZipMethod.STORED_ID,
                ZipMethod.STORED_ID,
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
                ZipMethod.STORED_ID,
                ZipMethod.STORED_ID,
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
                ZipMethod.STORED_ID,
                ZipMethod.STORED_ID,
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

    /// Verifies that decoded seekable entry channels reject entries too large to materialize in memory.
    @Test
    public void rejectsOversizedDecodedSeekableEntry() throws IOException {
        byte[] name = "huge-deflated.txt".getBytes(StandardCharsets.UTF_8);
        long uncompressedSize = (long) Integer.MAX_VALUE + 1L;
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                0,
                0,
                ZipMethod.DEFLATED_ID,
                ZipMethod.DEFLATED_ID,
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
                assertEquals(true, exception.getMessage().contains(
                        "ZIP entry is too large for seekable decoded access"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP reads validate stored entry CRC-32 values against actual data.
    @Test
    public void rejectsSeekableStoredEntryCrc32Mismatch() throws IOException {
        byte[] name = "stored-crc.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "stored data".getBytes(StandardCharsets.UTF_8);
        long crc32 = crc32(content) ^ 1L;
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                content,
                ZipMethod.STORED_ID,
                crc32,
                content.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/stored-crc.txt"))
                );
                assertEquals(true, exception.getMessage().contains(
                        "ZIP entry data does not match central directory"
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
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
                ZipMethod.DEFLATED_ID,
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

    /// Verifies that seekable decoded entry runtime drain failures still close the delegate stream.
    @Test
    public void seekableEntryRuntimeDrainFailureClosesDelegate() throws Exception {
        byte[] content = "seekable entry runtime drain failure".getBytes(StandardCharsets.UTF_8);
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(content, 0);
        InputStream input = newValidatingEntryInputStream(
                delegate,
                crc32(content),
                content.length
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that seekable decoded entry close can retry delegate cleanup after a close failure.
    @Test
    public void seekableEntryCloseFailureAllowsDelegateCleanupRetry() throws Exception {
        byte[] content = "seekable entry close failure".getBytes(StandardCharsets.UTF_8);
        CloseFailingOnceInputStream delegate = new CloseFailingOnceInputStream(content);
        InputStream input = newValidatingEntryInputStream(delegate, crc32(content), content.length);

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, input::read);
        assertEquals(false, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        input.close();

        assertEquals(true, delegate.closed());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that seekable stored channel runtime drain failures still close the delegate channel.
    @Test
    public void seekableStoredChannelRuntimeDrainFailureClosesDelegate() throws Exception {
        byte[] content = "seekable stored runtime drain failure".getBytes(StandardCharsets.UTF_8);
        RuntimeReadFailingCloseTrackingSeekableByteChannel delegate =
                new RuntimeReadFailingCloseTrackingSeekableByteChannel(content, 0);
        SeekableByteChannel channel = newValidatingStoredEntryByteChannel(
                delegate,
                crc32(content),
                content.length
        );

        RuntimeException exception = assertThrows(RuntimeException.class, channel::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertEquals(false, channel.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        channel.close();
    }

    /// Verifies that seekable stored entry close can retry delegate cleanup after a close failure.
    @Test
    public void seekableStoredChannelCloseFailureAllowsDelegateCleanupRetry() throws Exception {
        byte[] content = "seekable stored close failure".getBytes(StandardCharsets.UTF_8);
        SparseByteChannel delegate = new SparseByteChannel(
                content.length,
                true,
                new SparseSegment(0, content)
        );
        SeekableByteChannel channel = newValidatingStoredEntryByteChannel(
                delegate,
                crc32(content),
                content.length
        );

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals("close failed", exception.getMessage());
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertEquals(1, delegate.closeCount());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));

        channel.close();
        channel.close();

        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that seekable ZIP input streams ignore repeated close calls after a malformed deflate failure.
    @Test
    public void seekableInputStreamCloseIsIdempotentAfterMalformedDeflatedData() throws IOException {
        byte[] name = "malformed-deflate.txt".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = "not raw deflate".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.DEFLATED_ID,
                0,
                compressed.length,
                compressed.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                var input = Files.newInputStream(fileSystem.getPath("/malformed-deflate.txt"));
                assertThrows(IOException.class, input::close);
                input.close();
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP input streams reject reads after close.
    @Test
    public void seekableInputStreamReadAfterCloseIsRejected() throws IOException {
        Path archivePath = createDeflatedZipArchive();

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                var input = Files.newInputStream(fileSystem.getPath("/dir/hello.txt"));

                assertEquals('h', input.read());
                input.close();

                assertThrows(IOException.class, input::read);
                assertThrows(IOException.class, () -> input.read(new byte[1]));
                input.close();
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
                assertEquals(Set.of("size", "compressedSize", "method"), namedAttributes.keySet());
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.deflated(), namedAttributes.get("method"));
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

    /// Verifies that split ZIP setup closes opened channels when a later volume fails during setup.
    @Test
    public void failedSplitZipVolumeSetupSuppressesChannelCloseFailure() throws IOException {
        SizeFailingSplitVolumeSource volumes = new SizeFailingSplitVolumeSource();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
            IOException exception = assertThrows(IOException.class, fileSystem::preambleSize);

            assertEquals("size failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
            assertEquals(1, volumes.firstCloseCount());
            assertEquals(1, volumes.secondCloseCount());
        }
    }

    /// Verifies that split ZIP setup preserves setup failures when opened channel cleanup fails at runtime.
    @Test
    public void failedSplitZipVolumeSetupSuppressesChannelRuntimeCloseFailure() throws IOException {
        SizeFailingSplitVolumeSource volumes = new SizeFailingSplitVolumeSource(true);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
            IOException exception = assertThrows(IOException.class, fileSystem::preambleSize);

            assertEquals("size failed", exception.getMessage());
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
            assertEquals(1, volumes.firstCloseCount());
            assertEquals(1, volumes.secondCloseCount());
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

    /// Verifies that closing a writable ZIP file system opened by URI unregisters it immediately.
    @Test
    public void writableUriCloseUnregistersFileSystem() throws Exception {
        Path archivePath = createTemporaryArchivePath("uri-writable-close-");
        ZipArkivoFileSystemProvider provider = new ZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(ZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try {
            ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, environment);
            assertEquals(1, providerFileSystemCount(provider));

            fileSystem.close();

            assertEquals(0, providerFileSystemCount(provider));
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
        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/"));
        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
        assertThrows(ClosedFileSystemException.class, fileSystem::supportedFileAttributeViews);
    }

    /// Verifies that close action failures do not mask owned volume source close failures.
    @Test
    public void closeActionFailureIsSuppressedWhenZipVolumeSourceCloseFails() throws IOException {
        CloseFailingOwnedZipVolumeSource volumes = new CloseFailingOwnedZipVolumeSource();
        ZipArkivoFileSystemImpl fileSystem = new ZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    throw new IllegalStateException("close action failed");
                }
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume source close failed", exception.getMessage());
        assertEquals(1, volumes.closeCount());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close action failed", exception.getSuppressed()[0].getMessage());
    }

    /// Verifies that ZIP file system close retries owned volume source cleanup after failure.
    @Test
    public void zipFileSystemCloseRetriesVolumeSourceCleanupAfterFailure() throws IOException {
        CloseFailingOwnedZipVolumeSource volumes = new CloseFailingOwnedZipVolumeSource(1);
        int[] closeActionCount = new int[1];
        ZipArkivoFileSystemImpl fileSystem = new ZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                ZipArkivoFileSystemConfig.DEFAULTS,
                () -> closeActionCount[0]++
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);
        assertEquals("volume source close failed", exception.getMessage());
        assertEquals(false, fileSystem.isOpen());
        assertEquals(1, volumes.closeCount());
        assertEquals(1, closeActionCount[0]);

        fileSystem.close();
        fileSystem.close();

        assertEquals(2, volumes.closeCount());
        assertEquals(1, closeActionCount[0]);
    }

    /// Verifies that a stored entry channel can retry backing channel cleanup after close failure.
    @Test
    public void storedEntryChannelCloseFailureAllowsCleanupRetry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stored-channel-close-failure-");
        byte[] content = "stored close failure".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("stored.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                view.setUncompressedSizeAndCrc32(content.length, crc32(content));
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            CloseFailingChannelVolumeSource volumes =
                    new CloseFailingChannelVolumeSource(Files.readAllBytes(archivePath), 3);
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumes)) {
                SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/stored.bin"));

                IOException exception = assertThrows(IOException.class, channel::close);
                assertEquals(true, exception.getMessage().contains("close failed"));
                assertEquals(false, channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, channel::position);
                assertThrows(ClosedChannelException.class, channel::size);
                assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
                assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
                channel.close();
                channel.close();
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that single archive channel wrappers can retry delegate cleanup after close failure.
    @Test
    public void singleArchiveChannelCloseFailureAllowsCleanupRetry() throws Exception {
        SparseByteChannel delegate = new SparseByteChannel(
                3,
                true,
                new SparseSegment(0, new byte[]{1, 2, 3})
        );
        SeekableByteChannel channel = newSingleArchiveChannel(delegate);

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("close failed"));
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, () -> channel.position(0));
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        assertEquals(1, delegate.closeCount());

        channel.close();
        channel.close();

        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that bounded read-only ZIP channels can retry delegate cleanup after close failure.
    @Test
    public void boundedChannelCloseFailureAllowsCleanupRetry() throws Exception {
        SparseByteChannel delegate = new SparseByteChannel(
                3,
                true,
                new SparseSegment(0, new byte[]{1, 2, 3})
        );
        SeekableByteChannel channel = newBoundedSeekableByteChannel(delegate, 3);

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals(true, exception.getMessage().contains("close failed"));
        assertEquals(false, channel.isOpen());
        assertEquals(true, delegate.isOpen());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
        assertThrows(ClosedChannelException.class, channel::position);
        assertThrows(ClosedChannelException.class, channel::size);
        assertThrows(ClosedChannelException.class, () -> channel.truncate(0));
        assertEquals(1, delegate.closeCount());

        channel.close();
        channel.close();

        assertEquals(false, delegate.isOpen());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that concatenated archive channels retry only volume cleanup that has not completed.
    @Test
    public void concatenatedArchiveChannelCloseFailureRetriesOnlyFailedCleanup() throws Exception {
        SparseByteChannel first = new SparseByteChannel(
                1,
                true,
                new SparseSegment(0, new byte[]{1})
        );
        SparseByteChannel second = new SparseByteChannel(
                1,
                new SparseSegment(0, new byte[]{2})
        );
        SeekableByteChannel channel = newConcatenatedArchiveChannel(
                new SeekableByteChannel[]{first, second},
                new long[]{0, 1},
                2
        );

        IOException exception = assertThrows(IOException.class, channel::close);
        assertEquals("close failed", exception.getMessage());
        assertEquals(false, channel.isOpen());
        assertEquals(true, first.isOpen());
        assertEquals(false, second.isOpen());
        assertEquals(1, first.closeCount());
        assertEquals(1, second.closeCount());
        assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));

        channel.close();
        channel.close();

        assertEquals(false, first.isOpen());
        assertEquals(false, second.isOpen());
        assertEquals(2, first.closeCount());
        assertEquals(1, second.closeCount());
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

    /// Creates a bounded ZIP channel around the given delegate channel.
    private static SeekableByteChannel newBoundedSeekableByteChannel(
            SeekableByteChannel channel,
            long size
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl$BoundedSeekableByteChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class, long.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel, size);
    }

    /// Creates a single-volume archive channel around the given delegate channel.
    private static SeekableByteChannel newSingleArchiveChannel(SeekableByteChannel channel)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl$SingleArchiveChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel);
    }

    /// Creates a concatenated archive channel around the given delegate channels.
    private static SeekableByteChannel newConcatenatedArchiveChannel(
            SeekableByteChannel[] channels,
            long[] starts,
            long size
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl$ConcatenatedArchiveChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel[].class, long[].class, long.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance((Object) channels, starts, size);
    }

    /// Creates a validating seekable ZIP entry stream through its private constructor.
    private static InputStream newValidatingEntryInputStream(
            InputStream input,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl$ValidatingEntryInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class, long.class, long.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(input, expectedCrc32, expectedUncompressedSize);
    }

    /// Creates a validating stored entry channel through its private constructor.
    private static SeekableByteChannel newValidatingStoredEntryByteChannel(
            SeekableByteChannel channel,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.ZipArkivoFileSystemImpl$ValidatingStoredEntryByteChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class, long.class, long.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel, expectedCrc32, expectedUncompressedSize);
    }

    /// Replaces the streaming ZIP file system close action for close failure tests.
    private static void setStreamingZipCloseAction(
            StreamingZipArkivoFileSystemImpl fileSystem,
            Runnable closeAction
    ) throws ReflectiveOperationException {
        Field field = StreamingZipArkivoFileSystemImpl.class.getDeclaredField("closeAction");
        field.setAccessible(true);
        field.set(fileSystem, closeAction);
    }

    /// Returns the number of file systems currently registered in the provider.
    private static int providerFileSystemCount(ZipArkivoFileSystemProvider provider) throws ReflectiveOperationException {
        Field field = ZipArkivoFileSystemProvider.class.getDeclaredField("fileSystems");
        field.setAccessible(true);
        Map<?, ?> fileSystems = (Map<?, ?>) field.get(provider);
        return fileSystems.size();
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
        int localHeaderOffset = 0;
        int localHeaderSize = 30 + name.length;
        int centralDirectoryOffset = localHeaderSize + body.length;
        int centralDirectorySize = 46 + name.length;

        ByteBuffer buffer = ByteBuffer.allocate(centralDirectoryOffset + centralDirectorySize + 22)
                .order(ByteOrder.LITTLE_ENDIAN);
        writeLocalHeader(buffer, name, 0, method, crc32, compressedSize, uncompressedSize);
        buffer.put(body);
        writeCentralDirectoryEntry(
                buffer,
                name,
                0,
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
        writeLocalHeader(buffer, name, 0, ZipMethod.STORED_ID, 0, 0, 0, localExtraData);
        writeCentralDirectoryEntry(buffer, name, 0, ZipMethod.STORED_ID, 0, 0, 0, 0, centralExtraData);

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
        writeLocalHeader(buffer, name, flags, ZipMethod.STORED_ID);
        writeCentralDirectoryEntry(
                buffer,
                name,
                flags,
                ZipMethod.STORED_ID,
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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
                ZipMethod.STORED_ID,
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
        writeLocalHeader(buffer, name, 0, ZipMethod.STORED_ID);
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
        buffer.putShort((short) 20);
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
        writeCentralDirectoryEntry(buffer, name, 0, ZipMethod.STORED_ID, localHeaderOffset);
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
        buffer.putShort((short) 20);
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

    /// Returns the ZIP data descriptor signature bytes.
    private static byte[] dataDescriptorSignature() {
        return new byte[]{0x50, 0x4b, 0x07, 0x08};
    }

    /// Returns a minimal streaming stored ZIP archive with a raw entry name.
    private static byte[] streamingStoredArchiveWithRawName(byte[] name, int flags) {
        return streamingStoredArchiveWithRawNameAndExtraData(name, flags, new byte[0]);
    }

    /// Returns a minimal streaming stored ZIP archive with a raw entry name and extra field data.
    private static byte[] streamingStoredArchiveWithRawNameAndExtraData(byte[] name, int flags, byte[] extraData) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + extraData.length).order(ByteOrder.LITTLE_ENDIAN);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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
        buffer.putShort((short) ZipMethod.DEFLATED_ID);
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
        buffer.putShort((short) ZipMethod.DEFLATED_ID);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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

    /// Returns a WinZip AES ZIP64 stored entry with a bad descriptor CRC followed by one stored entry.
    private static byte[] winZipAesZip64StoredDataDescriptorCrcMismatchWithStoredEntry(
            byte[] password,
            byte[] firstContent,
            byte[] secondContent
    ) throws IOException {
        byte[] firstName = "aes-zip64-stored-descriptor-crc.bin".getBytes(StandardCharsets.UTF_8);
        byte[] secondName = "after.txt".getBytes(StandardCharsets.UTF_8);
        byte[] aesExtra = winZipAesExtraData(2, ZipMethod.STORED_ID);
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

    /// Returns a minimal ZIP archive whose local and central WinZip AES extra fields conflict.
    private static byte[] winZipAesArchiveWithMismatchedLocalExtra() {
        byte[] name = "aes-mismatch.txt".getBytes(StandardCharsets.UTF_8);
        byte[] localAesExtra = winZipAesExtraData(2, ZipMethod.STORED_ID);
        byte[] centralAesExtra = winZipAesExtraData(2, ZipMethod.DEFLATED_ID);
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

    /// Returns a copy of a ZIP archive with the first data descriptor CRC-32 modified.
    private static byte[] tamperFirstDataDescriptorCrc(byte[] archive) {
        byte[] tampered = archive.clone();
        ByteBuffer buffer = ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset <= tampered.length - 16; offset++) {
            if (buffer.getInt(offset) == 0x08074b50) {
                tampered[offset + 4] ^= 1;
                return tampered;
            }
        }
        throw new AssertionError("data descriptor signature not found");
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

    /// Creates a known-size entry input stream through its private constructor.
    private static InputStream newKnownSizeEntryInputStream(
            InputStream input,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$KnownSizeEntryInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class, long.class, long.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(input, expectedCrc32, expectedUncompressedSize);
    }

    /// Invokes the failed entry setup cleanup path through its private helper.
    private static void closeEntryAfterFailedSetup(InputStream input, Throwable failure)
            throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Method method = ownerType.getDeclaredMethod("closeEntryAfterFailedSetup", InputStream.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(owner, input, failure);
    }

    /// Creates a current streaming entry input stream through its private constructor.
    private static InputStream newCurrentEntryInputStream(InputStream input) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$CurrentEntryInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(ownerType, InputStream.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(owner, input);
    }

    /// Creates a stored data descriptor input stream through its private constructor.
    private static InputStream newStoredDataDescriptorInputStream(
            PushbackInputStream input,
            boolean zip64DataDescriptor
    ) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$StoredDataDescriptorInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(ownerType, PushbackInputStream.class, boolean.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(owner, input, zip64DataDescriptor);
    }

    /// Creates an encrypted stored data descriptor input stream through its private constructor.
    private static InputStream newEncryptedStoredDataDescriptorInputStream(
            PushbackInputStream input,
            Object decryptor,
            boolean zip64DataDescriptor
    ) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Class<?> decryptorType = Class.forName("org.glavo.arkivo.zip.internal.ZipTraditionalCrypto$Decryptor");
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$EncryptedStoredDataDescriptorInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                ownerType,
                PushbackInputStream.class,
                decryptorType,
                boolean.class
        );
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(owner, input, decryptor, zip64DataDescriptor);
    }

    /// Creates an inflater entry input stream through its private constructor.
    private static InputStream newEntryInflaterInputStream(
            InputStream input,
            Inflater inflater,
            @Nullable PushbackInputStream pushbackInput,
            boolean zip64DataDescriptor,
            long expectedCompressedSize,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Constructor<?> ownerConstructor = ownerType.getConstructor(
                ZipArkivoFileSystemProvider.class,
                InputStream.class,
                ZipArkivoFileSystemConfig.class
        );
        Object owner = ownerConstructor.newInstance(
                ZipArkivoFileSystemProvider.instance(),
                InputStream.nullInputStream(),
                ZipArkivoFileSystemConfig.DEFAULTS
        );
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$EntryInflaterInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                ownerType,
                InputStream.class,
                Inflater.class,
                PushbackInputStream.class,
                boolean.class,
                long.class,
                long.class,
                long.class
        );
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(
                owner,
                input,
                inflater,
                pushbackInput,
                zip64DataDescriptor,
                expectedCompressedSize,
                expectedCrc32,
                expectedUncompressedSize
        );
    }

    /// Creates a WinZip AES data descriptor inflater input stream through its private constructor.
    private static InputStream newAesDataDescriptorInflaterInputStream(
            PushbackInputStream input,
            Object decryptor,
            int authenticationCodeSize,
            int overheadSize,
            boolean zip64DataDescriptor
    ) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Class<?> decryptorType = Class.forName("org.glavo.arkivo.zip.internal.ZipAesCrypto$Decryptor");
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$AesDataDescriptorInflaterInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                ownerType,
                PushbackInputStream.class,
                decryptorType,
                int.class,
                int.class,
                boolean.class
        );
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(
                owner,
                input,
                decryptor,
                authenticationCodeSize,
                overheadSize,
                zip64DataDescriptor
        );
    }

    /// Creates an encrypted data descriptor inflater input stream through its private constructor.
    private static InputStream newEncryptedDataDescriptorInflaterInputStream(
            PushbackInputStream input,
            Object decryptor,
            boolean zip64DataDescriptor
    ) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl"
        );
        Object owner = newStreamingZipReadFileSystem(ownerType);
        Class<?> decryptorType = Class.forName("org.glavo.arkivo.zip.internal.ZipTraditionalCrypto$Decryptor");
        Class<?> type = Class.forName(
                "org.glavo.arkivo.zip.internal.StreamingZipArkivoReadFileSystemImpl$EncryptedDataDescriptorInflaterInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                ownerType,
                PushbackInputStream.class,
                decryptorType,
                boolean.class
        );
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(
                owner,
                input,
                decryptor,
                zip64DataDescriptor
        );
    }

    /// Creates a streaming ZIP read file system through its public constructor.
    private static Object newStreamingZipReadFileSystem(Class<?> ownerType) throws ReflectiveOperationException {
        Constructor<?> ownerConstructor = ownerType.getConstructor(
                ZipArkivoFileSystemProvider.class,
                InputStream.class,
                ZipArkivoFileSystemConfig.class
        );
        return ownerConstructor.newInstance(
                ZipArkivoFileSystemProvider.instance(),
                InputStream.nullInputStream(),
                ZipArkivoFileSystemConfig.DEFAULTS
        );
    }

    /// Creates a WinZip AES decryptor through the internal factory.
    private static Object newWinZipAesDecryptor(byte[] password) throws IOException, ReflectiveOperationException {
        Class<?> aesType = Class.forName("org.glavo.arkivo.zip.internal.ZipAesExtraField");
        Method forEncryption = aesType.getDeclaredMethod("forEncryption", ZipEncryption.class, int.class);
        forEncryption.setAccessible(true);
        Object aes = forEncryption.invoke(null, ZipEncryption.winZipAes256(), 8);

        Class<?> cryptoType = Class.forName("org.glavo.arkivo.zip.internal.ZipAesCrypto");
        Method openDecryptor = cryptoType.getDeclaredMethod(
                "openDecryptor",
                InputStream.class,
                aesType,
                byte[].class
        );
        openDecryptor.setAccessible(true);
        return openDecryptor.invoke(null, new ByteArrayInputStream(winZipAesEncryptedBody(password, new byte[0])), aes, password);
    }

    /// Creates a traditional ZIP decryptor through the internal factories.
    private static Object newTraditionalDecryptor(byte[] password) throws IOException, ReflectiveOperationException {
        Class<?> cryptoType = Class.forName("org.glavo.arkivo.zip.internal.ZipTraditionalCrypto");
        Method openEncryptingStream = cryptoType.getDeclaredMethod(
                "openEncryptingStream",
                OutputStream.class,
                byte[].class,
                int.class
        );
        openEncryptingStream.setAccessible(true);

        ByteArrayOutputStream header = new ByteArrayOutputStream();
        OutputStream output = (OutputStream) openEncryptingStream.invoke(null, header, password, 0);
        output.close();

        Method openDecryptor = cryptoType.getDeclaredMethod(
                "openDecryptor",
                InputStream.class,
                byte[].class,
                int.class
        );
        openDecryptor.setAccessible(true);
        return openDecryptor.invoke(null, new ByteArrayInputStream(header.toByteArray()), password, 0);
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

        /// The number of close calls that should fail without closing this channel.
        private int closeFailures;

        /// Whether close failure should be thrown at runtime.
        private final boolean failCloseAtRuntime;

        /// The number of close calls.
        private int closeCount;

        /// Creates a sparse channel with the given size and populated segments.
        private SparseByteChannel(long size, SparseSegment... segments) {
            this(size, false, segments);
        }

        /// Creates a sparse channel with the given size, close behavior, and populated segments.
        private SparseByteChannel(long size, boolean failClose, SparseSegment... segments) {
            this(size, failClose, false, segments);
        }

        /// Creates a sparse channel with the given size, close failure mode, and populated segments.
        private SparseByteChannel(
                long size,
                boolean failClose,
                boolean failCloseAtRuntime,
                SparseSegment... segments
        ) {
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
            this.size = size;
            this.closeFailures = failClose ? 1 : 0;
            this.failCloseAtRuntime = failCloseAtRuntime;
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
        public void close() throws IOException {
            closeCount++;
            if (closeFailures > 0) {
                closeFailures--;
                if (failCloseAtRuntime) {
                    throw new IllegalStateException("close failed");
                }
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
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

    /// Volume source whose second split volume fails while reporting its size.
    @NotNullByDefault
    private static final class SizeFailingSplitVolumeSource implements ArkivoVolumeSource {
        /// The first opened volume channel.
        private final CloseTrackingSeekableByteChannel first;

        /// The second opened volume channel.
        private final CloseTrackingSeekableByteChannel second =
                new CloseTrackingSeekableByteChannel(1L, true, false);

        /// Creates a volume source whose first volume fails to close with an `IOException`.
        private SizeFailingSplitVolumeSource() {
            this(false);
        }

        /// Creates a volume source with the requested first-volume close failure mode.
        private SizeFailingSplitVolumeSource(boolean failFirstCloseAtRuntime) {
            first = new CloseTrackingSeekableByteChannel(1L, false, true, failFirstCloseAtRuntime);
        }

        /// Opens the requested test volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index == 0) {
                return first;
            }
            if (index == 1) {
                return second;
            }
            return null;
        }

        /// Returns how many times the first channel was closed.
        private int firstCloseCount() {
            return first.closeCount();
        }

        /// Returns how many times the second channel was closed.
        private int secondCloseCount() {
            return second.closeCount();
        }
    }

    /// Seekable channel that records close calls and can fail selected setup operations.
    @NotNullByDefault
    private static final class CloseTrackingSeekableByteChannel implements SeekableByteChannel {
        /// The reported channel size.
        private final long size;

        /// Whether size lookup should fail.
        private final boolean failSize;

        /// Whether close should fail.
        private final boolean failClose;

        /// Whether close should fail at runtime.
        private final boolean failCloseAtRuntime;

        /// Whether the channel is open.
        private boolean open = true;

        /// The number of close calls.
        private int closeCount;

        /// Creates a close-tracking channel.
        private CloseTrackingSeekableByteChannel(long size, boolean failSize, boolean failClose) {
            this(size, failSize, failClose, false);
        }

        /// Creates a close-tracking channel with the requested close failure mode.
        private CloseTrackingSeekableByteChannel(
                long size,
                boolean failSize,
                boolean failClose,
                boolean failCloseAtRuntime
        ) {
            this.size = size;
            this.failSize = failSize;
            this.failClose = failClose;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Reads from the empty test channel.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            return -1;
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
            return 0L;
        }

        /// Moves the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0) {
                throw new IllegalArgumentException("newPosition must not be negative");
            }
            return this;
        }

        /// Returns the configured channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            if (failSize) {
                throw new IOException("size failed");
            }
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

        /// Closes this channel and records the call.
        @Override
        public void close() throws IOException {
            closeCount++;
            open = false;
            if (failClose) {
                if (failCloseAtRuntime) {
                    throw new IllegalStateException("close failed");
                }
                throw new IOException("close failed");
            }
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Seekable channel that fails reads at a configured offset and records close calls.
    @NotNullByDefault
    private static final class RuntimeReadFailingCloseTrackingSeekableByteChannel implements SeekableByteChannel {
        /// The source bytes.
        private final byte @Unmodifiable [] content;

        /// The first offset where reads fail.
        private final int failOffset;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a runtime read-failing channel over the given bytes.
        private RuntimeReadFailingCloseTrackingSeekableByteChannel(byte[] content, int failOffset) {
            if (failOffset < 0 || failOffset > content.length) {
                throw new IllegalArgumentException("failOffset is out of range");
            }
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failOffset = failOffset;
        }

        /// Reads bytes from the current position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(destination.remaining(), Math.min(failOffset, content.length) - position);
            if (count == 0) {
                throw new IllegalStateException("read failed");
            }
            destination.put(content, position, count);
            position += count;
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

        /// Moves the current channel position.
        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the channel size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Always rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
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

        /// Returns whether this channel has been closed.
        private boolean closed() {
            return !open;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Volume source whose selected opened channel fails to close.
    @NotNullByDefault
    private static final class CloseFailingChannelVolumeSource implements ArkivoVolumeSource {
        /// The archive bytes.
        private final byte @Unmodifiable [] archive;

        /// The one-based open count that should receive a close-failing channel.
        private final int closeFailingOpen;

        /// Whether close failure should be thrown at runtime.
        private final boolean failCloseAtRuntime;

        /// The current one-based open count.
        private int openCount;

        /// Creates a volume source for the given archive bytes and close-failing open count.
        private CloseFailingChannelVolumeSource(byte[] archive, int closeFailingOpen) {
            this(archive, closeFailingOpen, false);
        }

        /// Creates a volume source for the given archive bytes, close-failing open count, and failure mode.
        private CloseFailingChannelVolumeSource(
                byte[] archive,
                int closeFailingOpen,
                boolean failCloseAtRuntime
        ) {
            if (closeFailingOpen <= 0) {
                throw new IllegalArgumentException("closeFailingOpen must be positive");
            }
            this.archive = archive.clone();
            this.closeFailingOpen = closeFailingOpen;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Opens the only archive volume.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index != 0) {
                return null;
            }
            openCount++;
            return new SparseByteChannel(
                    archive.length,
                    openCount == closeFailingOpen,
                    failCloseAtRuntime,
                    new SparseSegment(0, archive)
            );
        }
    }

    /// Output stream that records whether it was closed.
    @NotNullByDefault
    private static final class CloseTrackingOutputStream extends ByteArrayOutputStream {
        /// Whether closing this output stream should fail.
        private final boolean failClose;

        /// Whether this output stream has been closed.
        private boolean closed;

        /// Creates a close-tracking output stream.
        private CloseTrackingOutputStream(boolean failClose) {
            this.failClose = failClose;
        }

        /// Closes this output stream and records the close.
        @Override
        public void close() throws IOException {
            closed = true;
            if (failClose) {
                throw new IOException("close failed");
            }
            super.close();
        }
    }

    /// Output stream that can fail writes at runtime and records close calls.
    @NotNullByDefault
    private static final class RuntimeFailingCloseTrackingOutputStream extends ByteArrayOutputStream {
        /// Whether writes should fail.
        private boolean failWrites;

        /// Whether this output stream has been closed.
        private boolean closed;

        /// Enables runtime write failures.
        private void failWrites() {
            failWrites = true;
        }

        /// Writes one byte unless failures are enabled.
        @Override
        public synchronized void write(int value) {
            ensureWritesAllowed();
            super.write(value);
        }

        /// Writes bytes unless failures are enabled.
        @Override
        public synchronized void write(byte[] bytes, int offset, int length) {
            ensureWritesAllowed();
            super.write(bytes, offset, length);
        }

        /// Records that this output stream has been closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this output stream has been closed.
        private boolean closed() {
            return closed;
        }

        /// Throws when runtime write failures are enabled.
        private void ensureWritesAllowed() {
            if (failWrites) {
                throw new IllegalStateException("write failed");
            }
        }
    }

    /// Output stream that fails close at runtime and records close calls.
    @NotNullByDefault
    private static final class RuntimeCloseFailingCloseTrackingOutputStream extends ByteArrayOutputStream {
        /// Whether this output stream has been closed.
        private boolean closed;

        /// Records that this output stream has been closed and fails at runtime.
        @Override
        public void close() {
            closed = true;
            throw new IllegalStateException("close failed");
        }

        /// Returns whether this output stream has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Input stream that fails when closed.
    @NotNullByDefault
    private static final class CloseFailingInputStream extends ByteArrayInputStream {
        /// Creates a close-failing input stream with the given bytes.
        private CloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Always fails when closed.
        @Override
        public void close() throws IOException {
            throw new IOException("close failed");
        }
    }

    /// Input stream that fails its first close call and records cleanup state.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// Whether this input stream has been closed.
        private boolean closed;

        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream with the given bytes.
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records every attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            closed = true;
            super.close();
        }

        /// Returns whether this input stream is closed.
        private boolean closed() {
            return closed;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Input stream that fails at runtime when closed.
    @NotNullByDefault
    private static final class RuntimeCloseFailingInputStream extends ByteArrayInputStream {
        /// Creates a runtime close-failing input stream with the given bytes.
        private RuntimeCloseFailingInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Always fails at runtime when closed.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }

    /// Input stream that fails reads with I/O and close at runtime.
    @NotNullByDefault
    private static final class ReadAndRuntimeCloseFailingInputStream extends InputStream {
        /// Always fails reads with I/O.
        @Override
        public int read() throws IOException {
            throw new IOException("read failed");
        }

        /// Always fails non-empty reads with I/O.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            throw new IOException("read failed");
        }

        /// Always fails at runtime when closed.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }

    /// Input stream that fails reads at a configured offset and records close calls.
    @NotNullByDefault
    private static final class ReadFailingCloseTrackingInputStream extends InputStream {
        /// The source bytes.
        private final byte @Unmodifiable [] content;

        /// The first offset where reads fail.
        private final int failOffset;

        /// The current source position.
        private int position;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a read-failing stream over the given bytes.
        private ReadFailingCloseTrackingInputStream(byte[] content, int failOffset) {
            if (failOffset < 0 || failOffset > content.length) {
                throw new IllegalArgumentException("failOffset is out of range");
            }
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failOffset = failOffset;
        }

        /// Reads one byte.
        @Override
        public int read() {
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= content.length) {
                return -1;
            }
            return Byte.toUnsignedInt(content[position++]);
        }

        /// Reads bytes until the configured failure offset.
        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (position >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, Math.min(failOffset, content.length) - position);
            if (count == 0) {
                throw new IllegalStateException("read failed");
            }
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }

        /// Records that this stream has been closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Test volume source whose close operation fails.
    @NotNullByDefault
    private static final class CloseFailingOwnedZipVolumeSource implements ArkivoVolumeSource {
        /// The number of close calls that should fail.
        private final int failureCount;

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates an owned ZIP volume source whose close always fails.
        private CloseFailingOwnedZipVolumeSource() {
            this(Integer.MAX_VALUE);
        }

        /// Creates an owned ZIP volume source that fails the given number of close calls.
        private CloseFailingOwnedZipVolumeSource(int failureCount) {
            if (failureCount < 0) {
                throw new IllegalArgumentException("failureCount must not be negative");
            }
            this.failureCount = failureCount;
        }

        /// Opens no volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            return null;
        }

        /// Records the close and fails while configured failures remain.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount <= failureCount) {
                throw new IOException("volume source close failed");
            }
        }

        /// Returns how many times this source was closed.
        private int closeCount() {
            return closeCount;
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
