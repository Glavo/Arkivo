// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemProvider;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ArchiveOptions;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeChannel;
import org.glavo.arkivo.archive.ArkivoVolumeOutput;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoFileSystemImpl;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemConfig;
import org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
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
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NotLinkException;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.attribute.UserPrincipalNotFoundException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.stream.StreamSupport;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic ZIP Arkivo file system behavior.
@NotNullByDefault
public final class ZipArkivoFileSystemTest {
    /// The standards-compliant split size used by ZIP volume tests.
    private static final int TEST_SPLIT_SIZE = Math.toIntExact(ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE);

    /// The ZIP LZMA general purpose flag indicating an EOS marker.
    private static final int LZMA_EOS_MARKER_FLAG = 1 << 1;

    /// The ZIP version needed to extract Deflate64 entries.
    private static final int DEFLATE64_VERSION_NEEDED = 21;

    /// The ZIP version needed to extract LZMA entries.
    private static final int LZMA_VERSION_NEEDED = 63;

    /// The LZMA SDK major version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MAJOR_VERSION = 9;

    /// The LZMA SDK minor version stored in ZIP LZMA property headers.
    private static final int LZMA_SDK_MINOR_VERSION = 20;

    /// The ZIP LZMA property data size for raw LZMA streams.
    private static final int LZMA_PROPERTY_SIZE = 5;

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

    /// Verifies that a streaming ZIP writer can append entries to an existing archive.
    @Test
    public void streamingWriterAppend() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-append-");
        Map<String, Object> appendEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("before.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoStreamingWriter writer =
                         ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(appendEnvironment))) {
                writer.beginFile("after.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("after".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("before.txt");
                assertThrows(FileAlreadyExistsException.class, writer::openOutputStream);
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

    /// Verifies that the streaming ZIP writer emits ZIP64 end records when the entry count overflows ZIP32 fields.
    @Test
    public void streamingWriterZip64EndRecordForManyEntries() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(output)) {
            for (int index = 0; index <= 0xffff; index++) {
                writer.beginDirectory("dir-" + index);
                writer.endEntry();
            }
        }

        byte[] archive = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int endOffset = archive.length - 22;
        int locatorOffset = endOffset - 20;
        int zip64EndOffset = Math.toIntExact(buffer.getLong(locatorOffset + 8));

        assertEquals(0x07064b50, buffer.getInt(locatorOffset));
        assertEquals(0x06064b50, buffer.getInt(zip64EndOffset));
        assertEquals(44L, buffer.getLong(zip64EndOffset + 4));
        assertEquals(0x1_0000L, buffer.getLong(zip64EndOffset + 24));
        assertEquals(0x1_0000L, buffer.getLong(zip64EndOffset + 32));
        assertEquals(0x06054b50, buffer.getInt(endOffset));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 8)));
        assertEquals(0xffff, Short.toUnsignedInt(buffer.getShort(endOffset + 10)));
    }

    /// Verifies that central directory entries store oversized local header offsets in ZIP64 extra data.
    @Test
    public void streamingWriterZip64CentralDirectoryEntryForLargeOffset() throws Exception {
        byte[] rawName = "large-offset.txt".getBytes(StandardCharsets.UTF_8);
        Object metadata = zipEntryMetadata();
        Object centralEntry = zipCentralEntry(
                "large-offset.txt",
                rawName,
                0L,
                0L,
                0xffff_ffffL + 1L,
                metadata
        );

        Method centralDirectoryEntryBytes = StreamingZipArkivoFileSystemImpl.class.getDeclaredMethod(
                "centralDirectoryEntryBytes",
                centralEntry.getClass()
        );
        centralDirectoryEntryBytes.setAccessible(true);
        byte[] centralDirectory = (byte[]) centralDirectoryEntryBytes.invoke(null, centralEntry);
        ByteBuffer buffer = ByteBuffer.wrap(centralDirectory).order(ByteOrder.LITTLE_ENDIAN);
        int extraOffset = 46 + rawName.length;

        assertEquals(0x02014b50, buffer.getInt(0));
        assertEquals(45, Short.toUnsignedInt(buffer.getShort(6)));
        assertEquals(0, buffer.getInt(20));
        assertEquals(0, buffer.getInt(24));
        assertEquals(0xffff_ffffL, Integer.toUnsignedLong(buffer.getInt(42)));
        assertEquals(rawName.length, Short.toUnsignedInt(buffer.getShort(28)));
        assertEquals(12, Short.toUnsignedInt(buffer.getShort(30)));
        assertEquals(0x0001, Short.toUnsignedInt(buffer.getShort(extraOffset)));
        assertEquals(8, Short.toUnsignedInt(buffer.getShort(extraOffset + 2)));
        assertEquals(0xffff_ffffL + 1L, buffer.getLong(extraOffset + 4));
    }

    /// Verifies that ZIP64 data descriptors use 64-bit size fields.
    @Test
    public void streamingWriterZip64DataDescriptor() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        StreamingZipArkivoFileSystemImpl fileSystem = new StreamingZipArkivoFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                output,
                ZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.EMPTY)
        );
        Method writeDataDescriptor = StreamingZipArkivoFileSystemImpl.class.getDeclaredMethod(
                "writeDataDescriptor",
                long.class,
                long.class,
                long.class,
                boolean.class
        );
        writeDataDescriptor.setAccessible(true);

        writeDataDescriptor.invoke(fileSystem, 0x1234_5678L, 0xffff_ffffL + 2L, 0xffff_ffffL + 1L, true);

        byte[] descriptor = output.toByteArray();
        ByteBuffer buffer = ByteBuffer.wrap(descriptor).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(24, descriptor.length);
        assertEquals(0x08074b50, buffer.getInt(0));
        assertEquals(0x1234_5678L, Integer.toUnsignedLong(buffer.getInt(4)));
        assertEquals(0xffff_ffffL + 2L, buffer.getLong(8));
        assertEquals(0xffff_ffffL + 1L, buffer.getLong(16));
    }

    /// Verifies that a streaming ZIP writer can write BZIP2-compressed file entries.
    @Test
    public void streamingWriterBzip2Entry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-bzip2-");
        byte[] content = "bzip2 writer content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("bzip2.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.bzip2());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bzip2.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.bzip2(), attributes.method());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertEquals(true, (attributes.generalPurposeFlags() & (1 << 3)) != 0);
                assertArrayEquals(content, Files.readAllBytes(file));
            }
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
                writer.beginFile("deflate64.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.deflate64());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/deflate64.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.deflate64(), attributes.method());
                assertEquals(DEFLATE64_VERSION_NEEDED, attributes.versionNeededToExtract());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            try (var zipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                    .setPath(archivePath)
                    .get()) {
                var entry = Objects.requireNonNull(zipFile.getEntry("deflate64.txt"));
                assertEquals(ZipMethod.DEFLATE64_ID, entry.getMethod());
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
            Map<String, Object> environment = Map.of(
                    ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)
            );
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
                writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.deflate64());
                view.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }

                writer.beginFile("after.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(after);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(environment))) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/secret.txt")));
                assertArrayEquals(after, Files.readAllBytes(fileSystem.getPath("/after.txt")));
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        fileSystem.getPath("/secret.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertEquals(ZipMethod.deflate64(), attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer can write Zstandard-compressed file entries.
    @Test
    public void streamingWriterZstandardEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-zstandard-");
        byte[] content = "zstandard writer content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("zstandard.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.zstandard());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/zstandard.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.zstandard(), attributes.method());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertEquals(true, (attributes.generalPurposeFlags() & (1 << 3)) != 0);
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer can write deprecated method 20 Zstandard entries.
    @Test
    public void streamingWriterDeprecatedZstandardEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-deprecated-zstandard-");
        byte[] content = "deprecated zstandard writer content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("deprecated-zstandard.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.deprecatedZstandard());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes streamingAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deprecated-zstandard.txt", streamingAttributes.path());
                assertEquals(ZipMethod.deprecatedZstandard(), streamingAttributes.method());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/deprecated-zstandard.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.deprecatedZstandard(), attributes.method());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a streaming ZIP writer can write XZ-compressed file entries.
    @Test
    public void streamingWriterXzEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-xz-");
        byte[] content = "xz writer content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("xz.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.xz());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/xz.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.xz(), attributes.method());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertEquals(true, (attributes.generalPurposeFlags() & (1 << 3)) != 0);
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read writer-produced BZIP2 entries with data descriptors.
    @Test
    public void streamingReaderBzip2DataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("bzip2-descriptor-");
        byte[] bzip2Content = "bzip2 descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after bzip2 descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("bzip2.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes bzip2Attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("bzip2.txt", bzip2Attributes.path());
                assertEquals(ZipMethod.bzip2(), bzip2Attributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, bzip2Attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, bzip2Attributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(bzip2Content, input.readAllBytes());
                }

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

    /// Verifies that a streaming ZIP writer can write LZMA-compressed file entries.
    @Test
    public void streamingWriterLzmaEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-lzma-");
        byte[] content = "lzma writer content".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("lzma.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.lzma());
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/lzma.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipMethod.lzma(), attributes.method());
                assertEquals(content.length, attributes.size());
                assertEquals(crc32(content), attributes.crc32());
                assertEquals(true, (attributes.generalPurposeFlags() & LZMA_EOS_MARKER_FLAG) != 0);
                assertEquals(true, (attributes.generalPurposeFlags() & (1 << 3)) != 0);
                assertEquals(LZMA_VERSION_NEEDED, attributes.versionNeededToExtract());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read writer-produced Zstandard entries with data descriptors.
    @Test
    public void streamingReaderZstandardDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("zstandard-descriptor-");
        byte[] zstandardContent = "zstandard descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after zstandard descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("zstandard.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes zstandardAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("zstandard.txt", zstandardAttributes.path());
                assertEquals(ZipMethod.zstandard(), zstandardAttributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, zstandardAttributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, zstandardAttributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(zstandardContent, input.readAllBytes());
                }

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

    /// Verifies that the streaming ZIP reader can read writer-produced XZ entries with data descriptors.
    @Test
    public void streamingReaderXzDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("xz-descriptor-");
        byte[] xzContent = "xz descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after xz descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("xz.txt");
                ZipArkivoEntryAttributeView xzView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(xzView);
                xzView.setMethod(ZipMethod.xz());
                try (var output = writer.openOutputStream()) {
                    output.write(xzContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes xzAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("xz.txt", xzAttributes.path());
                assertEquals(ZipMethod.xz(), xzAttributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, xzAttributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, xzAttributes.size());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(xzContent, input.readAllBytes());
                }

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

    /// Verifies that XZ descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterXzDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("xz-descriptor-crc-");
        byte[] xzContent = "xz descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after xz descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("xz-descriptor-crc.txt");
                ZipArkivoEntryAttributeView xzView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(xzView);
                xzView.setMethod(ZipMethod.xz());
                try (var output = writer.openOutputStream()) {
                    output.write(xzContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("xz-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.xz(), firstAttributes.method());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read writer-produced LZMA entries with data descriptors.
    @Test
    public void streamingReaderLzmaDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("lzma-descriptor-");
        byte[] lzmaContent = "lzma descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after lzma descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("lzma.txt");
                ZipArkivoEntryAttributeView lzmaView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(lzmaView);
                lzmaView.setMethod(ZipMethod.lzma());
                try (var output = writer.openOutputStream()) {
                    output.write(lzmaContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes lzmaAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("lzma.txt", lzmaAttributes.path());
                assertEquals(ZipMethod.lzma(), lzmaAttributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, lzmaAttributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, lzmaAttributes.size());
                assertEquals(true, (lzmaAttributes.generalPurposeFlags() & LZMA_EOS_MARKER_FLAG) != 0);
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(lzmaContent, input.readAllBytes());
                }

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

    /// Verifies that the streaming ZIP reader can read traditional encrypted XZ data descriptors.
    @Test
    public void streamingReaderTraditionalXzDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-xz-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] xzContent = "traditional xz descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional xz descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-xz.txt");
                ZipArkivoEntryAttributeView xzView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(xzView);
                xzView.setMethod(ZipMethod.xz());
                xzView.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(xzContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes xzAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-xz.txt", xzAttributes.path());
                assertEquals(ZipMethod.xz(), xzAttributes.method());
                assertEquals(ZipEncryption.traditional(), xzAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, xzAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(xzContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read traditional encrypted LZMA data descriptors.
    @Test
    public void streamingReaderTraditionalLzmaDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-lzma-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] lzmaContent = "traditional lzma descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional lzma descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-lzma.txt");
                ZipArkivoEntryAttributeView lzmaView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(lzmaView);
                lzmaView.setMethod(ZipMethod.lzma());
                lzmaView.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(lzmaContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes lzmaAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-lzma.txt", lzmaAttributes.path());
                assertEquals(ZipMethod.lzma(), lzmaAttributes.method());
                assertEquals(ZipEncryption.traditional(), lzmaAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, lzmaAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(lzmaContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read WinZip AES encrypted XZ data descriptors.
    @Test
    public void streamingReaderWinZipAesXzDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-xz-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] xzContent = "AES xz descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES xz descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-xz.txt");
                ZipArkivoEntryAttributeView xzView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(xzView);
                xzView.setMethod(ZipMethod.xz());
                xzView.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(xzContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes xzAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-xz.txt", xzAttributes.path());
                assertEquals(ZipMethod.xz(), xzAttributes.method());
                assertEquals(ZipEncryption.winZipAes256(), xzAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, xzAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(xzContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that Zstandard descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterZstandardDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("zstandard-descriptor-crc-");
        byte[] zstandardContent = "zstandard descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after zstandard descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("zstandard-descriptor-crc.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("zstandard-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.zstandard(), firstAttributes.method());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read traditional encrypted Zstandard data descriptors.
    @Test
    public void streamingReaderTraditionalZstandardDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-zstandard-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] zstandardContent = "traditional zstandard descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional zstandard descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-zstandard.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                zstandardView.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes zstandardAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-zstandard.txt", zstandardAttributes.path());
                assertEquals(ZipMethod.zstandard(), zstandardAttributes.method());
                assertEquals(ZipEncryption.traditional(), zstandardAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, zstandardAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(zstandardContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that traditional encrypted Zstandard descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterTraditionalZstandardDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-zstandard-descriptor-crc-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] zstandardContent = "traditional zstandard descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional zstandard descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-zstandard-descriptor-crc.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                zstandardView.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-zstandard-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.zstandard(), firstAttributes.method());
                assertEquals(ZipEncryption.traditional(), firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                assertEquals(ZipEncryption.none(), secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read WinZip AES encrypted LZMA data descriptors.
    @Test
    public void streamingReaderWinZipAesLzmaDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-lzma-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] lzmaContent = "AES lzma descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES lzma descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-lzma.txt");
                ZipArkivoEntryAttributeView lzmaView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(lzmaView);
                lzmaView.setMethod(ZipMethod.lzma());
                lzmaView.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(lzmaContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes lzmaAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-lzma.txt", lzmaAttributes.path());
                assertEquals(ZipMethod.lzma(), lzmaAttributes.method());
                assertEquals(ZipEncryption.winZipAes256(), lzmaAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, lzmaAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(lzmaContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                ZipEncryption.none(),
                ZipEncryption.traditional(),
                ZipEncryption.winZipAes256()
        }) {
            byte[] archive = streamingDeflatedDataDescriptorArchive(encryption, password, content, after);
            Map<String, Object> environment = Map.of(
                    ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                    ArkivoPasswordProvider.fixed(password)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(environment)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.deflated(), attributes.method());
                assertEquals(encryption, attributes.encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(Byte.toUnsignedInt(content[0]), input.read());
                }

                assertEquals(true, reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                ZipEncryption.none(),
                ZipEncryption.traditional(),
                ZipEncryption.winZipAes256()
        }) {
            byte[] archive = tamperFirstDataDescriptorCrc(
                    streamingDeflatedDataDescriptorArchive(encryption, password, content, after)
            );
            Map<String, Object> environment = Map.of(
                    ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                    ArkivoPasswordProvider.fixed(password)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(environment)
            )) {
                assertEquals(true, reader.next());
                InputStream input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor"));
                input.close();

                assertEquals(true, reader.next());
                try (InputStream afterInput = reader.openInputStream()) {
                    assertArrayEquals(after, afterInput.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                ZipEncryption.none(),
                ZipEncryption.traditional(),
                ZipEncryption.winZipAes256()
        }) {
            byte[] archive = streamingLzmaDataDescriptorArchive(encryption, password, content, after);
            Map<String, Object> environment = Map.of(
                    ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                    ArkivoPasswordProvider.fixed(password)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(environment)
            )) {
                assertEquals(true, reader.next());
                assertEquals(encryption, reader.readAttributes(ZipArkivoEntryAttributes.class).encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals(Byte.toUnsignedInt(content[0]), input.read());
                }

                assertEquals(true, reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
                ZipEncryption.none(),
                ZipEncryption.traditional(),
                ZipEncryption.winZipAes256()
        }) {
            byte[] archive = tamperFirstDataDescriptorCrc(
                    streamingLzmaDataDescriptorArchive(encryption, password, content, after)
            );
            Map<String, Object> environment = Map.of(
                    ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                    ArkivoPasswordProvider.fixed(password)
            );
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(environment)
            )) {
                assertEquals(true, reader.next());
                InputStream input = reader.openInputStream();
                IOException exception = assertThrows(IOException.class, input::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor"));
                input.close();

                assertEquals(true, reader.next());
                try (InputStream afterInput = reader.openInputStream()) {
                    assertArrayEquals(after, afterInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        }
    }

    /// Verifies that the streaming ZIP reader can read WinZip AES encrypted Zstandard data descriptors.
    @Test
    public void streamingReaderWinZipAesZstandardDataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-zstandard-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] zstandardContent = "AES zstandard descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES zstandard descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-zstandard.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                zstandardView.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes zstandardAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-zstandard.txt", zstandardAttributes.path());
                assertEquals(ZipMethod.zstandard(), zstandardAttributes.method());
                assertEquals(ZipEncryption.winZipAes256(), zstandardAttributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, zstandardAttributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(zstandardContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES encrypted Zstandard descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterWinZipAesZstandardDataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-zstandard-descriptor-crc-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] zstandardContent = "AES zstandard descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES zstandard descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-zstandard-descriptor-crc.txt");
                ZipArkivoEntryAttributeView zstandardView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(zstandardView);
                zstandardView.setMethod(ZipMethod.zstandard());
                zstandardView.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(zstandardContent);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-zstandard-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.zstandard(), firstAttributes.method());
                assertEquals(ZipEncryption.winZipAes256(), firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("WinZip AES data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                assertEquals(ZipEncryption.none(), secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that BZIP2 descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterBzip2DataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("bzip2-descriptor-crc-");
        byte[] bzip2Content = "bzip2 descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after bzip2 descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("bzip2-descriptor-crc.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("bzip2-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.bzip2(), firstAttributes.method());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read traditional encrypted BZIP2 data descriptors.
    @Test
    public void streamingReaderTraditionalBzip2DataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-bzip2-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] bzip2Content = "traditional bzip2 descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional bzip2 descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-bzip2.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                bzip2View.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes bzip2Attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-bzip2.txt", bzip2Attributes.path());
                assertEquals(ZipMethod.bzip2(), bzip2Attributes.method());
                assertEquals(ZipEncryption.traditional(), bzip2Attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, bzip2Attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(bzip2Content, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that traditional encrypted BZIP2 descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterTraditionalBzip2DataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("traditional-bzip2-descriptor-crc-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] bzip2Content = "traditional bzip2 descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after traditional bzip2 descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("secret-bzip2-descriptor-crc.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                bzip2View.setEncryption(ZipEncryption.traditional());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("secret-bzip2-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.bzip2(), firstAttributes.method());
                assertEquals(ZipEncryption.traditional(), firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                assertEquals(ZipEncryption.none(), secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the streaming ZIP reader can read WinZip AES encrypted BZIP2 data descriptors.
    @Test
    public void streamingReaderWinZipAesBzip2DataDescriptorFromWriter() throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-bzip2-descriptor-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] bzip2Content = "AES bzip2 descriptor content".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES bzip2 descriptor".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-bzip2.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                bzip2View.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes bzip2Attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-bzip2.txt", bzip2Attributes.path());
                assertEquals(ZipMethod.bzip2(), bzip2Attributes.method());
                assertEquals(ZipEncryption.winZipAes256(), bzip2Attributes.encryption());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, bzip2Attributes.compressedSize());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(bzip2Content, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes afterAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", afterAttributes.path());
                assertEquals(ZipMethod.stored(), afterAttributes.method());
                assertEquals(ZipEncryption.none(), afterAttributes.encryption());
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(afterContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that WinZip AES encrypted BZIP2 descriptor CRC failures do not consume the following entry.
    @Test
    public void streamingReaderCloseAfterWinZipAesBzip2DataDescriptorCrcMismatchConsumesDescriptor()
            throws IOException {
        Path archivePath = createTemporaryArchivePath("aes-bzip2-descriptor-crc-");
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        byte[] bzip2Content = "AES bzip2 descriptor crc mismatch".getBytes(StandardCharsets.UTF_8);
        byte[] afterContent = "after AES bzip2 descriptor mismatch".getBytes(StandardCharsets.UTF_8);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                writer.beginFile("aes-bzip2-descriptor-crc.txt");
                ZipArkivoEntryAttributeView bzip2View = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(bzip2View);
                bzip2View.setMethod(ZipMethod.bzip2());
                bzip2View.setEncryption(ZipEncryption.winZipAes256());
                try (var output = writer.openOutputStream()) {
                    output.write(bzip2Content);
                }

                writer.beginFile("after.txt");
                ZipArkivoEntryAttributeView afterView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(afterView);
                afterView.setMethod(ZipMethod.stored());
                try (var output = writer.openOutputStream()) {
                    output.write(afterContent);
                }
            }

            byte[] archive = tamperFirstDataDescriptorCrc(Files.readAllBytes(archivePath));
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes firstAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("aes-bzip2-descriptor-crc.txt", firstAttributes.path());
                assertEquals(ZipMethod.bzip2(), firstAttributes.method());
                assertEquals(ZipEncryption.winZipAes256(), firstAttributes.encryption());
                var firstInput = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, firstInput::readAllBytes);
                assertEquals(true, exception.getMessage().contains("WinZip AES data descriptor does not match"));
                firstInput.close();

                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes secondAttributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("after.txt", secondAttributes.path());
                assertEquals(ZipMethod.stored(), secondAttributes.method());
                assertEquals(ZipEncryption.none(), secondAttributes.encryption());
                try (var secondInput = reader.openInputStream()) {
                    assertArrayEquals(afterContent, secondInput.readAllBytes());
                }
                assertEquals(false, reader.next());
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

            assertThrows(
                    ClosedChannelException.class,
                    () -> reader.readAttributes(ZipArkivoEntryAttributes.class)
            );
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
        Path copiedDirectory = archivePath.getParent().resolve("copied-meta");
        Path existingFile = archivePath.getParent().resolve("existing-file");
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
                Files.copy(fileSystem.getPath("/meta"), copiedDirectory);
                assertEquals(true, Files.isDirectory(copiedDirectory));
                assertThrows(FileAlreadyExistsException.class, () -> Files.copy(fileSystem.getPath("/meta"), copiedDirectory));
                Files.copy(fileSystem.getPath("/meta"), copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
                Files.copy(fileSystem.getPath("/meta"), existingFile, StandardCopyOption.REPLACE_EXISTING);
                assertEquals(true, Files.isDirectory(existingFile));

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

                Path link = fileSystem.getPath("/meta/link");
                ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertArrayEquals(content, Files.readAllBytes(link));
                assertEquals(fileSystem.getPath("stored.bin"), Files.readSymbolicLink(link));
                assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(fileSystem.getPath("/meta/stored.bin")));
            }
        } finally {
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(copiedDirectory);
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(
                            ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed("wrong".getBytes(StandardCharsets.UTF_8))
                    ))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.winZipAes256()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.winZipAes256()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
                writer.beginFile("empty-password-aes.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                Path file = fileSystem.getPath("/empty-password-aes.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);

                assertEquals(ZipEncryption.winZipAes256(), attributes.encryption());
                assertArrayEquals(content, Files.readAllBytes(file));
            }

            byte[] archive = Files.readAllBytes(archivePath);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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

    /// Verifies that streaming writer channels commit wrapper closure after retrying entry validation failure.
    @Test
    public void streamingWriterChannelCloseFailureAllowsWrapperRetry() throws IOException {
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
                assertEquals(true, channel.isOpen());
                channel.close();
                assertEquals(false, channel.isOpen());
                assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
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
        IllegalStateException retryFailure = assertThrows(IllegalStateException.class, fileSystem::close);
        assertEquals("close failed", retryFailure.getMessage());
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
                                 ArchiveOptions.fromEnvironment(Map.of(
                                         ArkivoFileSystem.OPEN_OPTIONS.key(),
                                         Set.of(
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING,
                                                 StandardOpenOption.WRITE
                                         )
                                 ))
                         )) {
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
                Map<String, Object> namedAttributes = Files.readAttributes(file, "zip:size,compressedSize,method");
                ArrayList<String> rootChildren = new ArrayList<>();
                ArrayList<String> directoryChildren = new ArrayList<>();

                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(5L, fileAttributes.size());
                assertEquals(ZipMethod.deflated(), fileAttributes.method());
                assertEquals(false, linkAttributes.isRegularFile());
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(fileSystem.getPath("hello.txt"), Files.readSymbolicLink(link));
                assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(file));
                assertNotNull(fileAttributeView);
                assertEquals(5L, fileAttributeView.readAttributes().size());
                assertEquals(Set.of("size", "compressedSize", "method"), namedAttributes.keySet());
                assertEquals(5L, namedAttributes.get("size"));
                assertEquals(ZipMethod.deflated(), namedAttributes.get("method"));

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

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    ))
            )) {
                assertSymbolicLinkIdentity(fileSystem, true);
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies real-path resolution and file identity through persisted ZIP symbolic links.
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
                writer.beginFile("hello.txt");
                ZipArkivoEntryAttributeView attributeView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(attributeView);
                attributeView.setTimes(lastModifiedTime, null, null);
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("hello".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem sourceFileSystem = ZipArkivoFileSystem.open(sourcePath);
                 ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.open(
                         targetPath,
                         ArchiveOptions.fromEnvironment(Map.of(
                                 ArkivoFileSystem.OPEN_OPTIONS.key(),
                                 Set.of(
                                         StandardOpenOption.CREATE,
                                         StandardOpenOption.TRUNCATE_EXISTING,
                                         StandardOpenOption.WRITE
                                 )
                         ))
                 )) {
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
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/target.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("target".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginSymbolicLink("dir/link", "target.txt");
                writer.endEntry();
            }

            try (ZipArkivoFileSystem sourceFileSystem = ZipArkivoFileSystem.open(sourcePath);
                 ZipArkivoFileSystem targetFileSystem = ZipArkivoFileSystem.open(
                         targetPath,
                         ArchiveOptions.fromEnvironment(Map.of(
                                 ArkivoFileSystem.OPEN_OPTIONS.key(),
                                 Set.of(
                                         StandardOpenOption.CREATE,
                                         StandardOpenOption.TRUNCATE_EXISTING,
                                         StandardOpenOption.WRITE
                                 )
                         ))
                 )) {
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
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(environment))) {
                Files.createSymbolicLink(fileSystem.getPath("/link"), Path.of("target.txt"));
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                Path link = fileSystem.getPath("/link");
                ZipArkivoEntryAttributes linkAttributes = Files.readAttributes(
                        link,
                        ZipArkivoEntryAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(ZipEncryption.traditional(), linkAttributes.encryption());
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
            try (ZipArkivoFileSystem fileSystem =
                         ZipArkivoFileSystem.open(
                                 archivePath,
                                 ArchiveOptions.fromEnvironment(Map.of(
                                         ArkivoFileSystem.OPEN_OPTIONS.key(),
                                         Set.of(
                                                 StandardOpenOption.CREATE,
                                                 StandardOpenOption.TRUNCATE_EXISTING,
                                                 StandardOpenOption.WRITE
                                         )
                                 ))
                         )) {
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
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    sourcePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            ),
                            ArkivoFileSystem.COMMIT_TARGET.key(),
                            ArkivoCommitTarget.writeTo(targetPath)
                    ))
            )) {
                Path committed = fileSystem.getPath("/committed.txt");
                Files.writeString(committed, "committed", StandardCharsets.UTF_8);
                var fileStore = Files.getFileStore(committed);
                assertStreamingZipFileStoreAttributeViews(fileStore, false);
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
                writer.beginFile("before.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    sourcePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE),
                            ArkivoFileSystem.COMMIT_TARGET.key(),
                            ArkivoCommitTarget.writeTo(targetPath)
                    ))
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
                writer.beginFile("before.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("keep.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                    ))
            )) {
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
                writer.beginFile("before.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("keep.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    sourcePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE),
                            ArkivoFileSystem.COMMIT_TARGET.key(),
                            ArkivoCommitTarget.writeTo(targetPath)
                    ))
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
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/child.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("child".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginDirectory("empty");
                writer.endEntry();
                writer.beginFile("remove.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("recreate.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE)
                    ))
            )) {
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
        Map<String, Object> updateEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/child.txt");
                ZipArkivoEntryAttributeView childView = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(childView);
                childView.setCentralDirectoryExtraData(extraData);
                childView.setRawComment(rawComment);
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("child".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginSymbolicLink("dir/link", "child.txt");
                writer.endEntry();
                writer.beginFile("target.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("old-target".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("replacement.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("new-target".getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] originalCompressedChild = compressedEntryPayload(archivePath, "dir/child.txt");

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(updateEnvironment))) {
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
        Map<String, Object> updateEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(password),
                            ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                            ZipEncryption.traditional()
                    ))
            )) {
                writer.beginDirectory("dir");
                writer.endEntry();
                writer.beginFile("dir/existing.txt");
                ZipArkivoEntryAttributeView existingWriteView = writer.attributeView(
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(existingWriteView);
                existingWriteView.setEncryption(ZipEncryption.none());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("existing payload".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginSymbolicLink("dir/link", "existing.txt");
                writer.endEntry();
                writer.beginFile("dir/encrypted.txt");
                ZipArkivoEntryAttributeView encryptedWriteView = writer.attributeView(
                        ZipArkivoEntryAttributeView.class
                );
                assertNotNull(encryptedWriteView);
                encryptedWriteView.setTimes(encryptedTime, null, null);
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("encrypted payload".getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] originalCompressedPayload = compressedEntryPayload(archivePath, "dir/existing.txt");
            byte[] originalEncryptedPayload = compressedEntryPayload(archivePath, "dir/encrypted.txt");

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(updateEnvironment))) {
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
                        () -> existingView.setMethod(ZipMethod.stored())
                );
                assertThrows(
                        UnsupportedOperationException.class,
                        () -> existingView.setTimes(null, existingTime, null)
                );
                assertThrows(
                        IllegalArgumentException.class,
                        () -> existingView.setInternalAttributes(0x1_0000)
                );
                assertEquals(beforeRejectedChanges.method(), existingView.readAttributes().method());
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
                    ArchiveOptions.fromEnvironment(Map.of(
                            ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(password)
                    ))
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
        Map<String, Object> updateEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("replace.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("before".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("remove.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("keep.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(updateEnvironment))) {
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
        Map<String, Object> updateEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        try {
            try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archivePath))) {
                writeStoredZipEntry(output, "patch.txt", "abcdef".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "append.txt", "left".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "truncate.txt", "lengthy".getBytes(StandardCharsets.UTF_8), null, null);
                writeStoredZipEntry(output, "replace.txt", removedBody, null, null);
                writeStoredZipEntry(output, "conflict.txt", "stable".getBytes(StandardCharsets.UTF_8), null, null);
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath, ArchiveOptions.fromEnvironment(updateEnvironment))) {
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

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    ))
            )) {
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

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    ))
            )) {
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

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    ))
            )) {
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
                writer.beginFile("remove.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("remove".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("keep.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("keep".getBytes(StandardCharsets.UTF_8));
                }
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    sourcePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.WRITE),
                            ArkivoFileSystem.COMMIT_TARGET.key(),
                            ArkivoCommitTarget.writeTo(targetPath)
                    ))
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
            ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            ),
                            ArkivoFileSystem.COMMIT_TARGET.key(),
                            ArkivoCommitTarget.atomicReplace(directory)
                    ))
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

    /// Verifies that ZIP file system writes create split output volumes.
    @Test
    public void fileSystemCreateWritesSplitVolumes() throws IOException {
        Path archivePath = createTemporaryArchivePath("fs-create-split-");

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            ),
                            ZipArkivoFileSystem.SPLIT_SIZE.key(),
                            (long) TEST_SPLIT_SIZE
                    ))
            )) {
                Files.writeString(fileSystem.getPath("/hello.txt"), "split file system", StandardCharsets.UTF_8);
                Files.write(fileSystem.getPath("/padding.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));
            }

            List<Path> volumes = splitVolumePaths(archivePath);
            assertEquals(true, volumes.size() > 1);
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))) {
                assertEquals(
                        "split file system",
                        Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that ZIP streaming writer factories create split output volumes.
    @Test
    public void streamingWriterCreatesSplitVolumes() throws IOException {
        Path archivePath = createTemporaryArchivePath("stream-write-split-");

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), (long) TEST_SPLIT_SIZE))
            )) {
                writer.beginFile("stream.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("split streaming writer".getBytes(StandardCharsets.UTF_8));
                }
                writer.beginFile("padding.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(splitTestContent(TEST_SPLIT_SIZE * 2));
                }
            }

            List<Path> volumes = splitVolumePaths(archivePath);
            assertEquals(true, volumes.size() > 1);
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))) {
                assertEquals(
                        "split streaming writer",
                        Files.readString(fileSystem.getPath("/stream.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that streaming ZIP writers publish readable split archives to custom volume targets.
    @Test
    public void streamingWriterPublishesToCustomVolumeTarget() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE)) {
            writer.beginFile("content.bin");
            ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.stored());
            try (OutputStream output = writer.openOutputStream()) {
                output.write(content);
            }
        }

        TestVolumeOutput volumeOutput = target.output();
        assertEquals(true, volumeOutput.volumeCount() > 1);
        assertEquals(volumeOutput.volumeCount() - 1L, volumeOutput.finalVolumeIndex());
        assertEquals(1, volumeOutput.commitCount());
        assertEquals(0, volumeOutput.rollbackCount());
        assertEquals(1, volumeOutput.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(volumeOutput.volumeSource())) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies writable ZIP file systems publish split archives to custom volume targets.
    @Test
    public void fileSystemCreatesSplitArchiveOnCustomVolumeTarget() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(target, TEST_SPLIT_SIZE)) {
            Path entry = fileSystem.getPath("/content.bin");
            Files.write(entry, content);
        }

        TestVolumeOutput output = target.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(output.volumeCount() - 1L, output.finalVolumeIndex());
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies complete-rewrite mutation from split input to explicitly sized split output.
    @Test
    public void fileSystemUpdatesSplitArchiveOnCustomVolumeTarget() throws IOException {
        byte[] keepContent = splitTestContent(TEST_SPLIT_SIZE * 2);
        byte[] replacedContent = "replaced-local-record-secret".getBytes(StandardCharsets.UTF_8);
        byte[] removedContent = "removed-local-record-secret".getBytes(StandardCharsets.UTF_8);
        TestVolumeTarget originalTarget = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(originalTarget, TEST_SPLIT_SIZE)) {
            Files.write(fileSystem.getPath("/keep.bin"), keepContent);
            Files.write(fileSystem.getPath("/replace.txt"), replacedContent);
            Files.write(fileSystem.getPath("/remove.txt"), removedContent);
        }

        TrackingVolumeSource source =
                new TrackingVolumeSource(originalTarget.output().volumeSource());
        TestVolumeTarget updatedTarget = new TestVolumeTarget();
        byte[] updatedContent = "updated".getBytes(StandardCharsets.UTF_8);
        byte[] addedContent = new byte[137];
        for (int index = 0; index < addedContent.length; index++) {
            addedContent[index] = (byte) (index * 17);
        }

        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.update(source, updatedTarget, TEST_SPLIT_SIZE)) {
            assertEquals(false, fileSystem.isReadOnly());
            assertArrayEquals(keepContent, Files.readAllBytes(fileSystem.getPath("/keep.bin")));
            assertArrayEquals(replacedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            Files.write(fileSystem.getPath("/replace.txt"), updatedContent);
            Files.delete(fileSystem.getPath("/remove.txt"));
            Files.write(fileSystem.getPath("/added.bin"), addedContent);
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(addedContent, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            assertThrows(
                    NoSuchFileException.class,
                    () -> Files.readAllBytes(fileSystem.getPath("/remove.txt"))
            );
        }

        assertEquals(1, source.closeCount());
        TestVolumeOutput output = updatedTarget.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertEquals(0L, fileSystem.preambleSize());
            assertArrayEquals(keepContent, Files.readAllBytes(fileSystem.getPath("/keep.bin")));
            assertArrayEquals(updatedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(addedContent, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
        }
        try (ZipArkivoFileSystem fileSystem =
                     ZipArkivoFileSystem.open(originalTarget.output().volumeSource())) {
            assertArrayEquals(replacedContent, Files.readAllBytes(fileSystem.getPath("/replace.txt")));
            assertArrayEquals(removedContent, Files.readAllBytes(fileSystem.getPath("/remove.txt")));
        }
        byte[] updatedArchive = output.archiveBytes();
        ByteBuffer updatedHeader = ByteBuffer.wrap(updatedArchive).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x08074b50, updatedHeader.getInt());
        assertEquals(0x04034b50, updatedHeader.getInt());
        assertEquals(false, containsBytes(updatedArchive, "remove.txt".getBytes(StandardCharsets.UTF_8)));
    }

    /// Verifies explicit volume updates preserve preamble bytes while changing the output split layout.
    @Test
    public void volumeUpdatePreservesPreambleInSplitOutput() throws IOException {
        byte[] preamble = new byte[]{9, 7, 5, 3, 1};
        TrackingVolumeSource source = new TrackingVolumeSource(
                new TestSeekableChannelSource(updateSourceZip(preamble))
        );
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(source, target, TEST_SPLIT_SIZE)) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            Files.writeString(fileSystem.getPath("/replace.txt"), "new", StandardCharsets.UTF_8);
            Files.delete(fileSystem.getPath("/remove.txt"));
            Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            Files.write(fileSystem.getPath("/padding.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));
        }

        assertEquals(1, source.closeCount());
        TestVolumeOutput output = target.output();
        assertEquals(true, output.volumeCount() > 1);
        assertEquals(true, output.allVolumeSizesAtMost(TEST_SPLIT_SIZE));
        assertEquals(1, output.commitCount());
        assertEquals(0, output.rollbackCount());
        byte[] archive = output.archiveBytes();
        assertEquals(
                0x08074b50,
                ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN).getInt()
        );
        assertArrayEquals(preamble, Arrays.copyOfRange(archive, Integer.BYTES, Integer.BYTES + preamble.length));
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(output.volumeSource())) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
            assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
            assertEquals(false, Files.exists(fileSystem.getPath("/remove.txt")));
            assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
        }
    }

    /// Verifies a failed split rewrite rolls back its output and releases the owned input source.
    @Test
    public void volumeUpdateRollsBackAfterOutputVolumeFailure() throws IOException {
        TestVolumeTarget originalTarget = new TestVolumeTarget();
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(originalTarget, TEST_SPLIT_SIZE)) {
            Files.writeString(fileSystem.getPath("/original.txt"), "original", StandardCharsets.UTF_8);
        }
        TrackingVolumeSource source = new TrackingVolumeSource(originalTarget.output().volumeSource());
        TestVolumeTarget failingTarget = new TestVolumeTarget(1);
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(source, failingTarget, TEST_SPLIT_SIZE);
        Files.write(fileSystem.getPath("/added.bin"), splitTestContent(TEST_SPLIT_SIZE * 2));

        IOException exception = assertThrows(IOException.class, fileSystem::close);
        assertEquals(true, exception.getMessage().contains("volume open failed"));
        assertEquals(1, source.closeCount());
        TestVolumeOutput output = failingTarget.output();
        assertEquals(0, output.commitCount());
        assertEquals(1, output.rollbackCount());
        assertEquals(1, output.closeCount());
        try (ZipArkivoFileSystem original =
                     ZipArkivoFileSystem.open(originalTarget.output().volumeSource())) {
            assertEquals(
                    "original",
                    Files.readString(original.getPath("/original.txt"), StandardCharsets.UTF_8)
            );
            assertEquals(false, Files.exists(original.getPath("/added.bin")));
        }
    }
    /// Verifies archive finalization failures roll back custom split output transactions.
    @Test
    public void splitVolumeTargetRollsBackAfterEntryFinalizationFailure() throws IOException {
        byte[] content = "invalid expected size".getBytes(StandardCharsets.UTF_8);
        TestVolumeTarget target = new TestVolumeTarget();
        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE);
        writer.beginFile("invalid.txt");
        ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setUncompressedSizeAndCrc32(content.length + 1L, crc32(content));
        OutputStream outputStream = writer.openOutputStream();
        outputStream.write(content);

        IOException exception = assertThrows(IOException.class, writer::close);
        assertEquals(true, exception.getMessage().contains("configured size"));
        TestVolumeOutput output = target.output();
        assertEquals(0, output.commitCount());
        assertEquals(1, output.rollbackCount());
        assertEquals(1, output.closeCount());
    }
    /// Verifies that a custom volume target is rolled back when opening a later volume fails.
    @Test
    public void streamingWriterRollsBackCustomVolumeTargetAfterWriteFailure() throws IOException {
        byte[] content = splitTestContent(TEST_SPLIT_SIZE * 2);
        TestVolumeTarget target = new TestVolumeTarget(1);
        ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE);
        writer.beginFile("content.bin");
        ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setMethod(ZipMethod.stored());
        OutputStream output = writer.openOutputStream();

        assertThrows(IOException.class, () -> output.write(content));
        assertThrows(IOException.class, writer::close);

        TestVolumeOutput volumeOutput = target.output();
        assertEquals(0, volumeOutput.commitCount());
        assertEquals(1, volumeOutput.rollbackCount());
        assertEquals(1, volumeOutput.closeCount());
    }

    /// Verifies that split sizes outside the PKWARE bounds are rejected before opening a target.
    @Test
    public void rejectsSplitSizesOutsideSpecificationBounds() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoStreamingWriter.open(
                        new TestVolumeTarget(),
                        ZipArkivoFileSystem.MINIMUM_SPLIT_SIZE - 1L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoStreamingWriter.open(
                        new TestVolumeTarget(),
                        ZipArkivoFileSystem.MAXIMUM_SPLIT_SIZE + 1L
                )
        );
    }

    /// Verifies local and central directory header records start on disks where they fit completely.
    @Test
    public void splitWriterKeepsHeaderRecordsWithinVolumes() throws IOException {
        String firstName = "first.bin";
        String secondName = "second.bin";
        int firstHeaderSize = 30 + firstName.getBytes(StandardCharsets.UTF_8).length;
        int secondHeaderSize = 30 + secondName.getBytes(StandardCharsets.UTF_8).length;
        byte[] firstContent = splitTestContent(TEST_SPLIT_SIZE - Integer.BYTES - firstHeaderSize - 10);
        byte[] secondContent = splitTestContent(TEST_SPLIT_SIZE - secondHeaderSize - 20);
        TestVolumeTarget target = new TestVolumeTarget();

        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(target, TEST_SPLIT_SIZE)) {
            writeCompleteStoredEntry(writer, firstName, firstContent);
            writeCompleteStoredEntry(writer, secondName, secondContent);
        }

        TestVolumeOutput output = target.output();
        assertEquals(3, output.volumeCount());
        assertEquals(TEST_SPLIT_SIZE - 10, output.volumeBytes(0).length);
        assertEquals(TEST_SPLIT_SIZE - 20, output.volumeBytes(1).length);
        ByteBuffer firstVolume = ByteBuffer.wrap(output.volumeBytes(0)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer secondVolume = ByteBuffer.wrap(output.volumeBytes(1)).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer finalVolume = ByteBuffer.wrap(output.volumeBytes(2)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x08074b50, firstVolume.getInt(0));
        assertEquals(0x04034b50, firstVolume.getInt(Integer.BYTES));
        assertEquals(0x04034b50, secondVolume.getInt(0));
        assertEquals(0x02014b50, finalVolume.getInt(0));
    }

    /// Verifies that replacement split output removes numbered volumes from the previous archive.
    @Test
    public void splitOutputReplacementRemovesStaleVolumes() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-replace-");
        byte[] originalContent = splitTestContent(TEST_SPLIT_SIZE * 3);

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), (long) TEST_SPLIT_SIZE))
            )) {
                writer.beginFile("original.bin");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(originalContent);
                }
            }
            assertEquals(true, splitVolumePaths(archivePath).size() > 2);

            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(
                    archivePath,
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.SPLIT_SIZE.key(), (long) TEST_SPLIT_SIZE))
            )) {
                writer.beginFile("replacement.txt");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write("replacement".getBytes(StandardCharsets.UTF_8));
                }
            }

            assertEquals(List.of(archivePath), splitVolumePaths(archivePath));
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals(
                        "replacement",
                        Files.readString(fileSystem.getPath("/replacement.txt"), StandardCharsets.UTF_8)
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that create-new split output rejects any existing volume before staging starts.
    @Test
    public void splitOutputCreateNewRejectsExistingVolumeAtOpen() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-create-new-failure-");
        Path existingVolumePath = splitVolumePath(archivePath, 0);
        byte[] existingContent = "existing volume".getBytes(StandardCharsets.UTF_8);

        try {
            Files.write(existingVolumePath, existingContent);
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> ZipArkivoStreamingWriter.create(
                            archivePath,
                            ArchiveOptions.fromEnvironment(Map.of(
                                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                    ZipArkivoFileSystem.SPLIT_SIZE.key(),
                                    (long) TEST_SPLIT_SIZE
                            ))
                    )
            );
            assertArrayEquals(existingContent, Files.readAllBytes(existingVolumePath));
            assertEquals(false, Files.exists(archivePath));
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(archivePath.getParent())) {
                for (Path path : entries) {
                    assertEquals(false, path.getFileName().toString().startsWith(".arkivo-volumes-"));
                }
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
                    ArchiveOptions.fromEnvironment(Map.of(
                            ArkivoFileSystem.OPEN_OPTIONS.key(),
                            Set.of(
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE
                            )
                    ))
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
                                 ArchiveOptions.fromEnvironment(Map.of(
                                         ArkivoFileSystem.OPEN_OPTIONS.key(),
                                         new StandardOpenOption[]{
                                                 StandardOpenOption.CREATE_NEW,
                                                 StandardOpenOption.WRITE
                                         }
                                 ))
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
                            ArchiveOptions.fromEnvironment(Map.of(
                                    ArkivoFileSystem.OPEN_OPTIONS.key(),
                                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                            ))
                    )
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that read open options allow provider-specific non-write options.
    @Test
    public void fileSystemReadOpenOptionsAcceptProviderOptions() {
        ZipArkivoFileSystemConfig config = ZipArkivoFileSystemConfig.fromOptions(ArchiveOptions.fromEnvironment(Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.<OpenOption>of(StandardOpenOption.READ, TestOpenOption.DIRECT)
        )));

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

    /// Verifies that streaming ZIP readers decode known-size BZIP2-compressed entries.
    @Test
    public void streamingReaderReadsBzip2Entry() throws IOException {
        byte[] name = "bzip2-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "bzip2 streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = bzip2(content);
        byte[] archive = streamingBzip2ArchiveWithContent(
                name,
                compressed,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("bzip2-streaming.txt", attributes.path());
            assertEquals(ZipMethod.bzip2(), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that streaming ZIP readers decode known-size Zstandard-compressed entries.
    @Test
    public void streamingReaderReadsZstandardEntry() throws IOException {
        byte[] name = "zstandard-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "zstandard streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = zstandard(content);
        byte[] archive = streamingZstandardArchiveWithContent(
                name,
                compressed,
                ZipMethod.ZSTANDARD_ID,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("zstandard-streaming.txt", attributes.path());
            assertEquals(ZipMethod.zstandard(), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that streaming ZIP readers decode known-size XZ-compressed entries.
    @Test
    public void streamingReaderReadsXzEntry() throws IOException {
        byte[] name = "xz-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "xz streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = xz(content);
        byte[] archive = streamingXzArchiveWithContent(
                name,
                compressed,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("xz-streaming.txt", attributes.path());
            assertEquals(ZipMethod.xz(), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that streaming ZIP readers decode known-size LZMA-compressed entries.
    @Test
    public void streamingReaderReadsLzmaEntry() throws IOException {
        byte[] name = "lzma-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "lzma streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = lzma(content);
        byte[] archive = streamingLzmaArchiveWithContent(
                name,
                compressed,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("lzma-streaming.txt", attributes.path());
            assertEquals(ZipMethod.lzma(), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            assertEquals(LZMA_VERSION_NEEDED, attributes.versionNeededToExtract());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that streaming ZIP readers decode deprecated method 20 Zstandard entries.
    @Test
    public void streamingReaderReadsDeprecatedZstandardEntry() throws IOException {
        byte[] name = "deprecated-zstandard-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "deprecated zstandard streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = zstandard(content);
        byte[] archive = streamingZstandardArchiveWithContent(
                name,
                compressed,
                ZipMethod.DEPRECATED_ZSTANDARD_ID,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deprecated-zstandard-streaming.txt", attributes.path());
            assertEquals(ZipMethod.of(ZipMethod.DEPRECATED_ZSTANDARD_ID), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that streaming ZIP readers decode known-size Deflate64-compressed entries.
    @Test
    public void streamingReaderReadsDeflate64Entry() throws IOException {
        byte[] name = "deflate64-streaming.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "deflate64 streaming content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate64StoredBlock(content);
        byte[] archive = streamingDeflate64ArchiveWithContent(
                name,
                compressed,
                crc32(content),
                compressed.length,
                content.length
        );

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("deflate64-streaming.txt", attributes.path());
            assertEquals(ZipMethod.deflate64(), attributes.method());
            assertEquals(compressed.length, attributes.compressedSize());
            assertEquals(content.length, attributes.size());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
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
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals("deflate64-descriptor.txt", attributes.path());
                assertEquals(ZipMethod.deflate64(), attributes.method());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.compressedSize());
                assertEquals(ZipArkivoEntryAttributes.UNKNOWN_SIZE, attributes.size());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(firstContent, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                assertEquals("after.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(secondContent, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertEquals(Byte.toUnsignedInt(firstContent[0]), input.read());
            }
            assertEquals(true, reader.next());
            try (InputStream input = reader.openInputStream()) {
                assertArrayEquals(secondContent, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies encrypted Deflate64 descriptor entries preserve authentication and following-entry boundaries.
    @Test
    public void streamingReaderReadsEncryptedDeflate64DataDescriptorEntries() throws IOException {
        byte[] password = "deflate64 secret".getBytes(StandardCharsets.UTF_8);
        byte[] content = "encrypted Deflate64 descriptor content".repeat(128).getBytes(StandardCharsets.UTF_8);
        byte[] after = "after encrypted Deflate64".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );

        for (ZipEncryption encryption : new ZipEncryption[]{
                ZipEncryption.traditional(),
                ZipEncryption.winZipAes256()
        }) {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, ArchiveOptions.fromEnvironment(environment))) {
                writer.beginFile("secret.txt");
                ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.deflate64());
                view.setEncryption(encryption);
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content);
                }

                writer.beginFile("after.txt");
                view = writer.attributeView(ZipArkivoEntryAttributeView.class);
                assertNotNull(view);
                view.setMethod(ZipMethod.stored());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(after);
                }
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive.toByteArray()),
                    ArchiveOptions.fromEnvironment(environment)
            )) {
                assertEquals(true, reader.next());
                ZipArkivoEntryAttributes attributes = reader.readAttributes(ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.deflate64(), attributes.method());
                assertEquals(encryption, attributes.encryption());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(content, input.readAllBytes());
                }

                assertEquals(true, reader.next());
                try (InputStream input = reader.openInputStream()) {
                    assertArrayEquals(after, input.readAllBytes());
                }
                assertEquals(false, reader.next());
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
            assertEquals(true, reader.next());
            IOException exception = assertThrows(IOException.class, () -> {
                try (InputStream input = reader.openInputStream()) {
                    input.readAllBytes();
                }
            });
            assertEquals(true, exception.getMessage().contains("data descriptor"));
        }

        byte[] truncated = Arrays.copyOf(archive, descriptorOffset + Integer.BYTES + Integer.BYTES);
        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(truncated))) {
            assertEquals(true, reader.next());
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

    /// Verifies that advancing drains a directory entry and its declared data descriptor.
    @Test
    public void streamingReaderDrainsDirectoryDataDescriptor() throws IOException {
        byte[] content = "after directory descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingDirectoryDataDescriptorWithStoredEntry(content);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes directory = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("directory/", directory.path());
            assertEquals(true, directory.isDirectory());

            assertEquals(true, reader.next());
            ZipArkivoEntryAttributes file = reader.readAttributes(ZipArkivoEntryAttributes.class);
            assertEquals("after.txt", file.path());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that a matching signed descriptor is accepted when the local header omitted its descriptor flag.
    @Test
    public void streamingReaderAcceptsMatchingUndeclaredDataDescriptor() throws IOException {
        byte[] content = "undeclared descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithUndeclaredDataDescriptor(content, crc32(content));

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(content, input.readAllBytes());
            }
            assertEquals(false, reader.next());
        }
    }

    /// Verifies that an undeclared signed descriptor cannot override validated local-header metadata.
    @Test
    public void streamingReaderRejectsMismatchedUndeclaredDataDescriptor() throws IOException {
        byte[] content = "bad undeclared descriptor".getBytes(StandardCharsets.UTF_8);
        byte[] archive = streamingStoredArchiveWithUndeclaredDataDescriptor(content, crc32(content) ^ 1L);

        try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertEquals(true, reader.next());
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
            CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(tampered);
            ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
            try {
                assertEquals(true, reader.next());
                var input = reader.openInputStream();

                IOException exception = assertThrows(IOException.class, reader::close);
                assertEquals(true, exception.getMessage().contains("data descriptor does not match"));
                assertEquals(1, exception.getSuppressed().length);
                assertEquals("close failed", exception.getSuppressed()[0].getMessage());
                assertThrows(IOException.class, input::read);
                reader.close();
                reader.close();
                assertEquals(2, source.closeCount());
                assertEquals(true, source.closed());
            } finally {
                if (!source.closed()) {
                    reader.close();
                }
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password),
                ZipArkivoFileSystem.DEFAULT_ENCRYPTION.key(),
                ZipEncryption.traditional()
        );

        try {
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath, ArchiveOptions.fromEnvironment(environment))) {
                writer.beginFile("deflated.txt");
                try (var output = writer.openOutputStream()) {
                    output.write(content);
                }
            }

            byte[] archive = Files.readAllBytes(archivePath);
            byte[] tampered = tamperLastDataDescriptorCrc(archive);
            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(tampered),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
            )) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/aes.txt"))
                );
                assertEquals(true, exception.getMessage().contains("WinZip AES authentication failed"));
            }

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive),
                    ArchiveOptions.fromEnvironment(Map.of(ZipArkivoFileSystem.PASSWORD_PROVIDER.key(), ArkivoPasswordProvider.fixed(password)))
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
            Path normalized = fileSystem.getPath("/a/c.txt");

            assertEquals("/a/b/../c.txt", path.toString());
            assertEquals(fileSystem, path.getFileSystem());
            assertEquals(true, path.isAbsolute());
            assertEquals("/", path.getRoot().toString());
            assertEquals("c.txt", path.getFileName().toString());
            assertEquals("/a/b/..", path.getParent().toString());
            assertEquals(4, path.getNameCount());
            assertEquals("b", path.getName(1).toString());
            assertEquals("b/..", path.subpath(1, 3).toString());
            assertEquals(true, path.startsWith("/a"));
            assertEquals(true, path.endsWith("c.txt"));
            assertEquals(normalized, path.normalize());
            assertEquals("/a/child", fileSystem.getPath("/a").resolve("child").toString());
            assertEquals("/a/child", normalized.resolveSibling("child").toString());
            assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
            assertEquals("/relative", fileSystem.getPath("relative").toAbsolutePath().toString());
            assertEquals(0, normalized.compareTo(fileSystem.getPath("/a/c.txt")));
            assertEquals(List.of("a", "b", "..", "c.txt"),
                    StreamSupport.stream(path.spliterator(), false).map(Path::toString).toList());
            assertEquals(false, path.startsWith(Path.of("/a")));
            assertThrows(IllegalArgumentException.class, () -> path.resolve(Path.of("other")));
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

    /// Verifies that a repeatable seekable channel source supports random-access ZIP file system operations.
    @Test
    public void randomAccessFileSystemFromSeekableChannelSource() throws IOException {
        byte[] preamble = new byte[]{7, 6, 5, 4};
        TestSeekableChannelSource source = new TestSeekableChannelSource(
                singleEntryZipWithPreambleAndAdjustedOffsets(preamble)
        );

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source)) {
            assertEquals(preamble.length, fileSystem.preambleSize());
            assertPreambleContent(preamble, fileSystem);
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/a")));
            assertEquals(true, source.openCount() > 1);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that failed ZIP parsing closes channels opened from a seekable channel source.
    @Test
    public void failedSeekableChannelSourceReadClosesOpenedChannels() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(Map.of()))) {
            assertThrows(IOException.class, fileSystem::preambleSize);
            assertEquals(1, source.openCount());
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(1, source.closeCount());
    }

    /// Verifies channel and source cleanup when a channel-source update cannot parse its ZIP index.
    @Test
    public void failedSeekableChannelSourceUpdateOpenClosesOwnership() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(new byte[0]);
        Path targetPath = createTemporaryArchivePath("failed-channel-update-");
        Files.deleteIfExists(targetPath);
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.COMMIT_TARGET.key(),
                ArkivoCommitTarget.writeTo(targetPath)
        );

        try {
            assertThrows(IOException.class, () -> ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment)));
            assertEquals(true, source.openCount() > 0);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(1, source.closeCount());
            assertEquals(false, Files.exists(targetPath));
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies that channel-source update mode requires a commit target before opening source channels.
    @Test
    public void seekableChannelSourceUpdateRequiresCommitTarget() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(emptyZipWithPreamble(new byte[0]));
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment))
        );
        assertEquals(0, source.openCount());
        assertEquals(1, source.closeCount());
    }

    /// Verifies complete-rewrite mutation and preamble preservation from a repeatable single-volume source.
    @Test
    public void updatesSeekableChannelSourceIntoDerivedArchive() throws IOException {
        byte[] preamble = new byte[]{9, 7, 5, 3};
        byte[] original = updateSourceZip(preamble);
        TestSeekableChannelSource source = new TestSeekableChannelSource(original);
        Path targetPath = createTemporaryArchivePath("channel-update-derived-");
        Files.deleteIfExists(targetPath);
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.COMMIT_TARGET.key(),
                ArkivoCommitTarget.writeTo(targetPath)
        );

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment))) {
                assertEquals(false, fileSystem.isReadOnly());
                assertEquals(preamble.length, fileSystem.preambleSize());
                assertPreambleContent(preamble, fileSystem);
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertEquals("replace", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                try (SeekableByteChannel entry = Files.newByteChannel(
                        fileSystem.getPath("/replace.txt"),
                        Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                )) {
                    ByteBuffer prefix = ByteBuffer.allocate(3);
                    assertEquals(3, entry.read(prefix));
                    assertArrayEquals("rep".getBytes(StandardCharsets.UTF_8), prefix.array());
                    entry.position(0L);
                    entry.write(ByteBuffer.wrap("new".getBytes(StandardCharsets.UTF_8)));
                    entry.truncate(3L);
                }
                assertEquals("new", Files.readString(fileSystem.getPath("/replace.txt"), StandardCharsets.UTF_8));
                Files.delete(fileSystem.getPath("/remove.txt"));
                assertThrows(NoSuchFileException.class, () -> Files.readAllBytes(fileSystem.getPath("/remove.txt")));
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
                assertEquals("added", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
            }

            assertEquals(true, source.openCount() > 1);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(1, source.closeCount());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals(preamble.length, derived.preambleSize());
                assertPreambleContent(preamble, derived);
                assertEquals("keep", Files.readString(derived.getPath("/keep.txt"), StandardCharsets.UTF_8));
                assertEquals("new", Files.readString(derived.getPath("/replace.txt"), StandardCharsets.UTF_8));
                assertEquals(false, Files.exists(derived.getPath("/remove.txt")));
                assertEquals("added", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies complete-rewrite updates from one owned seekable channel.
    @Test
    public void updatesOwnedSeekableChannelIntoDerivedArchive() throws IOException {
        TestByteArraySeekableChannel channel =
                new TestByteArraySeekableChannel(updateSourceZip(new byte[0]));
        Path targetPath = createTemporaryArchivePath("owned-channel-update-derived-");
        Files.deleteIfExists(targetPath);
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.COMMIT_TARGET.key(),
                ArkivoCommitTarget.writeTo(targetPath)
        );

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(channel, ArchiveOptions.fromEnvironment(environment))) {
                assertEquals("keep", Files.readString(fileSystem.getPath("/keep.txt"), StandardCharsets.UTF_8));
                Files.delete(fileSystem.getPath("/remove.txt"));
                Files.writeString(fileSystem.getPath("/added.txt"), "owned", StandardCharsets.UTF_8);
                assertEquals("owned", Files.readString(fileSystem.getPath("/added.txt"), StandardCharsets.UTF_8));
            }

            assertEquals(false, channel.isOpen());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals(false, Files.exists(derived.getPath("/remove.txt")));
                assertEquals("owned", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            channel.close();
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
        }
    }

    /// Verifies source cleanup when channel-source commit setup fails.
    @Test
    public void failedSeekableChannelSourceCommitClosesSource() throws IOException {
        byte[] original = updateSourceZip(new byte[0]);
        TestSeekableChannelSource source = new TestSeekableChannelSource(original);
        ArkivoCommitTarget failingTarget = (@Nullable Path sourcePath) -> {
            assertNull(sourcePath);
            throw new IOException("channel commit target failed");
        };
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.COMMIT_TARGET.key(),
                failingTarget
        );

        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment));
        Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("channel commit target failed", exception.getMessage());
        assertEquals(false, fileSystem.isOpen());
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that a failed source close can be retried after a successful derived commit.
    @Test
    public void seekableChannelSourceCloseCanRetryAfterUpdate() throws IOException {
        TestSeekableChannelSource source =
                new TestSeekableChannelSource(updateSourceZip(new byte[0]), true);
        Path targetPath = createTemporaryArchivePath("channel-update-close-retry-");
        Files.deleteIfExists(targetPath);
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ArkivoFileSystem.COMMIT_TARGET.key(),
                ArkivoCommitTarget.writeTo(targetPath)
        );

        try {
            ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(source, ArchiveOptions.fromEnvironment(environment));
            Files.writeString(fileSystem.getPath("/added.txt"), "retry", StandardCharsets.UTF_8);

            IOException exception = assertThrows(IOException.class, fileSystem::close);
            assertEquals("source close failed", exception.getMessage());
            assertEquals(false, fileSystem.isOpen());
            assertEquals(1, source.closeCount());

            fileSystem.close();
            fileSystem.close();
            assertEquals(2, source.closeCount());
            try (ZipArkivoFileSystem derived = ZipArkivoFileSystem.open(targetPath)) {
                assertEquals("retry", Files.readString(derived.getPath("/added.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            Files.deleteIfExists(targetPath);
            Files.deleteIfExists(targetPath.getParent());
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

    /// Verifies that the local data-descriptor bit may differ from the central directory.
    @Test
    public void acceptsMismatchedLocalDataDescriptorFlag() throws IOException {
        byte[] name = "descriptor-flags.txt".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithRawNameAndLocalCentralMetadata(
                name,
                1 << 3,
                0,
                ZipMethod.STORED_ID,
                ZipMethod.STORED_ID
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                ZipArkivoEntryAttributes attributes = Files.readAttributes(
                        fileSystem.getPath("/descriptor-flags.txt"),
                        ZipArkivoEntryAttributes.class
                );
                assertEquals(0, attributes.generalPurposeFlags());
                assertEquals(0L, attributes.size());
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

    /// Verifies that decoded seekable channels stage sizes beyond array limits and still validate actual content.
    @Test
    public void validatesOversizedDecodedSeekableEntryWithoutArrayLimit() throws IOException {
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
                assertEquals("Unexpected end of raw deflate stream", exception.getMessage());
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

    /// Verifies that seekable ZIP file systems decode BZIP2-compressed entries.
    @Test
    public void readsSeekableBzip2Entry() throws IOException {
        byte[] name = "bzip2.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "bzip2 seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = bzip2(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.BZIP2_ID,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/bzip2.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.bzip2(), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    buffer.flip();
                    byte[] decoded = new byte[buffer.remaining()];
                    buffer.get(decoded);
                    assertArrayEquals(content, decoded);
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP file systems decode Zstandard-compressed entries.
    @Test
    public void readsSeekableZstandardEntry() throws IOException {
        byte[] name = "zstandard.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "zstandard seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = zstandard(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.ZSTANDARD_ID,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/zstandard.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.zstandard(), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    buffer.flip();
                    byte[] decoded = new byte[buffer.remaining()];
                    buffer.get(decoded);
                    assertArrayEquals(content, decoded);
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP file systems decode XZ-compressed entries.
    @Test
    public void readsSeekableXzEntry() throws IOException {
        byte[] name = "xz.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "xz seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = xz(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.XZ_ID,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/xz.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.xz(), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    buffer.flip();
                    byte[] decoded = new byte[buffer.remaining()];
                    buffer.get(decoded);
                    assertArrayEquals(content, decoded);
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP file systems decode LZMA-compressed entries.
    @Test
    public void readsSeekableLzmaEntry() throws IOException {
        byte[] name = "lzma.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "lzma seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = lzma(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.LZMA_ID,
                LZMA_EOS_MARKER_FLAG,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/lzma.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.lzma(), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertEquals(LZMA_VERSION_NEEDED, attributes.versionNeededToExtract());
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    buffer.flip();
                    byte[] decoded = new byte[buffer.remaining()];
                    buffer.get(decoded);
                    assertArrayEquals(content, decoded);
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP file systems decode deprecated method 20 Zstandard entries.
    @Test
    public void readsSeekableDeprecatedZstandardEntry() throws IOException {
        byte[] name = "deprecated-zstandard.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "deprecated zstandard seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = zstandard(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.DEPRECATED_ZSTANDARD_ID,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/deprecated-zstandard.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.of(ZipMethod.DEPRECATED_ZSTANDARD_ID), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that seekable ZIP file systems decode Deflate64-compressed entries.
    @Test
    public void readsSeekableDeflate64Entry() throws IOException {
        byte[] name = "deflate64.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "deflate64 seekable content".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = deflate64StoredBlock(content);
        Path archivePath = createTemporaryArchiveContent(singleEntryZipWithEntryBody(
                name,
                compressed,
                ZipMethod.DEFLATE64_ID,
                crc32(content),
                compressed.length,
                content.length
        ));

        try {
            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/deflate64.txt");
                ZipArkivoEntryAttributes attributes = Files.readAttributes(file, ZipArkivoEntryAttributes.class);
                assertEquals(ZipMethod.deflate64(), attributes.method());
                assertEquals(compressed.length, attributes.compressedSize());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));

                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    buffer.flip();
                    byte[] decoded = new byte[buffer.remaining()];
                    buffer.get(decoded);
                    assertArrayEquals(content, decoded);
                }
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

    /// Verifies that conventional ZIP split volumes can be discovered from the final archive path.
    @Test
    public void readSplitZipFromArchivePath() throws IOException {
        Path archivePath = createTemporaryArchivePath("split-zip-path-");
        Path firstVolume = splitVolumePath(archivePath, 0);
        byte[][] volumes = splitZipArchive();
        Files.write(firstVolume, volumes[0]);
        Files.write(archivePath, volumes[1]);

        try {
            List<Path> discoveredPaths = Objects.requireNonNull(
                    ZipArkivoFormat.instance().discoverVolumePaths(archivePath)
            );
            assertEquals(List.of(firstVolume, archivePath), discoveredPaths);
            assertThrows(UnsupportedOperationException.class, discoveredPaths::clear);

            try (ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(archivePath)) {
                assertEquals(true, reader.next());
                assertEquals("hello.txt", reader.readAttributes(ZipArkivoEntryAttributes.class).path());
                try (InputStream input = reader.openInputStream()) {
                    assertEquals("split", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                assertEquals(false, reader.next());
            }

            try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archivePath)) {
                assertEquals("split", Files.readString(fileSystem.getPath("/hello.txt"), StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that companion numbered files do not make a single-volume ZIP path open as split storage.
    @Test
    public void staleSplitCompanionDoesNotOverrideSingleArchivePath() throws IOException {
        Path archivePath = createTemporaryArchivePath("single-zip-with-stale-split-");
        Path staleVolume = splitVolumePath(archivePath, 0);

        try {
            Files.write(staleVolume, "stale".getBytes(StandardCharsets.UTF_8));
            try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginFile("single.txt");
                try (OutputStream output = writer.openOutputStream()) {
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
    public void writableUriCloseUnregistersFileSystem() throws IOException {
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
            assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));

            fileSystem.close();

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
        SeekableByteChannel channel = ArkivoVolumeChannel.open(
                index -> index == 0L ? first : index == 1L ? second : null
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

    /// Writes one stored JDK ZIP entry with optional extra data and comment metadata.
    private static void writeStoredZipEntry(
            ZipOutputStream output,
            String name,
            byte[] content,
            byte @Nullable [] extraData,
            @Nullable String comment
    ) throws IOException {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc32.getValue());
        if (extraData != null) {
            entry.setExtra(extraData);
        }
        if (comment != null) {
            entry.setComment(comment);
        }
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    /// Returns a complete single-entry stored ZIP archive.
    private static byte[] singleStoredZipArchive(String name, byte[] content) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            writeStoredZipEntry(output, name, content, null, null);
        }
        return archive.toByteArray();
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

    /// Returns whether a byte array contains the given exact byte sequence.
    private static boolean containsBytes(byte[] bytes, byte[] expected) {
        if (expected.length == 0) {
            return true;
        }
        int lastStart = bytes.length - expected.length;
        for (int start = 0; start <= lastStart; start++) {
            int index = 0;
            while (index < expected.length && bytes[start + index] == expected[index]) {
                index++;
            }
            if (index == expected.length) {
                return true;
            }
        }
        return false;
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
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl$BoundedSeekableByteChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class, long.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel, size);
    }

    /// Creates a single-volume archive channel around the given delegate channel.
    private static SeekableByteChannel newSingleArchiveChannel(SeekableByteChannel channel)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl$SingleArchiveChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel);
    }

    /// Creates a validating seekable ZIP entry stream through its private constructor.
    private static InputStream newValidatingEntryInputStream(
            InputStream input,
            long expectedCrc32,
            long expectedUncompressedSize
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl$ValidatingEntryInputStream"
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
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoFileSystemImpl$ValidatingStoredEntryByteChannel"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(SeekableByteChannel.class, long.class, long.class);
        constructor.setAccessible(true);
        return (SeekableByteChannel) constructor.newInstance(channel, expectedCrc32, expectedUncompressedSize);
    }

    /// Verifies common streaming ZIP file store attribute view declarations.
    private static void assertStreamingZipFileStoreAttributeViews(FileStore fileStore, boolean readOnly) {
        assertEquals("zip-stream", fileStore.name());
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

    /// Replaces the streaming ZIP file system close action for close failure tests.
    private static void setStreamingZipCloseAction(
            StreamingZipArkivoFileSystemImpl fileSystem,
            Runnable closeAction
    ) throws ReflectiveOperationException {
        Field field = StreamingZipArkivoFileSystemImpl.class.getDeclaredField("closeAction");
        field.setAccessible(true);
        field.set(fileSystem, closeAction);
    }

    /// Creates streaming ZIP entry metadata through its private constructor.
    private static Object zipEntryMetadata() throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoFileSystemImpl$EntryMetadata"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                int.class,
                ZipEncryption.class,
                FileTime.class,
                int.class,
                int.class,
                long.class,
                long.class,
                long.class,
                byte[].class,
                byte[].class,
                byte[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                ZipMethod.STORED_ID,
                ZipEncryption.none(),
                null,
                20,
                0,
                0L,
                0L,
                0L,
                new byte[0],
                new byte[0],
                null
        );
    }

    /// Creates a streaming ZIP central directory entry through its private constructor.
    private static Object zipCentralEntry(
            String name,
            byte[] rawName,
            long compressedSize,
            long uncompressedSize,
            long localHeaderOffset,
            Object metadata
    ) throws ReflectiveOperationException {
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.StreamingZipArkivoFileSystemImpl$CentralEntry"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(
                String.class,
                byte[].class,
                int.class,
                int.class,
                int.class,
                int.class,
                long.class,
                long.class,
                 long.class,
                 int.class,
                 long.class,
                 long.class,
                 long.class,
                 metadata.getClass()
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                name,
                rawName,
                1 << 11,
                ZipMethod.STORED_ID,
                0,
                0,
                0L,
                compressedSize,
                uncompressedSize,
                 0,
                 localHeaderOffset,
                 0L,
                 0L,
                 metadata
        );
    }

    /// Returns a ZIP update fixture with a preamble and three regular entries.
    private static byte[] updateSourceZip(byte[] preamble) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(Objects.requireNonNull(preamble, "preamble"));
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            for (String name : List.of("keep.txt", "replace.txt", "remove.txt")) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.substring(0, name.indexOf('.')).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
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

    /// Returns the ZIP version needed to extract field for the method.
    private static int zipVersionNeeded(int method) {
        return method == ZipMethod.LZMA_ID ? LZMA_VERSION_NEEDED : 20;
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

    /// Returns the ZIP data descriptor signature bytes.
    private static byte[] dataDescriptorSignature() {
        return new byte[]{0x50, 0x4b, 0x07, 0x08};
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
        writeLocalHeader(buffer, directoryName, 1 << 3 | 1 << 11, ZipMethod.STORED_ID, 0, 0, 0);
        buffer.putInt(0x08074b50);
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.putInt(0);
        writeLocalHeader(
                buffer,
                fileName,
                1 << 11,
                ZipMethod.STORED_ID,
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
                ZipMethod.STORED_ID,
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
                ZipMethod.STORED_ID,
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

    /// Returns a minimal streaming BZIP2 ZIP archive with compressed data and local header metadata.
    private static byte[] streamingBzip2ArchiveWithContent(
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
        buffer.putShort((short) ZipMethod.BZIP2_ID);
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

    /// Returns a minimal streaming Zstandard ZIP archive with compressed data and local header metadata.
    private static byte[] streamingZstandardArchiveWithContent(
            byte[] name,
            byte[] compressed,
            int method,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) method);
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

    /// Returns a minimal streaming XZ ZIP archive with compressed data and local header metadata.
    private static byte[] streamingXzArchiveWithContent(
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
        buffer.putShort((short) ZipMethod.XZ_ID);
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

    /// Returns a minimal streaming LZMA ZIP archive with compressed data and local header metadata.
    private static byte[] streamingLzmaArchiveWithContent(
            byte[] name,
            byte[] compressed,
            long crc32,
            long compressedSize,
            long uncompressedSize
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + compressed.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) LZMA_VERSION_NEEDED);
        buffer.putShort((short) LZMA_EOS_MARKER_FLAG);
        buffer.putShort((short) ZipMethod.LZMA_ID);
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, ArchiveOptions.fromEnvironment(environment))) {
            writer.beginFile("deflated.txt");
            ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.deflated());
            view.setEncryption(encryption);
            try (OutputStream output = writer.openOutputStream()) {
                output.write(content);
            }

            writer.beginFile("after.txt");
            view = writer.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.stored());
            try (OutputStream output = writer.openOutputStream()) {
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
        Map<String, Object> environment = Map.of(
                ZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive, ArchiveOptions.fromEnvironment(environment))) {
            writer.beginFile("lzma.txt");
            ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.lzma());
            view.setEncryption(encryption);
            try (OutputStream output = writer.openOutputStream()) {
                output.write(content);
            }

            writer.beginFile("after.txt");
            view = writer.attributeView(ZipArkivoEntryAttributeView.class);
            assertNotNull(view);
            view.setMethod(ZipMethod.stored());
            try (OutputStream output = writer.openOutputStream()) {
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
        buffer.putShort((short) ZipMethod.DEFLATE64_ID);
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
        buffer.putShort((short) ZipMethod.STORED_ID);
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

    /// Returns a minimal streaming Deflate64 ZIP archive with compressed data and local header metadata.
    private static byte[] streamingDeflate64ArchiveWithContent(
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
        buffer.putShort((short) ZipMethod.DEFLATE64_ID);
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

    /// Returns BZIP2-compressed bytes for a ZIP BZIP2 entry.
    private static byte[] bzip2(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(output)) {
            bzip2.write(content);
        }
        return output.toByteArray();
    }

    /// Returns Zstandard-compressed bytes for a ZIP Zstandard entry.
    private static byte[] zstandard(byte[] content) throws IOException {
        ByteBuffer compressed = new ZstdCodec().compress(ByteBuffer.wrap(content));
        byte[] result = new byte[compressed.remaining()];
        compressed.get(result);
        return result;
    }

    /// Returns XZ-compressed bytes for a ZIP XZ entry.
    private static byte[] xz(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XZCompressorOutputStream xz = new XZCompressorOutputStream(output)) {
            xz.write(content);
        }
        return output.toByteArray();
    }

    /// Returns ZIP LZMA segment bytes for a ZIP LZMA entry.
    private static byte[] lzma(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LZMA2Options options = new LZMA2Options();
        try (LZMAOutputStream lzma = new LZMAOutputStream(output, options, true)) {
            writeLzmaPropertyHeader(output, lzma.getProps(), options.getDictSize());
            lzma.write(content);
        }
        return output.toByteArray();
    }

    /// Writes a ZIP LZMA property header.
    private static void writeLzmaPropertyHeader(
            OutputStream output,
            int properties,
            int dictionarySize
    ) throws IOException {
        output.write(LZMA_SDK_MAJOR_VERSION);
        output.write(LZMA_SDK_MINOR_VERSION);
        output.write(LZMA_PROPERTY_SIZE);
        output.write(0);
        output.write(properties & 0xff);
        output.write(dictionarySize & 0xff);
        output.write((dictionarySize >>> 8) & 0xff);
        output.write((dictionarySize >>> 16) & 0xff);
        output.write((dictionarySize >>> 24) & 0xff);
    }

    /// Returns one raw Deflate64 stored-block payload.
    private static byte[] deflate64StoredBlock(byte[] content) {
        if (content.length > 0xffff) {
            throw new IllegalArgumentException("content is too large for a single stored block");
        }
        ByteBuffer buffer = ByteBuffer.allocate(5 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 0x01);
        buffer.putShort((short) content.length);
        buffer.putShort((short) ~content.length);
        buffer.put(content);
        return buffer.array();
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
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl$KnownSizeEntryInputStream"
        );
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class, long.class, long.class);
        constructor.setAccessible(true);
        return (InputStream) constructor.newInstance(input, expectedCrc32, expectedUncompressedSize);
    }

    /// Invokes the failed entry setup cleanup path through its private helper.
    private static void closeEntryAfterFailedSetup(InputStream input, Throwable failure)
            throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl"
        );
        Object owner = newZipStreamingReader(ownerType);
        Method method = ownerType.getDeclaredMethod("closeEntryAfterFailedSetup", InputStream.class, Throwable.class);
        method.setAccessible(true);
        method.invoke(owner, input, failure);
    }

    /// Creates a current streaming entry input stream through its private constructor.
    private static InputStream newCurrentEntryInputStream(InputStream input) throws ReflectiveOperationException {
        Class<?> ownerType = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl"
        );
        Object owner = newZipStreamingReader(ownerType);
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl$CurrentEntryInputStream"
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
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl"
        );
        Object owner = newZipStreamingReader(ownerType);
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl$StoredDataDescriptorInputStream"
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
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl"
        );
        Object owner = newZipStreamingReader(ownerType);
        Class<?> decryptorType = Class.forName("org.glavo.arkivo.archive.zip.internal.ZipTraditionalCrypto$Decryptor");
        Class<?> type = Class.forName(
                "org.glavo.arkivo.archive.zip.internal.ZipArkivoStreamingReaderImpl$EncryptedStoredDataDescriptorInputStream"
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

    /// Creates a ZIP streaming reader through its public constructor.
    private static Object newZipStreamingReader(Class<?> ownerType) throws ReflectiveOperationException {
        Constructor<?> ownerConstructor = ownerType.getConstructor(
                ReadableByteChannel.class,
                ZipArkivoFileSystemConfig.class
        );
        return ownerConstructor.newInstance(
                Channels.newChannel(InputStream.nullInputStream()),
                ZipArkivoFileSystemConfig.DEFAULTS
        );
    }

    /// Creates a WinZip AES decryptor through the internal factory.
    private static Object newWinZipAesDecryptor(byte[] password) throws IOException, ReflectiveOperationException {
        Class<?> aesType = Class.forName("org.glavo.arkivo.archive.zip.internal.ZipAesExtraField");
        Method forEncryption = aesType.getDeclaredMethod("forEncryption", ZipEncryption.class, int.class);
        forEncryption.setAccessible(true);
        Object aes = forEncryption.invoke(null, ZipEncryption.winZipAes256(), 8);

        Class<?> cryptoType = Class.forName("org.glavo.arkivo.archive.zip.internal.ZipAesCrypto");
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
        Class<?> cryptoType = Class.forName("org.glavo.arkivo.archive.zip.internal.ZipTraditionalCrypto");
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

    /// Writes one stored streaming entry with exact size and CRC-32 metadata.
    private static void writeCompleteStoredEntry(
            ZipArkivoStreamingWriter writer,
            String entryName,
            byte[] content
    ) throws IOException {
        writer.beginFile(entryName);
        ZipArkivoEntryAttributeView view = writer.attributeView(ZipArkivoEntryAttributeView.class);
        assertNotNull(view);
        view.setMethod(ZipMethod.stored());
        view.setUncompressedSizeAndCrc32(content.length, crc32(content));
        try (OutputStream output = writer.openOutputStream()) {
            output.write(content);
        }
    }

    /// Returns deterministic incompressible content for split ZIP tests.
    private static byte[] splitTestContent(int size) {
        byte[] content = new byte[size];
        new Random(0x41524b49564fL + size).nextBytes(content);
        return content;
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

    /// Returns the split volume paths that make up an archive written to the given final path.
    private static List<Path> splitVolumePaths(Path archivePath) {
        ArrayList<Path> volumes = new ArrayList<>();
        for (int diskNumber = 0; ; diskNumber++) {
            Path volumePath = splitVolumePath(archivePath, diskNumber);
            if (!Files.exists(volumePath)) {
                break;
            }
            volumes.add(volumePath);
        }
        volumes.add(archivePath);
        return List.copyOf(volumes);
    }

    /// Returns the path for a numbered split volume.
    private static Path splitVolumePath(Path archivePath, int diskNumber) {
        String volumeNumber = Integer.toString(diskNumber + 1);
        if (volumeNumber.length() == 1) {
            volumeNumber = "0" + volumeNumber;
        }
        String fileName = archivePath.getFileName().toString();
        String baseName = fileName.length() >= 4
                && fileName.regionMatches(true, fileName.length() - 4, ".zip", 0, 4)
                ? fileName.substring(0, fileName.length() - 4)
                : fileName;
        Path parent = archivePath.getParent();
        Path volumeFileName = Path.of(baseName + ".z" + volumeNumber);
        return parent != null ? parent.resolve(volumeFileName) : volumeFileName;
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
        for (int diskNumber = 0; ; diskNumber++) {
            if (!Files.deleteIfExists(splitVolumePath(archivePath, diskNumber))) {
                break;
            }
        }
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

    /// Repeatable single-archive source that records opened channel and source lifecycles.
    @NotNullByDefault
    private static final class TestSeekableChannelSource implements ArkivoSeekableChannelSource {
        /// The archive bytes exposed by each opened channel.
        private final byte @Unmodifiable [] content;

        /// The channels opened from this source.
        private final ArrayList<TestByteArraySeekableChannel> openedChannels = new ArrayList<>();

        /// Whether the first close attempt should fail.
        private final boolean failFirstClose;

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a repeatable source over the given archive bytes.
        private TestSeekableChannelSource(byte[] content) {
            this(content, false);
        }

        /// Creates a repeatable source with an optional first-close failure.
        private TestSeekableChannelSource(byte[] content, boolean failFirstClose) {
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failFirstClose = failFirstClose;
        }

        /// Opens an independent channel over the archive bytes.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount > 0) {
                throw new IOException("source is closed");
            }
            TestByteArraySeekableChannel channel = new TestByteArraySeekableChannel(content);
            openedChannels.add(channel);
            return channel;
        }

        /// Records that this source has been closed.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (failFirstClose && closeCount == 1) {
                throw new IOException("source close failed");
            }
        }

        /// Returns the number of channels opened from this source.
        private int openCount() {
            return openedChannels.size();
        }

        /// Returns whether every channel opened from this source has been closed.
        private boolean allOpenedChannelsClosed() {
            for (TestByteArraySeekableChannel channel : openedChannels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of times this source has been closed.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Read-only seekable channel over an immutable byte array.
    @NotNullByDefault
    private static final class TestByteArraySeekableChannel implements SeekableByteChannel {
        /// The immutable channel content.
        private final byte @Unmodifiable [] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Creates a read-only channel over the given content.
        private TestByteArraySeekableChannel(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            Objects.requireNonNull(destination, "destination");
            ensureOpen();
            if (!destination.hasRemaining()) {
                return 0;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(destination.remaining(), content.length - position);
            destination.put(content, position, count);
            position += count;
            return count;
        }

        /// Always rejects writes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            ensureOpen();
            Objects.requireNonNull(source, "source");
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
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("newPosition is out of range");
            }
            position = (int) newPosition;
            return this;
        }

        /// Returns the content size.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Always rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            ensureOpen();
            if (size < 0) {
                throw new IllegalArgumentException("size must not be negative");
            }
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

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Repeatable volume source that tracks ownership closure.
    @NotNullByDefault
    private static final class TrackingVolumeSource implements ArkivoVolumeSource {
        /// The source that opens the actual volume channels.
        private final ArkivoVolumeSource delegate;

        /// The number of close attempts.
        private int closeCount;

        /// Whether this source has been closed.
        private boolean closed;

        /// Creates a tracking source.
        private TrackingVolumeSource(ArkivoVolumeSource delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /// Opens one independently positioned volume channel.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) throws IOException {
            if (closed) {
                throw new IOException("volume source is closed");
            }
            return delegate.openVolume(index);
        }

        /// Closes the delegate and records source ownership release.
        @Override
        public void close() throws IOException {
            closeCount++;
            closed = true;
            delegate.close();
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Test target that creates one in-memory multi-volume output transaction.
    @NotNullByDefault
    private static final class TestVolumeTarget implements ArkivoVolumeTarget {
        /// The sentinel used when no volume open should fail.
        private static final int NO_FAILURE_VOLUME_INDEX = -1;

        /// The volume index whose open operation should fail.
        private final int failureVolumeIndex;

        /// The output transaction opened from this target, or `null` before use.
        private @Nullable TestVolumeOutput output;

        /// Creates a target whose volume opens succeed.
        private TestVolumeTarget() {
            this(NO_FAILURE_VOLUME_INDEX);
        }

        /// Creates a target that fails while opening the given volume index.
        private TestVolumeTarget(int failureVolumeIndex) {
            if (failureVolumeIndex < NO_FAILURE_VOLUME_INDEX) {
                throw new IllegalArgumentException("failureVolumeIndex is out of range");
            }
            this.failureVolumeIndex = failureVolumeIndex;
        }

        /// Opens the single output transaction supported by this test target.
        @Override
        public ArkivoVolumeOutput openOutput() throws IOException {
            if (output != null) {
                throw new IOException("volume target output is already open");
            }
            TestVolumeOutput openedOutput = new TestVolumeOutput(failureVolumeIndex);
            output = openedOutput;
            return openedOutput;
        }

        /// Returns the output transaction opened from this target.
        private TestVolumeOutput output() {
            return Objects.requireNonNull(output, "output");
        }
    }

    /// In-memory multi-volume output that records commit, rollback, and close operations.
    @NotNullByDefault
    private static final class TestVolumeOutput implements ArkivoVolumeOutput {
        /// The sentinel used before a final volume index has been committed.
        private static final long NO_FINAL_VOLUME_INDEX = -1L;

        /// The volume index whose open operation should fail.
        private final int failureVolumeIndex;

        /// The byte streams that receive individual volume content.
        private final ArrayList<ByteArrayOutputStream> volumes = new ArrayList<>();

        /// The final committed volume index, or `NO_FINAL_VOLUME_INDEX` before commit.
        private long finalVolumeIndex = NO_FINAL_VOLUME_INDEX;

        /// The number of commit calls.
        private int commitCount;

        /// The number of rollback calls.
        private int rollbackCount;

        /// The number of close calls.
        private int closeCount;

        /// Whether this output has been committed or rolled back.
        private boolean finished;

        /// Creates an in-memory volume output with the requested open failure index.
        private TestVolumeOutput(int failureVolumeIndex) {
            this.failureVolumeIndex = failureVolumeIndex;
        }

        /// Opens the next in-memory volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            ensureOpen();
            if (index < 0 || index > Integer.MAX_VALUE || index != volumes.size()) {
                throw new IllegalArgumentException("Volume indexes must be opened once in ascending order");
            }
            if (index == failureVolumeIndex) {
                throw new IOException("volume open failed");
            }
            ByteArrayOutputStream volume = new ByteArrayOutputStream();
            volumes.add(volume);
            return Channels.newChannel(volume);
        }

        /// Commits all in-memory volumes.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            ensureOpen();
            if (finalVolumeIndex != volumes.size() - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex must identify the last opened volume");
            }
            this.finalVolumeIndex = finalVolumeIndex;
            commitCount++;
            finished = true;
        }

        /// Rolls back this in-memory output.
        @Override
        public void rollback() {
            if (finished) {
                return;
            }
            rollbackCount++;
            finished = true;
        }

        /// Closes this output and rolls it back when unfinished.
        @Override
        public void close() {
            closeCount++;
            rollback();
        }

        /// Returns a readable source over the committed volume bytes.
        private ArkivoVolumeSource volumeSource() {
            if (commitCount == 0) {
                throw new IllegalStateException("volume output has not been committed");
            }
            return index -> {
                if (index < 0 || index >= volumes.size()) {
                    return null;
                }
                return new TestByteArraySeekableChannel(volumes.get((int) index).toByteArray());
            };
        }

        /// Returns a copy of one physical volume's bytes.
        private byte[] volumeBytes(int index) {
            return volumes.get(index).toByteArray();
        }

        /// Returns the concatenated logical archive bytes.
        private byte[] archiveBytes() {
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            for (ByteArrayOutputStream volume : volumes) {
                archive.writeBytes(volume.toByteArray());
            }
            return archive.toByteArray();
        }

        /// Returns whether every physical volume respects the requested maximum size.
        private boolean allVolumeSizesAtMost(long maximumSize) {
            if (maximumSize <= 0L) {
                throw new IllegalArgumentException("maximumSize must be positive");
            }
            for (ByteArrayOutputStream volume : volumes) {
                if (volume.size() > maximumSize) {
                    return false;
                }
            }
            return true;
        }

        /// Returns the number of opened volumes.
        private int volumeCount() {
            return volumes.size();
        }

        /// Returns the final committed volume index.
        private long finalVolumeIndex() {
            return finalVolumeIndex;
        }

        /// Returns the number of commit calls.
        private int commitCount() {
            return commitCount;
        }

        /// Returns the number of rollback calls.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }

        /// Requires this output transaction to remain unfinished.
        private void ensureOpen() throws IOException {
            if (finished) {
                throw new IOException("volume output is closed");
            }
        }
    }

    /// Open option used to emulate provider-specific options in tests.
    private enum TestOpenOption implements OpenOption {
        /// Provider-specific direct I/O marker.
        DIRECT
    }
}
