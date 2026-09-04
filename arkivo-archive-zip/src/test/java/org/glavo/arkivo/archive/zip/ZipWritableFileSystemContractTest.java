// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies NIO infrastructure and entry-channel contracts of writable ZIP file systems.
@NotNullByDefault
final class ZipWritableFileSystemContractTest {
    /// Temporary directory used for path-backed ZIP archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies a forward-only writer exposes coherent NIO infrastructure and rejects unavailable read operations.
    @Test
    void exposesForwardOnlyNioInfrastructure() throws IOException {
        Path archive = temporaryDirectory.resolve("created.zip");
        ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(archive);

        try (fileSystem) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/value.txt");
            assertFalse(fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(ZipArkivoFormat.instance().uriScheme(), fileSystem.provider().getScheme());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(root));
            assertEquals("zip", store.name());
            assertEquals("zip", store.type());
            assertFalse(store.isReadOnly());
            assertEquals(0L, store.getTotalSpace());
            assertEquals(0L, store.getUnallocatedSpace());
            assertEquals(0L, store.getUsableSpace());
            assertTrue(store.supportsFileAttributeView(BasicFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(ZipArkivoEntryAttributeView.class));
            assertFalse(store.supportsFileAttributeView(PosixFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("zip"));
            assertFalse(store.supportsFileAttributeView("posix"));
            assertNull(store.getFileStoreAttributeView(FileStoreAttributeView.class));
            assertEquals(Set.of("basic", "zip", "owner", "posix"), fileSystem.supportedFileAttributeViews());

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/value\\.txt").matches(file));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.getPathMatcher("literal:value.txt")
            );
            UserPrincipalLookupService principals = fileSystem.getUserPrincipalLookupService();
            assertEquals("owner", principals.lookupPrincipalByName("owner").getName());
            assertEquals("group", principals.lookupPrincipalByGroupName("group").getName());
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);

            Files.createDirectory(directory);
            Files.writeString(file, "value", StandardCharsets.UTF_8);
            assertFalse(Files.isHidden(file));
            fileSystem.provider().checkAccess(file, AccessMode.WRITE);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.provider().checkAccess(file, AccessMode.READ)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.provider().checkAccess(file, AccessMode.EXECUTE)
            );

            List<Path> children = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root, path -> path.equals(directory))) {
                stream.forEach(children::add);
            }
            assertEquals(List.of(directory), children);
            assertThrows(NoSuchFileException.class, () -> Files.newDirectoryStream(file).close());
            DirectoryIteratorException filterFailure = assertThrows(
                    DirectoryIteratorException.class,
                    () -> Files.newDirectoryStream(root, path -> {
                        throw new IOException("filter failure");
                    })
            );
            assertEquals("filter failure", filterFailure.getCause().getMessage());

            assertNull(Files.getFileAttributeView(file, DosFileAttributeView.class));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(file, DosFileAttributes.class)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
                    )
            );
            assertThrows(IOException.class, () -> Files.readAllBytes(file));
        }

        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
        assertThrows(ClosedFileSystemException.class, fileSystem::supportedFileAttributeViews);
        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPath("/"));
        assertThrows(ClosedFileSystemException.class, () -> fileSystem.getPathMatcher("glob:*"));
        assertThrows(ClosedFileSystemException.class, fileSystem::getUserPrincipalLookupService);

        try (ZipArkivoFileSystem reader = ZipArkivoFileSystem.open(archive)) {
            assertEquals(
                    "value",
                    Files.readString(reader.getPath("/dir/value.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Verifies stream and channel factories apply defaults or explicit options and close their owned endpoints.
    @Test
    void createsOwnedStreamAndChannelArchives() throws IOException {
        Path defaultStreamArchive = temporaryDirectory.resolve("default-stream.zip");
        OutputStream defaultStream = Files.newOutputStream(defaultStreamArchive);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(defaultStream)) {
            Files.writeString(fileSystem.getPath("/default-stream.txt"), "default stream", StandardCharsets.UTF_8);
        }
        assertThrows(IOException.class, () -> defaultStream.write(0));
        assertArchiveEntry(defaultStreamArchive, "/default-stream.txt", "default stream");

        Path explicitStreamArchive = temporaryDirectory.resolve("explicit-stream.zip");
        OutputStream explicitStream = Files.newOutputStream(explicitStreamArchive);
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(
                explicitStream,
                ZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.writeString(fileSystem.getPath("/explicit-stream.txt"), "explicit stream", StandardCharsets.UTF_8);
        }
        assertThrows(IOException.class, () -> explicitStream.write(0));
        assertArchiveEntry(explicitStreamArchive, "/explicit-stream.txt", "explicit stream");

        Path defaultChannelArchive = temporaryDirectory.resolve("default-channel.zip");
        WritableByteChannel defaultChannel = Files.newByteChannel(
                defaultChannelArchive,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(defaultChannel)) {
            Files.writeString(fileSystem.getPath("/default-channel.txt"), "default channel", StandardCharsets.UTF_8);
        }
        assertFalse(defaultChannel.isOpen());
        assertArchiveEntry(defaultChannelArchive, "/default-channel.txt", "default channel");

        Path explicitChannelArchive = temporaryDirectory.resolve("explicit-channel.zip");
        WritableByteChannel explicitChannel = Files.newByteChannel(
                explicitChannelArchive,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.create(
                explicitChannel,
                ZipArchiveOptions.CREATE_DEFAULTS
        )) {
            Files.writeString(fileSystem.getPath("/explicit-channel.txt"), "explicit channel", StandardCharsets.UTF_8);
        }
        assertFalse(explicitChannel.isOpen());
        assertArchiveEntry(explicitChannelArchive, "/explicit-channel.txt", "explicit channel");
    }

    /// Verifies complete-rewrite channels validate options, isolate pending content, and persist creation metadata.
    @Test
    void enforcesUpdateEntryChannelContracts() throws IOException {
        Path archive = createArchive();
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.update(archive)) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/existing.txt");
            Path missing = fileSystem.getPath("/missing.txt");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.READ),
                            PosixFilePermissions.asFileAttribute(permissions)
                    )
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(file, Set.of(LinkOption.NOFOLLOW_LINKS))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(missing, Set.of(StandardOpenOption.CREATE))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(file, Set.of(StandardOpenOption.APPEND, StandardOpenOption.READ))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.APPEND, StandardOpenOption.TRUNCATE_EXISTING)
                    )
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE)
                    )
            );
            assertThrows(
                    NoSuchFileException.class,
                    () -> Files.newByteChannel(missing, Set.of(StandardOpenOption.WRITE))
            );
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> Files.newByteChannel(file, Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW))
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(directory, Set.of(StandardOpenOption.WRITE))
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(root, Set.of(StandardOpenOption.WRITE))
            );

            Path created = fileSystem.getPath("/created.bin");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    created,
                    Set.of(
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE
                    ),
                    PosixFilePermissions.asFileAttribute(permissions)
            )) {
                assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
                channel.position(0L);
                ByteBuffer target = ByteBuffer.allocate(3);
                assertEquals(3, channel.read(target));
                assertArrayEquals(new byte[]{1, 2, 3}, target.array());

                assertThrows(IOException.class, () -> Files.readAllBytes(created));
                assertThrows(
                        IOException.class,
                        () -> Files.newByteChannel(file, Set.of(StandardOpenOption.WRITE))
                );
                assertThrows(IOException.class, () -> Files.delete(file));
                assertThrows(
                        IOException.class,
                        () -> Files.setAttribute(file, "zip:internalAttributes", 5)
                );
            }

            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(created));
            assertEquals(
                    permissions,
                    Files.readAttributes(created, PosixFileAttributes.class).permissions()
            );
        }

        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            Path created = fileSystem.getPath("/created.bin");
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(created));
            assertEquals(
                    permissions,
                    Files.readAttributes(created, PosixFileAttributes.class).permissions()
            );
        }
    }

    /// Creates an archive containing one regular file below a synthetic directory.
    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("sample.zip");
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.create(archive)) {
            try (OutputStream output = writer.beginFile("dir/existing.txt").openOutputStream()) {
                output.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archive;
    }

    /// Verifies one path-backed ZIP archive contains the expected UTF-8 text entry.
    private static void assertArchiveEntry(Path archive, String entry, String expected) throws IOException {
        try (ZipArkivoFileSystem fileSystem = ZipArkivoFileSystem.open(archive)) {
            assertEquals(expected, Files.readString(fileSystem.getPath(entry), StandardCharsets.UTF_8));
        }
    }
}
