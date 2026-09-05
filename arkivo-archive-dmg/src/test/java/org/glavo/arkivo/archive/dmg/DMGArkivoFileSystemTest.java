// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArkivoFileSystemThreadSafety;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.NotLinkException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createFragmentedHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.createHFSPlusDisk;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.fragmentedFileContents;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.readFully;
import static org.glavo.arkivo.archive.dmg.DMGTestFixtures.writeRawImage;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises the complete generated UDIF-to-HFS-Plus-to-NIO file-system path.
@NotNullByDefault
final class DMGArkivoFileSystemTest {
    /// The generated test directory.
    @TempDir
    Path temporaryDirectory;

    /// Reads files, follows symbolic links, enumerates directories, and exposes HFS Plus attributes.
    @Test
    void readsGeneratedHFSPlusFileSystem() throws IOException {
        Path imagePath = createImage("filesystem.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            Path link = fileSystem.getPath("/link");

            assertTrue(fileSystem.isReadOnly());
            assertEquals(DMGPartitionScheme.RAW, fileSystem.partition().scheme());
            assertEquals("hello", Files.readString(file));
            assertEquals("hello", Files.readString(link));
            assertEquals(fileSystem.getPath("hello.txt"), Files.readSymbolicLink(link));
            try (var children = Files.list(root)) {
                assertEquals(List.of("hello.txt", "link"), children
                        .map(path -> path.getFileName().toString())
                        .sorted()
                        .toList());
            }

            BasicFileAttributes followed = Files.readAttributes(link, BasicFileAttributes.class);
            BasicFileAttributes notFollowed = Files.readAttributes(
                    link,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            assertTrue(followed.isRegularFile());
            assertEquals(5L, followed.size());
            assertTrue(notFollowed.isSymbolicLink());
            assertEquals(9L, notFollowed.size());

            PosixFileAttributes attributes = Files.readAttributes(file, PosixFileAttributes.class);
            assertEquals("501", attributes.owner().getName());
            assertEquals("20", attributes.group().getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), attributes.permissions());
            assertEquals(5L, Files.getAttribute(file, "basic:size"));

            FileStore store = Files.getFileStore(root);
            assertEquals("hfsplus", store.type());
            assertTrue(store.isReadOnly());
            assertEquals(16L * DMGTestFixtures.SECTOR_SIZE, store.getTotalSpace());
            assertEquals(7L * DMGTestFixtures.SECTOR_SIZE, store.getUnallocatedSpace());
            assertEquals(0L, store.getUsableSpace());
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.writeString(file, "changed"));
        }
    }

    /// Maps HFS Plus slash and NUL characters into safe characters within one archive path element.
    @Test
    void mapsUnsafeCatalogNameCharacters() throws IOException {
        byte[] disk = createHFSPlusDisk();
        int fileName = 5 * DMGTestFixtures.SECTOR_SIZE + 118 + 8;
        ByteArrayAccess.writeShortBigEndian(disk, fileName, (short) '/');
        ByteArrayAccess.writeShortBigEndian(disk, fileName + Character.BYTES, (short) 0);
        Path image = writeRawImage(temporaryDirectory.resolve("mapped-catalog-name.dmg"), disk);

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(image)) {
            Path mapped = fileSystem.getPath("/:\u2400llo.txt");
            assertEquals("hello", Files.readString(mapped));
            try (var children = Files.list(fileSystem.getPath("/"))) {
                assertTrue(children.anyMatch(mapped::equals));
            }
        }
    }

    /// Ignores folder-thread and file-thread catalog records while retaining ordinary entries.
    ///
    /// @param recordType the HFS Plus catalog thread-record type
    @ParameterizedTest(name = "catalog record type {0}")
    @ValueSource(ints = {3, 4})
    void ignoresCatalogThreadRecords(int recordType) throws IOException {
        byte[] disk = createHFSPlusDisk();
        int threadRecordType = 6 * DMGTestFixtures.SECTOR_SIZE + 14 + 16;
        ByteArrayAccess.writeShortBigEndian(disk, threadRecordType, (short) recordType);
        Path image = writeRawImage(
                temporaryDirectory.resolve("catalog-thread-" + recordType + ".dmg"),
                disk
        );

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(image)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
            assertFalse(Files.exists(fileSystem.getPath("/link"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// Exposes following and non-following standard attribute views and immutable named selections.
    @Test
    void exposesStandardAttributeViews() throws IOException {
        Path imagePath = createImage("attribute-views.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path file = fileSystem.getPath("/hello.txt");
            Path link = fileSystem.getPath("/link");
            assertEquals(Set.of("basic", "owner", "posix"), fileSystem.supportedFileAttributeViews());

            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            assertTrue(basicView.readAttributes().isRegularFile());
            assertThrows(ReadOnlyFileSystemException.class, () -> basicView.setTimes(null, null, null));

            BasicFileAttributeView followedLinkView = Objects.requireNonNull(
                    Files.getFileAttributeView(link, BasicFileAttributeView.class)
            );
            BasicFileAttributeView linkView = Objects.requireNonNull(
                    Files.getFileAttributeView(link, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
            );
            assertTrue(followedLinkView.readAttributes().isRegularFile());
            assertTrue(linkView.readAttributes().isSymbolicLink());

            FileOwnerAttributeView ownerView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, FileOwnerAttributeView.class)
            );
            assertEquals("owner", ownerView.name());
            assertEquals("501", ownerView.getOwner().getName());
            assertThrows(ReadOnlyFileSystemException.class, () -> ownerView.setOwner(() -> "other"));

            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );
            assertEquals("posix", posixView.name());
            assertEquals("501", posixView.getOwner().getName());
            assertEquals("20", posixView.readAttributes().group().getName());
            assertEquals(
                    PosixFilePermissions.fromString("rw-r--r--"),
                    posixView.readAttributes().permissions()
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setTimes(null, null, null));
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setOwner(() -> "other"));
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setGroup(() -> "other"));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setPermissions(Set.<PosixFilePermission>of())
            );
            assertNull(Files.getFileAttributeView(file, DosFileAttributeView.class));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(file, DosFileAttributes.class)
            );

            Map<String, Object> basic = Files.readAttributes(file, "basic:size,isRegularFile,fileKey");
            assertEquals(Set.of("size", "isRegularFile", "fileKey"), basic.keySet());
            assertEquals(5L, basic.get("size"));
            assertEquals(true, basic.get("isRegularFile"));
            assertEquals(16L, basic.get("fileKey"));
            assertThrows(UnsupportedOperationException.class, basic::clear);

            Map<String, Object> owner = Files.readAttributes(file, "owner:owner");
            assertEquals("501", ((java.nio.file.attribute.UserPrincipal) owner.get("owner")).getName());
            Map<String, Object> posix = Files.readAttributes(
                    file,
                    "posix:owner,group,permissions,isRegularFile"
            );
            assertEquals("501", ((java.nio.file.attribute.UserPrincipal) posix.get("owner")).getName());
            assertEquals("20", ((java.nio.file.attribute.GroupPrincipal) posix.get("group")).getName());
            assertEquals(PosixFilePermissions.fromString("rw-r--r--"), posix.get("permissions"));
            assertEquals(true, posix.get("isRegularFile"));
            assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "zip:*"));
            assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "basic:"));

            FileStore store = Files.getFileStore(file);
            assertTrue(store.supportsFileAttributeView(BasicFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
            assertFalse(store.supportsFileAttributeView(DosFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("owner"));
            assertTrue(store.supportsFileAttributeView("posix"));
            assertFalse(store.supportsFileAttributeView("dos"));
            assertNull(store.getFileStoreAttributeView(FileStoreAttributeView.class));
            assertEquals(store.name(), store.getAttribute("name"));
            assertEquals(store.type(), store.getAttribute("basic:type"));
            assertEquals(true, store.getAttribute("readOnly"));
            assertEquals(store.getTotalSpace(), store.getAttribute("totalSpace"));
            assertEquals(store.getUsableSpace(), store.getAttribute("usableSpace"));
            assertEquals(store.getUnallocatedSpace(), store.getAttribute("unallocatedSpace"));
            assertThrows(UnsupportedOperationException.class, () -> store.getAttribute("missing"));
        }
    }

    /// Enforces read-only open options, initial-attribute rejection, access checks, and link traversal.
    @Test
    void enforcesReadOnlyEntryOperations() throws IOException {
        Path imagePath = createImage("read-only-contract.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            Path link = fileSystem.getPath("/link");
            Path missing = fileSystem.getPath("/missing");

            try (SeekableByteChannel channel = Files.newByteChannel(link, StandardOpenOption.READ)) {
                assertEquals(5L, channel.size());
            }
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(link, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            );
            assertThrows(FileSystemException.class, () -> Files.newByteChannel(root, StandardOpenOption.READ));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.newByteChannel(file, StandardOpenOption.WRITE)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.READ),
                            PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ))
                    )
            );
            assertThrows(FileSystemException.class, () -> Files.newInputStream(link, LinkOption.NOFOLLOW_LINKS));
            assertThrows(NotLinkException.class, () -> Files.readSymbolicLink(file));

            fileSystem.provider().checkAccess(file, AccessMode.READ);
            assertThrows(
                    AccessDeniedException.class,
                    () -> fileSystem.provider().checkAccess(file, AccessMode.WRITE)
            );
            assertThrows(
                    AccessDeniedException.class,
                    () -> fileSystem.provider().checkAccess(file, AccessMode.EXECUTE)
            );
            assertThrows(
                    NoSuchFileException.class,
                    () -> fileSystem.provider().checkAccess(missing, AccessMode.READ)
            );
            assertTrue(Files.isReadable(file));
            assertFalse(Files.isWritable(file));
            assertFalse(Files.isExecutable(file));
        }

        byte[] malformedLinkDisk = createHFSPlusDisk();
        malformedLinkDisk[8 * DMGTestFixtures.SECTOR_SIZE] = (byte) 0xc3;
        malformedLinkDisk[8 * DMGTestFixtures.SECTOR_SIZE + 1] = 0x28;
        Path malformedLinkImage = writeRawImage(
                temporaryDirectory.resolve("malformed-link-target.dmg"),
                malformedLinkDisk
        );
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(malformedLinkImage)) {
            IOException exception = assertThrows(
                    IOException.class,
                    () -> Files.readSymbolicLink(fileSystem.getPath("/link"))
            );
            assertTrue(exception.getMessage().contains("Invalid UTF-8 HFS Plus symbolic-link target"));
        }
    }

    /// Applies directory filters once, enforces single-iterator semantics, and closes managed streams.
    @Test
    void exposesContractCompliantDirectoryStreams() throws IOException {
        Path imagePath = createImage("directory-streams.dmg");
        DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath);
        Path root = fileSystem.getPath("/");
        try (fileSystem) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(
                    root,
                    path -> path.getFileName().toString().endsWith(".txt")
            )) {
                var iterator = stream.iterator();
                assertTrue(iterator.hasNext());
                assertEquals(fileSystem.getPath("/hello.txt"), iterator.next());
                assertFalse(iterator.hasNext());
                assertThrows(IllegalStateException.class, stream::iterator);
            }

            IOException filterFailure = new IOException("filter failure");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, path -> {
                throw filterFailure;
            })) {
                DirectoryIteratorException exception = assertThrows(
                        DirectoryIteratorException.class,
                        stream::iterator
                );
                assertSame(filterFailure, exception.getCause());
            }

            DirectoryStream<Path> closed = Files.newDirectoryStream(root);
            closed.close();
            closed.close();
            assertThrows(IllegalStateException.class, closed::iterator);
            assertThrows(
                    NotDirectoryException.class,
                    () -> Files.newDirectoryStream(fileSystem.getPath("/hello.txt"))
            );
            assertThrows(
                    NotDirectoryException.class,
                    () -> Files.newDirectoryStream(fileSystem.getPath("/link"))
            );

            DirectoryStream<Path> managed = Files.newDirectoryStream(root);
            fileSystem.close();
            assertThrows(IllegalStateException.class, managed::iterator);
            managed.close();
        }
    }

    /// Resolves entry identity and copies files, links, and empty directory snapshots to the host file system.
    @Test
    void supportsIdentityAndCrossFileSystemCopies() throws IOException {
        Path imagePath = createImage("identity-and-copy.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/hello.txt");
            Path link = fileSystem.getPath("/link");
            Path missing = fileSystem.getPath("/missing");

            assertTrue(Files.isSameFile(file, link));
            assertTrue(Files.isSameFile(file, fileSystem.getPath("/./hello.txt")));
            assertFalse(Files.isSameFile(file, root));
            assertFalse(Files.isSameFile(file, temporaryDirectory.resolve("foreign")));
            assertThrows(NoSuchFileException.class, () -> Files.isSameFile(file, missing));
            assertFalse(Files.isHidden(file));
            assertThrows(NoSuchFileException.class, () -> Files.isHidden(missing));

            Path copiedFile = temporaryDirectory.resolve("copied-file.txt");
            fileSystem.provider().copy(file, copiedFile, StandardCopyOption.COPY_ATTRIBUTES);
            assertEquals("hello", Files.readString(copiedFile));
            assertEquals(Files.getLastModifiedTime(file), Files.getLastModifiedTime(copiedFile));
            assertThrows(FileAlreadyExistsException.class, () -> Files.copy(file, copiedFile));
            Files.writeString(copiedFile, "old");
            Files.copy(file, copiedFile, StandardCopyOption.REPLACE_EXISTING);
            assertEquals("hello", Files.readString(copiedFile));

            Path copiedLinkTarget = temporaryDirectory.resolve("copied-link-target.txt");
            Files.copy(link, copiedLinkTarget);
            assertEquals("hello", Files.readString(copiedLinkTarget));

            Path copiedDirectory = temporaryDirectory.resolve("copied-directory");
            Files.copy(root, copiedDirectory);
            assertTrue(Files.isDirectory(copiedDirectory));
            try (var entries = Files.list(copiedDirectory)) {
                assertEquals(0L, entries.count());
            }
        }
    }

    /// Resolves overflow extents for a fragmented catalog and a fragmented regular-file data fork.
    @Test
    void readsFragmentedOverflowExtents() throws IOException {
        byte[] expected = fragmentedFileContents();
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("fragmented-extents.dmg"),
                createFragmentedHFSPlusDisk()
        );

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            Path file = fileSystem.getPath("/fragmented.bin");
            assertArrayEquals(expected, Files.readAllBytes(file));
            assertEquals(
                    fileSystem.getPath("fragmented.bin"),
                    Files.readSymbolicLink(fileSystem.getPath("/link"))
            );

            int start = 16 * DMGTestFixtures.SECTOR_SIZE - 17;
            int length = 41;
            ByteBuffer range = ByteBuffer.allocate(length);
            try (SeekableByteChannel channel = Files.newByteChannel(file)) {
                channel.position(start);
                readFully(channel, range);
                assertEquals(start + length, channel.position());
            }
            assertArrayEquals(
                    Arrays.copyOfRange(expected, start, start + length),
                    range.array()
            );
        }
    }

    /// Verifies HFS Plus fork channels preserve positioning, read-only failures, progress, and closed state.
    @Test
    void exposesContractCompliantEntryChannels() throws IOException {
        Path imagePath = createImage("entry-channel-contract.dmg");
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            SeekableByteChannel channel = Files.newByteChannel(fileSystem.getPath("/hello.txt"));
            assertEquals(5L, channel.size());
            assertSame(channel, channel.position(Long.MAX_VALUE));
            assertEquals(0, channel.read(ByteBuffer.allocate(0)));
            assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
            assertEquals(Long.MAX_VALUE, channel.position());
            assertThrows(IllegalArgumentException.class, () -> channel.position(-1L));

            assertSame(channel, channel.position(0L));
            ByteBuffer readOnlyTarget = ByteBuffer.allocate(1).asReadOnlyBuffer();
            assertThrows(ReadOnlyBufferException.class, () -> channel.read(readOnlyTarget));
            assertEquals(0, readOnlyTarget.position());
            assertEquals(0L, channel.position());

            ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
            assertThrows(NonWritableChannelException.class, () -> channel.write(source));
            assertEquals(0, source.position());
            assertThrows(IllegalArgumentException.class, () -> channel.truncate(-1L));
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));

            channel.close();
            channel.close();
            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, channel::position);
            assertThrows(ClosedChannelException.class, channel::size);
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.truncate(0L));
        }
    }

    /// Reads one fragmented fork concurrently through independent channels from the same file system.
    @Test
    @Timeout(30)
    void readsFragmentedForkConcurrently() throws Exception {
        byte[] expected = fragmentedFileContents();
        Path imagePath = writeRawImage(
                temporaryDirectory.resolve("concurrent-fragmented-extents.dmg"),
                createFragmentedHFSPlusDisk()
        );

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            assertEquals(ArkivoFileSystemThreadSafety.CONCURRENT_READ, fileSystem.threadSafety());
            Path file = fileSystem.getPath("/fragmented.bin");
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try {
                List<Future<?>> readers = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    readers.add(executor.submit(() -> {
                        for (int iteration = 0; iteration < 16; iteration++) {
                            assertArrayEquals(expected, Files.readAllBytes(file));
                        }
                        return null;
                    }));
                }
                for (Future<?> reader : readers) {
                    reader.get(20L, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10L, TimeUnit.SECONDS));
            }
        }
    }

    /// Selects an explicit partition and rejects an index outside the discovered list.
    @Test
    void appliesPartitionSelection() throws IOException {
        Path imagePath = createImage("partition-selection.dmg");
        DMGArchiveOptions explicit = DMGArchiveOptions.DEFAULT.withPartitionIndex(0);
        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath, explicit)) {
            assertEquals(0, fileSystem.partition().index());
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }

        DMGArchiveOptions outside = explicit.withPartitionIndex(1);
        IOException exception = assertThrows(
                IOException.class,
                () -> DMGArkivoFileSystem.open(imagePath, outside)
        );
        assertTrue(exception.getMessage().contains("outside the discovered partition list"));
    }

    /// Opens an HFSX signature-and-version pair through the same read-only file-system implementation.
    @Test
    void readsGeneratedHFSXFileSystem() throws IOException {
        byte[] disk = createHFSPlusDisk();
        int volumeHeader = 2 * DMGTestFixtures.SECTOR_SIZE;
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader, (short) 0x4858);
        ByteArrayAccess.writeShortBigEndian(disk, volumeHeader + 2, (short) 5);
        Path imagePath = writeRawImage(temporaryDirectory.resolve("hfsx.dmg"), disk);

        try (DMGArkivoFileSystem fileSystem = DMGArkivoFileSystem.open(imagePath)) {
            assertEquals("hello", Files.readString(fileSystem.getPath("/hello.txt")));
        }
    }

    /// Writes the shared generated HFS Plus disk as one flattened UDIF image.
    private Path createImage(String name) throws IOException {
        return writeRawImage(temporaryDirectory.resolve(name), createHFSPlusDisk());
    }
}
