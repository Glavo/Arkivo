// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArkivoSeekableChannelSource;
import org.glavo.arkivo.archive.rar.internal.RarArkivoFileSystemProvider;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
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
import java.util.zip.CRC32;

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
                storedRar4Archive("dir/value.txt".getBytes(StandardCharsets.US_ASCII), content)
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
                storedRar4Archive("value.txt".getBytes(StandardCharsets.US_ASCII), content)
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
                storedRar4Archive(new byte[]{(byte) 0xe4}, content)
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
                RarArchiveOptions.READ_DEFAULTS.withLegacyCharsetDetector(detector)
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
                storedRar4Archive("value.txt".getBytes(StandardCharsets.US_ASCII), new byte[]{1})
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

    /// Creates one minimal RAR4 archive containing a stored regular file with the supplied raw name bytes.
    private static byte[] storedRar4Archive(byte[] name, byte[] body) {
        byte[] fields = new byte[25 + name.length];
        ByteArrayAccess.writeIntLittleEndian(fields, 0, body.length);
        ByteArrayAccess.writeIntLittleEndian(fields, 4, body.length);
        fields[8] = RarArkivoEntryAttributes.HOST_OS_UNIX;
        CRC32 bodyChecksum = new CRC32();
        bodyChecksum.update(body);
        ByteArrayAccess.writeIntLittleEndian(fields, 9, (int) bodyChecksum.getValue());
        ByteArrayAccess.writeIntLittleEndian(fields, 13, 0);
        fields[17] = 29;
        fields[18] = 0x30;
        ByteArrayAccess.writeShortLittleEndian(fields, 19, (short) name.length);
        ByteArrayAccess.writeIntLittleEndian(fields, 21, 0100644);
        System.arraycopy(name, 0, fields, 25, name.length);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(new byte[]{'R', 'a', 'r', '!', 0x1a, 0x07, 0x00});
        output.writeBytes(rar4BlockHeader(0x73, 0, new byte[6]));
        output.writeBytes(rar4BlockHeader(0x74, 0x8000, fields));
        output.writeBytes(body);
        output.writeBytes(rar4BlockHeader(0x7b, 0, new byte[0]));
        return output.toByteArray();
    }

    /// Encodes one complete RAR4 block header with its low CRC-32 bits stored as CRC-16.
    private static byte[] rar4BlockHeader(int type, int flags, byte[] fields) {
        byte[] headerData = new byte[5 + fields.length];
        headerData[0] = (byte) type;
        ByteArrayAccess.writeShortLittleEndian(headerData, 1, (short) flags);
        ByteArrayAccess.writeShortLittleEndian(headerData, 3, (short) (headerData.length + Short.BYTES));
        System.arraycopy(fields, 0, headerData, 5, fields.length);

        CRC32 checksum = new CRC32();
        checksum.update(headerData);
        byte[] header = new byte[Short.BYTES + headerData.length];
        ByteArrayAccess.writeShortLittleEndian(header, 0, (short) checksum.getValue());
        System.arraycopy(headerData, 0, header, Short.BYTES, headerData.length);
        return header;
    }
}
