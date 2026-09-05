// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.ArchiveMetadataCharsetDetector;
import org.glavo.arkivo.archive.ArkivoCommitTarget;
import org.glavo.arkivo.archive.ar.internal.ArArkivoFileSystemProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.time.Instant;
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

/// Verifies the NIO file-system and attribute-view contracts exposed by indexed AR archives.
@NotNullByDefault
final class ArArkivoFileSystemContractTest {
    /// Temporary directory used for path-backed AR archives.
    @TempDir
    private Path temporaryDirectory;

    /// Verifies file-system infrastructure, matchers, principals, stores, and read-only basic views.
    @Test
    void exposesNioFileSystemInfrastructure() throws IOException {
        Path archive = createArchive();
        ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive);

        try (fileSystem) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/dir/value.txt");
            assertEquals("/", fileSystem.getSeparator());
            assertEquals(ArArkivoFormat.instance().uriScheme(), fileSystem.provider().getScheme());

            Iterator<Path> roots = fileSystem.getRootDirectories().iterator();
            assertEquals(root, roots.next());
            assertFalse(roots.hasNext());

            Iterator<FileStore> stores = fileSystem.getFileStores().iterator();
            FileStore store = stores.next();
            assertFalse(stores.hasNext());
            assertSame(store, Files.getFileStore(file));
            assertEquals(Files.size(archive), store.getTotalSpace());
            assertEquals(Set.of("basic", "owner", "posix", "ar"), fileSystem.supportedFileAttributeViews());
            assertTrue(store.supportsFileAttributeView("basic"));
            assertTrue(store.supportsFileAttributeView("ar"));
            assertFalse(store.supportsFileAttributeView("dos"));
            assertFalse(store.supportsFileAttributeView(DosFileAttributeView.class));

            assertTrue(fileSystem.getPathMatcher("glob:**/*.txt").matches(file));
            assertFalse(fileSystem.getPathMatcher("glob:**/*.bin").matches(file));
            assertTrue(fileSystem.getPathMatcher("regex:.*/value\\.txt").matches(file));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher("glob"));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher(":*.txt"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.getPathMatcher("literal:value.txt")
            );

            UserPrincipalLookupService principals = fileSystem.getUserPrincipalLookupService();
            assertEquals("123", principals.lookupPrincipalByName("123").getName());
            assertEquals("456", principals.lookupPrincipalByGroupName("456").getName());
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);

            assertFalse(Files.isHidden(file));
            assertTrue(Files.isSameFile(file, fileSystem.getPath("/dir/./value.txt")));
            Path copiedFile = temporaryDirectory.resolve("copied-ar-value.txt");
            Files.copy(file, copiedFile);
            assertEquals("value", Files.readString(copiedFile, StandardCharsets.UTF_8));

            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            assertEquals("basic", basicView.name());
            assertTrue(basicView.readAttributes().isRegularFile());
            assertEquals("value".length(), basicView.readAttributes().size());
            assertThrows(
                    ReadOnlyFileSystemException.class,
                    () -> basicView.setTimes(FileTime.fromMillis(1L), null, null)
            );
            assertNull(Files.getFileAttributeView(file, DosFileAttributeView.class));
        }
    }

    /// Verifies raw NIO environments accept every documented metadata-charset representation.
    @Test
    void acceptsMetadataCharsetEnvironmentRepresentations() throws IOException {
        Path archive = createArchive();
        ArArkivoFileSystemProvider provider = new ArArkivoFileSystemProvider();
        Object[] representations = {
                ArchiveMetadataCharsetDetector.fixed(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8,
                "UTF-8"
        };

        for (Object representation : representations) {
            try (ArArkivoFileSystem fileSystem = provider.newFileSystem(
                    archive,
                    Map.of("arkivo.ar.metadataCharsetDetector", representation)
            )) {
                assertEquals(
                        "value",
                        Files.readString(fileSystem.getPath("/dir/value.txt"), StandardCharsets.UTF_8)
                );
            }
        }

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of("arkivo.ar.metadataCharsetDetector", 1)
                )
        );
    }

    /// Verifies standard and AR-specific attribute mutations share one persistent metadata model.
    @Test
    void persistsStandardAndArAttributeMutations() throws IOException {
        Path archive = createArchive();
        FileTime firstTime = FileTime.from(Instant.parse("2036-04-05T06:07:08Z"));
        FileTime secondTime = FileTime.from(Instant.parse("2037-05-06T07:08:09Z"));
        Set<PosixFilePermission> firstPermissions = Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        );

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            BasicFileAttributeView basicView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, BasicFileAttributeView.class)
            );
            FileOwnerAttributeView ownerView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, FileOwnerAttributeView.class)
            );
            PosixFileAttributeView posixView = Objects.requireNonNull(
                    Files.getFileAttributeView(file, PosixFileAttributeView.class)
            );

            basicView.setTimes(firstTime, null, null);
            assertEquals(firstTime, basicView.readAttributes().lastModifiedTime());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> basicView.setTimes(null, firstTime, null)
            );

            ownerView.setOwner(() -> "101");
            assertEquals("101", ownerView.getOwner().getName());
            posixView.setOwner(() -> "202");
            posixView.setGroup((GroupPrincipal) () -> "303");
            posixView.setPermissions(firstPermissions);
            posixView.setTimes(null, null, null);
            PosixFileAttributes intermediate = posixView.readAttributes();
            assertEquals("202", intermediate.owner().getName());
            assertEquals("303", intermediate.group().getName());
            assertEquals(firstPermissions, intermediate.permissions());

            Files.setAttribute(file, "basic:lastModifiedTime", secondTime);
            Files.setAttribute(file, "owner:owner", (UserPrincipal) () -> "404");
            Files.setAttribute(file, "posix:group", (GroupPrincipal) () -> "505");
            Files.setAttribute(file, "posix:permissions", firstPermissions);
            Files.setAttribute(file, "ar:userId", 606L);
            Files.setAttribute(file, "ar:groupId", 707L);
            Files.setAttribute(file, "ar:mode", 0100604);

            ArArkivoEntryAttributes attributes = Files.readAttributes(file, ArArkivoEntryAttributes.class);
            assertEquals(secondTime, attributes.lastModifiedTime());
            assertEquals(606L, attributes.userId());
            assertEquals(707L, attributes.groupId());
            assertEquals(0100604, attributes.mode());

            assertThrows(IllegalArgumentException.class, () -> ownerView.setOwner(() -> "not-numeric"));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> posixView.setGroup((GroupPrincipal) () -> "-1")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "basic:lastModifiedTime", "not-a-time")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "owner:group", (UserPrincipal) () -> "1")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:owner", "not-a-principal")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "posix:lastAccessTime", secondTime)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "ar:userId", "not-a-number")
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "ar:unknown", 0L)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "unknown:value", 0L)
            );
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            ArArkivoEntryAttributes attributes = Files.readAttributes(file, ArArkivoEntryAttributes.class);
            assertEquals(secondTime, attributes.lastModifiedTime());
            assertEquals(606L, attributes.userId());
            assertEquals(707L, attributes.groupId());
            assertEquals(0100604, attributes.mode());
            assertEquals(
                    Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OTHERS_READ
                    ),
                    Files.readAttributes(file, PosixFileAttributes.class).permissions()
            );
        }
    }

    /// Verifies update mode creates structural entries and rejects mutations that would corrupt the indexed tree.
    @Test
    void createsUpdateEntriesAndRejectsInvalidTreeMutations() throws IOException {
        Path archive = createArchive();

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archive)) {
            Path source = fileSystem.getPath("/dir/value.txt");
            Path implicitDirectory = fileSystem.getPath("/new-parent");
            Path directory = fileSystem.getPath("/new-parent/nested");
            Path link = fileSystem.getPath("/new-parent/nested/link");

            Files.createDirectory(directory);
            assertTrue(Files.isDirectory(implicitDirectory));
            assertTrue(Files.isDirectory(directory));
            Files.createSymbolicLink(link, Path.of("../../dir/value.txt"));
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("../../dir/value.txt", Files.readSymbolicLink(link).toString());
            assertEquals("value", Files.readString(link, StandardCharsets.UTF_8));

            assertThrows(FileAlreadyExistsException.class, () -> Files.createDirectory(directory));
            assertThrows(FileSystemException.class, () -> Files.delete(implicitDirectory));
            assertThrows(FileSystemException.class, () -> Files.delete(fileSystem.getPath("/")));
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(implicitDirectory, implicitDirectory.resolve("child"))
            );
            assertThrows(FileAlreadyExistsException.class, () -> Files.move(source, directory));
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(source, directory, StandardCopyOption.REPLACE_EXISTING)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.setAttribute(directory, "ar:size", 1L)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.setAttribute(implicitDirectory, "ar:userId", 1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(source, "ar:mode", 040755)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(source, "ar:size", -1L)
            );
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            Path link = fileSystem.getPath("/new-parent/nested/link");
            assertTrue(Files.isDirectory(fileSystem.getPath("/new-parent")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/new-parent/nested")));
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("value", Files.readString(link, StandardCharsets.UTF_8));
        }
    }

    /// Verifies update channels validate option combinations and publish staged content only after close.
    @Test
    void enforcesUpdateChannelOptionsAndCommitVisibility() throws IOException {
        Path archive = createArchive();

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archive)) {
            Path root = fileSystem.getPath("/");
            Path directory = fileSystem.getPath("/dir");
            Path file = fileSystem.getPath("/dir/value.txt");
            Path missing = fileSystem.getPath("/missing.txt");

            try (SeekableByteChannel channel = Files.newByteChannel(file, Set.of())) {
                ByteBuffer contents = ByteBuffer.allocate(5);
                assertEquals(5, channel.read(contents));
                assertEquals("value", new String(contents.array(), StandardCharsets.UTF_8));
            }

            try (SeekableByteChannel channel = Files.newByteChannel(
                    file,
                    Set.of(StandardOpenOption.READ, StandardOpenOption.WRITE)
            )) {
                ByteBuffer firstByte = ByteBuffer.allocate(1);
                assertEquals(1, channel.read(firstByte));
                channel.position(channel.size());
                assertEquals(1, channel.write(ByteBuffer.wrap(new byte[]{'!'})));
            }
            assertEquals("value!", Files.readString(file, StandardCharsets.UTF_8));

            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.READ, StandardOpenOption.APPEND)
                    )
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
                    IllegalArgumentException.class,
                    () -> Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE))
            );
            assertThrows(
                    java.nio.file.NoSuchFileException.class,
                    () -> Files.newByteChannel(missing, Set.of(StandardOpenOption.WRITE))
            );
            assertThrows(
                    FileAlreadyExistsException.class,
                    () -> Files.newByteChannel(
                            file,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    )
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(directory, Set.of(StandardOpenOption.WRITE))
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newByteChannel(root, Set.of(StandardOpenOption.WRITE))
            );

            try (SeekableByteChannel append = Files.newByteChannel(file, Set.of(StandardOpenOption.APPEND))) {
                assertEquals(6L, append.position());
                append.position(0L);
                assertEquals(1, append.write(ByteBuffer.wrap(new byte[]{'!'})));
                assertThrows(
                        FileSystemException.class,
                        () -> Files.readString(file, StandardCharsets.UTF_8)
                );
                assertThrows(
                        FileSystemException.class,
                        () -> Files.newByteChannel(
                                missing,
                                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                        )
                );
                assertThrows(FileSystemException.class, () -> Files.delete(file));
            }
            assertEquals("value!!", Files.readString(file, StandardCharsets.UTF_8));

            Path created = fileSystem.getPath("/created/child.txt");
            Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
            try (SeekableByteChannel channel = Files.newByteChannel(
                    created,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                    PosixFilePermissions.asFileAttribute(permissions)
            )) {
                assertEquals(3, channel.write(ByteBuffer.wrap(new byte[]{1, 2, 3})));
                assertFalse(Files.exists(created));
            }
            assertTrue(Files.isDirectory(fileSystem.getPath("/created")));
            assertEquals(permissions, Files.readAttributes(created, PosixFileAttributes.class).permissions());

            try (SeekableByteChannel truncate = Files.newByteChannel(
                    file,
                    Set.of(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            )) {
                assertEquals(0L, truncate.size());
                assertEquals(1, truncate.write(ByteBuffer.wrap(new byte[]{'x'})));
            }
            assertEquals("x", Files.readString(file, StandardCharsets.UTF_8));
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            assertEquals("x", Files.readString(fileSystem.getPath("/dir/value.txt"), StandardCharsets.UTF_8));
            assertEquals(
                    new byte[]{1, 2, 3}.length,
                    Files.size(fileSystem.getPath("/created/child.txt"))
            );
        }
    }

    /// Verifies update moves preserve directory descendants and implement same-kind replacement atomically.
    @Test
    void movesAndReplacesEntriesAcrossTheIndexedTree() throws IOException {
        Path archive = createArchive();

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archive)) {
            Path root = fileSystem.getPath("/");
            Path sourceDirectory = fileSystem.getPath("/dir");
            Path targetDirectory = fileSystem.getPath("/target");
            Files.createDirectory(targetDirectory);

            Files.move(
                    sourceDirectory,
                    targetDirectory,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
            Path movedFile = targetDirectory.resolve("value.txt");
            assertFalse(Files.exists(sourceDirectory));
            assertEquals("value", Files.readString(movedFile, StandardCharsets.UTF_8));

            Path replacement = fileSystem.getPath("/replacement.txt");
            Files.writeString(
                    replacement,
                    "replacement",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            Files.move(replacement, movedFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(movedFile, movedFile, StandardCopyOption.ATOMIC_MOVE);
            assertEquals("replacement", Files.readString(movedFile, StandardCharsets.UTF_8));

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.move(movedFile, fileSystem.getPath("/unsupported.txt"), LinkOption.NOFOLLOW_LINKS)
            );
            assertThrows(
                    java.nio.file.NoSuchFileException.class,
                    () -> Files.move(movedFile, fileSystem.getPath("/missing/child.txt"))
            );

            Path regularParent = fileSystem.getPath("/regular-parent");
            Files.writeString(
                    regularParent,
                    "parent",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(movedFile, regularParent.resolve("child.txt"))
            );

            Path sourceDirectory2 = fileSystem.getPath("/source-directory");
            Path nonemptyTarget = fileSystem.getPath("/nonempty-target");
            Files.createDirectory(sourceDirectory2);
            Files.createDirectory(nonemptyTarget);
            Files.writeString(
                    nonemptyTarget.resolve("child.txt"),
                    "child",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            assertThrows(
                    java.nio.file.DirectoryNotEmptyException.class,
                    () -> Files.move(sourceDirectory2, nonemptyTarget, StandardCopyOption.REPLACE_EXISTING)
            );
            assertThrows(
                    FileSystemException.class,
                    () -> Files.move(sourceDirectory2, regularParent, StandardCopyOption.REPLACE_EXISTING)
            );
            assertThrows(FileSystemException.class, () -> Files.move(root, fileSystem.getPath("/moved-root")));
            assertThrows(FileSystemException.class, () -> Files.move(movedFile, root));
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            assertEquals(
                    "replacement",
                    Files.readString(fileSystem.getPath("/target/value.txt"), StandardCharsets.UTF_8)
            );
            assertTrue(Files.isDirectory(fileSystem.getPath("/source-directory")));
            assertTrue(Files.isDirectory(fileSystem.getPath("/nonempty-target")));
        }
    }

    /// Verifies typed and named size updates persist truncation and zero-filled expansion.
    @Test
    void resizesRegularMembersAndValidatesWritableAttributeShapes() throws IOException {
        Path archive = createArchive();

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.update(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            ArArkivoEntryAttributeView view = Objects.requireNonNull(
                    Files.getFileAttributeView(file, ArArkivoEntryAttributeView.class)
            );
            assertEquals("ar", view.name());

            view.setSize(8L);
            assertArrayEquals(
                    new byte[]{'v', 'a', 'l', 'u', 'e', 0, 0, 0},
                    Files.readAllBytes(file)
            );
            view.setSize(8L);
            Files.setAttribute(file, "ar:size", 2L);
            assertArrayEquals(new byte[]{'v', 'a'}, Files.readAllBytes(file));

            assertThrows(IllegalArgumentException.class, () -> view.setSize(10_000_000_000L));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "ar:size", "2")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "ar:groupId", -1L)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "ar:mode", -1)
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:group", "1")
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> Files.setAttribute(file, "posix:permissions", Set.of("OWNER_READ"))
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.setAttribute(file, "ar:creationTime", FileTime.fromMillis(1L))
            );
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            Path file = fileSystem.getPath("/dir/value.txt");
            assertArrayEquals(new byte[]{'v', 'a'}, Files.readAllBytes(file));
            ArArkivoEntryAttributeView view = Objects.requireNonNull(
                    Files.getFileAttributeView(file, ArArkivoEntryAttributeView.class)
            );
            assertThrows(ReadOnlyFileSystemException.class, () -> view.setSize(1L));
        }
    }

    /// Verifies raw NIO archive open options select only the supported read, create, and update modes.
    @Test
    void validatesArchiveOpenOptionModes() throws IOException {
        Path archive = createArchive();
        ArArkivoFileSystemProvider provider = new ArArkivoFileSystemProvider();
        String key = "arkivo.openOptions";

        assertThrows(
                IllegalArgumentException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(StandardOpenOption.TRUNCATE_EXISTING))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(StandardOpenOption.WRITE))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING
                        ))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.APPEND))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(
                                StandardOpenOption.WRITE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.DELETE_ON_CLOSE
                        ))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(
                                StandardOpenOption.READ,
                                StandardOpenOption.DELETE_ON_CLOSE
                        ))
                )
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        archive,
                        Map.of(key, Set.<OpenOption>of(
                                StandardOpenOption.READ,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.DELETE_ON_CLOSE
                        ))
                )
        );

        Path rejectedCommitArchive = temporaryDirectory.resolve("commit-target-write.a");
        assertThrows(
                UnsupportedOperationException.class,
                () -> provider.newFileSystem(
                        rejectedCommitArchive,
                        Map.of(
                                key,
                                Set.<OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                                "arkivo.commitTarget",
                                ArkivoCommitTarget.writeTo(temporaryDirectory.resolve("published.a"))
                        )
                )
        );
        assertFalse(Files.exists(rejectedCommitArchive));

        Path updateArchive = temporaryDirectory.resolve("created-by-update.a");
        assertThrows(
                java.nio.file.NoSuchFileException.class,
                () -> provider.newFileSystem(
                        updateArchive,
                        Map.of(key, Set.<OpenOption>of(StandardOpenOption.READ, StandardOpenOption.WRITE))
                )
        );
        try (ArArkivoFileSystem fileSystem = (ArArkivoFileSystem) provider.newFileSystem(
                updateArchive,
                Map.of(key, Set.<OpenOption>of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE
                ))
        )) {
            Files.writeString(
                    fileSystem.getPath("/updated.txt"),
                    "updated",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        }
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(updateArchive)) {
            assertEquals("updated", Files.readString(fileSystem.getPath("/updated.txt")));
        }

        Path createdArchive = temporaryDirectory.resolve("created-with-open-options.a");
        try (ArArkivoFileSystem fileSystem = (ArArkivoFileSystem) provider.newFileSystem(
                createdArchive,
                Map.of(key, Set.<OpenOption>of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW))
        )) {
            Files.writeString(
                    fileSystem.getPath("/created.txt"),
                    "created",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
        }
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(createdArchive)) {
            assertEquals("created", Files.readString(fileSystem.getPath("/created.txt")));
        }
    }

    /// Verifies indexed directory streams apply filters eagerly and reject contradictory logical member paths.
    @Test
    void enforcesDirectoryAndDuplicateMemberContracts() throws IOException {
        Path archive = createArchive();
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            Path directory = fileSystem.getPath("/dir");
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, path -> false)) {
                assertFalse(stream.iterator().hasNext());
            }

            DirectoryIteratorException exception = assertThrows(
                    DirectoryIteratorException.class,
                    () -> Files.newDirectoryStream(directory, path -> {
                        throw new IOException("filter failed");
                    })
            );
            assertEquals("filter failed", exception.getCause().getMessage());
            assertThrows(
                    FileSystemException.class,
                    () -> Files.newDirectoryStream(fileSystem.getPath("/dir/value.txt"))
            );
        }

        Path duplicateArchive = temporaryDirectory.resolve("duplicate.a");
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(duplicateArchive)) {
            try (OutputStream body = writer.beginFile("duplicate.txt").openOutputStream()) {
                body.write(1);
            }
            try (OutputStream body = writer.beginFile("duplicate.txt").openOutputStream()) {
                body.write(2);
            }
        }
        IOException duplicate = assertThrows(
                IOException.class,
                () -> ArArkivoFileSystem.open(duplicateArchive)
        );
        assertTrue(duplicate.getMessage().contains("Duplicate AR entry path"));

        String[][] pathConflicts = {
                {"dir", "dir/file.txt"},
                {"dir/file.txt", "dir"}
        };
        for (int index = 0; index < pathConflicts.length; index++) {
            Path conflictArchive = temporaryDirectory.resolve("directory-conflict-" + index + ".a");
            try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(conflictArchive)) {
                for (String path : pathConflicts[index]) {
                    try (OutputStream ignored = writer.beginFile(path).openOutputStream()) {
                        // Empty bodies are sufficient to exercise indexed path construction.
                    }
                }
            }
            IOException conflict = assertThrows(
                    IOException.class,
                    () -> ArArkivoFileSystem.open(conflictArchive)
            );
            assertTrue(conflict.getMessage().contains("AR entry path conflicts with directory"));
        }
    }

    /// Verifies forward-only file systems expose writes and metadata only for entries already emitted.
    @Test
    void enforcesForwardOnlyFileSystemAccess() throws IOException {
        Path archive = temporaryDirectory.resolve("forward.a");

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.create(archive)) {
            Path root = fileSystem.getPath("/");
            Path file = fileSystem.getPath("/value.txt");
            Path missing = fileSystem.getPath("/missing.txt");

            fileSystem.provider().checkAccess(root, java.nio.file.AccessMode.WRITE);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> fileSystem.provider().checkAccess(root, java.nio.file.AccessMode.READ)
            );
            assertThrows(
                    java.nio.file.NoSuchFileException.class,
                    () -> fileSystem.provider().checkAccess(missing, java.nio.file.AccessMode.WRITE)
            );
            assertSame(fileSystem.getFileStores().iterator().next(), Files.getFileStore(root));

            Files.writeString(file, "forward", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            fileSystem.provider().checkAccess(file, java.nio.file.AccessMode.WRITE);
            assertSame(fileSystem.getFileStores().iterator().next(), Files.getFileStore(file));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newOutputStream(fileSystem.getPath("/read.txt"), StandardOpenOption.READ)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newOutputStream(fileSystem.getPath("/append.txt"), StandardOpenOption.APPEND)
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newOutputStream(
                            fileSystem.getPath("/delete-on-close.txt"),
                            StandardOpenOption.DELETE_ON_CLOSE
                    )
            );
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.newByteChannel(fileSystem.getPath("/read-channel.txt"), Set.of(StandardOpenOption.READ))
            );
            assertThrows(FileAlreadyExistsException.class, () -> Files.newOutputStream(file));
            assertThrows(FileAlreadyExistsException.class, () -> Files.newOutputStream(root));
            assertThrows(UnsupportedOperationException.class, () -> Files.newInputStream(file));
            assertThrows(UnsupportedOperationException.class, () -> Files.newDirectoryStream(root));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> Files.readAttributes(file, "basic:size")
            );
        }

        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            assertEquals(
                    "forward",
                    Files.readString(fileSystem.getPath("/value.txt"), StandardCharsets.UTF_8)
            );
        }
    }

    /// Creates an AR archive containing one regular member below a synthetic directory.
    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("sample.a");
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.create(archive)) {
            try (OutputStream body = writer.beginFile("dir/value.txt").openOutputStream()) {
                body.write("value".getBytes(StandardCharsets.UTF_8));
            }
        }
        return archive;
    }
}
