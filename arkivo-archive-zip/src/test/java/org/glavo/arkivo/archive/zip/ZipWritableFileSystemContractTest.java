// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.Charset;
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
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /// Rejects non-directory parents before emitting records and leaves the archive usable after each failure.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCreationBelowRegularFiles(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("file-parent.zip");
        try (var fs = ZipArkivoFileSystem.create(archive)) {
            Files.writeString(fs.getPath("parent"), "preserved");
            Files.createSymbolicLink(fs.getPath("alias"), fs.getPath("parent"));
            if (!update) assertNonDirectoryParentsRejected(fs);
        }
        if (update) {
            try (var fs = ZipArkivoFileSystem.update(archive)) {
                assertNonDirectoryParentsRejected(fs);
            }
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertEquals("preserved", Files.readString(fs.getPath("parent")));
            assertEquals("valid", Files.readString(fs.getPath("valid")));
            assertTrue(Files.isSymbolicLink(fs.getPath("alias")));
            try (var entries = Files.list(fs.getPath("/"))) {
                assertEquals(3, entries.count());
            }
        }
    }

    /// Checks all NIO creation entry points, including a parent that cannot be cancelled by `..`.
    private static void assertNonDirectoryParentsRejected(ZipArkivoFileSystem fs) throws IOException {
        for (String name : List.of("parent/child", "parent/../child", "alias/child")) {
            Path path = fs.getPath(name);
            assertThrows(NotDirectoryException.class, () -> Files.createFile(path));
            assertThrows(NotDirectoryException.class, () -> Files.createDirectory(path));
            assertThrows(NotDirectoryException.class,
                    () -> Files.createSymbolicLink(path, fs.getPath("target")));
            assertThrows(NotDirectoryException.class, () -> {
                try (var ignored = Files.newByteChannel(path,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) { }
            });
            assertThrows(NotDirectoryException.class, () -> {
                try (var ignored = Files.newOutputStream(path)) { }
            });
        }
        Files.writeString(fs.getPath("valid"), "valid");
    }

    /// Resolves existing parent links during creation while retaining support for implicit directories.
    @Test
    void resolvesCreationParents() throws IOException {
        Path archive = temporaryDirectory.resolve("creation-parents.zip");
        try (var fs = ZipArkivoFileSystem.create(archive)) {
            Files.createDirectory(fs.getPath("real/sub"));
            Files.createSymbolicLink(fs.getPath("alias"), fs.getPath("real/sub"));
            Files.writeString(fs.getPath("alias/../value"), "value");
            Files.createFile(fs.getPath("alias/file"));
            Files.createDirectory(fs.getPath("alias/directory"));
            Files.createSymbolicLink(fs.getPath("alias/link"), fs.getPath("file"));
            Files.writeString(fs.getPath("implicit/nested/value"), "nested");
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertEquals("value", Files.readString(fs.getPath("real/value")));
            assertFalse(Files.exists(fs.getPath("value")));
            assertTrue(Files.isRegularFile(fs.getPath("real/sub/file")));
            assertTrue(Files.isDirectory(fs.getPath("real/sub/directory")));
            assertEquals(fs.getPath("file"), Files.readSymbolicLink(fs.getPath("real/sub/link")));
            assertEquals("nested", Files.readString(fs.getPath("implicit/nested/value")));
        }
    }

    /// Verifies that store capabilities agree with readable views in create, update, and read sessions.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void reportsAvailableFileStoreViews(int mode) throws IOException {
        Path archive = temporaryDirectory.resolve("views.zip");
        if (mode != 0) {
            try (var fs = ZipArkivoFileSystem.create(archive)) {
                Files.writeString(fs.getPath("entry"), "value");
            }
        }
        try (var fs = switch (mode) {
            case 0 -> ZipArkivoFileSystem.create(archive);
            case 1 -> ZipArkivoFileSystem.update(archive);
            case 2 -> ZipArkivoFileSystem.open(archive);
            default -> throw new AssertionError(mode);
        }) {
            Path entry = fs.getPath("entry");
            if (mode == 0) {
                Files.writeString(entry, "value");
            }
            FileStore store = Files.getFileStore(entry);
            assertSame(store, fs.getFileStores().iterator().next());
            assertEquals(mode == 2, store.isReadOnly());
            for (Path path : List.of(fs.getPath("/"), entry)) {
                @Nullable var owner = Files.getFileAttributeView(path, FileOwnerAttributeView.class);
                @Nullable var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class);
                assertNotNull(owner);
                assertNotNull(posix);
                assertEquals(Files.getOwner(path), owner.getOwner());
                assertEquals(Files.getPosixFilePermissions(path), posix.readAttributes().permissions());
                assertEquals(owner.getOwner(), posix.readAttributes().owner());
            }
            assertTrue(store.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
            for (String view : fs.supportedFileAttributeViews()) {
                assertTrue(store.supportsFileAttributeView(view), view);
            }
            assertFalse(store.supportsFileAttributeView(DosFileAttributeView.class));
            assertFalse(store.supportsFileAttributeView("dos"));
        }
    }

    /// Repeated renames preserve legacy comments and keep source and staged local headers consistent.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesEncodingAcrossRenames(boolean staged) throws IOException {
        Charset charset = Charset.forName("IBM437");
        var decoder = ArchiveMetadataDecoder.forCharset(charset);
        for (String original : List.of("A", "café")) {
            for (String comment : List.of("", "plain", "café")) {
                for (String destination : List.of("B", "目录", original)) {
                    Path archive = temporaryDirectory.resolve("renamed.zip");
                    try (var output = new ZipOutputStream(Files.newOutputStream(archive), charset)) {
                        ZipEntry entry = new ZipEntry(original);
                        entry.setComment(comment);
                        output.putNextEntry(entry);
                        output.write("payload".getBytes(StandardCharsets.UTF_8));
                    }
                    try (var fs = ZipArkivoFileSystem.update(archive,
                            ZipArchiveOptions.UPDATE_DEFAULTS.withLegacyMetadataDecoder(decoder))) {
                        if (staged) {
                            Files.writeString(fs.getPath(original), "payload");
                        }
                        Files.move(fs.getPath(original), fs.getPath("intermediate"));
                        Files.move(fs.getPath("intermediate"), fs.getPath(destination));
                        assertEquals("payload", Files.readString(fs.getPath(destination)));
                    }
                    try (var fs = ZipArkivoFileSystem.open(archive,
                            ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder))) {
                        assertEquals("payload", Files.readString(fs.getPath(destination)));
                        byte @Nullable [] actual = Files.readAttributes(fs.getPath(destination),
                                ZipArkivoEntryAttributes.class).rawComment();
                        assertArrayEquals(comment.getBytes(charset), actual == null ? new byte[0] : actual);
                    }
                    try (var zip = new ZipFile(archive.toFile(), charset)) {
                        ZipEntry entry = zip.entries().nextElement();
                        assertEquals(comment.isEmpty() ? null : comment, entry.getComment());
                        try (var input = zip.getInputStream(entry)) {
                            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    try (var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                            .setPath(archive).setCharset(charset).get()) {
                        @Nullable var entry = zip.getEntry(destination);
                        assertNotNull(entry);
                        assertEquals(comment, entry.getComment());
                        try (var input = zip.getInputStream(entry)) {
                            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                    try (var reader = ZipArkivoStreamingReader.open(archive,
                            ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(decoder))) {
                        assertTrue(reader.next());
                        assertEquals(destination, reader.readAttributes().path());
                        try (var input = reader.openInputStream()) {
                            assertEquals("payload", new String(input.readAllBytes(), StandardCharsets.UTF_8));
                        }
                        assertFalse(reader.next());
                    }
                }
            }
        }
    }

    /// Treats implicit directories as occupied names for both link and directory creation.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsLinksOverImplicitDirectories(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("implicit-directory.zip");
        if (update) {
            try (var fs = ZipArkivoFileSystem.create(archive)) {
                Files.writeString(fs.getPath("/implicit/child"), "child");
            }
        }
        try (var fs = update ? ZipArkivoFileSystem.update(archive) : ZipArkivoFileSystem.create(archive)) {
            if (!update) {
                Files.writeString(fs.getPath("/implicit/child"), "child");
            }
            Path directory = fs.getPath("/implicit");
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.createSymbolicLink(directory, fs.getPath("target")));
            assertTrue(Files.isDirectory(directory));
            assertTrue(Files.exists(directory.resolve("child")));
            assertThrows(FileAlreadyExistsException.class, () -> Files.createDirectory(directory));
            Files.writeString(fs.getPath("/valid"), "valid");
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertTrue(Files.isDirectory(fs.getPath("/implicit")));
            assertEquals("child", Files.readString(fs.getPath("/implicit/child")));
            assertEquals("valid", Files.readString(fs.getPath("/valid")));
        }
    }

    /// Rejects creating existing root, explicit, and implicit directories in create and update sessions.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsCreationOfExistingDirectories(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("existing-directories.zip");
        try (var fs = ZipArkivoFileSystem.create(archive)) {
            Files.writeString(fs.getPath("implicit/child"), "child");
            Files.createDirectory(fs.getPath("explicit"));
            if (!update) assertExistingDirectoryCreationRejected(fs);
        }
        if (update) {
            try (var fs = ZipArkivoFileSystem.update(archive)) {
                assertExistingDirectoryCreationRejected(fs);
            }
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertEquals("child", Files.readString(fs.getPath("implicit/child")));
            assertTrue(Files.isDirectory(fs.getPath("explicit")));
        }
    }

    /// Rejects duplicate creation without changing permissions, while recursive creation remains idempotent.
    private static void assertExistingDirectoryCreationRejected(ZipArkivoFileSystem fs) throws IOException {
        for (String name : new String[]{"/", "/implicit", "/explicit"}) {
            Path path = fs.getPath(name);
            Set<PosixFilePermission> before = Files.getPosixFilePermissions(path);
            assertThrows(FileAlreadyExistsException.class, () -> Files.createDirectory(path,
                    PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ))));
            assertEquals(before, Files.getPosixFilePermissions(path));
            assertEquals(path, Files.createDirectories(path));
        }
    }

    /// Preserves explicitly empty permissions for newly created entries and updated files after reopening.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesEmptyPosixPermissions(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("empty-permissions.zip");
        if (update) {
            try (var fs = ZipArkivoFileSystem.create(archive)) {
                Files.writeString(fs.getPath("existing"), "content");
            }
        }
        try (var fs = update ? ZipArkivoFileSystem.update(archive) : ZipArkivoFileSystem.create(archive)) {
            var permissions = PosixFilePermissions.asFileAttribute(Set.<PosixFilePermission>of());
            Files.createFile(fs.getPath("file"), permissions);
            Files.createDirectory(fs.getPath("directory"), permissions);
            Files.createSymbolicLink(fs.getPath("link"), fs.getPath("file"), permissions);
            for (String name : new String[]{"file", "directory", "link"}) {
                assertEquals(Set.of(), Files.readAttributes(fs.getPath(name), PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).permissions());
            }
            if (update) Files.setPosixFilePermissions(fs.getPath("existing"), Set.of());
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            for (String name : new String[]{"file", "directory", "link"}) {
                assertEquals(Set.of(), Files.readAttributes(fs.getPath(name), PosixFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).permissions());
            }
            assertTrue(Files.isRegularFile(fs.getPath("file")));
            assertTrue(Files.isDirectory(fs.getPath("directory")));
            assertTrue(Files.isSymbolicLink(fs.getPath("link")));
            if (update) {
                assertEquals(Set.of(), Files.getPosixFilePermissions(fs.getPath("existing")));
                assertEquals("content", Files.readString(fs.getPath("existing")));
            }
        }
    }

    /// Rejects body writes to explicit and synthetic directories without damaging their entries.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsDirectoryBodiesBeforeWriting(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("directories.zip");
        if (update) {
            try (var fs = ZipArkivoFileSystem.create(archive)) {
                Files.createDirectory(fs.getPath("/explicit"));
                Files.writeString(fs.getPath("/implicit/child"), "child");
            }
        }
        try (var fs = update ? ZipArkivoFileSystem.update(archive) : ZipArkivoFileSystem.create(archive)) {
            if (!update) {
                Files.createDirectory(fs.getPath("/explicit"));
                Files.writeString(fs.getPath("/implicit/child"), "child");
            }
            for (String name : List.of("/explicit", "/implicit")) {
                Path directory = fs.getPath(name);
                assertThrows(FileSystemException.class, () -> Files.newOutputStream(directory).close());
                assertThrows(FileSystemException.class, () -> Files.newByteChannel(directory,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).close());
                assertTrue(Files.isDirectory(directory));
            }
            Files.writeString(fs.getPath("/valid"), "valid");
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertTrue(Files.isDirectory(fs.getPath("/explicit")));
            assertTrue(Files.isDirectory(fs.getPath("/implicit")));
            assertEquals("child", Files.readString(fs.getPath("/implicit/child")));
            assertEquals("valid", Files.readString(fs.getPath("/valid")));
        }
    }

    /// Requires explicit creation options when callers supply output options.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requiresCreateForMissingEntries(boolean update) throws IOException {
        Path archive = temporaryDirectory.resolve("creation-options.zip");
        if (update) {
            try (var ignored = ZipArkivoFileSystem.create(archive)) {
            }
        }
        try (var fs = update ? ZipArkivoFileSystem.update(archive) : ZipArkivoFileSystem.create(archive)) {
            Path missing = fs.getPath("/missing");
            assertThrows(NoSuchFileException.class,
                    () -> Files.newOutputStream(missing, StandardOpenOption.WRITE).close());
            assertThrows(NoSuchFileException.class,
                    () -> Files.newOutputStream(missing, StandardOpenOption.TRUNCATE_EXISTING).close());
            assertThrows(NoSuchFileException.class,
                    () -> Files.newByteChannel(missing, StandardOpenOption.WRITE).close());
            assertFalse(Files.exists(missing));
            Files.writeString(fs.getPath("/default"), "default");
            Files.writeString(fs.getPath("/explicit"), "explicit", StandardOpenOption.CREATE_NEW);
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            assertFalse(Files.exists(fs.getPath("/missing")));
            assertEquals("default", Files.readString(fs.getPath("/default")));
            assertEquals("explicit", Files.readString(fs.getPath("/explicit")));
        }
    }

    /// Resolves update paths before selecting entries while retaining terminal links for namespace operations.
    @Test
    void resolvesUpdatePathsWithoutChangingLexicalNeighbors() throws IOException {
        Path archive = temporaryDirectory.resolve("update-paths.zip");
        try (var fs = ZipArkivoFileSystem.create(archive)) {
            Files.createDirectories(fs.getPath("/real/sub"));
            for (String name : List.of("value", "deleted", "moved")) {
                Files.writeString(fs.getPath("/" + name), "ROOT");
                Files.writeString(fs.getPath("/real/" + name), "REAL");
            }
            Files.createSymbolicLink(fs.getPath("/alias"), fs.getPath("real/sub"));
            Files.createSymbolicLink(fs.getPath("/filelink"), fs.getPath("real/value"));
            Files.createSymbolicLink(fs.getPath("/dangling"), fs.getPath("real/new"));
        }
        try (var fs = ZipArkivoFileSystem.update(archive)) {
            Files.writeString(fs.getPath("/alias/../value"), "STREAM");
            assertEquals("STREAM", Files.readString(fs.getPath("/real/value")));
            assertEquals("ROOT", Files.readString(fs.getPath("/value")));
            try (var channel = Files.newByteChannel(fs.getPath("/filelink"),
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                channel.write(ByteBuffer.wrap("CHANNEL".getBytes(StandardCharsets.UTF_8)));
            }
            assertEquals("CHANNEL", Files.readString(fs.getPath("/real/value")));
            assertTrue(Files.isSymbolicLink(fs.getPath("/filelink")));
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.newByteChannel(fs.getPath("/filelink"),
                            StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW).close());
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.newOutputStream(fs.getPath("/dangling"), StandardOpenOption.CREATE_NEW).close());
            Files.writeString(fs.getPath("/dangling"), "NEW");
            assertTrue(Files.isSymbolicLink(fs.getPath("/dangling")));
            assertEquals("NEW", Files.readString(fs.getPath("/real/new")));
            Files.delete(fs.getPath("/alias/../deleted"));
            Files.move(fs.getPath("/alias/../moved"), fs.getPath("/alias/../renamed"));
            Files.createDirectory(fs.getPath("/alias/../created"));
            Files.createSymbolicLink(fs.getPath("/alias/../link"), fs.getPath("value"));
            Files.move(fs.getPath("/filelink"), fs.getPath("/moved-link"), StandardCopyOption.REPLACE_EXISTING);
            assertTrue(Files.isSymbolicLink(fs.getPath("/moved-link")));
            Files.delete(fs.getPath("/moved-link"));
            assertEquals("CHANNEL", Files.readString(fs.getPath("/real/value")));
            assertThrows(NoSuchFileException.class, () -> Files.writeString(fs.getPath("/absent/../value"), "BAD"));
            assertThrows(NotDirectoryException.class, () -> Files.writeString(fs.getPath("/value/../value"), "BAD"));
            assertThrows(NoSuchFileException.class, () -> Files.delete(fs.getPath("/absent/../value")));
            assertThrows(NoSuchFileException.class,
                    () -> Files.move(fs.getPath("/value"), fs.getPath("/absent/../value")));
        }
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            for (String name : List.of("value", "deleted", "moved")) {
                assertEquals("ROOT", Files.readString(fs.getPath("/" + name)));
            }
            assertEquals("CHANNEL", Files.readString(fs.getPath("/real/value")));
            assertFalse(Files.exists(fs.getPath("/real/deleted")));
            assertFalse(Files.exists(fs.getPath("/real/moved")));
            assertEquals("REAL", Files.readString(fs.getPath("/real/renamed")));
            assertTrue(Files.isDirectory(fs.getPath("/real/created")));
            assertTrue(Files.isSymbolicLink(fs.getPath("/real/link")));
        }
    }

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
            assertTrue(store.supportsFileAttributeView(FileOwnerAttributeView.class));
            assertTrue(store.supportsFileAttributeView(PosixFileAttributeView.class));
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("zip"));
            assertTrue(store.supportsFileAttributeView("owner"));
            assertTrue(store.supportsFileAttributeView("posix"));
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
            try (var failing = Files.newDirectoryStream(root, path -> {
                throw new IOException("filter failure");
            })) {
                DirectoryIteratorException filterFailure = assertThrows(
                        DirectoryIteratorException.class, failing.iterator()::hasNext);
                assertEquals("filter failure", filterFailure.getCause().getMessage());
            }

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
                    NoSuchFileException.class,
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
