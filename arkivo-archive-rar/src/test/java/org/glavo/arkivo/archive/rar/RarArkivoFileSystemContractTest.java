// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies the NIO infrastructure, metadata, and cached-content contracts of indexed RAR file systems.
@NotNullByDefault
final class RarArkivoFileSystemContractTest {
    /// Temporary directory used for generated RAR4 archives and copied entries.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies roots, stores, matchers, principals, views, directory filters, and read-only operations.
    @Test
    void exposesNioFileSystemInfrastructure() throws IOException {
        byte[] content = "value".getBytes(StandardCharsets.US_ASCII);
        Path archive = Files.write(
                temporaryDirectory.resolve("contract.rar"),
                RarTestArchiveFixtures.rar4StoredArchive(
                        "dir/value.txt".getBytes(StandardCharsets.US_ASCII),
                        false,
                        content
                )
        );
        RarArkivoFileSystemProvider provider = new RarArkivoFileSystemProvider();
        RarArkivoFileSystem fileSystem = provider.newFileSystem(archive, Map.of());

        try (fileSystem) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/value.txt");
            assertSame(provider, fileSystem.provider());
            assertEquals(RarArkivoFormat.instance().uriScheme(), provider.getScheme());
            assertEquals("/", fileSystem.getSeparator());
            assertTrue(fileSystem.isReadOnly());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));
            assertEquals(archive.toString(), store.name());
            assertEquals("rar", store.type());
            assertTrue(store.isReadOnly());
            assertEquals(Files.size(archive), store.getTotalSpace());
            assertEquals(0L, store.getUnallocatedSpace());
            assertEquals(0L, store.getUsableSpace());
            assertEquals(Set.of("basic", "owner", "posix", "rar"), fileSystem.supportedFileAttributeViews());
            assertTrue(store.supportsFileAttributeView(BasicFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView(RarArkivoEntryAttributeView.class));
            assertFalse(store.supportsFileAttributeView(DosFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("rar"));
            assertFalse(store.supportsFileAttributeView("dos"));
            assertNull(store.getFileStoreAttributeView(FileStoreAttributeView.class));

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertFalse(fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/value\\.txt").matches(file));
            assertThrows(NullPointerException.class, () -> fileSystem.getPathMatcher(null));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher("glob"));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher(":*.txt"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.getPathMatcher("literal:value.txt")
            );

            UserPrincipalLookupService principals = fileSystem.getUserPrincipalLookupService();
            assertEquals("archive-user", principals.lookupPrincipalByName("archive-user").getName());
            assertEquals("archive-group", principals.lookupPrincipalByGroupName("archive-group").getName());
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);

            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            assertEquals(content.length, basicView.readAttributes().size());
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> basicView.setTimes(FileTime.fromMillis(1L), null, null)
            );

            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> posixView.setTimes(FileTime.fromMillis(1L), null, null)
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> posixView.setOwner(() -> "other"));
            assertNull(Files.getFileAttributeView(file, DosFileAttributeView.class));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(file, DosFileAttributes.class)
            );

            assertNamedAttributes(file);

            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                Iterator<Path> iterator = children.iterator();
                assertEquals(directory, iterator.next());
                assertFalse(iterator.hasNext());
            }
            try (DirectoryStream<Path> rejected = Files.newDirectoryStream(root, ignored -> false)) {
                assertFalse(rejected.iterator().hasNext());
            }
            DirectoryIteratorException filterFailure = assertThrows(
                    DirectoryIteratorException.class,
                    () -> Files.newDirectoryStream(root, ignored -> {
                        throw new IOException("filter failure");
                    })
            );
            assertEquals("filter failure", filterFailure.getCause().getMessage());
            assertThrows(FileSystemException.class, () -> Files.newDirectoryStream(file));
            assertThrows(FileSystemException.class, () -> Files.newInputStream(directory));
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(directory, StandardOpenOption.READ)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newInputStream(file, StandardOpenOption.WRITE)
            );

            assertTrue(Files.isReadable(file));
            assertFalse(Files.isWritable(file));
            assertFalse(Files.isExecutable(file));
            provider.checkAccess(file, AccessMode.READ);
            assertThrows(ProviderMismatchException.class, () -> provider.isHidden(archive));
            assertFalse(Files.isHidden(file));
            assertTrue(Files.isSameFile(file, fileSystem.getPath("/dir/./value.txt")));

            Path copied = temporaryDirectory.resolve("copied.txt");
            provider.copy(file, copied, StandardCopyOption.COPY_ATTRIBUTES);
            assertArrayEquals(content, Files.readAllBytes(copied));
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.newOutputStream(file));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.createDirectory(fileSystem.getPath("/created"))
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> Files.delete(file));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.move(file, copied));
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", FileTime.fromMillis(1L))
            );
        }

        assertThrows(ClosedFileSystemException.class, fileSystem::getRootDirectories);
        assertThrows(ClosedFileSystemException.class, fileSystem::getFileStores);
    }

    /// Verifies one lazily materialized body exposes independent read-only seekable channel behavior.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void implementsCachedContentChannelContract() throws IOException {
        byte[] content = "cached content".getBytes(StandardCharsets.US_ASCII);
        Path archive = Files.write(
                temporaryDirectory.resolve("cached.rar"),
                RarTestArchiveFixtures.rar4StoredArchive(
                        "value.txt".getBytes(StandardCharsets.US_ASCII),
                        false,
                        content
                )
        );

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(archive)) {
            SeekableByteChannel channel = Files.newByteChannel(
                    fileSystem.getPath("/value.txt"),
                    StandardOpenOption.READ
            );
            assertTrue(channel.isOpen());
            assertEquals(0L, channel.position());
            assertEquals(content.length, channel.size());
            assertSame(channel, channel.position(2L));
            ByteBuffer target = ByteBuffer.allocateDirect(4);
            assertEquals(4, channel.read(target));
            target.flip();
            byte[] actual = new byte[target.remaining()];
            target.get(actual);
            assertArrayEquals("ched".getBytes(StandardCharsets.US_ASCII), actual);

            ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
            assertThrows(NonWritableChannelException.class, () -> channel.write(source));
            assertEquals(0, source.position());
            assertThrows(NonWritableChannelException.class, () -> channel.truncate(0L));
            assertThrows(NullPointerException.class, () -> channel.write(null));

            channel.close();
            assertFalse(channel.isOpen());
            channel.close();
            assertThrows(ClosedChannelException.class, channel::position);
            assertThrows(ClosedChannelException.class, channel::size);
            assertThrows(ClosedChannelException.class, () -> channel.position(0L));
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.write(ByteBuffer.allocate(1)));
            assertThrows(ClosedChannelException.class, () -> channel.truncate(0L));
        }
    }

    /// Verifies typed and NIO configurations apply a legacy-name charset detector.
    @Test
    void appliesLegacyCharsetDetectorConfigurations() throws IOException {
        byte[] content = {1, 2, 3};
        Path archive = Files.write(
                temporaryDirectory.resolve("legacy-name.rar"),
                RarTestArchiveFixtures.rar4StoredArchive(new byte[]{(byte) 0xe4}, false, content)
        );
        ArchiveMetadataCharsetDetector detector = ArchiveMetadataCharsetDetector.fixed(
                StandardCharsets.ISO_8859_1
        );
        RarArkivoFileSystemProvider provider = new RarArkivoFileSystemProvider();

        try (RarArkivoFileSystem fileSystem = provider.newFileSystem(
                archive,
                Map.of("arkivo.rar.legacyCharsetDetector", detector)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/ä")));
        }
        try (RarArkivoFileSystem fileSystem = provider.newFileSystem(
                archive,
                Map.of("arkivo.rar.legacyCharsetDetector", StandardCharsets.ISO_8859_1)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/ä")));
        }
        try (RarArkivoFileSystem fileSystem = provider.newFileSystem(
                archive,
                Map.of("arkivo.rar.legacyCharsetDetector", "ISO-8859-1")
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/ä")));
        }
        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                archive,
                RarArchiveOptions.DEFAULT.withLegacyCharsetDetector(detector)
        )) {
            assertArrayEquals(content, Files.readAllBytes(fileSystem.getPath("/ä")));
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of("arkivo.rar.legacyCharsetDetector", 1)
                )
        );
    }

    /// Verifies channel-backed file stores report the absence of a physical archive path.
    @Test
    void exposesPathlessFileStore() throws IOException {
        Path archive = Files.write(
                temporaryDirectory.resolve("channel-source.rar"),
                RarTestArchiveFixtures.rar4StoredArchive(
                        "value.txt".getBytes(StandardCharsets.US_ASCII),
                        false,
                        new byte[]{1}
                )
        );
        SeekableByteChannel backing = Files.newByteChannel(archive, StandardOpenOption.READ);

        try (RarArkivoFileSystem fileSystem = RarArkivoFileSystem.open(
                ArkivoSeekableChannelSource.of(backing)
        )) {
            FileStore store = fileSystem.getFileStores().iterator().next();
            assertEquals("rar", store.name());
            assertEquals(0L, store.getTotalSpace());
        }

        assertFalse(backing.isOpen());
    }

    /// Verifies failed construction preserves a shared volume-open and source-close failure without self-suppression.
    @Test
    void preservesSharedVolumeOpenAndSourceCloseFailure() {
        IOException sharedFailure = new IOException("shared volume failure");
        FailingVolumeSource source = new FailingVolumeSource(sharedFailure, sharedFailure);

        IOException failure = assertThrows(
                IOException.class,
                () -> RarArkivoFileSystem.open(source, RarArchiveOptions.DEFAULT)
        );

        assertSame(sharedFailure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(1, source.closeCount());
    }

    /// Verifies failed construction suppresses a distinct source-close failure behind the volume-open failure.
    @Test
    void suppressesDistinctSourceCloseFailureAfterVolumeOpenFailure() {
        IOException openFailure = new IOException("volume open failure");
        IOException closeFailure = new IOException("source close failure");
        FailingVolumeSource source = new FailingVolumeSource(openFailure, closeFailure);

        IOException failure = assertThrows(
                IOException.class,
                () -> RarArkivoFileSystem.open(source, RarArchiveOptions.DEFAULT)
        );

        assertSame(openFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
        assertSame(closeFailure, failure.getSuppressed()[0]);
        assertEquals(1, source.closeCount());
    }

    /// Verifies wildcard named views expose their complete immutable attribute sets.
    private static void assertNamedAttributes(Path file) throws IOException {
        Map<String, Object> basic = Files.readAttributes(file, "basic:*");
        assertEquals(Set.of(
                "size",
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "isDirectory",
                "isRegularFile",
                "isSymbolicLink",
                "isOther",
                "fileKey"
        ), basic.keySet());

        Map<String, Object> owner = Files.readAttributes(file, "owner:*");
        assertEquals(Set.of("owner"), owner.keySet());

        Map<String, Object> posix = Files.readAttributes(file, "posix:*");
        assertEquals(Set.of(
                "size",
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "isDirectory",
                "isRegularFile",
                "isSymbolicLink",
                "isOther",
                "fileKey",
                "owner",
                "group",
                "permissions"
        ), posix.keySet());

        Map<String, Object> rar = Files.readAttributes(file, "rar:*");
        assertEquals(Set.of(
                "size",
                "lastModifiedTime",
                "lastAccessTime",
                "creationTime",
                "isDirectory",
                "isRegularFile",
                "isSymbolicLink",
                "isOther",
                "fileKey",
                "path",
                "hostOs",
                "fileAttributes",
                "compressionMethod",
                "packedSize",
                "unpackedSize",
                "dataCrc32",
                "blake2spHash",
                "isEncrypted",
                "continuesFromPreviousVolume",
                "continuesInNextVolume",
                "linkName",
                "redirectionType",
                "redirectionFlags",
                "redirectionTarget",
                "redirectionTargetDirectory",
                "userName",
                "groupName",
                "userId",
                "groupId"
        ), rar.keySet());

        assertThrows(UnsupportedOperationException.class, () -> basic.put("size", 0L));
        assertThrows(IllegalArgumentException.class, () -> Files.readAttributes(file, "basic:"));
        assertThrows(UnsupportedOperationException.class, () -> Files.readAttributes(file, "dos:*"));
        assertEquals(Map.of("size", Files.size(file)), Files.readAttributes(file, "size,unknown"));
    }

    /// Reports configurable volume-open and source-close failures.
    @NotNullByDefault
    private static final class FailingVolumeSource implements ArkivoVolumeSource {
        /// Failure reported while opening any volume.
        private final IOException openFailure;

        /// Failure reported while closing the source.
        private final IOException closeFailure;

        /// Number of source-close calls.
        private int closeCount;

        /// Creates a source with the supplied failures.
        private FailingVolumeSource(IOException openFailure, IOException closeFailure) {
            this.openFailure = openFailure;
            this.closeFailure = closeFailure;
        }

        /// Reports the configured volume-open failure.
        @Override
        public SeekableByteChannel openVolume(long index) throws IOException {
            throw openFailure;
        }

        /// Records source closure and reports the configured failure.
        @Override
        public void close() throws IOException {
            closeCount++;
            throw closeFailure;
        }

        /// Returns the number of source-close calls.
        private int closeCount() {
            return closeCount;
        }
    }
}
