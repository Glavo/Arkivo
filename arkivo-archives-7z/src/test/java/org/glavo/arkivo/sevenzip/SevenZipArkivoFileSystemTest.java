// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import java.io.ByteArrayOutputStream;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoVolumeOutput;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.glavo.arkivo.ArkivoVolumeTarget;
import org.glavo.arkivo.ArkivoFileSystem;
import org.glavo.arkivo.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemConfig;
import org.glavo.arkivo.sevenzip.internal.SevenZipArkivoFileSystemImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.ArrayCache;
import org.tukaani.xz.ARMOptions;
import org.tukaani.xz.ARMThumbOptions;
import org.tukaani.xz.DeltaOptions;
import org.tukaani.xz.FilterOptions;
import org.tukaani.xz.FinishableOutputStream;
import org.tukaani.xz.IA64Options;
import org.tukaani.xz.LZMA2Options;
import org.tukaani.xz.LZMAOutputStream;
import org.tukaani.xz.PowerPCOptions;
import org.tukaani.xz.SPARCOptions;
import org.tukaani.xz.X86Options;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
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
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests basic 7z Arkivo file system behavior.
@NotNullByDefault
public final class SevenZipArkivoFileSystemTest {
    /// Verifies that a 7z file system can be opened from an archive path.
    @Test
    public void openPath() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(SevenZipArkivoFileSystemProvider.instance(), fileSystem.provider());
                assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
                assertEquals(true, fileSystem.isOpen());
                assertEquals(true, fileSystem.isReadOnly());
                assertEquals("/", fileSystem.getSeparator());
                assertEquals(0, fileSystem.majorVersion());
                assertEquals(4, fileSystem.minorVersion());
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(0L, fileSystem.nextHeaderSize());
                assertEquals(0L, fileSystem.nextHeaderCrc32());
                assertEquals(true, fileSystem.supportedFileAttributeViews().contains("basic"));
                assertEquals(true, fileSystem.supportedFileAttributeViews().contains("owner"));
                assertEquals(true, fileSystem.supportedFileAttributeViews().contains("posix"));
                var fileStore = Files.getFileStore(fileSystem.getPath("/"));
                assertEquals("7z", fileStore.type());
                assertEquals(fileStore.name(), fileStore.getAttribute("name"));
                assertEquals(fileStore.type(), fileStore.getAttribute("type"));
                assertEquals(Boolean.valueOf(fileStore.isReadOnly()), fileStore.getAttribute("basic:readOnly"));
                assertEquals(Long.valueOf(fileStore.getTotalSpace()), fileStore.getAttribute("totalSpace"));
                assertEquals(Long.valueOf(fileStore.getUsableSpace()), fileStore.getAttribute("usableSpace"));
                assertEquals(Long.valueOf(fileStore.getUnallocatedSpace()), fileStore.getAttribute("unallocatedSpace"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("7z:type"));
                assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("missing"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z file systems expose synthesized owner and group principal lookup.
    @Test
    public void userPrincipalLookupService() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
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
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies basic 7z path operations.
    @Test
    public void paths() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path path = fileSystem.getPath("/a/b/../c.txt");

                assertEquals("/a/b/../c.txt", path.toString());
                assertEquals("c.txt", path.getFileName().toString());
                assertEquals("/a/b/..", path.getParent().toString());
                assertEquals("/a/c.txt", path.normalize().toString());
                assertEquals("b/../c.txt", fileSystem.getPath("/a").relativize(path).toString());
                assertEquals(true, fileSystem.getPathMatcher("glob:**/*.txt").matches(path));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies root directory and basic attributes.
    @Test
    public void rootDirectory() throws IOException {
        Path archivePath = createMinimalArchive();

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path root = fileSystem.getPath("/");
                BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class);
                PosixFileAttributes posixAttributes = Files.readAttributes(root, PosixFileAttributes.class);
                ArrayList<Path> children = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                    for (Path child : stream) {
                        children.add(child);
                    }
                }

                assertEquals(true, attributes.isDirectory());
                assertEquals("owner", posixAttributes.owner().getName());
                assertEquals("group", posixAttributes.group().getName());
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_EXECUTE
                ), posixAttributes.permissions());
                assertEquals(List.of(), children);
                assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(
                        fileSystem.getPath("/missing"),
                        BasicFileAttributes.class
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that empty directory and file entries are indexed.
    @Test
    public void emptyEntries() throws IOException {
        Path archivePath = createTemporaryArchivePath("empty-entries-");
        Path copiedDirectory = archivePath.getParent().resolve("copied-dir");
        Path existingFile = archivePath.getParent().resolve("existing-file");
        Files.write(archivePath, archiveWithEmptyEntries());

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                ArrayList<String> rootChildren = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                Path directory = fileSystem.getPath("/dir");
                Path emptyFile = fileSystem.getPath("/empty.txt");
                BasicFileAttributes directoryAttributes = Files.readAttributes(directory, BasicFileAttributes.class);
                BasicFileAttributes fileAttributes = Files.readAttributes(emptyFile, BasicFileAttributes.class);

                assertEquals(List.of("/dir", "/empty.txt"), rootChildren);
                assertEquals(true, directoryAttributes.isDirectory());
                assertEquals(false, directoryAttributes.isRegularFile());
                assertEquals(false, fileAttributes.isDirectory());
                assertEquals(true, fileAttributes.isRegularFile());
                assertEquals(0L, fileAttributes.size());
                assertArrayEquals(new byte[0], Files.readAllBytes(emptyFile));
                try (SeekableByteChannel channel = Files.newByteChannel(emptyFile)) {
                    assertEquals(0L, channel.size());
                }

                Files.copy(directory, copiedDirectory);
                assertEquals(true, Files.isDirectory(copiedDirectory));
                assertThrows(FileAlreadyExistsException.class, () -> Files.copy(directory, copiedDirectory));
                Files.copy(directory, copiedDirectory, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(existingFile, "existing", StandardCharsets.UTF_8);
                Files.copy(directory, existingFile, StandardCopyOption.REPLACE_EXISTING);
                assertEquals(true, Files.isDirectory(existingFile));
            }
        } finally {
            Files.deleteIfExists(existingFile);
            Files.deleteIfExists(copiedDirectory);
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that sized 7z dummy properties are skipped while parsing supported header scopes.
    @Test
    public void dummyProperties() throws IOException {
        byte[] content = "dummy properties content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("dummy-properties-");
        Files.write(archivePath, archiveWithDummyProperties(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dummy.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z archive properties are skipped while parsing the top-level header.
    @Test
    public void archiveProperties() throws IOException {
        byte[] content = "archive properties content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("archive-properties-");
        Files.write(archivePath, archiveWithArchiveProperties(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z Copy method can be read.
    @Test
    public void copyFileEntry() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("copy-file-");
        Files.write(archivePath, archiveWithCopyFile(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(false, attributes.isDirectory());
                assertEquals(true, attributes.isRegularFile());
                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z archive can be read through multiple logical volumes.
    @Test
    public void splitVolumeSource() throws IOException {
        byte[] content = "split volume content body".getBytes(StandardCharsets.UTF_8);
        byte[] archive = archiveWithCopyFile(content);
        int bodyStart = 32;
        ArkivoVolumeSource volumes = new SplitVolumeSource(splitArchive(
                archive,
                5,
                bodyStart + 2,
                bodyStart + content.length - 1,
                archive.length - 3
        ));

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(volumes)) {
            Path file = fileSystem.getPath("/hello.txt");
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

            assertEquals(content.length, attributes.size());
            assertArrayEquals(content, Files.readAllBytes(file));
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                assertEquals(content.length, channel.size());
                channel.position(1);
                ByteBuffer buffer = ByteBuffer.allocate(content.length - 1);
                assertEquals(content.length - 1, channel.read(buffer));
                assertArrayEquals(Arrays.copyOfRange(content, 1, content.length), buffer.array());
            }
        }
    }

    /// Verifies that a repeatable seekable channel source supports random-access 7z file system operations.
    @Test
    public void randomAccessFileSystemFromSeekableChannelSource() throws IOException {
        byte[] content = "seekable channel source content".getBytes(StandardCharsets.UTF_8);
        TestSeekableChannelSource source = new TestSeekableChannelSource(archiveWithCopyFile(content));

        try (ArkivoFileSystem fileSystem = SevenZipArkivoFormat.instance().open(source)) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hello.txt")));
            assertEquals(true, source.openCount() > 1);
            assertEquals(true, source.allOpenedChannelsClosed());
            assertEquals(0, source.closeCount());
        }

        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that conventional 7z split volumes can be discovered from the first volume path.
    @Test
    public void splitVolumePathDiscovery() throws IOException {
        byte[] content = "split volume path content body".getBytes(StandardCharsets.UTF_8);
        byte[][] volumes = splitArchive(archiveWithCopyFile(content), 5);
        Path firstVolume = createTemporaryArchivePath("split-7z-path-").resolveSibling("sample.7z.001");
        Path secondVolume = firstVolume.resolveSibling("sample.7z.002");
        Files.write(firstVolume, volumes[0]);
        Files.write(secondVolume, volumes[1]);

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
                Path file = fileSystem.getPath("/hello.txt");

                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    channel.position(1);
                    ByteBuffer buffer = ByteBuffer.allocate(content.length - 1);
                    assertEquals(content.length - 1, channel.read(buffer));
                    assertArrayEquals(Arrays.copyOfRange(content, 1, content.length), buffer.array());
                }
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that a 7z file named like a first split part ignores a stale second part when already complete.
    @Test
    public void staleSecondSplitVolumeDoesNotOverrideSingleArchivePath() throws IOException {
        byte[] content = "single numbered 7z content".getBytes(StandardCharsets.UTF_8);
        Path firstVolume = createTemporaryArchivePath("single-numbered-7z-").resolveSibling("sample.7z.001");
        Path secondVolume = firstVolume.resolveSibling("sample.7z.002");
        Files.write(firstVolume, archiveWithCopyFile(content));
        Files.write(secondVolume, "stale".getBytes(StandardCharsets.UTF_8));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/hello.txt")));
            }
        } finally {
            Files.deleteIfExists(secondVolume);
            deleteTemporaryArchive(firstVolume);
        }
    }

    /// Verifies that 7z archives can be created through a forward-only writable file system.
    @Test
    public void createsEntriesAsWritableFileSystem() throws IOException {
        byte[] content = "hello from writable 7z file system".getBytes(StandardCharsets.UTF_8);
        byte[] channelContent = new byte[]{1, 2, 3};
        Path archivePath = createTemporaryArchivePath("writable-7z-fs-");
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Set<PosixFilePermission> channelFilePermissions = PosixFilePermissions.fromString("rw-r-----");
        Set<PosixFilePermission> linkPermissions = PosixFilePermissions.fromString("rwxr-xr--");
        Map<String, Object> environment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                )
        );

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath, environment)) {
                assertEquals(false, fileSystem.isReadOnly());
                assertEquals(false, Files.getFileStore(fileSystem.getPath("/")).isReadOnly());

                Path directory = fileSystem.getPath("/dir");
                Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(directoryPermissions));

                Path file = fileSystem.getPath("/dir/hello.txt");
                Files.write(file, content);
                assertThrows(FileAlreadyExistsException.class, () -> Files.write(file, content));

                Path channelFile = fileSystem.getPath("/channel.bin");
                try (SeekableByteChannel channel =
                             Files.newByteChannel(
                                     channelFile,
                                     Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                                     PosixFilePermissions.asFileAttribute(channelFilePermissions)
                             )) {
                    assertEquals(0L, channel.position());
                    assertEquals(channelContent.length, channel.write(ByteBuffer.wrap(channelContent)));
                    assertEquals(channelContent.length, channel.size());
                    assertThrows(UnsupportedOperationException.class, () -> channel.position(0L));
                }

                Files.createSymbolicLink(
                        fileSystem.getPath("/link"),
                        Path.of("dir/hello.txt"),
                        PosixFilePermissions.asFileAttribute(linkPermissions)
                );
                assertThrows(UnsupportedOperationException.class, () -> Files.readAllBytes(file));
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path directory = fileSystem.getPath("/dir");
                Path channelFile = fileSystem.getPath("/channel.bin");
                Path link = fileSystem.getPath("/link");
                PosixFileAttributes directoryPosixAttributes = Files.readAttributes(directory, PosixFileAttributes.class);
                PosixFileAttributes channelPosixAttributes = Files.readAttributes(channelFile, PosixFileAttributes.class);
                BasicFileAttributes linkAttributes = Files.readAttributes(link, BasicFileAttributes.class);
                PosixFileAttributes linkPosixAttributes = Files.readAttributes(link, PosixFileAttributes.class);

                assertEquals(true, fileSystem.isReadOnly());
                assertEquals(true, Files.isDirectory(directory));
                assertEquals(directoryPermissions, directoryPosixAttributes.permissions());
                assertEquals(0040750, Files.readAttributes(directory, SevenZipArkivoEntryAttributes.class).unixMode());
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/dir/hello.txt")));
                assertArrayEquals(channelContent, Files.readAllBytes(channelFile));
                assertEquals(channelFilePermissions, channelPosixAttributes.permissions());
                assertEquals(0100640, Files.readAttributes(channelFile, SevenZipArkivoEntryAttributes.class).unixMode());
                assertEquals(false, linkAttributes.isRegularFile());
                assertEquals(true, linkAttributes.isSymbolicLink());
                assertEquals(linkPermissions, linkPosixAttributes.permissions());
                assertEquals(0120754, Files.readAttributes(link, SevenZipArkivoEntryAttributes.class).unixMode());
                assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
                assertEquals("dir/hello.txt", Files.readString(link, StandardCharsets.UTF_8));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that the 7z streaming writer creates every supported entry type and preserves writable metadata.
    @Test
    public void createsEntriesWithStreamingWriter() throws IOException {
        byte[] content = "streaming 7z payload".getBytes(StandardCharsets.UTF_8);
        FileTime lastModifiedTime = FileTime.from(Instant.parse("2024-01-02T03:04:05.123Z"));
        FileTime lastAccessTime = FileTime.from(Instant.parse("2024-02-03T04:05:06.234Z"));
        FileTime creationTime = FileTime.from(Instant.parse("2024-03-04T05:06:07.345Z"));
        Set<PosixFilePermission> directoryPermissions = PosixFilePermissions.fromString("rwxr-x---");
        Set<PosixFilePermission> filePermissions = PosixFilePermissions.fromString("rw-r-----");
        Set<PosixFilePermission> linkPermissions = PosixFilePermissions.fromString("rwxr-xr--");
        Path archivePath = createTemporaryArchivePath("streaming-writer-");

        try {
            try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(archivePath)) {
                writer.beginDirectory("meta");
                Objects.requireNonNull(writer.attributeView(PosixFileAttributeView.class))
                        .setPermissions(directoryPermissions);
                writer.endEntry();

                writer.beginFile("meta/payload.bin");
                Objects.requireNonNull(writer.attributeView(BasicFileAttributeView.class))
                        .setTimes(lastModifiedTime, lastAccessTime, creationTime);
                SevenZipArkivoEntryAttributeView sevenZipView = Objects.requireNonNull(
                        writer.attributeView(SevenZipArkivoEntryAttributeView.class)
                );
                sevenZipView.setWindowsAttributes(0x20);
                Objects.requireNonNull(writer.attributeView(PosixFileAttributeView.class))
                        .setPermissions(filePermissions);
                SevenZipArkivoEntryAttributes pendingAttributes = sevenZipView.readAttributes();
                assertEquals("meta/payload.bin", pendingAttributes.path());
                assertEquals(lastModifiedTime, pendingAttributes.lastModifiedTime());
                assertEquals(0100640, pendingAttributes.unixMode());
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content);
                }

                writer.beginSymbolicLink("meta/link", "payload.bin");
                Objects.requireNonNull(writer.attributeView(PosixFileAttributeView.class))
                        .setPermissions(linkPermissions);
                writer.endEntry();

                writer.beginFile("empty.txt");
                writer.endEntry();
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path directory = fileSystem.getPath("/meta");
                Path file = fileSystem.getPath("/meta/payload.bin");
                Path link = fileSystem.getPath("/meta/link");
                SevenZipArkivoEntryAttributes fileAttributes =
                        Files.readAttributes(file, SevenZipArkivoEntryAttributes.class);

                assertEquals(directoryPermissions, Files.readAttributes(directory, PosixFileAttributes.class).permissions());
                assertArrayEquals(content, Files.readAllBytes(file));
                assertEquals(lastModifiedTime, fileAttributes.lastModifiedTime());
                assertEquals(lastAccessTime, fileAttributes.lastAccessTime());
                assertEquals(creationTime, fileAttributes.creationTime());
                assertEquals(0100640, fileAttributes.unixMode());
                assertEquals(0x20, fileAttributes.windowsAttributes() & 0xffff);
                assertEquals(filePermissions, Files.readAttributes(file, PosixFileAttributes.class).permissions());
                assertEquals(true, Files.readAttributes(link, BasicFileAttributes.class).isSymbolicLink());
                assertEquals(linkPermissions, Files.readAttributes(link, PosixFileAttributes.class).permissions());
                assertEquals(fileSystem.getPath("payload.bin"), Files.readSymbolicLink(link));
                assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/empty.txt")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that output-stream writers stage a complete archive, close active bodies, and own their output.
    @Test
    public void createsStreamingArchiveInOutputStream() throws IOException {
        byte[] content = new byte[]{4, 3, 2, 1};
        TrackingOutputStream archiveOutput = new TrackingOutputStream();
        SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archiveOutput);
        writer.beginFile("content.bin");
        OutputStream body = writer.openOutputStream();
        body.write(content);

        assertEquals(0, archiveOutput.size());
        writer.close();

        assertEquals(true, archiveOutput.closed());
        assertThrows(ClosedChannelException.class, () -> body.write(0));
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                new SplitVolumeSource(new byte[][]{archiveOutput.toByteArray()})
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that writable-channel writers own their channel and produce a readable single-volume archive.
    @Test
    public void createsStreamingArchiveInWritableChannel() throws IOException {
        TrackingOutputStream archiveOutput = new TrackingOutputStream();
        WritableByteChannel archiveChannel = Channels.newChannel(archiveOutput);

        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archiveChannel)) {
            writer.beginFile("channel.txt");
            try (WritableByteChannel body = writer.openChannel()) {
                assertEquals(7, body.write(ByteBuffer.wrap("channel".getBytes(StandardCharsets.UTF_8))));
            }
        }

        assertEquals(false, archiveChannel.isOpen());
        assertEquals(true, archiveOutput.closed());
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                new SplitVolumeSource(new byte[][]{archiveOutput.toByteArray()})
        )) {
            assertEquals("channel", Files.readString(fileSystem.getPath("/channel.txt")));
        }
    }

    /// Verifies that the streaming writer publishes bounded output through a transactional volume target.
    @Test
    public void createsSplitArchiveWithStreamingWriter() throws IOException {
        byte[] content = new byte[512];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 13);
        }
        TestVolumeTarget target = new TestVolumeTarget(-1L, false);

        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(target, 64L)) {
            writer.beginFile("content.bin");
            writer.openOutputStream().write(content);
            assertEquals(0, target.openOutputCount());
        }

        byte[][] volumes = target.committedVolumes();
        assertEquals(true, volumes.length > 1);
        assertEquals(true, target.allOpenedChannelsClosed());
        for (byte[] volume : volumes) {
            assertEquals(true, volume.length > 0 && volume.length <= 64);
        }
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(new SplitVolumeSource(volumes))) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that streaming target failures roll back unpublished volumes and support close retry.
    @Test
    public void streamingWriterTargetFailureRollsBack() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(1L, false);
        SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(target, 64L);
        writer.beginFile("content.bin");
        try (OutputStream output = writer.openOutputStream()) {
            output.write(new byte[512]);
        }

        IOException exception = assertThrows(IOException.class, writer::close);

        assertEquals("volume open failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertEquals(true, target.allOpenedChannelsClosed());
        writer.close();
    }

    /// Verifies streaming writer state validation, path validation, and automatic empty-entry completion.
    @Test
    public void validatesStreamingWriterStateAndConfiguration() throws IOException {
        TrackingOutputStream archiveOutput = new TrackingOutputStream();
        SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archiveOutput);

        assertThrows(IllegalArgumentException.class, () -> writer.beginFile("../escape"));
        assertThrows(IllegalArgumentException.class, () -> writer.beginDirectory("/absolute"));
        assertThrows(IllegalArgumentException.class, () -> writer.beginFile("C:/drive"));
        assertThrows(IllegalArgumentException.class, () -> writer.beginSymbolicLink("link", ""));
        assertThrows(IllegalStateException.class, writer::endEntry);

        writer.beginDirectory("dir");
        assertThrows(IllegalStateException.class, writer::openOutputStream);
        writer.endEntry();

        writer.beginFile("body.txt");
        OutputStream body = writer.openOutputStream();
        assertThrows(IllegalStateException.class, () -> writer.beginFile("next.txt"));
        body.close();

        writer.beginFile("implicit-empty.txt");
        writer.close();
        assertThrows(IllegalStateException.class, () -> writer.beginFile("closed.txt"));

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                new SplitVolumeSource(new byte[][]{archiveOutput.toByteArray()})
        )) {
            assertEquals(true, Files.isDirectory(fileSystem.getPath("/dir")));
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/body.txt")));
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/implicit-empty.txt")));
        }

        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoStreamingWriter.open(
                new TrackingOutputStream(),
                Map.of(ArkivoFileSystem.OPEN_OPTIONS.key(), Set.of(StandardOpenOption.WRITE))
        ));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoStreamingWriter.open(
                new TestVolumeTarget(-1L, false),
                0L
        ));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoStreamingWriter.open(
                new TestVolumeTarget(-1L, false),
                64L,
                Map.of(SevenZipArkivoFileSystem.SPLIT_SIZE.key(), 32L)
        ));
    }

    /// Verifies that direct output publication failures close owned output and surface the write failure.
    @Test
    public void streamingWriterOutputFailureClosesOwnedStream() throws IOException {
        FailingOutputStream archiveOutput = new FailingOutputStream();
        SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(archiveOutput);
        writer.beginFile("content.bin");
        try (OutputStream output = writer.openOutputStream()) {
            output.write(new byte[128]);
        }

        IOException exception = assertThrows(IOException.class, writer::close);

        assertEquals("forced output failure", exception.getMessage());
        assertEquals(true, archiveOutput.closed());
        writer.close();
    }

    /// Verifies path-backed AES writing, Arkivo password behavior, and Commons Compress interoperability.
    @Test
    public void createsEncryptedStreamingArchive() throws IOException {
        String passwordText = "p\u00e4ss-\u5bc6\u7801";
        char[] passwordCharacters = passwordText.toCharArray();
        byte[] password = passwordText.getBytes(StandardCharsets.UTF_16LE);
        byte[] content = "encrypted 7z streaming content".getBytes(StandardCharsets.UTF_8);
        RecordingPasswordProvider writePasswordProvider = RecordingPasswordProvider.supplying(password);
        Path archivePath = createTemporaryArchivePath("encrypted-streaming-");

        try {
            try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.create(
                    archivePath,
                    Map.of(SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(), writePasswordProvider)
            )) {
                assertEquals(1, writePasswordProvider.archiveRequestCount());
                writer.beginFile("content.bin");
                try (OutputStream output = writer.openOutputStream()) {
                    output.write(content);
                }
                writer.beginSymbolicLink("link", "content.bin");
                writer.endEntry();
            }
            assertEquals(1, writePasswordProvider.archiveRequestCount());

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/content.bin");
                Path link = fileSystem.getPath("/link");
                assertEquals(true, Files.exists(file));
                assertEquals(true, Files.readAttributes(link, BasicFileAttributes.class).isSymbolicLink());
                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z AES encrypted data requires a password"));
                assertThrows(IOException.class, () -> Files.readSymbolicLink(link));
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed("wrong".getBytes(StandardCharsets.UTF_16LE))
                    )
            )) {
                IOException exception = assertThrows(
                        IOException.class,
                        () -> Files.readAllBytes(fileSystem.getPath("/content.bin"))
                );
                assertEquals(true, exception.getMessage().contains("7z entry data does not match CRC-32"));
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(password)
                    )
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
                assertEquals(fileSystem.getPath("content.bin"), Files.readSymbolicLink(fileSystem.getPath("/link")));
            }

            try (SevenZFile sevenZFile = SevenZFile.builder()
                    .setPath(archivePath)
                    .setPassword(passwordCharacters)
                    .get()) {
                SevenZArchiveEntry entry = Objects.requireNonNull(sevenZFile.getNextEntry());
                assertEquals("content.bin", entry.getName());
                boolean aesMethodPresent = false;
                for (var method : entry.getContentMethods()) {
                    if (method.getMethod() == SevenZMethod.AES256SHA256) {
                        aesMethodPresent = true;
                    }
                }
                assertEquals(true, aesMethodPresent);
                try (var input = sevenZFile.getInputStream(entry)) {
                    assertArrayEquals(content, input.readAllBytes());
                }
            }
        } finally {
            Arrays.fill(passwordCharacters, '\0');
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that an empty password encrypts writable-file-system content and remains usable for reads.
    @Test
    public void createsEncryptedArchiveWithEmptyPassword() throws IOException {
        byte[] content = "empty password content".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("empty-password-write-");
        Map<String, Object> writeEnvironment = Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(),
                Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                ),
                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(new byte[0])
        );

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    writeEnvironment
            )) {
                Files.write(fileSystem.getPath("/content.bin"), content);
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertThrows(IOException.class, () -> Files.readAllBytes(fileSystem.getPath("/content.bin")));
            }
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(new byte[0])
                    )
            )) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies AES writing through output-stream, writable-channel, and transactional split targets.
    @Test
    public void createsEncryptedArchivesInAllStreamingTargets() throws IOException {
        byte[] password = "target-password".getBytes(StandardCharsets.UTF_16LE);
        byte[] content = new byte[384];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 29);
        }
        Map<String, Object> environment = Map.of(
                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                ArkivoPasswordProvider.fixed(password)
        );

        TrackingOutputStream streamOutput = new TrackingOutputStream();
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(
                streamOutput,
                environment
        )) {
            writeStreamingContent(writer, content);
        }
        assertEquals(true, streamOutput.closed());
        assertEncryptedContent(new byte[][]{streamOutput.toByteArray()}, password, content);

        TrackingOutputStream channelOutput = new TrackingOutputStream();
        WritableByteChannel channel = Channels.newChannel(channelOutput);
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(channel, environment)) {
            writeStreamingContent(writer, content);
        }
        assertEquals(false, channel.isOpen());
        assertEncryptedContent(new byte[][]{channelOutput.toByteArray()}, password, content);

        TestVolumeTarget target = new TestVolumeTarget(-1L, false);
        try (SevenZipArkivoStreamingWriter writer = SevenZipArkivoStreamingWriter.open(
                target,
                64L,
                environment
        )) {
            writeStreamingContent(writer, content);
            assertEquals(0, target.openOutputCount());
        }
        byte[][] volumes = target.committedVolumes();
        assertEquals(true, volumes.length > 1);
        assertEncryptedContent(volumes, password, content);
    }

    /// Verifies password-provider failures occur before publication and preserve header-encryption rejection.
    @Test
    public void rejectsInvalidEncryptedWritePasswordsBeforePublication() throws IOException {
        TrackingOutputStream missingPasswordOutput = new TrackingOutputStream();
        IOException missingPasswordException = assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(
                        missingPasswordOutput,
                        Map.of(
                                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                                RecordingPasswordProvider.missing()
                        )
                )
        );
        assertEquals("7z encrypted archive write requires a password", missingPasswordException.getMessage());
        assertEquals(true, missingPasswordOutput.closed());
        assertEquals(0, missingPasswordOutput.size());

        TrackingOutputStream failingProviderOutput = new TrackingOutputStream();
        IOException providerException = assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(
                        failingProviderOutput,
                        Map.of(
                                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                                RecordingPasswordProvider.failing()
                        )
                )
        );
        assertEquals("password provider failed", providerException.getMessage());
        assertEquals(true, failingProviderOutput.closed());
        assertEquals(0, failingProviderOutput.size());

        TrackingOutputStream invalidEncodingOutput = new TrackingOutputStream();
        IOException encodingException = assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(
                        invalidEncodingOutput,
                        Map.of(
                                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                                RecordingPasswordProvider.supplying(new byte[]{1})
                        )
                )
        );
        assertEquals("7z write password must contain valid UTF-16LE bytes", encodingException.getMessage());
        assertEquals(true, invalidEncodingOutput.closed());
        assertEquals(0, invalidEncodingOutput.size());

        Path invalidPasswordPath = createTemporaryArchivePath("invalid-write-password-");
        try {
            assertThrows(
                    IOException.class,
                    () -> SevenZipArkivoStreamingWriter.create(
                            invalidPasswordPath,
                            Map.of(
                                    SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                                    RecordingPasswordProvider.missing()
                            )
                    )
            );
            assertEquals(false, Files.exists(invalidPasswordPath));
        } finally {
            deleteTemporaryArchive(invalidPasswordPath);
        }

        TestVolumeTarget target = new TestVolumeTarget(-1L, false);
        assertThrows(
                IOException.class,
                () -> SevenZipArkivoStreamingWriter.open(
                        target,
                        64L,
                        Map.of(
                                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                                RecordingPasswordProvider.missing()
                        )
                )
        );
        assertEquals(0, target.openOutputCount());

        RecordingPasswordProvider unusedProvider = RecordingPasswordProvider.supplying(
                "header-password".getBytes(StandardCharsets.UTF_16LE)
        );
        TrackingOutputStream encryptedHeaderOutput = new TrackingOutputStream();
        UnsupportedOperationException headerException = assertThrows(
                UnsupportedOperationException.class,
                () -> SevenZipArkivoStreamingWriter.open(
                        encryptedHeaderOutput,
                        Map.of(
                                SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(), unusedProvider,
                                SevenZipArkivoFileSystem.ENCRYPT_HEADERS.key(), true
                        )
                )
        );
        assertEquals("7z encrypted header writes are not supported", headerException.getMessage());
        assertEquals(0, unusedProvider.archiveRequestCount());
        assertEquals(true, encryptedHeaderOutput.closed());
    }

    /// Verifies that path-backed 7z writes produce conventional bounded split volumes that can be reopened.
    @Test
    public void createsPathBackedSplitArchive() throws IOException {
        long splitSize = 128L;
        byte[] content = new byte[1024];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) index;
        }
        Path firstVolume = createTemporaryArchivePath("split-write-").resolveSibling("sample.7z.001");
        Map<String, Object> environment = splitWriteEnvironment(splitSize, false);

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume, environment)) {
                Files.write(fileSystem.getPath("/content.bin"), content);
                assertEquals(false, Files.exists(firstVolume));
            }

            List<Path> volumePaths = existingTestVolumePaths(firstVolume);
            assertEquals(true, volumePaths.size() > 1);
            for (int index = 0; index < volumePaths.size(); index++) {
                long volumeSize = Files.size(volumePaths.get(index));
                assertEquals(true, volumeSize > 0L);
                assertEquals(true, volumeSize <= splitSize);
                if (index + 1 < volumePaths.size()) {
                    assertEquals(splitSize, volumeSize);
                }
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
            }
        } finally {
            deleteTemporaryArchiveDirectory(firstVolume);
        }
    }

    /// Verifies that replacing path-backed split output removes stale higher-numbered volumes.
    @Test
    public void pathBackedSplitArchiveRemovesStaleVolumes() throws IOException {
        long splitSize = 4096L;
        byte[] content = "replacement split archive".getBytes(StandardCharsets.UTF_8);
        Path firstVolume = createTemporaryArchivePath("split-replace-").resolveSibling("sample.7z.001");
        Path secondVolume = testVolumePath(firstVolume, 2);
        Path thirdVolume = testVolumePath(firstVolume, 3);
        Files.write(firstVolume, new byte[]{1});
        Files.write(secondVolume, new byte[]{2});
        Files.write(thirdVolume, new byte[]{3});

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    firstVolume,
                    splitWriteEnvironment(splitSize, false)
            )) {
                Files.write(fileSystem.getPath("/replacement.txt"), content);
                assertArrayEquals(new byte[]{1}, Files.readAllBytes(firstVolume));
                assertArrayEquals(new byte[]{2}, Files.readAllBytes(secondVolume));
                assertArrayEquals(new byte[]{3}, Files.readAllBytes(thirdVolume));
            }

            assertEquals(true, Files.exists(firstVolume));
            assertEquals(false, Files.exists(secondVolume));
            assertEquals(false, Files.exists(thirdVolume));
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(firstVolume)) {
                assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/replacement.txt")));
            }
        } finally {
            deleteTemporaryArchiveDirectory(firstVolume);
        }
    }

    /// Verifies that create-new split publication preserves existing volumes after a conflict.
    @Test
    public void pathBackedSplitCreateNewRollsBack() throws IOException {
        byte[] existingContent = new byte[]{9, 8, 7};
        Path firstVolume = createTemporaryArchivePath("split-create-new-").resolveSibling("sample.7z.001");
        Path secondVolume = testVolumePath(firstVolume, 2);
        try {
            SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    firstVolume,
                    splitWriteEnvironment(64L, true)
            );
            Files.write(fileSystem.getPath("/content.bin"), new byte[512]);
            Files.write(secondVolume, existingContent);

            assertThrows(FileAlreadyExistsException.class, fileSystem::close);
            assertEquals(false, Files.exists(firstVolume));
            assertArrayEquals(existingContent, Files.readAllBytes(secondVolume));

            fileSystem.close();
        } finally {
            deleteTemporaryArchiveDirectory(firstVolume);
        }
    }

    /// Verifies that create-new path output rejects any existing numbered volume before assembly starts.
    @Test
    public void pathBackedSplitCreateNewRejectsExistingVolumeAtOpen() throws IOException {
        byte[] existingContent = new byte[]{6, 5, 4};
        Path firstVolume = createTemporaryArchivePath("split-create-new-existing-")
                .resolveSibling("sample.7z.001");
        Path secondVolume = testVolumePath(firstVolume, 2);
        Files.write(secondVolume, existingContent);

        try {
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> SevenZipArkivoFileSystem.open(firstVolume, splitWriteEnvironment(64L, true))
            );
            assertEquals(false, Files.exists(firstVolume));
            assertArrayEquals(existingContent, Files.readAllBytes(secondVolume));
        } finally {
            deleteTemporaryArchiveDirectory(firstVolume);
        }
    }

    /// Verifies that arbitrary transactional targets receive bounded 7z volumes only when the file system closes.
    @Test
    public void createsSplitArchiveInVolumeTarget() throws IOException {
        long splitSize = 96L;
        byte[] content = new byte[768];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (index * 31);
        }
        TestVolumeTarget target = new TestVolumeTarget(-1L, false);

        try (ArkivoFileSystem fileSystem = SevenZipArkivoFormat.instance().create(
                target,
                splitSize,
                Map.of(ArkivoFileSystem.THREAD_SAFETY.key(), ArkivoFileSystemThreadSafety.STRICT)
        )) {
            assertEquals(false, fileSystem.isReadOnly());
            Files.write(fileSystem.getPath("/content.bin"), content);
            assertEquals(0, target.openOutputCount());
        }

        byte[][] volumes = target.committedVolumes();
        assertEquals(1, target.openOutputCount());
        assertEquals(true, volumes.length > 1);
        assertEquals(true, target.allOpenedChannelsClosed());
        for (int index = 0; index < volumes.length; index++) {
            assertEquals(true, volumes[index].length > 0);
            assertEquals(true, volumes[index].length <= splitSize);
            if (index + 1 < volumes.length) {
                assertEquals(splitSize, volumes[index].length);
            }
        }

        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(new SplitVolumeSource(volumes))) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Verifies that an empty split 7z archive still commits one readable non-empty volume.
    @Test
    public void createsEmptySplitArchiveInVolumeTarget() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(-1L, false);

        try (SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.create(target, 1024L)) {
            // Closing the file system finalizes an archive without entries.
        }

        byte[][] volumes = target.committedVolumes();
        assertEquals(1, volumes.length);
        assertEquals(true, volumes[0].length > 0);
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(new SplitVolumeSource(volumes));
             DirectoryStream<Path> children = Files.newDirectoryStream(fileSystem.getPath("/"))) {
            assertEquals(false, children.iterator().hasNext());
        }
    }

    /// Verifies that an arbitrary target failure rolls back unpublished 7z volumes and supports close retry.
    @Test
    public void splitArchiveTargetFailureRollsBack() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(1L, false);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[512]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume open failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertEquals(true, target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that a target commit failure rolls back all staged 7z volumes.
    @Test
    public void splitArchiveTargetCommitFailureRollsBack() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(-1L, true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[256]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume commit failed", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(0, target.committedVolumes().length);
        assertEquals(true, target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that a zero-progress volume channel fails instead of blocking close and is rolled back.
    @Test
    public void splitArchiveZeroProgressTargetRollsBack() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(-1L, false, true);
        SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.create(target, 64L);
        Files.write(fileSystem.getPath("/content.bin"), new byte[128]);

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("7z volume write made no progress", exception.getMessage());
        assertEquals(1, target.rollbackCount());
        assertEquals(true, target.allOpenedChannelsClosed());

        fileSystem.close();
    }

    /// Verifies that split output factories reject ambiguous sizes, options, and path names.
    @Test
    public void rejectsInvalidSplitOutputConfiguration() throws IOException {
        TestVolumeTarget target = new TestVolumeTarget(-1L, false);
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoFileSystem.create(target, 0L));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoFileSystem.create(
                target,
                64L,
                Map.of(SevenZipArkivoFileSystem.SPLIT_SIZE.key(), 32L)
        ));
        assertThrows(IllegalArgumentException.class, () -> SevenZipArkivoFileSystem.create(
                target,
                64L,
                Map.of(ArkivoFileSystem.OPEN_OPTIONS.key(), Set.of(StandardOpenOption.WRITE))
        ));

        Path archivePath = createTemporaryArchivePath("invalid-split-name-");
        try {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SevenZipArkivoFileSystem.open(archivePath, splitWriteEnvironment(64L, false))
            );
            assertEquals(false, Files.exists(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z entry channel and stream open options must remain read-only.
    @Test
    public void rejectsWritableEntryOpenOptions() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("writable-entry-options-");
        Files.write(archivePath, archiveWithCopyFile(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
                try (var input = Files.newInputStream(file, StandardOpenOption.READ)) {
                    assertArrayEquals(content, input.readAllBytes());
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

    /// Verifies that a declared folder CRC-32 mismatch is rejected while reading entry data.
    @Test
    public void rejectsMismatchedFolderCrc() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("bad-folder-crc-");
        Files.write(archivePath, archiveWithMismatchedFolderCrc(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z entry data does not match CRC-32"));

                IOException channelException = assertThrows(IOException.class, () -> {
                    try (SeekableByteChannel ignored = Files.newByteChannel(file)) {
                        // Closing an unread channel drains the entry and validates its CRC-32.
                    }
                });
                assertEquals(true, channelException.getMessage().contains("7z entry data does not match CRC-32"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a declared packed stream CRC-32 mismatch is rejected while reading entry data.
    @Test
    public void rejectsMismatchedPackCrc() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("bad-pack-crc-");
        Files.write(archivePath, archiveWithMismatchedPackCrc(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z packed stream data does not match CRC-32"));

                IOException channelException = assertThrows(IOException.class, () -> {
                    try (SeekableByteChannel ignored = Files.newByteChannel(file)) {
                        // Closing an unread channel drains the entry and validates its packed CRC-32.
                    }
                });
                assertEquals(
                        true,
                        channelException.getMessage().contains("7z packed stream data does not match CRC-32")
                );
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that `SubStreamsInfo` can omit a single substream digest when folder CRC-32 is defined.
    @Test
    public void singleSubStreamUsesFolderCrc() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("single-substream-folder-crc-");
        Files.write(archivePath, archiveWithSingleSubStreamFolderCrc(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that multiple file entries can share one Copy-method 7z folder output.
    @Test
    public void copySubStreamEntries() throws IOException {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two!".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[first.length + second.length];
        System.arraycopy(first, 0, content, 0, first.length);
        System.arraycopy(second, 0, content, first.length, second.length);
        Path archivePath = createTemporaryArchivePath("copy-substreams-");
        Files.write(archivePath, archiveWithCopySubStreams(content, first.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path firstFile = fileSystem.getPath("/one.txt");
                Path secondFile = fileSystem.getPath("/two.txt");
                ArrayList<String> rootChildren = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                assertEquals(List.of("/one.txt", "/two.txt"), rootChildren);
                assertArrayEquals(first, Files.readAllBytes(firstFile));
                assertArrayEquals(second, Files.readAllBytes(secondFile));
                try (var input = Files.newInputStream(secondFile)) {
                    assertArrayEquals(second, input.readAllBytes());
                }
                try (SeekableByteChannel channel = Files.newByteChannel(secondFile)) {
                    assertEquals(second.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(second.length);
                    assertEquals(second.length, channel.read(buffer));
                    assertArrayEquals(second, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a declared substream CRC-32 mismatch is rejected while reading entry data.
    @Test
    public void rejectsMismatchedSubStreamCrc() throws IOException {
        byte[] first = "one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "two!".getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[first.length + second.length];
        System.arraycopy(first, 0, content, 0, first.length);
        System.arraycopy(second, 0, content, first.length, second.length);
        Path archivePath = createTemporaryArchivePath("bad-substream-crc-");
        Files.write(archivePath, archiveWithMismatchedSubStreamCrc(content, first.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/two.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z entry data does not match CRC-32"));

                IOException channelException = assertThrows(IOException.class, () -> {
                    try (SeekableByteChannel ignored = Files.newByteChannel(file)) {
                        // Closing an unread channel drains the entry and validates its CRC-32.
                    }
                });
                assertEquals(true, channelException.getMessage().contains("7z entry data does not match CRC-32"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z folder with no file-addressable substreams is ignored safely.
    @Test
    public void folderWithNoSubStreams() throws IOException {
        Path archivePath = createTemporaryArchivePath("no-substreams-");
        Files.write(archivePath, archiveWithFolderWithoutSubStreams());

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path emptyFile = fileSystem.getPath("/empty.txt");
                BasicFileAttributes attributes = Files.readAttributes(emptyFile, BasicFileAttributes.class);
                ArrayList<String> rootChildren = new ArrayList<>();

                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        rootChildren.add(child.toString());
                    }
                }

                assertEquals(List.of("/empty.txt"), rootChildren);
                assertEquals(true, attributes.isRegularFile());
                assertEquals(0L, attributes.size());
                assertArrayEquals(new byte[0], Files.readAllBytes(emptyFile));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z entry paths ignore `.` and repeated separators.
    @Test
    public void normalizesEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("normalized-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileName("dir//./hello.txt"));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/dir/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                SevenZipArkivoEntryAttributes sevenZipAttributes =
                        Files.readAttributes(file, SevenZipArkivoEntryAttributes.class);

                assertEquals(0L, attributes.size());
                assertEquals("dir//./hello.txt", sevenZipAttributes.path());
                assertArrayEquals(new byte[0], Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that duplicate 7z entry paths are rejected.
    @Test
    public void rejectsDuplicateEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("duplicate-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileNames("dir/hello.txt", "dir/hello.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("Duplicate 7z entry path"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that duplicate normalized 7z entry paths are rejected.
    @Test
    public void rejectsDuplicateNormalizedEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("duplicate-normalized-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileNames("dir//hello.txt", "dir/./hello.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("Duplicate 7z entry path"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z entry paths cannot contain parent-directory segments.
    @Test
    public void rejectsParentSegmentEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("parent-segment-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileName("../evil.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z entry path must not contain .."));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z entry paths must be relative.
    @Test
    public void rejectsAbsoluteEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("absolute-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileName("/evil.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z entry path must be relative"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z entry paths cannot contain drive roots.
    @Test
    public void rejectsDriveRootEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("drive-root-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileName("C:/evil.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z entry path must be relative"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that backslash-separated parent-directory segments are rejected.
    @Test
    public void rejectsBackslashParentSegmentEntryName() throws IOException {
        Path archivePath = createTemporaryArchivePath("backslash-parent-segment-entry-name-");
        Files.write(archivePath, archiveWithEmptyFileName("..\\evil.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z entry path must not contain .."));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a regular 7z file entry cannot also be an indexed directory parent.
    @Test
    public void rejectsFileParentEntryNameConflict() throws IOException {
        Path archivePath = createTemporaryArchivePath("file-parent-entry-name-conflict-");
        Files.write(archivePath, archiveWithEmptyFileNames("dir", "dir/file.txt"));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z entry path conflicts with directory"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z anti items are treated as deletion markers and are not exposed as normal entries.
    @Test
    public void skipsAntiItemEntry() throws IOException {
        Path archivePath = createTemporaryArchivePath("anti-item-entry-");
        Files.write(archivePath, archiveWithAntiItemName("deleted.txt"));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                ArrayList<Path> children = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(fileSystem.getPath("/"))) {
                    for (Path child : stream) {
                        children.add(child);
                    }
                }

                assertEquals(List.of(), children);
                assertThrows(java.nio.file.NoSuchFileException.class, () -> Files.readAttributes(
                        fileSystem.getPath("/deleted.txt"),
                        BasicFileAttributes.class
                ));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that substream counts cannot appear after metadata that depends on those counts.
    @Test
    public void rejectsSubStreamCountsAfterDependentMetadata() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-substream-order-");
        Files.write(archivePath, archiveWithSubStreamCountsAfterDependentMetadata());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(
                    true,
                    exception.getMessage().contains("substream counts appeared after dependent substream metadata")
            );
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that oversized unsigned 7z counts are rejected as I/O errors.
    @Test
    public void rejectsOversizedFolderCount() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-folder-count-");
        Files.write(archivePath, archiveWithOversizedFolderCount());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("number is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that packed streams must declare their packed sizes.
    @Test
    public void rejectsMissingPackSizes() throws IOException {
        Path archivePath = createTemporaryArchivePath("missing-pack-sizes-");
        Files.write(archivePath, archiveWithMissingPackSizes());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("pack sizes are missing"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that owned volume sources are closed when 7z file system construction fails.
    @Test
    public void failedVolumeBackedOpenClosesVolumeSource() throws IOException {
        CloseFailingOwnedVolumeSource volumes = new CloseFailingOwnedVolumeSource(archiveWithMissingPackSizes());

        IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(volumes));

        assertEquals(true, exception.getMessage().contains("pack sizes are missing"));
        assertEquals(1, volumes.closeCount());
        assertEquals(true, hasSuppressedMessage(exception, "volume source close failed"));
    }

    /// Verifies that failed 7z parsing closes a seekable channel source and every channel opened from it.
    @Test
    public void failedSeekableChannelSourceOpenClosesSource() throws IOException {
        TestSeekableChannelSource source = new TestSeekableChannelSource(archiveWithMissingPackSizes());

        IOException exception = assertThrows(
                IOException.class,
                () -> SevenZipArkivoFileSystem.open(source, Map.of())
        );

        assertEquals(true, exception.getMessage().contains("pack sizes are missing"));
        assertEquals(true, source.openCount() > 0);
        assertEquals(true, source.allOpenedChannelsClosed());
        assertEquals(1, source.closeCount());
    }

    /// Verifies that close action failures do not mask owned volume source close failures.
    @Test
    public void closeActionFailureIsSuppressedWhenVolumeSourceCloseFails() throws IOException {
        CloseFailingOwnedVolumeSource volumes = new CloseFailingOwnedVolumeSource(minimalArchive());
        SevenZipArkivoFileSystemImpl fileSystem = new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                SevenZipArkivoFileSystemConfig.DEFAULTS,
                () -> {
                    throw new IllegalStateException("close action failed");
                }
        );

        IOException exception = assertThrows(IOException.class, fileSystem::close);

        assertEquals("volume source close failed", exception.getMessage());
        assertEquals(1, volumes.closeCount());
        assertEquals(true, hasSuppressedMessage(exception, "close action failed"));
    }

    /// Verifies that 7z file system close retries owned volume source cleanup after failure.
    @Test
    public void closeRetriesVolumeSourceCleanupAfterFailure() throws IOException {
        CloseFailingOwnedVolumeSource volumes = new CloseFailingOwnedVolumeSource(minimalArchive(), 1);
        int[] closeActionCount = new int[1];
        SevenZipArkivoFileSystemImpl fileSystem = new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                null,
                volumes,
                SevenZipArkivoFileSystemConfig.DEFAULTS,
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

    /// Verifies that packed stream and folder counts must match.
    @Test
    public void rejectsPackStreamFolderCountMismatch() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-pack-folder-count-");
        Files.write(archivePath, archiveWithPackStreamFolderCountMismatch());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("pack stream count does not match folder count"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that external file properties must reference declared additional streams.
    @Test
    public void rejectsMissingExternalFilePropertyStream() throws IOException {
        Path archivePath = createTemporaryArchivePath("missing-external-property-stream-");
        Files.write(archivePath, archiveWithMissingExternalFilePropertyStream());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("external file names reference a missing stream"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that overflowing packed stream offsets are rejected as I/O errors.
    @Test
    public void rejectsOverflowingPackPosition() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-pack-position-");
        Files.write(archivePath, archiveWithOverflowingPackPosition());

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("packed stream offset is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that additional stream metadata can be parsed and skipped.
    @Test
    public void additionalStreamsInfo() throws IOException {
        byte[] content = "main stream".getBytes(StandardCharsets.UTF_8);
        byte[] additional = "unused".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("additional-streams-");
        Files.write(archivePath, archiveWithAdditionalStreamsInfo(additional, content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z file time metadata is exposed through basic file attributes.
    @Test
    public void fileTimes() throws IOException {
        byte[] content = "metadata".getBytes(StandardCharsets.UTF_8);
        FileTime creationTime = FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
        FileTime lastAccessTime = FileTime.from(Instant.parse("2026-01-03T03:04:05Z"));
        FileTime lastModifiedTime = FileTime.from(Instant.parse("2026-01-04T03:04:05Z"));
        Path archivePath = createTemporaryArchivePath("file-times-");
        Files.write(archivePath, archiveWithCopyFile(
                content,
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                0x20
        ));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                PosixFileAttributes posixAttributes = Files.readAttributes(file, PosixFileAttributes.class);
                SevenZipArkivoEntryAttributes sevenZipAttributes =
                        Files.readAttributes(file, SevenZipArkivoEntryAttributes.class);
                FileOwnerAttributeView ownerView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, FileOwnerAttributeView.class));
                PosixFileAttributeView posixView =
                        Objects.requireNonNull(Files.getFileAttributeView(file, PosixFileAttributeView.class));
                SevenZipArkivoEntryAttributeView sevenZipView =
                        Objects.requireNonNull(Files.getFileAttributeView(
                                file,
                                SevenZipArkivoEntryAttributeView.class
                        ));
                Set<PosixFilePermission> expectedPermissions = Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ
                );
                Map<String, Object> namedAttributes = Files.readAttributes(
                        file,
                        "basic:creationTime,lastAccessTime,lastModifiedTime"
                );
                Map<String, Object> namedSevenZipAttributes = Files.readAttributes(
                        file,
                        "7z:path,windowsAttributes"
                );
                Map<String, Object> namedOwnerAttributes = Files.readAttributes(file, "owner:owner");
                Map<String, Object> namedPosixAttributes = Files.readAttributes(
                        file,
                        "posix:owner,group,permissions,isRegularFile"
                );

                assertEquals(creationTime, attributes.creationTime());
                assertEquals(lastAccessTime, attributes.lastAccessTime());
                assertEquals(lastModifiedTime, attributes.lastModifiedTime());
                assertEquals("owner", posixAttributes.owner().getName());
                assertEquals("group", posixAttributes.group().getName());
                assertEquals(expectedPermissions, posixAttributes.permissions());
                assertEquals("owner", ownerView.name());
                assertEquals("owner", ownerView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other"));
                assertEquals("posix", posixView.name());
                assertEquals(expectedPermissions, posixView.readAttributes().permissions());
                assertEquals("owner", posixView.getOwner().getName());
                assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(() -> "other"));
                assertThrows(
                        ReadOnlyFileSystemException.class,
                        () -> posixView.setPermissions(Set.of(PosixFilePermission.OWNER_READ))
                );
                assertEquals("hello.txt", sevenZipAttributes.path());
                assertEquals(0x20, sevenZipAttributes.windowsAttributes());
                assertEquals("7z", sevenZipView.name());
                assertEquals("hello.txt", sevenZipView.readAttributes().path());
                assertEquals(0x20, sevenZipView.readAttributes().windowsAttributes());
                assertThrows(
                        ReadOnlyFileSystemException.class,
                        () -> sevenZipView.setTimes(lastModifiedTime, null, null)
                );
                assertEquals(
                        true,
                        Files.getFileStore(file).supportsFileAttributeView(SevenZipArkivoEntryAttributeView.class)
                );
                assertEquals(true, Files.getFileStore(file).supportsFileAttributeView(FileOwnerAttributeView.class));
                assertEquals(true, Files.getFileStore(file).supportsFileAttributeView(PosixFileAttributeView.class));
                assertEquals(true, Files.getFileStore(file).supportsFileAttributeView("owner"));
                assertEquals(true, Files.getFileStore(file).supportsFileAttributeView("posix"));
                assertEquals(true, Files.getFileStore(file).supportsFileAttributeView("7z"));
                assertEquals(creationTime, namedAttributes.get("creationTime"));
                assertEquals(lastAccessTime, namedAttributes.get("lastAccessTime"));
                assertEquals(lastModifiedTime, namedAttributes.get("lastModifiedTime"));
                assertEquals(false, namedAttributes.containsKey("size"));
                assertEquals("hello.txt", namedSevenZipAttributes.get("path"));
                assertEquals(0x20, namedSevenZipAttributes.get("windowsAttributes"));
                assertEquals(false, namedSevenZipAttributes.containsKey("size"));
                assertEquals(false, namedSevenZipAttributes.containsKey("lastModifiedTime"));
                assertEquals("owner", ((UserPrincipal) namedOwnerAttributes.get("owner")).getName());
                assertEquals("owner", ((UserPrincipal) namedPosixAttributes.get("owner")).getName());
                assertEquals("group", ((GroupPrincipal) namedPosixAttributes.get("group")).getName());
                assertEquals(expectedPermissions, namedPosixAttributes.get("permissions"));
                assertEquals(true, namedPosixAttributes.get("isRegularFile"));

                Map<String, Object> rootAttributes = Files.readAttributes(
                        fileSystem.getPath("/"),
                        "basic:size,isDirectory"
                );
                assertEquals(0L, rootAttributes.get("size"));
                assertEquals(true, rootAttributes.get("isDirectory"));
                assertEquals(false, rootAttributes.containsKey("isRegularFile"));
                assertNull(Files.getFileAttributeView(
                        fileSystem.getPath("/"),
                        SevenZipArkivoEntryAttributeView.class
                ));
                assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:size"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z Unix mode metadata exposes symbolic links and POSIX permissions.
    @Test
    public void unixModeSymbolicLinkAttributes() throws IOException {
        byte[] target = "dir/hello.txt".getBytes(StandardCharsets.UTF_8);
        int unixMode = 0120777;
        int windowsAttributes = unixMode << 16;
        Path archivePath = createTemporaryArchivePath("unix-mode-link-");
        Files.write(archivePath, archiveWithCopyFile(target, null, null, null, windowsAttributes));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path link = fileSystem.getPath("/hello.txt");
                BasicFileAttributes basicAttributes = Files.readAttributes(link, BasicFileAttributes.class);
                PosixFileAttributes posixAttributes = Files.readAttributes(link, PosixFileAttributes.class);
                SevenZipArkivoEntryAttributes sevenZipAttributes =
                        Files.readAttributes(link, SevenZipArkivoEntryAttributes.class);
                Map<String, Object> namedSevenZipAttributes =
                        Files.readAttributes(link, "7z:path,windowsAttributes,unixMode,isSymbolicLink");

                assertEquals(false, basicAttributes.isRegularFile());
                assertEquals(false, basicAttributes.isDirectory());
                assertEquals(true, basicAttributes.isSymbolicLink());
                assertEquals(false, basicAttributes.isOther());
                assertEquals(target.length, basicAttributes.size());
                assertEquals(unixMode, sevenZipAttributes.unixMode());
                assertEquals(windowsAttributes, sevenZipAttributes.windowsAttributes());
                assertEquals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.GROUP_WRITE,
                        PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ,
                        PosixFilePermission.OTHERS_WRITE,
                        PosixFilePermission.OTHERS_EXECUTE
                ), posixAttributes.permissions());
                assertEquals(fileSystem.getPath("dir/hello.txt"), Files.readSymbolicLink(link));
                assertEquals("dir/hello.txt", Files.readString(link, StandardCharsets.UTF_8));
                assertEquals("hello.txt", namedSevenZipAttributes.get("path"));
                assertEquals(windowsAttributes, namedSevenZipAttributes.get("windowsAttributes"));
                assertEquals(unixMode, namedSevenZipAttributes.get("unixMode"));
                assertEquals(true, namedSevenZipAttributes.get("isSymbolicLink"));
                assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(fileSystem.getPath("/")));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that 7z file properties stored in additional streams are exposed.
    @Test
    public void externalFileProperties() throws IOException {
        byte[] content = "external metadata".getBytes(StandardCharsets.UTF_8);
        FileTime lastModifiedTime = FileTime.from(Instant.parse("2026-02-03T04:05:06Z"));
        Path archivePath = createTemporaryArchivePath("external-file-properties-");
        Files.write(archivePath, archiveWithExternalFileProperties(content, lastModifiedTime, 0x20));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/external.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
                SevenZipArkivoEntryAttributes sevenZipAttributes =
                        Files.readAttributes(file, SevenZipArkivoEntryAttributes.class);

                assertEquals(content.length, attributes.size());
                assertEquals(lastModifiedTime, attributes.lastModifiedTime());
                assertEquals("external.txt", sevenZipAttributes.path());
                assertEquals(0x20, sevenZipAttributes.windowsAttributes());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that FILETIME metadata is interpreted as an unsigned 64-bit tick value.
    @Test
    public void readsUnsignedFileTimeMetadata() throws IOException {
        byte[] content = "unsigned file time".getBytes(StandardCharsets.UTF_8);
        long windowsTicks = -1L;
        FileTime expectedTime = fileTimeFromUnsignedWindowsTicks(windowsTicks);
        Path archivePath = createTemporaryArchivePath("unsigned-file-time-");
        Files.write(archivePath, archiveWithExternalFileProperties(content, windowsTicks, 0x20));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/external.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(expectedTime, attributes.lastModifiedTime());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that folder definitions stored in additional streams can describe main streams.
    @Test
    public void externalFolderDefinitions() throws IOException {
        byte[] content = "external folder".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("external-folder-definitions-");
        Files.write(archivePath, archiveWithExternalFolderDefinitions(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/external-folder.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z LZMA method can be read.
    @Test
    public void lzmaFileEntry() throws IOException {
        byte[] content = "hello lzma".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = lzmaPayload(content);
        Path archivePath = createTemporaryArchivePath("lzma-file-");
        Files.write(archivePath, archiveWithLZMAFile(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a failed 7z decoder setup closes the opened archive stream.
    @Test
    public void failedLzmaDecoderSetupClosesArchiveStream() throws IOException {
        byte[] content = "bad lzma".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("bad-lzma-properties-");
        Files.write(archivePath, archiveWithInvalidLZMAProperties(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z LZMA coder properties must contain five bytes"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that decoded stream setup keeps the primary skip failure when cleanup close fails.
    @Test
    public void failedDecodedOffsetSkipSuppressesArchiveCloseFailure() throws IOException {
        byte[] content = "short lzma2".getBytes(StandardCharsets.UTF_8);
        int firstSize = content.length + 1;
        byte[] archive = archiveWithTruncatedLZMA2SubStreams(content, firstSize, firstSize + 1);

        try (SevenZipArkivoFileSystem fileSystem =
                     SevenZipArkivoFileSystem.open(new CloseFailingVolumeSource(archive, 2))) {
            Path file = fileSystem.getPath("/two.txt");

            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
            assertEquals(false, "close failed".equals(exception.getMessage()));
            assertEquals(true, hasSuppressedMessage(exception, "close failed"));
        }
    }

    /// Verifies that runtime decoded stream cleanup failures do not replace the primary skip failure.
    @Test
    public void failedDecodedOffsetSkipSuppressesArchiveRuntimeCloseFailure() throws IOException {
        byte[] content = "short runtime lzma2".getBytes(StandardCharsets.UTF_8);
        int firstSize = content.length + 1;
        byte[] archive = archiveWithTruncatedLZMA2SubStreams(content, firstSize, firstSize + 1);

        try (SevenZipArkivoFileSystem fileSystem =
                     SevenZipArkivoFileSystem.open(new CloseFailingVolumeSource(archive, 2, true))) {
            Path file = fileSystem.getPath("/two.txt");

            IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
            assertEquals(false, "close failed".equals(exception.getMessage()));
            assertEquals(true, hasSuppressedMessage(exception, "close failed"));
        }
    }

    /// Verifies that a file stored in a linear multi-coder folder can be read.
    @Test
    public void linearMultiCoderFileEntry() throws IOException {
        byte[] content = "hello lzma copy pipeline".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = lzmaPayload(content);
        Path archivePath = createTemporaryArchivePath("linear-multi-coder-file-");
        Files.write(archivePath, archiveWithLZMAMultiCoderFile(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/multi-coder.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z LZMA2 method can be read.
    @Test
    public void lzma2FileEntry() throws IOException {
        byte[] content = "hello lzma2".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = lzma2Payload(content);
        Path archivePath = createTemporaryArchivePath("lzma2-file-");
        Files.write(archivePath, archiveWithLZMA2File(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z Deflate method can be read.
    @Test
    public void deflateFileEntry() throws IOException {
        byte[] content = "hello deflate".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = deflatePayload(content);
        Path archivePath = createTemporaryArchivePath("deflate-file-");
        Files.write(archivePath, archiveWithDeflateFile(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z Deflate64 method can be read.
    @Test
    public void deflate64FileEntry() throws IOException {
        byte[] content = "hello deflate64".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = deflate64StoredPayload(content);
        Path archivePath = createTemporaryArchivePath("deflate64-file-");
        Files.write(archivePath, archiveWithDeflate64File(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z BZip2 method can be read.
    @Test
    public void bzip2FileEntry() throws IOException {
        byte[] content = "hello bzip2".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = bzip2Payload(content);
        Path archivePath = createTemporaryArchivePath("bzip2-file-");
        Files.write(archivePath, archiveWithBZip2File(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty file stored with the 7z AES method can be read with a password.
    @Test
    public void aesFileEntry() throws IOException {
        byte[] password = sevenZipAesPassword();
        byte[] content = "hello aes encrypted file".getBytes(StandardCharsets.UTF_8);
        CoderPayload payload = aesPayload(content, password);
        Path archivePath = createTemporaryArchivePath("aes-file-");
        Files.write(archivePath, archiveWithAesFile(payload, content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z AES encrypted data requires a password"));
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(password)
                    )
            )) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed("wrong".getBytes(StandardCharsets.UTF_16LE))
                    )
            )) {
                Path file = fileSystem.getPath("/hello.txt");

                IOException exception = assertThrows(IOException.class, () -> Files.readAllBytes(file));
                assertEquals(true, exception.getMessage().contains("7z entry data does not match CRC-32"));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a file stored with a Delta-to-LZMA2 7z coder pipeline can be read.
    @Test
    public void deltaLzma2FileEntry() throws IOException {
        byte[] content = "abcabcabc-delta-filtered-content".getBytes(StandardCharsets.UTF_8);
        int deltaDistance = 3;
        CoderPayload payload = deltaLzma2Payload(content, deltaDistance);
        Path archivePath = createTemporaryArchivePath("delta-lzma2-file-");
        Files.write(archivePath, archiveWithDeltaLZMA2File(payload, content.length, deltaDistance));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/delta-lzma2.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a file stored with an x86 BCJ-to-LZMA2 7z coder pipeline can be read.
    @Test
    public void x86BcjLzma2FileEntry() throws IOException {
        byte[] content = new byte[]{
                0x55, (byte) 0x8b, (byte) 0xec,
                (byte) 0xe8, 0x01, 0x00, 0x00, 0x00,
                (byte) 0xe9, (byte) 0xf4, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                0x5d, (byte) 0xc3
        };
        CoderPayload payload = x86BcjLzma2Payload(content);
        Path archivePath = createTemporaryArchivePath("x86-bcj-lzma2-file-");
        Files.write(archivePath, archiveWithX86BcjLZMA2File(payload, content.length));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/x86-bcj-lzma2.bin");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that additional 7z BCJ filter pipelines can be read.
    @Test
    public void additionalBcjLzma2FileEntries() throws IOException {
        assertBcjLzma2FileEntry(
                "powerpc-bcj-lzma2.bin",
                new byte[]{0x48, 0x00, 0x00, 0x01, 0x4e, (byte) 0x80, 0x00, 0x20},
                new PowerPCOptions(),
                new byte[]{0x03, 0x03, 0x02, 0x05}
        );
        assertBcjLzma2FileEntry(
                "ia64-bcj-lzma2.bin",
                "ia64 branch conversion sample".getBytes(StandardCharsets.UTF_8),
                new IA64Options(),
                new byte[]{0x03, 0x03, 0x04, 0x01}
        );
        assertBcjLzma2FileEntry(
                "arm-bcj-lzma2.bin",
                new byte[]{0x00, 0x00, 0x00, (byte) 0xeb, 0x04, 0x00, 0x00, (byte) 0xeb},
                new ARMOptions(),
                new byte[]{0x03, 0x03, 0x05, 0x01}
        );
        assertBcjLzma2FileEntry(
                "arm-thumb-bcj-lzma2.bin",
                new byte[]{0x00, (byte) 0xf0, 0x00, (byte) 0xf8, 0x02, (byte) 0xf0, 0x04, (byte) 0xf8},
                new ARMThumbOptions(),
                new byte[]{0x03, 0x03, 0x07, 0x01}
        );
        assertBcjLzma2FileEntry(
                "sparc-bcj-lzma2.bin",
                new byte[]{0x40, 0x00, 0x00, 0x00, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff},
                new SPARCOptions(),
                new byte[]{0x03, 0x03, 0x08, 0x05}
        );
    }

    /// Verifies a BCJ-to-LZMA2 archive round trip for one file.
    private static void assertBcjLzma2FileEntry(
            String fileName,
            byte[] content,
            FilterOptions bcjOptions,
            byte[] bcjMethodId
    ) throws IOException {
        CoderPayload payload = bcjLzma2Payload(content, bcjOptions);
        Path archivePath = createTemporaryArchivePath(fileName + "-");
        Files.write(archivePath, archiveWithBcjLZMA2File(fileName, payload, content.length, bcjMethodId));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/" + fileName);
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
                try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                    assertEquals(content.length, channel.size());
                    ByteBuffer buffer = ByteBuffer.allocate(content.length);
                    assertEquals(content.length, channel.read(buffer));
                    assertArrayEquals(content, buffer.array());
                }
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z archive with an LZMA2-compressed header can be opened.
    @Test
    public void encodedHeader() throws IOException {
        byte[] content = "encoded header body".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("encoded-header-");
        Files.write(archivePath, archiveWithEncodedHeader(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z archive with an AES-encrypted LZMA2-compressed header can be opened with a password.
    @Test
    public void aesLzma2EncodedHeader() throws IOException {
        byte[] password = sevenZipAesPassword();
        byte[] content = "aes encoded header body".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("aes-encoded-header-");
        Files.write(archivePath, archiveWithAesLzma2EncodedHeader(content, password));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("7z AES encrypted data requires a password"));

            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                    archivePath,
                    Map.of(
                            SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                            ArkivoPasswordProvider.fixed(password)
                    )
            )) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a declared encoded-header packed stream CRC-32 mismatch is rejected while opening the archive.
    @Test
    public void rejectsMismatchedEncodedHeaderPackCrc() throws IOException {
        byte[] content = "bad encoded header pack crc".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("bad-encoded-header-pack-crc-");
        Files.write(archivePath, archiveWithEncodedHeaderAndMismatchedPackCrc(content));

        try {
            IOException exception = assertThrows(IOException.class, () -> {
                try (SevenZipArkivoFileSystem ignored = SevenZipArkivoFileSystem.open(archivePath)) {
                    // Opening the archive parses the encoded header and validates its packed CRC-32.
                }
            });
            assertEquals(true, exception.getMessage().contains("7z packed stream data does not match CRC-32"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z archive with a multi-substream encoded header can be opened.
    @Test
    public void encodedHeaderSubStreams() throws IOException {
        byte[] content = "encoded header substream body".getBytes(StandardCharsets.UTF_8);
        Path archivePath = createTemporaryArchivePath("encoded-header-substreams-");
        Files.write(archivePath, archiveWithEncodedHeaderSubStreams(content));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                Path file = fileSystem.getPath("/hello.txt");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);

                assertEquals(content.length, attributes.size());
                assertArrayEquals(content, Files.readAllBytes(file));
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a 7z file system can be opened and resolved through provider URIs.
    @Test
    public void openUri() throws IOException {
        Path archivePath = createMinimalArchive().toAbsolutePath().normalize();
        SevenZipArkivoFileSystemProvider provider = new SevenZipArkivoFileSystemProvider();
        URI fileSystemUri = URI.create(SevenZipArkivoFileSystemProvider.SCHEME + ":" + archivePath.toUri());
        URI rootUri = URI.create(fileSystemUri + "!/");

        try {
            try (ArkivoFileSystem fileSystem = provider.newFileSystem(fileSystemUri, Map.of())) {
                assertEquals(fileSystem, provider.getFileSystem(fileSystemUri));
                assertEquals("/", provider.getPath(rootUri).toString());
                assertEquals(rootUri, fileSystem.getPath("/").toUri());
                assertThrows(
                        FileSystemAlreadyExistsException.class,
                        () -> provider.newFileSystem(fileSystemUri, Map.of())
                );
            }
            assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(fileSystemUri));
        } finally {
            try {
                provider.getFileSystem(fileSystemUri).close();
            } catch (FileSystemNotFoundException ignored) {
                // The test closes the file system in the normal path.
            }
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid 7z signatures are rejected.
    @Test
    public void invalidSignature() throws IOException {
        Path archivePath = createTemporaryArchivePath("invalid-");
        Files.write(archivePath, new byte[32]);

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that a non-empty next header CRC is validated.
    @Test
    public void nonEmptyNextHeader() throws IOException {
        byte[] nextHeader = new byte[]{0};
        Path archivePath = createTemporaryArchivePath("next-header-");
        Files.write(archivePath, archive(nextHeader, crc32(nextHeader)));

        try {
            try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(archivePath)) {
                assertEquals(0L, fileSystem.nextHeaderOffset());
                assertEquals(1L, fileSystem.nextHeaderSize());
                assertEquals(crc32(nextHeader), fileSystem.nextHeaderCrc32());
            }
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that invalid next header CRC values are rejected.
    @Test
    public void invalidNextHeaderCrc() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-next-header-");
        Files.write(archivePath, archive(new byte[]{0}, 1L));

        try {
            assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that oversized unsigned next-header offsets are rejected as I/O errors.
    @Test
    public void rejectsOversizedNextHeaderOffset() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-next-header-offset-");
        Files.write(archivePath, archiveWithRawStartHeader(Long.MIN_VALUE, 0L, 0L));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("next header offset is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Verifies that oversized unsigned next-header sizes are rejected as I/O errors.
    @Test
    public void rejectsOversizedNextHeaderSize() throws IOException {
        Path archivePath = createTemporaryArchivePath("bad-next-header-size-");
        Files.write(archivePath, archiveWithRawStartHeader(0L, Long.MIN_VALUE, 0L));

        try {
            IOException exception = assertThrows(IOException.class, () -> SevenZipArkivoFileSystem.open(archivePath));
            assertEquals(true, exception.getMessage().contains("next header size is too large"));
        } finally {
            deleteTemporaryArchive(archivePath);
        }
    }

    /// Creates a temporary minimal 7z archive under the module build directory.
    private static Path createMinimalArchive() throws IOException {
        Path archivePath = createTemporaryArchivePath("minimal-");
        Files.write(archivePath, minimalArchive());
        return archivePath;
    }

    /// Creates a temporary archive path under the module build directory.
    private static Path createTemporaryArchivePath(String prefix) throws IOException {
        Path temporaryRoot = Path.of("build", "tmp", "arkivo-7z-tests");
        Files.createDirectories(temporaryRoot);
        Path temporaryDirectory = Files.createTempDirectory(temporaryRoot, prefix);
        return temporaryDirectory.resolve("sample.7z");
    }

    /// Deletes a temporary archive file and its containing directory.
    private static void deleteTemporaryArchive(Path archivePath) throws IOException {
        Files.deleteIfExists(archivePath);
        Files.deleteIfExists(archivePath.getParent());
    }

    /// Returns a writable 7z environment with the requested path-backed split size.
    private static Map<String, Object> splitWriteEnvironment(long splitSize, boolean createNew) {
        Set<StandardOpenOption> openOptions = createNew
                ? Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                : Set.of(
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE
                );
        return Map.of(
                ArkivoFileSystem.OPEN_OPTIONS.key(), openOptions,
                SevenZipArkivoFileSystem.SPLIT_SIZE.key(), splitSize
        );
    }

    /// Returns one conventional numbered test volume path.
    private static Path testVolumePath(Path firstVolumePath, int volumeNumber) {
        if (volumeNumber <= 0) {
            throw new IllegalArgumentException("volumeNumber must be positive");
        }
        String fileName = firstVolumePath.getFileName().toString();
        int suffixStart = fileName.lastIndexOf('.') + 1;
        int suffixWidth = fileName.length() - suffixStart;
        String volumeText = Integer.toString(volumeNumber);
        StringBuilder builder = new StringBuilder(fileName.substring(0, suffixStart));
        for (int index = volumeText.length(); index < suffixWidth; index++) {
            builder.append('0');
        }
        return firstVolumePath.resolveSibling(builder.append(volumeText).toString());
    }

    /// Returns the contiguous conventional test volumes that currently exist.
    private static @Unmodifiable List<Path> existingTestVolumePaths(Path firstVolumePath) {
        ArrayList<Path> paths = new ArrayList<>();
        for (int volumeNumber = 1; ; volumeNumber++) {
            Path path = testVolumePath(firstVolumePath, volumeNumber);
            if (!Files.exists(path)) {
                return List.copyOf(paths);
            }
            paths.add(path);
        }
    }

    /// Deletes every file in a dedicated temporary archive directory and then the directory itself.
    private static void deleteTemporaryArchiveDirectory(Path archivePath) throws IOException {
        Path directory = Objects.requireNonNull(archivePath.getParent(), "archive directory");
        if (!Files.exists(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                Files.deleteIfExists(child);
            }
        }
        Files.deleteIfExists(directory);
    }

    /// Returns a minimal 7z archive with an empty next header.
    private static byte[] minimalArchive() {
        return archive(new byte[0], 0L);
    }

    /// Returns a 7z archive with one empty directory and one empty file.
    private static byte[] archiveWithEmptyEntries() throws IOException {
        byte[] header = emptyEntriesHeader();
        return archive(header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the Copy method.
    private static byte[] archiveWithCopyFile(byte[] content) throws IOException {
        byte[] header = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and dummy properties in supported header scopes.
    private static byte[] archiveWithDummyProperties(byte[] content) throws IOException {
        byte[] header = dummyPropertiesHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and top-level archive properties.
    private static byte[] archiveWithArchiveProperties(byte[] content) throws IOException {
        byte[] header = withArchiveProperties(fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]));
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and an incorrect folder CRC-32.
    private static byte[] archiveWithMismatchedFolderCrc(byte[] content) throws IOException {
        long wrongCrc32 = crc32(content) ^ 1L;
        byte[] header = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0], wrongCrc32);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and an incorrect packed stream CRC-32.
    private static byte[] archiveWithMismatchedPackCrc(byte[] content) throws IOException {
        long wrongCrc32 = crc32(content) ^ 1L;
        byte[] header = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0], -1L, wrongCrc32);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file, folder CRC-32, and empty substream CRC-32 vector.
    private static byte[] archiveWithSingleSubStreamFolderCrc(byte[] content) throws IOException {
        byte[] header = fileHeaderWithSingleSubStreamCrcVector(
                content.length,
                content.length,
                new byte[]{0x00},
                new byte[0],
                crc32(content)
        );
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with two files stored as substreams of one Copy-method folder.
    private static byte[] archiveWithCopySubStreams(byte[] content, int firstSize) throws IOException {
        byte[] header = copySubStreamsHeader(firstSize, content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive whose LZMA2 folder declares more decoded bytes than the payload provides.
    private static byte[] archiveWithTruncatedLZMA2SubStreams(
            byte[] content,
            int firstSize,
            int declaredTotalSize
    ) throws IOException {
        CoderPayload payload = lzma2Payload(content);
        byte[] header = lzma2SubStreamsHeader(
                payload.content().length,
                declaredTotalSize,
                firstSize,
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with two Copy-method substreams and an incorrect second substream CRC-32.
    private static byte[] archiveWithMismatchedSubStreamCrc(byte[] content, int firstSize) throws IOException {
        int secondSize = content.length - firstSize;
        long firstCrc32 = crc32(content, 0, firstSize);
        long wrongSecondCrc32 = crc32(content, firstSize, secondSize) ^ 1L;
        byte[] header = copySubStreamsHeader(firstSize, content.length, firstCrc32, wrongSecondCrc32);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with an unused folder that has no substreams and one empty file entry.
    private static byte[] archiveWithFolderWithoutSubStreams() throws IOException {
        byte[] header = folderWithoutSubStreamsHeader();
        return archive(new byte[0], header, crc32(header));
    }

    /// Returns a 7z archive with one empty file using the given name.
    private static byte[] archiveWithEmptyFileName(String name) throws IOException {
        byte[] header = emptyFileNameHeader(name);
        return archive(header, crc32(header));
    }

    /// Returns a 7z archive with two empty files using the given names.
    private static byte[] archiveWithEmptyFileNames(String firstName, String secondName) throws IOException {
        byte[] header = emptyFileNamesHeader(firstName, secondName);
        return archive(header, crc32(header));
    }

    /// Returns a 7z archive with one anti item using the given name.
    private static byte[] archiveWithAntiItemName(String name) throws IOException {
        byte[] header = antiItemNameHeader(name);
        return archive(header, crc32(header));
    }

    /// Returns a malformed 7z archive with late substream counts.
    private static byte[] archiveWithSubStreamCountsAfterDependentMetadata() throws IOException {
        byte[] content = "xy".getBytes(StandardCharsets.UTF_8);
        byte[] header = subStreamCountsAfterDependentMetadataHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a malformed 7z archive with an unsupported unsigned folder count.
    private static byte[] archiveWithOversizedFolderCount() throws IOException {
        byte[] header = oversizedFolderCountHeader();
        return archive(header, crc32(header));
    }

    /// Returns a malformed 7z archive whose PackInfo omits packed stream sizes.
    private static byte[] archiveWithMissingPackSizes() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        byte[] header = missingPackSizesHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a malformed 7z archive whose PackInfo stream count does not match the folder count.
    private static byte[] archiveWithPackStreamFolderCountMismatch() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        byte[] header = packStreamFolderCountMismatchHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a malformed 7z archive whose external file property references a missing additional stream.
    private static byte[] archiveWithMissingExternalFilePropertyStream() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        byte[] header = missingExternalFilePropertyStreamHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a malformed 7z archive whose pack position overflows an absolute archive offset.
    private static byte[] archiveWithOverflowingPackPosition() throws IOException {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        byte[] header = overflowingPackPositionHeader(content.length);
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with an unused additional stream before the main file stream.
    private static byte[] archiveWithAdditionalStreamsInfo(byte[] additional, byte[] content) throws IOException {
        byte[] packedData = concatenate(additional, content);
        byte[] header = additionalStreamsInfoHeader(additional.length, content.length);
        return archive(packedData, header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and metadata.
    private static byte[] archiveWithCopyFile(
            byte[] content,
            FileTime creationTime,
            FileTime lastAccessTime,
            FileTime lastModifiedTime,
            int windowsAttributes
    ) throws IOException {
        byte[] header = fileHeader(
                content.length,
                content.length,
                new byte[]{0x00},
                new byte[0],
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with file properties stored in additional streams.
    private static byte[] archiveWithExternalFileProperties(
            byte[] content,
            FileTime lastModifiedTime,
            int windowsAttributes
    ) throws IOException {
        return archiveWithExternalFileProperties(content, windowsTicks(lastModifiedTime), windowsAttributes);
    }

    /// Returns a 7z archive with raw file properties stored in additional streams.
    private static byte[] archiveWithExternalFileProperties(
            byte[] content,
            long lastModifiedWindowsTicks,
            int windowsAttributes
    ) throws IOException {
        byte[] names = namesPayload("external.txt");
        ByteArrayOutputStream times = new ByteArrayOutputStream();
        writeLongLE(times, lastModifiedWindowsTicks);
        ByteArrayOutputStream attributes = new ByteArrayOutputStream();
        writeIntLE(attributes, windowsAttributes);

        byte[] timesBytes = times.toByteArray();
        byte[] attributesBytes = attributes.toByteArray();
        byte[] packedData = concatenateAll(names, timesBytes, attributesBytes, content);
        byte[] header = externalFilePropertiesHeader(
                names.length,
                timesBytes.length,
                attributesBytes.length,
                content.length
        );
        return archive(packedData, header, crc32(header));
    }

    /// Returns a 7z archive whose main stream folder definition is stored externally.
    private static byte[] archiveWithExternalFolderDefinitions(byte[] content) throws IOException {
        byte[] folder = copyFolderPayload();
        byte[] packedData = concatenate(folder, content);
        byte[] header = externalFolderDefinitionsHeader(folder.length, content.length);
        return archive(packedData, header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the LZMA method.
    private static byte[] archiveWithLZMAFile(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x03, 0x01, 0x01},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one LZMA file whose coder properties are invalid.
    private static byte[] archiveWithInvalidLZMAProperties(byte[] content) throws IOException {
        byte[] header = fileHeader(
                content.length,
                content.length,
                new byte[]{0x03, 0x01, 0x01},
                new byte[0]
        );
        return archive(content, header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through an LZMA-to-Copy coder pipeline.
    private static byte[] archiveWithLZMAMultiCoderFile(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = lzmaCopyPipelineFileHeader(payload.content().length, uncompressedSize, payload.properties());
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the LZMA2 method.
    private static byte[] archiveWithLZMA2File(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x21},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the Deflate method.
    private static byte[] archiveWithDeflateFile(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x04, 0x01, 0x08},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the Deflate64 method.
    private static byte[] archiveWithDeflate64File(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x04, 0x01, 0x09},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the BZip2 method.
    private static byte[] archiveWithBZip2File(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                uncompressedSize,
                new byte[]{0x04, 0x02, 0x02},
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through the AES method.
    private static byte[] archiveWithAesFile(CoderPayload payload, byte[] content) throws IOException {
        byte[] header = fileHeader(
                payload.content().length,
                content.length,
                sevenZipAesMethodId(),
                payload.properties(),
                crc32(content)
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through a Delta-to-LZMA2 coder pipeline.
    private static byte[] archiveWithDeltaLZMA2File(
            CoderPayload payload,
            int uncompressedSize,
            int deltaDistance
    ) throws IOException {
        byte[] header = lzma2DeltaPipelineFileHeader(
                payload.content().length,
                uncompressedSize,
                payload.properties(),
                deltaDistance
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through an x86 BCJ-to-LZMA2 coder pipeline.
    private static byte[] archiveWithX86BcjLZMA2File(CoderPayload payload, int uncompressedSize) throws IOException {
        byte[] header = lzma2X86BcjPipelineFileHeader(
                payload.content().length,
                uncompressedSize,
                payload.properties()
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one file stored through a BCJ-to-LZMA2 coder pipeline.
    private static byte[] archiveWithBcjLZMA2File(
            String fileName,
            CoderPayload payload,
            int uncompressedSize,
            byte[] bcjMethodId
    ) throws IOException {
        byte[] header = lzma2BcjPipelineFileHeader(
                payload.content().length,
                uncompressedSize,
                payload.properties(),
                bcjMethodId,
                new byte[0],
                fileName
        );
        return archive(payload.content(), header, crc32(header));
    }

    /// Returns a 7z archive with one Copy-method file and an LZMA2-compressed header.
    private static byte[] archiveWithEncodedHeader(byte[] content) throws IOException {
        byte[] plainHeader = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        CoderPayload encodedHeaderPayload = lzma2Payload(plainHeader);
        byte[] nextHeader = encodedHeader(
                content.length,
                encodedHeaderPayload.content().length,
                plainHeader.length,
                new byte[]{0x21},
                encodedHeaderPayload.properties()
        );
        return archive(concatenate(content, encodedHeaderPayload.content()), nextHeader, crc32(nextHeader));
    }

    /// Returns a 7z archive with one Copy-method file and an AES-encrypted LZMA2-compressed header.
    private static byte[] archiveWithAesLzma2EncodedHeader(byte[] content, byte[] password) throws IOException {
        byte[] plainHeader = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        CoderPayload lzma2HeaderPayload = lzma2Payload(plainHeader);
        CoderPayload encryptedHeaderPayload = aesPayload(lzma2HeaderPayload.content(), password);
        byte[] nextHeader = aesLzma2EncodedHeader(
                content.length,
                encryptedHeaderPayload.content().length,
                lzma2HeaderPayload.content().length,
                plainHeader.length,
                encryptedHeaderPayload.properties(),
                lzma2HeaderPayload.properties()
        );
        return archive(concatenate(content, encryptedHeaderPayload.content()), nextHeader, crc32(nextHeader));
    }

    /// Returns a 7z archive with one Copy-method file and an LZMA2-compressed header with an incorrect pack CRC-32.
    private static byte[] archiveWithEncodedHeaderAndMismatchedPackCrc(byte[] content) throws IOException {
        byte[] plainHeader = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        CoderPayload encodedHeaderPayload = lzma2Payload(plainHeader);
        long wrongPackCrc32 = crc32(encodedHeaderPayload.content()) ^ 1L;
        byte[] nextHeader = encodedHeader(
                content.length,
                encodedHeaderPayload.content().length,
                plainHeader.length,
                new byte[]{0x21},
                encodedHeaderPayload.properties(),
                wrongPackCrc32
        );
        return archive(concatenate(content, encodedHeaderPayload.content()), nextHeader, crc32(nextHeader));
    }

    /// Returns a 7z archive whose encoded header is split across two LZMA2 substreams.
    private static byte[] archiveWithEncodedHeaderSubStreams(byte[] content) throws IOException {
        byte[] plainHeader = fileHeader(content.length, content.length, new byte[]{0x00}, new byte[0]);
        CoderPayload encodedHeaderPayload = lzma2Payload(plainHeader);
        byte[] nextHeader = encodedHeaderSubStreams(
                content.length,
                encodedHeaderPayload.content().length,
                plainHeader.length,
                plainHeader.length / 2,
                new byte[]{0x21},
                encodedHeaderPayload.properties()
        );
        return archive(concatenate(content, encodedHeaderPayload.content()), nextHeader, crc32(nextHeader));
    }

    /// Returns a 7z archive with the given next header and expected next header CRC-32.
    private static byte[] archive(byte[] nextHeader, long nextHeaderCrc32) {
        return archive(new byte[0], nextHeader, nextHeaderCrc32);
    }

    /// Returns a 7z archive containing only a fixed start header with raw next-header fields.
    private static byte[] archiveWithRawStartHeader(long nextHeaderOffset, long nextHeaderSize, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(nextHeaderOffset);
        buffer.putLong(nextHeaderSize);
        buffer.putInt((int) nextHeaderCrc32);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns a 7z archive with packed data followed by the given next header.
    private static byte[] archive(byte[] packedData, byte[] nextHeader, long nextHeaderCrc32) {
        ByteBuffer buffer = ByteBuffer.allocate(32 + packedData.length + nextHeader.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put(new byte[]{'7', 'z', (byte) 0xbc, (byte) 0xaf, 0x27, 0x1c});
        buffer.put((byte) 0);
        buffer.put((byte) 4);
        buffer.putInt(0);
        buffer.putLong(packedData.length);
        buffer.putLong(nextHeader.length);
        buffer.putInt((int) nextHeaderCrc32);
        buffer.put(packedData);
        buffer.put(nextHeader);

        CRC32 crc32 = new CRC32();
        crc32.update(buffer.array(), 12, 20);
        buffer.putInt(8, (int) crc32.getValue());
        return buffer.array();
    }

    /// Returns a plain 7z header with one empty directory and one empty file.
    private static byte[] emptyEntriesHeader() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        output.write(0x05);
        writeNumber(output, 2);

        byte[] emptyStreamBits = new byte[]{(byte) 0xc0};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{0x40};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] names = namesProperty("dir", "empty.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with dummy properties around one Copy-method file.
    private static byte[] dummyPropertiesHeader(int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        writeDummyProperty(output, 0x01);

        output.write(0x04);
        writeDummyProperty(output, 0x04);

        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        writeDummyProperty(output, 0x06);
        output.write(0x09);
        writeNumber(output, contentSize);
        output.write(0x00);

        output.write(0x07);
        writeDummyProperty(output, 0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, contentSize);
        writeDummyProperty(output, 0x0c);
        output.write(0x00);

        output.write(0x08);
        writeDummyProperty(output, 0x08);
        output.write(0x00);

        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        writeDummyProperty(output, 0x05);
        byte[] names = namesProperty("dummy.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Adds an `ArchiveProperties` block after the leading 7z `Header` marker.
    private static byte[] withArchiveProperties(byte[] header) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        writeArchiveProperties(output);
        output.write(header, 1, header.length - 1);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one empty file using the given name.
    private static byte[] emptyFileNameHeader(String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        output.write(0x05);
        writeNumber(output, 1);

        byte[] emptyStreamBits = new byte[]{(byte) 0x80};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{(byte) 0x80};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] names = namesProperty(name);
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with two empty files using the given names.
    private static byte[] emptyFileNamesHeader(String firstName, String secondName) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        output.write(0x05);
        writeNumber(output, 2);

        byte[] emptyStreamBits = new byte[]{(byte) 0xc0};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{(byte) 0xc0};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] names = namesProperty(firstName, secondName);
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one anti item using the given name.
    private static byte[] antiItemNameHeader(String name) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);
        output.write(0x05);
        writeNumber(output, 1);

        byte[] emptyStreamBits = new byte[]{(byte) 0x80};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{(byte) 0x80};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] antiBits = new byte[]{(byte) 0x80};
        output.write(0x10);
        writeNumber(output, antiBits.length);
        output.write(antiBits);

        byte[] names = namesProperty(name);
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file using the given coder.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties
    ) throws IOException {
        return fileHeader(packedSize, uncompressedSize, methodId, properties, null, null, null, -1);
    }

    /// Returns a plain 7z header with one file using the given coder and folder CRC-32.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            long folderCrc32
    ) throws IOException {
        return fileHeader(packedSize, uncompressedSize, methodId, properties, folderCrc32, -1L);
    }

    /// Returns a plain 7z header with one file using the given coder, folder CRC-32, and pack CRC-32.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            long folderCrc32,
            long packCrc32
    ) throws IOException {
        return fileHeader(packedSize, uncompressedSize, methodId, properties, null, null, null, -1, folderCrc32, packCrc32);
    }

    /// Returns a plain 7z header with one file using the given coder and metadata.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) throws IOException {
        return fileHeader(
                packedSize,
                uncompressedSize,
                methodId,
                properties,
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes,
                -1L,
                -1L
        );
    }

    /// Returns a plain 7z header with one file using the given coder, metadata, and optional CRC-32 values.
    private static byte[] fileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes,
            long folderCrc32,
            long packCrc32
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        if (packCrc32 >= 0) {
            output.write(0x0a);
            output.write(1);
            writeIntLE(output, (int) packCrc32);
        }
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(methodId.length | (properties.length != 0 ? 0x20 : 0));
        output.write(methodId);
        if (properties.length != 0) {
            writeNumber(output, properties.length);
            output.write(properties);
        }
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        if (folderCrc32 >= 0) {
            output.write(0x0a);
            output.write(1);
            writeIntLE(output, (int) folderCrc32);
        }
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("hello.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        if (creationTime != null) {
            writeTimeProperty(output, 0x12, creationTime);
        }
        if (lastAccessTime != null) {
            writeTimeProperty(output, 0x13, lastAccessTime);
        }
        if (lastModifiedTime != null) {
            writeTimeProperty(output, 0x14, lastModifiedTime);
        }
        if (windowsAttributes != -1) {
            writeWindowsAttributesProperty(output, windowsAttributes);
        }
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file, folder CRC-32, and an empty substream CRC-32 vector.
    private static byte[] fileHeaderWithSingleSubStreamCrcVector(
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            long folderCrc32
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(methodId.length | (properties.length != 0 ? 0x20 : 0));
        output.write(methodId);
        if (properties.length != 0) {
            writeNumber(output, properties.length);
            output.write(properties);
        }
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        output.write(0x0a);
        output.write(1);
        writeIntLE(output, (int) folderCrc32);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0a);
        output.write(1);
        output.write(0x00);

        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("hello.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file using an LZMA-to-Copy coder pipeline.
    private static byte[] lzmaCopyPipelineFileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] lzmaProperties
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 2);
        output.write(0x23);
        output.write(new byte[]{0x03, 0x01, 0x01});
        writeNumber(output, lzmaProperties.length);
        output.write(lzmaProperties);
        output.write(0x01);
        output.write(0x00);
        writeNumber(output, 1);
        writeNumber(output, 0);
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("multi-coder.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file using an LZMA2-to-Delta coder pipeline.
    private static byte[] lzma2DeltaPipelineFileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] lzma2Properties,
            int deltaDistance
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 2);
        output.write(0x21);
        output.write(0x21);
        writeNumber(output, lzma2Properties.length);
        output.write(lzma2Properties);
        output.write(0x21);
        output.write(0x03);
        writeNumber(output, 1);
        output.write(deltaDistance - 1);
        writeNumber(output, 1);
        writeNumber(output, 0);
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("delta-lzma2.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with one file using an LZMA2-to-x86 BCJ coder pipeline.
    private static byte[] lzma2X86BcjPipelineFileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] lzma2Properties
    ) throws IOException {
        return lzma2BcjPipelineFileHeader(
                packedSize,
                uncompressedSize,
                lzma2Properties,
                new byte[]{0x03, 0x03, 0x01, 0x03},
                new byte[0],
                "x86-bcj-lzma2.bin"
        );
    }

    /// Returns a plain 7z header with one file using an LZMA2-to-BCJ coder pipeline.
    private static byte[] lzma2BcjPipelineFileHeader(
            int packedSize,
            int uncompressedSize,
            byte[] lzma2Properties,
            byte[] bcjMethodId,
            byte[] bcjProperties,
            String fileName
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 2);
        output.write(0x21);
        output.write(0x21);
        writeNumber(output, lzma2Properties.length);
        output.write(lzma2Properties);
        output.write(bcjMethodId.length | (bcjProperties.length != 0 ? 0x20 : 0));
        output.write(bcjMethodId);
        if (bcjProperties.length != 0) {
            writeNumber(output, bcjProperties.length);
            output.write(bcjProperties);
        }
        writeNumber(output, 1);
        writeNumber(output, 0);
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty(fileName);
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with two Copy-method file substreams.
    private static byte[] copySubStreamsHeader(int firstSize, int totalSize) throws IOException {
        return copySubStreamsHeader(firstSize, totalSize, -1L, -1L);
    }

    /// Returns a plain 7z header with two LZMA2 file substreams.
    private static byte[] lzma2SubStreamsHeader(
            int packedSize,
            int totalSize,
            int firstSize,
            byte[] properties
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(0x21);
        output.write(0x21);
        writeNumber(output, properties.length);
        output.write(properties);
        output.write(0x0c);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0d);
        writeNumber(output, 2);
        output.write(0x09);
        writeNumber(output, firstSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 2);
        byte[] names = namesProperty("one.txt", "two.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with two Copy-method file substreams and optional CRC-32 values.
    private static byte[] copySubStreamsHeader(
            int firstSize,
            int totalSize,
            long firstCrc32,
            long secondCrc32
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0d);
        writeNumber(output, 2);
        output.write(0x09);
        writeNumber(output, firstSize);
        if (firstCrc32 >= 0 && secondCrc32 >= 0) {
            output.write(0x0a);
            output.write(1);
            writeIntLE(output, (int) firstCrc32);
            writeIntLE(output, (int) secondCrc32);
        }
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 2);
        byte[] names = namesProperty("one.txt", "two.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with a zero-substream Copy folder and one empty file.
    private static byte[] folderWithoutSubStreamsHeader() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, 0);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, 0);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0d);
        writeNumber(output, 0);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);

        byte[] emptyStreamBits = new byte[]{(byte) 0x80};
        output.write(0x0e);
        writeNumber(output, emptyStreamBits.length);
        output.write(emptyStreamBits);

        byte[] emptyFileBits = new byte[]{(byte) 0x80};
        output.write(0x0f);
        writeNumber(output, emptyFileBits.length);
        output.write(emptyFileBits);

        byte[] names = namesProperty("empty.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose substream counts appear after size metadata.
    private static byte[] subStreamCountsAfterDependentMetadataHeader(int totalSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, totalSize);
        output.write(0x00);

        output.write(0x08);
        output.write(0x09);
        output.write(0x0d);
        writeNumber(output, 2);
        output.write(0x00);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose folder count is too large to represent.
    private static byte[] oversizedFolderCountHeader() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x07);
        output.write(0x0b);
        writeTooLargeNumber(output);
        output.write(0);
        output.write(0x00);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose PackInfo has streams but no size property.
    private static byte[] missingPackSizesHeader(int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 1);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, contentSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("missing-pack-size.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose PackInfo declares more packed streams than folders.
    private static byte[] packStreamFolderCountMismatchHeader(int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, 0);
        writeNumber(output, 2);
        output.write(0x09);
        writeNumber(output, contentSize);
        writeNumber(output, 0);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, contentSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose external file-name property has no additional stream.
    private static byte[] missingExternalFilePropertyStreamHeader(int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        writeSingleFolderStreamsInfo(output, 0, contentSize, contentSize, new byte[]{0x00}, new byte[0]);

        output.write(0x05);
        writeNumber(output, 1);
        output.write(0x11);
        writeNumber(output, 2);
        output.write(1);
        writeNumber(output, 0);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header whose pack position overflows the absolute stream offset.
    private static byte[] overflowingPackPositionHeader(int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x04);
        output.write(0x06);
        writeLongNumber(output, Long.MAX_VALUE);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, contentSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        output.write(0x0c);
        writeNumber(output, contentSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("overflow.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with an ignored additional stream and one main Copy-method file stream.
    private static byte[] additionalStreamsInfoHeader(int additionalSize, int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x03);
        writeSingleFolderStreamsInfo(output, 0, additionalSize, additionalSize, new byte[]{0x00}, new byte[0]);

        output.write(0x04);
        writeSingleFolderStreamsInfo(output, additionalSize, contentSize, contentSize, new byte[]{0x00}, new byte[0]);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("hello.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with external file property streams and one Copy-method file stream.
    private static byte[] externalFilePropertiesHeader(
            int namesSize,
            int timeSize,
            int attributesSize,
            int contentSize
    ) throws IOException {
        int additionalSize = namesSize + timeSize + attributesSize;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x03);
        writeCopyStreamsInfo(output, 0, namesSize, timeSize, attributesSize);

        output.write(0x04);
        writeSingleFolderStreamsInfo(output, additionalSize, contentSize, contentSize, new byte[]{0x00}, new byte[0]);

        output.write(0x05);
        writeNumber(output, 1);

        output.write(0x11);
        writeNumber(output, 2);
        output.write(1);
        writeNumber(output, 0);

        output.write(0x14);
        writeNumber(output, 3);
        output.write(1);
        output.write(1);
        writeNumber(output, 1);

        output.write(0x15);
        writeNumber(output, 3);
        output.write(1);
        output.write(1);
        writeNumber(output, 2);

        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns a plain 7z header with external folder definitions and one Copy-method file stream.
    private static byte[] externalFolderDefinitionsHeader(int folderSize, int contentSize) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x01);

        output.write(0x03);
        writeSingleFolderStreamsInfo(output, 0, folderSize, folderSize, new byte[]{0x00}, new byte[0]);

        output.write(0x04);
        output.write(0x06);
        writeNumber(output, folderSize);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, contentSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(1);
        writeNumber(output, 0);
        output.write(0x0c);
        writeNumber(output, contentSize);
        output.write(0x00);
        output.write(0x00);

        output.write(0x05);
        writeNumber(output, 1);
        byte[] names = namesProperty("external-folder.txt");
        output.write(0x11);
        writeNumber(output, names.length);
        output.write(names);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns an encoded-header descriptor that points to one packed header stream.
    private static byte[] encodedHeader(
            int packPosition,
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties
    ) throws IOException {
        return encodedHeader(packPosition, packedSize, uncompressedSize, methodId, properties, -1L);
    }

    /// Returns an encoded-header descriptor that points to one packed header stream and optional pack CRC-32.
    private static byte[] encodedHeader(
            int packPosition,
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            long packCrc32
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x17);
        writeSingleFolderStreamsInfo(output, packPosition, packedSize, uncompressedSize, methodId, properties, packCrc32);
        return output.toByteArray();
    }

    /// Returns an encoded-header descriptor that decodes one AES-to-LZMA2 pipeline.
    private static byte[] aesLzma2EncodedHeader(
            int packPosition,
            int packedSize,
            int aesUnpackSize,
            int uncompressedSize,
            byte[] aesProperties,
            byte[] lzma2Properties
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x17);

        output.write(0x06);
        writeNumber(output, packPosition);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 2);
        output.write(0x24);
        output.write(sevenZipAesMethodId());
        writeNumber(output, aesProperties.length);
        output.write(aesProperties);
        output.write(0x21);
        output.write(0x21);
        writeNumber(output, lzma2Properties.length);
        output.write(lzma2Properties);
        writeNumber(output, 1);
        writeNumber(output, 0);
        output.write(0x0c);
        writeNumber(output, aesUnpackSize);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);
        return output.toByteArray();
    }

    /// Returns an encoded-header descriptor with one packed folder exposed as two substreams.
    private static byte[] encodedHeaderSubStreams(
            int packPosition,
            int packedSize,
            int uncompressedSize,
            int firstSubStreamSize,
            byte[] methodId,
            byte[] properties
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0x17);

        output.write(0x06);
        writeNumber(output, packPosition);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(methodId.length | (properties.length != 0 ? 0x20 : 0));
        output.write(methodId);
        if (properties.length != 0) {
            writeNumber(output, properties.length);
            output.write(properties);
        }
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        output.write(0x00);

        output.write(0x08);
        output.write(0x0d);
        writeNumber(output, 2);
        output.write(0x09);
        writeNumber(output, firstSubStreamSize);
        output.write(0x00);

        output.write(0x00);
        return output.toByteArray();
    }

    /// Writes a `StreamsInfo` block with one folder and one unpack stream.
    private static void writeSingleFolderStreamsInfo(
            ByteArrayOutputStream output,
            int packPosition,
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties
    ) throws IOException {
        writeSingleFolderStreamsInfo(output, packPosition, packedSize, uncompressedSize, methodId, properties, -1L);
    }

    /// Writes a `StreamsInfo` block with one folder, one unpack stream, and optional pack CRC-32.
    private static void writeSingleFolderStreamsInfo(
            ByteArrayOutputStream output,
            int packPosition,
            int packedSize,
            int uncompressedSize,
            byte[] methodId,
            byte[] properties,
            long packCrc32
    ) throws IOException {
        output.write(0x06);
        writeNumber(output, packPosition);
        writeNumber(output, 1);
        output.write(0x09);
        writeNumber(output, packedSize);
        if (packCrc32 >= 0) {
            output.write(0x0a);
            output.write(1);
            writeIntLE(output, (int) packCrc32);
        }
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, 1);
        output.write(0);
        writeNumber(output, 1);
        output.write(methodId.length | (properties.length != 0 ? 0x20 : 0));
        output.write(methodId);
        if (properties.length != 0) {
            writeNumber(output, properties.length);
            output.write(properties);
        }
        output.write(0x0c);
        writeNumber(output, uncompressedSize);
        output.write(0x00);
        output.write(0x00);
    }

    /// Writes a `StreamsInfo` block with Copy folders for each given packed size.
    private static void writeCopyStreamsInfo(
            ByteArrayOutputStream output,
            int packPosition,
            int... sizes
    ) throws IOException {
        output.write(0x06);
        writeNumber(output, packPosition);
        writeNumber(output, sizes.length);
        output.write(0x09);
        for (int size : sizes) {
            writeNumber(output, size);
        }
        output.write(0x00);

        output.write(0x07);
        output.write(0x0b);
        writeNumber(output, sizes.length);
        output.write(0);
        for (int ignored : sizes) {
            writeNumber(output, 1);
            output.write(1);
            output.write(0);
        }
        output.write(0x0c);
        for (int size : sizes) {
            writeNumber(output, size);
        }
        output.write(0x00);
        output.write(0x00);
    }

    /// Returns a serialized Copy folder definition.
    private static byte[] copyFolderPayload() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeNumber(output, 1);
        output.write(1);
        output.write(0);
        return output.toByteArray();
    }

    /// Writes a one-file 7z time property.
    private static void writeTimeProperty(ByteArrayOutputStream output, int property, FileTime time) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(1);
        data.write(0);
        writeLongLE(data, windowsTicks(time));
        output.write(property);
        writeNumber(output, data.size());
        output.write(data.toByteArray());
    }

    /// Writes a one-file 7z Windows attributes property.
    private static void writeWindowsAttributesProperty(ByteArrayOutputStream output, int attributes) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(1);
        data.write(0);
        writeIntLE(data, attributes);
        output.write(0x15);
        writeNumber(output, data.size());
        output.write(data.toByteArray());
    }

    /// Writes a sized 7z dummy property.
    private static void writeDummyProperty(ByteArrayOutputStream output, int marker) {
        output.write(0x19);
        writeNumber(output, 3);
        output.write(marker);
        output.write(marker ^ 0x55);
        output.write(0);
    }

    /// Writes a 7z archive properties block with one ignored property.
    private static void writeArchiveProperties(ByteArrayOutputStream output) {
        output.write(0x02);
        output.write(0x19);
        writeNumber(output, 4);
        output.write(0x61);
        output.write(0x72);
        output.write(0x63);
        output.write(0);
        output.write(0x00);
    }

    /// Returns a raw LZMA payload and its 7z coder properties.
    private static CoderPayload lzmaPayload(byte[] content) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (LZMAOutputStream lzma = new LZMAOutputStream(output, options, false)) {
            lzma.write(content);
        }

        byte[] properties = new byte[5];
        properties[0] = (byte) ((options.getPb() * 5 + options.getLp()) * 9 + options.getLc());
        ByteBuffer.wrap(properties, 1, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(options.getDictSize());
        return new CoderPayload(output.toByteArray(), properties);
    }

    /// Returns a raw LZMA2 payload and its 7z coder properties.
    private static CoderPayload lzma2Payload(byte[] content) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FinishableByteArrayOutputStream target = new FinishableByteArrayOutputStream(output);
        try (FinishableOutputStream lzma2 = options.getOutputStream(target, ArrayCache.getDummyCache())) {
            lzma2.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[]{lzma2Property(options.getDictSize())});
    }

    /// Returns a raw Deflate payload and its 7z coder properties.
    private static CoderPayload deflatePayload(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(output, deflater)) {
            deflate.write(content);
        } finally {
            deflater.end();
        }
        return new CoderPayload(output.toByteArray(), new byte[0]);
    }

    /// Returns a raw Deflate64 stored-block payload and its 7z coder properties.
    private static CoderPayload deflate64StoredPayload(byte[] content) {
        if (content.length > 0xffff) {
            throw new IllegalArgumentException("Deflate64 stored-block fixture content is too large");
        }
        ByteBuffer output = ByteBuffer.allocate(5 + content.length).order(ByteOrder.LITTLE_ENDIAN);
        output.put((byte) 0x01);
        output.putShort((short) content.length);
        output.putShort((short) ~content.length);
        output.put(content);
        return new CoderPayload(output.array(), new byte[0]);
    }

    /// Returns a BZip2 payload and its 7z coder properties.
    private static CoderPayload bzip2Payload(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (BZip2CompressorOutputStream bzip2 = new BZip2CompressorOutputStream(output)) {
            bzip2.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[0]);
    }

    /// Returns an AES-encrypted payload and its 7zAES coder properties.
    private static CoderPayload aesPayload(byte[] content, byte[] password) throws IOException {
        byte[] properties = sevenZipAesProperties();
        byte[] key = deriveSevenZipAesKey(properties, password);
        byte[] paddedContent = Arrays.copyOf(content, roundUpToAesBlock(content.length));
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(sevenZipAesInitializationVector())
            );
            return new CoderPayload(cipher.doFinal(paddedContent), properties);
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to create 7z AES test payload", exception);
        }
    }

    /// Returns the 7zAES method ID.
    private static byte[] sevenZipAesMethodId() {
        return new byte[]{0x06, (byte) 0xf1, 0x07, 0x01};
    }

    /// Returns the password bytes used by 7zAES test fixtures.
    private static byte[] sevenZipAesPassword() {
        return "secret".getBytes(StandardCharsets.UTF_16LE);
    }

    /// Returns the 7zAES coder properties used by test fixtures.
    private static byte[] sevenZipAesProperties() {
        byte[] salt = sevenZipAesSalt();
        byte[] initializationVector = sevenZipAesInitializationVector();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int cyclePower = 3;
        output.write(cyclePower | 0xc0);
        output.write(((salt.length - 1) << 4) | (initializationVector.length - 1));
        output.writeBytes(salt);
        output.writeBytes(initializationVector);
        return output.toByteArray();
    }

    /// Returns the 7zAES salt bytes used by test fixtures.
    private static byte[] sevenZipAesSalt() {
        return new byte[]{1, 2, 3, 4};
    }

    /// Returns the full 7zAES initialization vector used by test fixtures.
    private static byte[] sevenZipAesInitializationVector() {
        return new byte[]{16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    }

    /// Derives the 7zAES key for test fixture encryption.
    private static byte[] deriveSevenZipAesKey(byte[] properties, byte[] password) throws IOException {
        int cyclePower = properties[0] & 0x3f;
        int saltSize = ((properties[0] >>> 7) & 1) + ((properties[1] & 0xff) >>> 4);
        byte[] salt = Arrays.copyOfRange(properties, 2, 2 + saltSize);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] counter = new byte[Long.BYTES];
            long rounds = 1L << cyclePower;
            for (long round = 0; round < rounds; round++) {
                sha256.update(salt);
                sha256.update(password);
                sha256.update(counter);
                incrementLittleEndianCounter(counter);
            }
            return sha256.digest();
        } catch (GeneralSecurityException exception) {
            throw new IOException("Failed to derive 7z AES test key", exception);
        }
    }

    /// Increments an eight-byte little-endian counter.
    private static void incrementLittleEndianCounter(byte[] counter) {
        for (int index = 0; index < counter.length; index++) {
            counter[index]++;
            if (counter[index] != 0) {
                return;
            }
        }
    }

    /// Rounds a byte count up to the next AES block boundary.
    private static int roundUpToAesBlock(int length) {
        return (length + 15) & ~15;
    }

    /// Returns an LZMA2-compressed Delta-filtered payload and its LZMA2 coder properties.
    private static CoderPayload deltaLzma2Payload(byte[] content, int deltaDistance) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FinishableByteArrayOutputStream target = new FinishableByteArrayOutputStream(output);
        try (
                FinishableOutputStream lzma2 = options.getOutputStream(target, ArrayCache.getDummyCache());
                FinishableOutputStream delta = new DeltaOptions(deltaDistance)
                        .getOutputStream(lzma2, ArrayCache.getDummyCache())
        ) {
            delta.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[]{lzma2Property(options.getDictSize())});
    }

    /// Returns an LZMA2-compressed x86 BCJ-filtered payload and its LZMA2 coder properties.
    private static CoderPayload x86BcjLzma2Payload(byte[] content) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FinishableByteArrayOutputStream target = new FinishableByteArrayOutputStream(output);
        try (
                FinishableOutputStream lzma2 = options.getOutputStream(target, ArrayCache.getDummyCache());
                FinishableOutputStream x86 = new X86Options().getOutputStream(lzma2, ArrayCache.getDummyCache())
        ) {
            x86.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[]{lzma2Property(options.getDictSize())});
    }

    /// Returns an LZMA2-compressed BCJ-filtered payload and its LZMA2 coder properties.
    private static CoderPayload bcjLzma2Payload(byte[] content, FilterOptions bcjOptions) throws IOException {
        LZMA2Options options = new LZMA2Options();
        options.setDictSize(1 << 20);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FinishableByteArrayOutputStream target = new FinishableByteArrayOutputStream(output);
        try (
                FinishableOutputStream lzma2 = options.getOutputStream(target, ArrayCache.getDummyCache());
                FinishableOutputStream bcj = bcjOptions.getOutputStream(lzma2, ArrayCache.getDummyCache())
        ) {
            bcj.write(content);
        }
        return new CoderPayload(output.toByteArray(), new byte[]{lzma2Property(options.getDictSize())});
    }

    /// Returns the 7z LZMA2 dictionary property for an exact dictionary size.
    private static byte lzma2Property(int dictionarySize) {
        for (int property = 0; property <= 37; property++) {
            int value = (2 | (property & 1)) << ((property >>> 1) + 11);
            if (value == dictionarySize) {
                return (byte) property;
            }
        }
        throw new IllegalArgumentException("dictionarySize cannot be represented exactly");
    }

    /// Splits archive bytes at the given logical offsets.
    private static byte[][] splitArchive(byte[] archive, int... offsets) {
        byte[][] result = new byte[offsets.length + 1][];
        int previous = 0;
        for (int index = 0; index < offsets.length; index++) {
            int offset = offsets[index];
            if (offset <= previous || offset >= archive.length) {
                throw new IllegalArgumentException("split offsets must be strictly inside the archive");
            }
            result[index] = Arrays.copyOfRange(archive, previous, offset);
            previous = offset;
        }
        result[offsets.length] = Arrays.copyOfRange(archive, previous, archive.length);
        return result;
    }

    /// Concatenates two byte arrays.
    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /// Concatenates byte arrays.
    private static byte[] concatenateAll(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] result = new byte[length];
        int position = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, position, part.length);
            position += part.length;
        }
        return result;
    }

    /// Writes one standard non-empty file through a streaming writer.
    private static void writeStreamingContent(
            SevenZipArkivoStreamingWriter writer,
            byte @Unmodifiable [] content
    ) throws IOException {
        writer.beginFile("content.bin");
        try (OutputStream output = writer.openOutputStream()) {
            output.write(content);
        }
    }

    /// Verifies encrypted split-volume content through the Arkivo reader.
    private static void assertEncryptedContent(
            byte @Unmodifiable [] @Unmodifiable [] volumes,
            byte @Unmodifiable [] password,
            byte @Unmodifiable [] expectedContent
    ) throws IOException {
        try (SevenZipArkivoFileSystem fileSystem = SevenZipArkivoFileSystem.open(
                new SplitVolumeSource(volumes),
                Map.of(
                        SevenZipArkivoFileSystem.PASSWORD_PROVIDER.key(),
                        ArkivoPasswordProvider.fixed(password)
                )
        )) {
            assertArrayEquals(expectedContent, Files.readAllBytes(fileSystem.getPath("/content.bin")));
        }
    }

    /// Returns whether a throwable has a direct suppressed exception with the given message.
    private static boolean hasSuppressedMessage(Throwable throwable, String message) {
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (message.equals(suppressed.getMessage())) {
                return true;
            }
        }
        return false;
    }

    /// Records byte-array output close ownership for streaming writer tests.
    @NotNullByDefault
    private static final class TrackingOutputStream extends ByteArrayOutputStream {
        /// Whether this stream has been closed.
        private boolean closed;

        /// Records stream close.
        @Override
        public void close() throws IOException {
            super.close();
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Fails every archive publication write while recording output ownership.
    @NotNullByDefault
    private static final class FailingOutputStream extends OutputStream {
        /// Whether this stream has been closed.
        private boolean closed;

        /// Fails one-byte writes.
        @Override
        public void write(int value) throws IOException {
            throw new IOException("forced output failure");
        }

        /// Fails bulk writes.
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            throw new IOException("forced output failure");
        }

        /// Records stream close.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Records archive-password requests and optionally returns or rejects password data.
    @NotNullByDefault
    private static final class RecordingPasswordProvider implements ArkivoPasswordProvider {
        /// The password returned for an archive, or `null` when no password should be supplied.
        private final byte @Nullable @Unmodifiable [] password;

        /// Whether archive password requests should fail.
        private final boolean fail;

        /// The number of archive password requests.
        private int archiveRequestCount;

        /// Creates a recording provider with the requested behavior.
        private RecordingPasswordProvider(byte @Nullable [] password, boolean fail) {
            this.password = password != null ? password.clone() : null;
            this.fail = fail;
        }

        /// Creates a provider that supplies a defensive copy of the given password.
        private static RecordingPasswordProvider supplying(byte[] password) {
            return new RecordingPasswordProvider(Objects.requireNonNull(password, "password"), false);
        }

        /// Creates a provider that returns no password.
        private static RecordingPasswordProvider missing() {
            return new RecordingPasswordProvider(null, false);
        }

        /// Creates a provider that fails archive password requests.
        private static RecordingPasswordProvider failing() {
            return new RecordingPasswordProvider(null, true);
        }

        /// Returns or rejects the configured password while recording the request.
        @Override
        public byte @Nullable [] passwordForArchive() throws IOException {
            archiveRequestCount++;
            if (fail) {
                throw new IOException("password provider failed");
            }
            return password != null ? password.clone() : null;
        }

        /// Returns the number of archive password requests.
        private int archiveRequestCount() {
            return archiveRequestCount;
        }
    }

    /// Creates in-memory transactional volume output with configurable failures.
    @NotNullByDefault
    private static final class TestVolumeTarget implements ArkivoVolumeTarget {
        /// The volume index whose open should fail, or a negative value when opens succeed.
        private final long failVolumeIndex;

        /// Whether commit should fail.
        private final boolean failCommit;

        /// Whether opened volume channels should make no write progress.
        private final boolean zeroProgress;

        /// The number of output transactions opened by this target.
        private int openOutputCount;

        /// The latest opened output transaction, or `null` before first use.
        private @Nullable TestVolumeOutput output;

        /// Creates an in-memory target with the requested failures.
        private TestVolumeTarget(long failVolumeIndex, boolean failCommit) {
            this(failVolumeIndex, failCommit, false);
        }

        /// Creates an in-memory target with the requested failures and progress behavior.
        private TestVolumeTarget(long failVolumeIndex, boolean failCommit, boolean zeroProgress) {
            this.failVolumeIndex = failVolumeIndex;
            this.failCommit = failCommit;
            this.zeroProgress = zeroProgress;
        }

        /// Opens one new in-memory output transaction.
        @Override
        public ArkivoVolumeOutput openOutput() {
            openOutputCount++;
            output = new TestVolumeOutput(failVolumeIndex, failCommit, zeroProgress);
            return output;
        }

        /// Returns the number of output transactions opened by this target.
        private int openOutputCount() {
            return openOutputCount;
        }

        /// Returns committed volume snapshots, or an empty array when publication failed.
        private byte @Unmodifiable [] @Unmodifiable [] committedVolumes() {
            @Nullable TestVolumeOutput currentOutput = output;
            return currentOutput != null ? currentOutput.committedVolumes() : new byte[0][];
        }

        /// Returns the number of effective rollback operations.
        private int rollbackCount() {
            @Nullable TestVolumeOutput currentOutput = output;
            return currentOutput != null ? currentOutput.rollbackCount() : 0;
        }

        /// Returns whether every volume channel opened by the target has been closed.
        private boolean allOpenedChannelsClosed() {
            @Nullable TestVolumeOutput currentOutput = output;
            return currentOutput == null || currentOutput.allOpenedChannelsClosed();
        }
    }

    /// Records one in-memory multi-volume output transaction.
    @NotNullByDefault
    private static final class TestVolumeOutput implements ArkivoVolumeOutput {
        /// The volume index whose open should fail, or a negative value when opens succeed.
        private final long failVolumeIndex;

        /// Whether commit should fail.
        private final boolean failCommit;

        /// Whether opened volume channels should make no write progress.
        private final boolean zeroProgress;

        /// Bytes written to each opened volume.
        private final ArrayList<ByteArrayOutputStream> volumeBytes = new ArrayList<>();

        /// Channels opened for each volume.
        private final ArrayList<WritableByteChannel> channels = new ArrayList<>();

        /// The number of effective rollback operations.
        private int rollbackCount;

        /// Whether all written volumes were committed.
        private boolean committed;

        /// Whether this transaction has committed or rolled back.
        private boolean finished;

        /// Creates one recording output transaction.
        private TestVolumeOutput(long failVolumeIndex, boolean failCommit, boolean zeroProgress) {
            this.failVolumeIndex = failVolumeIndex;
            this.failCommit = failCommit;
            this.zeroProgress = zeroProgress;
        }

        /// Opens the next in-memory volume channel.
        @Override
        public WritableByteChannel openVolume(long index) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (index != channels.size()) {
                throw new IllegalArgumentException("volume indexes must be contiguous");
            }
            if (!channels.isEmpty() && channels.get(channels.size() - 1).isOpen()) {
                throw new IOException("previous volume is still open");
            }
            if (index == failVolumeIndex) {
                throw new IOException("volume open failed");
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            WritableByteChannel channel = zeroProgress
                    ? new ZeroProgressWritableChannel()
                    : Channels.newChannel(bytes);
            volumeBytes.add(bytes);
            channels.add(channel);
            return channel;
        }

        /// Commits all opened in-memory volumes.
        @Override
        public void commit(long finalVolumeIndex) throws IOException {
            if (finished) {
                throw new IOException("volume output is finished");
            }
            if (finalVolumeIndex != channels.size() - 1L) {
                throw new IllegalArgumentException("finalVolumeIndex does not identify the last volume");
            }
            if (!allOpenedChannelsClosed()) {
                throw new IOException("volume channel is still open");
            }
            if (failCommit) {
                throw new IOException("volume commit failed");
            }
            committed = true;
            finished = true;
        }

        /// Rolls back this transaction once.
        @Override
        public void rollback() {
            if (finished) {
                return;
            }
            rollbackCount++;
            finished = true;
        }

        /// Closes this transaction and rolls it back when uncommitted.
        @Override
        public void close() {
            rollback();
        }

        /// Returns committed volume snapshots, or an empty array when publication failed.
        private byte @Unmodifiable [] @Unmodifiable [] committedVolumes() {
            if (!committed) {
                return new byte[0][];
            }
            byte[][] result = new byte[volumeBytes.size()][];
            for (int index = 0; index < volumeBytes.size(); index++) {
                result[index] = volumeBytes.get(index).toByteArray();
            }
            return result;
        }

        /// Returns the number of effective rollback operations.
        private int rollbackCount() {
            return rollbackCount;
        }

        /// Returns whether every opened volume channel is closed.
        private boolean allOpenedChannelsClosed() {
            for (WritableByteChannel channel : channels) {
                if (channel.isOpen()) {
                    return false;
                }
            }
            return true;
        }
    }

    /// Writable channel that remains open but never accepts bytes.
    @NotNullByDefault
    private static final class ZeroProgressWritableChannel implements WritableByteChannel {
        /// Whether this channel remains open.
        private boolean open = true;

        /// Reports zero bytes written without consuming the source buffer.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (!open) {
                throw new ClosedChannelException();
            }
            return 0;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this channel.
        @Override
        public void close() {
            open = false;
        }
    }

    /// Provides archive bytes through multiple in-memory split volumes.
    @NotNullByDefault
    private static final class SplitVolumeSource implements ArkivoVolumeSource {
        /// The split archive volumes.
        private final byte @Unmodifiable [] @Unmodifiable [] volumes;

        /// Creates an in-memory split-volume source.
        private SplitVolumeSource(byte[][] volumes) {
            this.volumes = new byte[volumes.length][];
            for (int index = 0; index < volumes.length; index++) {
                this.volumes[index] = Objects.requireNonNull(volumes[index], "volume").clone();
            }
        }

        /// Opens the requested volume channel, or returns `null` when the volume is absent.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index < 0 || index >= volumes.length) {
                return null;
            }
            return new MemorySeekableByteChannel(volumes[(int) index], false);
        }
    }

    /// Repeatable single-archive source that records opened channel and source lifecycles.
    @NotNullByDefault
    private static final class TestSeekableChannelSource implements ArkivoSeekableChannelSource {
        /// The archive bytes exposed by each opened channel.
        private final byte @Unmodifiable [] content;

        /// The channels opened from this source.
        private final ArrayList<MemorySeekableByteChannel> openedChannels = new ArrayList<>();

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a repeatable source over the given archive bytes.
        private TestSeekableChannelSource(byte[] content) {
            this.content = Objects.requireNonNull(content, "content").clone();
        }

        /// Opens an independent channel over the archive bytes.
        @Override
        public SeekableByteChannel openChannel() throws IOException {
            if (closeCount > 0) {
                throw new IOException("source is closed");
            }
            MemorySeekableByteChannel channel = new MemorySeekableByteChannel(content, false);
            openedChannels.add(channel);
            return channel;
        }

        /// Records that this source has been closed.
        @Override
        public void close() {
            closeCount++;
        }

        /// Returns the number of channels opened from this source.
        private int openCount() {
            return openedChannels.size();
        }

        /// Returns whether every channel opened from this source has been closed.
        private boolean allOpenedChannelsClosed() {
            for (MemorySeekableByteChannel channel : openedChannels) {
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

    /// Provides archive bytes through a volume source with a selectable close-failing open.
    @NotNullByDefault
    private static final class CloseFailingVolumeSource implements ArkivoVolumeSource {
        /// The archive bytes exposed by this source.
        private final byte @Unmodifiable [] archive;

        /// The one-based open count whose returned channel fails on close.
        private final int closeFailingOpen;

        /// Whether close failure should be thrown at runtime.
        private final boolean failCloseAtRuntime;

        /// The number of opened first-volume channels.
        private int openCount;

        /// Creates a volume source over the given archive bytes.
        private CloseFailingVolumeSource(byte[] archive, int closeFailingOpen) {
            this(archive, closeFailingOpen, false);
        }

        /// Creates a volume source over the given archive bytes and close failure mode.
        private CloseFailingVolumeSource(byte[] archive, int closeFailingOpen, boolean failCloseAtRuntime) {
            this.archive = Objects.requireNonNull(archive, "archive").clone();
            this.closeFailingOpen = closeFailingOpen;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Opens the requested volume channel, or returns `null` for missing split volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index != 0) {
                return null;
            }
            openCount++;
            return new MemorySeekableByteChannel(archive, openCount == closeFailingOpen, failCloseAtRuntime);
        }
    }

    /// Provides archive bytes through a volume source whose own close operation fails.
    @NotNullByDefault
    private static final class CloseFailingOwnedVolumeSource implements ArkivoVolumeSource {
        /// The archive bytes exposed by this source.
        private final byte @Unmodifiable [] archive;

        /// The number of close calls that should fail.
        private final int failureCount;

        /// The number of times this source has been closed.
        private int closeCount;

        /// Creates a close-failing volume source over the given archive bytes.
        private CloseFailingOwnedVolumeSource(byte[] archive) {
            this(archive, Integer.MAX_VALUE);
        }

        /// Creates a volume source that fails the given number of close calls.
        private CloseFailingOwnedVolumeSource(byte[] archive, int failureCount) {
            if (failureCount < 0) {
                throw new IllegalArgumentException("failureCount must not be negative");
            }
            this.archive = Objects.requireNonNull(archive, "archive").clone();
            this.failureCount = failureCount;
        }

        /// Opens the requested volume channel, or returns `null` for missing split volumes.
        @Override
        public @Nullable SeekableByteChannel openVolume(long index) {
            if (index != 0) {
                return null;
            }
            return new MemorySeekableByteChannel(archive, false);
        }

        /// Fails while configured close failures remain.
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

    /// Provides an in-memory read-only seekable byte channel for tests.
    @NotNullByDefault
    private static final class MemorySeekableByteChannel implements SeekableByteChannel {
        /// The channel content.
        private final byte @Unmodifiable [] content;

        /// The current channel position.
        private int position;

        /// Whether this channel is open.
        private boolean open = true;

        /// Whether closing this channel fails without closing it.
        private final boolean failClose;

        /// Whether close failure should be thrown at runtime.
        private final boolean failCloseAtRuntime;

        /// Creates an in-memory channel for the given content and close behavior.
        private MemorySeekableByteChannel(byte[] content, boolean failClose) {
            this(content, failClose, false);
        }

        /// Creates an in-memory channel for the given content and close failure mode.
        private MemorySeekableByteChannel(byte[] content, boolean failClose, boolean failCloseAtRuntime) {
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failClose = failClose;
            this.failCloseAtRuntime = failCloseAtRuntime;
        }

        /// Reads bytes from the current channel position.
        @Override
        public int read(ByteBuffer destination) throws IOException {
            ensureOpen();
            Objects.requireNonNull(destination, "destination");
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

        /// Rejects writes.
        @Override
        public int write(ByteBuffer source) {
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

        /// Returns the number of bytes in this channel.
        @Override
        public long size() throws IOException {
            ensureOpen();
            return content.length;
        }

        /// Rejects truncation.
        @Override
        public SeekableByteChannel truncate(long size) {
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
        public void close() throws IOException {
            if (failClose) {
                if (failCloseAtRuntime) {
                    throw new IllegalStateException("close failed");
                }
                throw new IOException("close failed");
            }
            open = false;
        }

        /// Requires this channel to be open.
        private void ensureOpen() throws IOException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }

    /// Stores generated coder payload bytes and 7z coder properties.
    @NotNullByDefault
    private static final class CoderPayload {
        /// The raw coder payload bytes.
        private final byte[] content;

        /// The 7z coder properties.
        private final byte[] properties;

        /// Creates a generated coder payload.
        private CoderPayload(byte[] content, byte[] properties) {
            this.content = content;
            this.properties = properties;
        }

        /// Returns the raw coder payload bytes.
        private byte[] content() {
            return content;
        }

        /// Returns the 7z coder properties.
        private byte[] properties() {
            return properties;
        }
    }

    /// Adapts a byte array output stream to XZ for Java's finishable output API.
    @NotNullByDefault
    private static final class FinishableByteArrayOutputStream extends FinishableOutputStream {
        /// The target output stream.
        private final ByteArrayOutputStream target;

        /// Creates a finishable output stream adapter.
        private FinishableByteArrayOutputStream(ByteArrayOutputStream target) {
            this.target = target;
        }

        /// Writes one byte.
        @Override
        public void write(int value) {
            target.write(value);
        }

        /// Writes bytes.
        @Override
        public void write(byte[] buffer, int offset, int length) {
            target.write(buffer, offset, length);
        }

        /// Finishes this stream.
        @Override
        public void finish() {
        }
    }

    /// Returns a 7z names property payload.
    private static byte[] namesProperty(String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(0);
        output.write(namesPayload(names));
        return output.toByteArray();
    }

    /// Returns a 7z names payload without the inline/external storage flag.
    private static byte[] namesPayload(String... names) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String name : names) {
            output.write(name.getBytes(StandardCharsets.UTF_16LE));
            output.write(0);
            output.write(0);
        }
        return output.toByteArray();
    }

    /// Writes a 7z variable-length integer.
    private static void writeNumber(ByteArrayOutputStream output, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("test value is out of range");
        }
        if (value < 0x80) {
            output.write(value);
        } else if (value < 0x4000) {
            output.write(0x80 | (value >>> 8));
            output.write(value);
        } else if (value < 0x20_0000) {
            output.write(0xc0 | (value >>> 16));
            output.write(value);
            output.write(value >>> 8);
        } else {
            throw new IllegalArgumentException("test value is out of range");
        }
    }

    /// Writes a 7z variable-length integer that may exceed the smaller test helper range.
    private static void writeLongNumber(ByteArrayOutputStream output, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("test value is out of range");
        }
        if (value < 0x20_0000L) {
            writeNumber(output, (int) value);
            return;
        }
        output.write(0xff);
        writeLongLE(output, value);
    }

    /// Writes a 7z variable-length integer larger than signed `long`.
    private static void writeTooLargeNumber(ByteArrayOutputStream output) {
        output.write(0xff);
        for (int index = 0; index < Long.BYTES; index++) {
            output.write(0xff);
        }
    }

    /// Writes a little-endian `int`.
    private static void writeIntLE(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
        output.write(value >>> 16);
        output.write(value >>> 24);
    }

    /// Writes a little-endian `long`.
    private static void writeLongLE(ByteArrayOutputStream output, long value) {
        writeIntLE(output, (int) value);
        writeIntLE(output, (int) (value >>> 32));
    }

    /// Converts a file time to Windows FILETIME ticks.
    private static long windowsTicks(FileTime time) {
        Instant instant = time.toInstant();
        return 116_444_736_000_000_000L
                + instant.getEpochSecond() * 10_000_000L
                + instant.getNano() / 100L;
    }

    /// Converts unsigned Windows FILETIME ticks to a Java file time.
    private static FileTime fileTimeFromUnsignedWindowsTicks(long windowsTicks) {
        long unixTicks = windowsTicks - 116_444_736_000_000_000L;
        long seconds = Long.divideUnsigned(unixTicks, 10_000_000L);
        long nanos = Long.remainderUnsigned(unixTicks, 10_000_000L) * 100L;
        return FileTime.from(Instant.ofEpochSecond(seconds, nanos));
    }

    /// Returns the unsigned CRC-32 value of the given content.
    private static long crc32(byte[] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Returns the unsigned CRC-32 value of the given content range.
    private static long crc32(byte[] content, int offset, int length) {
        CRC32 crc32 = new CRC32();
        crc32.update(content, offset, length);
        return crc32.getValue();
    }
}
