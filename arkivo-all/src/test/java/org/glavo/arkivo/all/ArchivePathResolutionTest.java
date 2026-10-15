// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoPathVolumeTarget;
import org.glavo.arkivo.archive.ArkivoVolumePathLayout;
import org.glavo.arkivo.archive.sevenzip.SevenZipArchiveOptions;
import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoFileSystem;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies component traversal and symbolic-link policies in read-only and update archive sessions.
@NotNullByDefault
final class ArchivePathResolutionTest {
    /// Resolves publication and discovery paths through parent links before removing parent components.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void publishesVolumesThroughParentLinks(boolean repeatedDiscovery, @TempDir Path directory) throws IOException {
        Path archive = volumePathArchive(directory);
        try (var fs = ArkivoFormats.updateFileSystem("zip", archive)) {
            Path selected = fs.getPath("/alias/../data.bin");
            Path actual = fs.getPath("/real/data.bin");
            var layout = new VolumeLayout(selected.getParent(), List.of(selected),
                    repeatedDiscovery ? List.of(selected, actual) : List.of(selected));
            try (var output = new ArkivoPathVolumeTarget(layout).openOutput()) {
                try (var paths = Files.list(fs.getPath("/real"))) {
                    assertTrue(paths.anyMatch(path -> path.getFileName().toString().startsWith(".arkivo-volumes-")));
                }
                try (var channel = output.openVolume(0)) {
                    channel.write(ByteBuffer.wrap(new byte[]{'n', 'e', 'w'}));
                }
                output.commit(0);
            }
            assertEquals("new", Files.readString(selected));
            assertEquals("root", Files.readString(fs.getPath("/data.bin")));
            try (var paths = Files.list(fs.getPath("/real"))) {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".arkivo-volumes-")));
            }
        }
        try (var fs = ArkivoFormats.openFileSystem("zip", archive)) {
            assertEquals("new", Files.readString(fs.getPath("/real/data.bin")));
            assertEquals("root", Files.readString(fs.getPath("/data.bin")));
        }
    }

    /// Rejects two spellings of one publication target before backing up either existing volume.
    @Test
    void rejectsAliasedVolumeDestinations(@TempDir Path directory) throws IOException {
        Path archive = volumePathArchive(directory);
        try (var fs = ArkivoFormats.updateFileSystem("zip", archive)) {
            Path selected = fs.getPath("/alias/../data.bin");
            Path actual = fs.getPath("/real/data.bin");
            var layout = new VolumeLayout(fs.getPath("/real"), List.of(selected, actual), List.of(actual));
            try (var output = new ArkivoPathVolumeTarget(layout).openOutput()) {
                for (int index = 0; index < 2; index++) {
                    try (var channel = output.openVolume(index)) {
                        channel.write(ByteBuffer.wrap(new byte[]{42}));
                    }
                }
                assertThrows(IllegalArgumentException.class, () -> output.commit(1));
                assertEquals("real", Files.readString(actual));
                assertEquals("root", Files.readString(fs.getPath("/data.bin")));
            }
        }
    }

    /// Keeps parent-link traversal intact through the built-in split ZIP and 7z creation entry points.
    @ParameterizedTest
    @ValueSource(strings = {"zip", "7z"})
    void createsSplitArchiveThroughParentLinks(String format, @TempDir Path directory) throws IOException {
        Path archive = volumePathArchive(directory);
        String name = format.equals("zip") ? "data.zip" : "data.7z.001";
        byte[] body = new byte[70000];
        new Random(42).nextBytes(body);
        try (var fs = ArkivoFormats.updateFileSystem("zip", archive)) {
            Path selected = fs.getPath("/alias/../" + name);
            try (var output = format.equals("zip")
                    ? fs.provider().newFileSystem(selected, Map.of("arkivo.openOptions",
                            Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                            "arkivo.zip.splitSize", 65536L))
                    : SevenZipArkivoFileSystem.create(selected, 65536, SevenZipArchiveOptions.CREATE_DEFAULTS)) {
                Files.writeString(output.getPath("/entry"), "payload");
                Files.write(output.getPath("/body"), body);
            }
            assertTrue(Files.exists(selected));
            assertFalse(Files.exists(fs.getPath("/" + name)));
            try (var paths = Files.list(fs.getPath("/real"))) {
                assertTrue(paths.filter(path -> path.getFileName().toString().startsWith("data.")).count() >= 3);
            }
            if (format.equals("7z")) {
                try (var update = ArkivoFormats.updateFileSystem(format, selected)) {
                    assertArrayEquals(body, Files.readAllBytes(update.getPath("/body")));
                    Files.writeString(update.getPath("/entry"), "updated");
                }
            }
        }
        try (var fs = ArkivoFormats.openFileSystem("zip", archive);
             var nested = ArkivoFormats.openFileSystem(format, fs.getPath("/alias/../" + name))) {
            assertEquals(format.equals("7z") ? "updated" : "payload", Files.readString(nested.getPath("/entry")));
            assertArrayEquals(body, Files.readAllBytes(nested.getPath("/body")));
            assertFalse(Files.exists(fs.getPath("/" + name)));
        }
    }

    /// Creates an editable outer archive with a parent link and distinct root and resolved target entries.
    private static Path volumePathArchive(Path directory) throws IOException {
        Path archive = directory.resolve("outer.zip");
        try (var fs = ArkivoFormats.createFileSystem("zip", archive)) {
            Files.createDirectories(fs.getPath("/real/sub"));
            Files.createSymbolicLink(fs.getPath("/alias"), fs.getPath("real/sub"));
            Files.writeString(fs.getPath("/data.bin"), "root");
            Files.writeString(fs.getPath("/real/data.bin"), "real");
        }
        return archive;
    }

    /// Supplies explicit path spellings to the shared volume publisher.
    ///
    /// @param outputDirectory directory for staging
    /// @param destinations final volume paths
    /// @param existingVolumePaths paths currently owned by the layout
    @NotNullByDefault
    private record VolumeLayout(Path outputDirectory, @Unmodifiable List<Path> destinations,
                                @Unmodifiable List<Path> existingVolumePaths) implements ArkivoVolumePathLayout {
        /// Copies the lists without changing any path components.
        private VolumeLayout {
            destinations = List.copyOf(destinations);
            existingVolumePaths = List.copyOf(existingVolumePaths);
        }

        /// Returns the explicitly configured final path.
        @Override
        public Path volumePath(long index, long finalVolumeIndex) {
            return destinations.get(Math.toIntExact(index));
        }
    }

    /// Uses one registry identity for legal Unicode and percent-escaped spellings of the same source URI.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void canonicalizesSourceUriSpelling(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("archive-\u6d4b\u8bd5." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.writeString(fs.getPath("/entry.txt"), "content");
        }
        String ascii = archive.toUri().toASCIIString();
        List<String> spellings = List.of(archive.toUri().toString(), ascii,
                ascii.replace("archive-", "%61rchive-"), ascii.replace("file:///", "file:/"));
        for (String spelling : spellings) {
            URI opening = URI.create("arkivo+" + format + ':' + spelling + "!/");
            try (var fs = FileSystems.newFileSystem(opening, Map.of())) {
                Path entry = fs.getPath("/./entry.txt");
                assertEquals(entry, Path.of(entry.toUri()));
                assertEquals("content", Files.readString(Path.of(entry.toUri())));
                for (String alternate : spellings) {
                    URI alias = URI.create("arkivo+" + format + ':' + alternate + "!/");
                    assertEquals(fs, FileSystems.getFileSystem(alias));
                    assertThrows(FileSystemAlreadyExistsException.class,
                            () -> FileSystems.newFileSystem(alias, Map.of()));
                }
            }
        }
    }

    /// Preserves source and entry URI escaping across one or two enclosing archive file systems.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void preservesNestedArchiveUris(String format, @TempDir Path directory) throws IOException {
        Path sourceDirectory = Files.createDirectories(directory.resolve("source !/literal%21"));
        Path inner = sourceDirectory.resolve("inner." + format);
        String innerName = "/dir!/inner %21 \u6d4b\u8bd5." + format;
        try (var fs = ArkivoFormats.createFileSystem(format, inner)) {
            Files.createDirectory(fs.getPath("/dir!"));
            Files.writeString(fs.getPath("/dir!/entry %21 \u6d4b\u8bd5.txt"), "nested content");
        }
        Path middle = sourceDirectory.resolve("middle.zip");
        try (var fs = ArkivoFormats.createFileSystem("zip", middle)) {
            Files.createDirectory(fs.getPath("/dir!"));
            Files.copy(inner, fs.getPath(innerName));
        }
        Path outer = sourceDirectory.resolve("outer.zip");
        String middleName = "/middle !%21.zip";
        try (var fs = ArkivoFormats.createFileSystem("zip", outer)) {
            Files.copy(middle, fs.getPath(middleName));
            Files.createDirectory(fs.getPath("/dir!"));
            Files.copy(inner, fs.getPath(innerName));
        }
        URI outerUri;
        try (var fs = ArkivoFormats.openFileSystem("zip", outer)) {
            outerUri = fs.getPath("/").toUri();
        }
        try (var outerFs = FileSystems.newFileSystem(outerUri, Map.of())) {
            assertNestedArchiveUris(format, outerFs.getPath(innerName));
            try (var directMiddle = ArkivoFormats.openFileSystem("zip", outerFs.getPath(middleName));
                 var middleFs = FileSystems.newFileSystem(directMiddle.getPath("/").toUri(), Map.of())) {
                assertNestedArchiveUris(format, middleFs.getPath(innerName));
            }
        }
    }

    /// Checks archive-only and entry URIs against the installed provider registry without normalizing entry names.
    private static void assertNestedArchiveUris(String format, Path source) throws IOException {
        try (var direct = ArkivoFormats.openFileSystem(format, source)) {
            URI root = direct.getPath("/").toUri();
            String rootText = root.toASCIIString();
            URI archiveOnly = URI.create(rootText.substring(0, rootText.length() - 2));
            for (URI openingUri : List.of(root, archiveOnly)) {
                try (var registered = FileSystems.newFileSystem(openingUri, Map.of())) {
                    for (String name : List.of("/dir!/entry %21 \u6d4b\u8bd5.txt",
                            "/dir!/./entry %21 \u6d4b\u8bd5.txt")) {
                        Path original = registered.getPath(name);
                        Path restored = Path.of(original.toUri());
                        assertEquals(original, restored);
                        assertEquals("nested content", Files.readString(restored));
                        assertEquals(registered, FileSystems.getFileSystem(original.toUri()));
                    }
                    assertEquals(registered, FileSystems.getFileSystem(archiveOnly));
                    assertThrows(FileSystemAlreadyExistsException.class,
                            () -> FileSystems.newFileSystem(root, Map.of()));
                }
            }
        }
    }

    /// Read-only creation flags neither skip link resolution nor create a dangling link's target.
    @ParameterizedTest
    @MethodSource("configurations")
    void readChannelCreationOptionsStillFollowLinks(String format, boolean update, @TempDir Path directory)
            throws IOException {
        try (var fs = open(format, update, directory)) {
            byte[] expected = Files.readAllBytes(fs.getPath("/real/value.txt"));
            for (StandardOpenOption option : new StandardOpenOption[]{StandardOpenOption.CREATE,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.TRUNCATE_EXISTING}) {
                for (String name : new String[]{"/alias/../value.txt", "/dirlink/filelink"}) {
                    try (var channel = Files.newByteChannel(fs.getPath(name), Set.of(StandardOpenOption.READ, option))) {
                        ByteBuffer result = ByteBuffer.allocate(expected.length);
                        while (result.hasRemaining() && channel.read(result) >= 0) {
                            // Read the complete entry through its resolved path.
                        }
                        assertArrayEquals(expected, result.array());
                        assertEquals(expected.length, result.position());
                    }
                }
                assertThrows(NoSuchFileException.class,
                        () -> Files.newByteChannel(fs.getPath("/broken"), Set.of(StandardOpenOption.READ, option)));
            }
        }
    }

    /// Removing the last child keeps implicit parents addressable, including after the archive is rewritten.
    @ParameterizedTest
    @ValueSource(strings = {"zip", "7z"})
    void retainsImplicitParentAfterChildRemoval(String format, @TempDir Path directory) throws IOException {
        for (boolean move : new boolean[]{false, true}) {
            Path archive = directory.resolve(move + "." + format);
            try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
                Files.writeString(fs.getPath("/parent/nested/child"), "content");
            }
            try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
                if (move) {
                    Files.move(fs.getPath("/parent/nested/child"), fs.getPath("/moved"));
                } else {
                    Files.delete(fs.getPath("/parent/nested/child"));
                }
                assertTrue(Files.isDirectory(fs.getPath("/parent/nested")));
                try (var children = Files.list(fs.getPath("/parent/nested"))) {
                    assertEquals(0, children.count());
                }
            }
            try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
                assertTrue(Files.isDirectory(fs.getPath("/parent/nested")));
                Files.move(fs.getPath("/parent/nested"), fs.getPath("/empty"));
                assertTrue(Files.isDirectory(fs.getPath("/parent")));
                Files.delete(fs.getPath("/parent"));
                Files.delete(fs.getPath("/empty"));
                if (move) {
                    assertEquals("content", Files.readString(fs.getPath("/moved")));
                }
            }
            try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
                assertFalse(Files.exists(fs.getPath("/parent")));
                assertFalse(Files.exists(fs.getPath("/empty")));
            }
        }
    }

    /// Standard postorder directory deletion works for archive trees whose directories were implicit on disk.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void recursivelyDeletesImplicitDirectories(String format, @TempDir Path directory) throws IOException {
        Path archive = directory.resolve("recursive." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.writeString(fs.getPath("/parent/nested/child"), "content");
        }
        try (var fs = ArkivoFormats.updateFileSystem(format, archive)) {
            Files.walkFileTree(fs.getPath("/parent"), new SimpleFileVisitor<Path>() {
                /// Deletes a visited child without removing its parent.
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.delete(file);
                    assertTrue(Files.isDirectory(file.getParent()));
                    return FileVisitResult.CONTINUE;
                }

                /// Deletes each directory after its children, propagating traversal failures.
                @Override
                public FileVisitResult postVisitDirectory(Path path,
                        @Nullable IOException failure) throws IOException {
                    if (failure != null) {
                        throw failure;
                    }
                    Files.delete(path);
                    return FileVisitResult.CONTINUE;
                }
            });
            assertFalse(Files.exists(fs.getPath("/parent")));
        }
    }

    /// File-store lookup follows both parent and terminal links and checks the resolved entry.
    @ParameterizedTest
    @MethodSource("configurations")
    void resolvesFileStorePaths(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            var expected = Files.getFileStore(fs.getPath("/real/value.txt"));
            assertEquals(expected, Files.getFileStore(fs.getPath("/dirlink/value.txt")));
            assertEquals(expected, Files.getFileStore(fs.getPath("/alias/../value.txt")));
            assertEquals(expected, Files.getFileStore(fs.getPath("/dirlink/filelink")));
            assertThrows(NoSuchFileException.class, () -> Files.getFileStore(fs.getPath("/broken")));
            assertThrows(FileSystemLoopException.class, () -> Files.getFileStore(fs.getPath("/cycle")));
        }
    }

    /// Symbolic-link targets reject NUL and malformed UTF-16 without preventing later valid or dangling links.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void rejectsMalformedLinkTargets(String format, @TempDir Path directory) throws IOException {
        for (boolean update : new boolean[]{false, true}) {
            Path archive = directory.resolve("links-" + update + "." + format);
            if (update) {
                try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
                    Files.writeString(fs.getPath("/keep"), "original");
                }
            }
            String target = "target\uD83D\uDE00";
            try (var fs = update ? ArkivoFormats.updateFileSystem(format, archive)
                    : ArkivoFormats.createFileSystem(format, archive)) {
                for (String invalid : List.of("x\uD800", "x\uDC00", "x\uD800y", "x\uDC00\uD800", "x\0y")) {
                    assertThrows(IllegalArgumentException.class,
                            () -> Files.createSymbolicLink(fs.getPath("/link"), fs.getPath(invalid)));
                }
                Files.writeString(fs.getPath("/" + target), "content");
                Files.createSymbolicLink(fs.getPath("/link"), fs.getPath(target));
                Files.createSymbolicLink(fs.getPath("/dangling"), fs.getPath("../absent"));
            }
            try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
                assertEquals(fs.getPath(target), Files.readSymbolicLink(fs.getPath("/link")));
                assertEquals("content", Files.readString(fs.getPath("/link")));
                assertEquals(fs.getPath("../absent"), Files.readSymbolicLink(fs.getPath("/dangling")));
                if (update) assertEquals("original", Files.readString(fs.getPath("/keep")));
            }
        }
    }

    /// NIO writes reject names that archive indexing would reinterpret, without changing existing entries.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void rejectsUnrepresentableEntryNames(String format, @TempDir Path directory) throws IOException {
        for (boolean update : new boolean[]{false, true}) {
            Path archive = directory.resolve(update + "." + format);
            if (update) {
                try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
                    Files.writeString(fs.getPath("/keep"), "original");
                }
            }
            try (var fs = update ? ArkivoFormats.updateFileSystem(format, archive)
                    : ArkivoFormats.createFileSystem(format, archive)) {
                if (!update) {
                    Files.writeString(fs.getPath("/keep"), "original");
                }
                for (String name : List.of("/a\\b", "/C:entry", "/C:", "/x\uD800", "/x\uDC00")) {
                    Path invalid = fs.getPath(name);
                    assertThrows(IOException.class, () -> Files.newOutputStream(invalid));
                    assertThrows(IOException.class, () -> Files.newByteChannel(invalid,
                            Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)));
                    assertThrows(IOException.class, () -> Files.createDirectory(invalid));
                    assertThrows(IOException.class, () -> Files.createSymbolicLink(invalid, fs.getPath("keep")));
                    if (format.equals("tar")) {
                        assertThrows(IOException.class, () -> Files.createLink(invalid, fs.getPath("/keep")));
                    }
                    if (update) {
                        assertThrows(IOException.class, () -> Files.copy(fs.getPath("/keep"), invalid));
                        assertThrows(IOException.class, () -> Files.move(fs.getPath("/keep"), invalid));
                        assertEquals("original", Files.readString(fs.getPath("/keep")));
                    }
                }
                Files.createDirectories(fs.getPath("/a"));
                Files.writeString(fs.getPath("/a/b"), "separate");
                Files.writeString(fs.getPath("/a/C:entry"), "nested colon");
                Files.writeString(fs.getPath("/x\uD83D\uDE00"), "surrogate pair");
                Files.writeString(fs.getPath("/following"), "following");
            }
            try (var fs = ArkivoFormats.openFileSystem(format, archive)) {
                assertEquals("original", Files.readString(fs.getPath("/keep")));
                assertEquals("separate", Files.readString(fs.getPath("/a/b")));
                assertEquals("nested colon", Files.readString(fs.getPath("/a/C:entry")));
                assertEquals("surrogate pair", Files.readString(fs.getPath("/x\uD83D\uDE00")));
                assertEquals("following", Files.readString(fs.getPath("/following")));
                assertFalse(Files.exists(fs.getPath("/a\\b")));
                assertFalse(Files.exists(fs.getPath("/C:entry")));
            }
        }
    }

    /// Attribute-view mutation and deletion are serialized, including path resolution and attribute conversion.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "7z"})
    void serializesAttributeViewWrites(String format, @TempDir Path directory) throws Exception {
        try (var fs = open(format, true, directory)) {
            Path entry = fs.getPath("/real/value.txt");
            PosixFileAttributeView view = Files.getFileAttributeView(entry, PosixFileAttributeView.class);
            assertNotNull(view);
            GatedPermissions permissions = new GatedPermissions();
            FutureTask<Void> setter = new FutureTask<>(() -> {
                view.setPermissions(permissions);
                return null;
            });
            CountDownLatch deleteStarted = new CountDownLatch(1);
            FutureTask<Void> delete = new FutureTask<>(() -> {
                deleteStarted.countDown();
                Files.delete(entry);
                return null;
            });
            Thread setterThread = new Thread(setter);
            Thread deleteThread = new Thread(delete);
            setterThread.setDaemon(true);
            deleteThread.setDaemon(true);
            setterThread.start();
            try {
                assertTrue(permissions.entered.await(10, TimeUnit.SECONDS));
                deleteThread.start();
                assertTrue(deleteStarted.await(10, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> delete.get(100, TimeUnit.MILLISECONDS));
            } finally {
                permissions.release.countDown();
                setter.get(10, TimeUnit.SECONDS);
                if (deleteThread.getState() != Thread.State.NEW) delete.get(10, TimeUnit.SECONDS);
            }
            assertFalse(Files.exists(entry));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertFalse(Files.exists(fs.getPath("/real/value.txt")));
        }
    }

    /// Supplies valid permissions while pausing attribute conversion at a known mutation boundary.
    @NotNullByDefault
    private static final class GatedPermissions extends AbstractSet<PosixFilePermission> {
        /// Signals that conversion has started.
        private final CountDownLatch entered = new CountDownLatch(1);
        /// Allows conversion to finish.
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public int size() {
            return 1;
        }

        @Override
        public Iterator<PosixFilePermission> iterator() {
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("Permission conversion timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError(exception);
            }
            return Set.of(PosixFilePermission.OWNER_READ).iterator();
        }
    }

    /// Named attribute mutations follow parent links and honor the final-link policy.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "7z", "zip"})
    void resolvesAttributeMutationPaths(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            FileTime rootTime = Files.getLastModifiedTime(fs.getPath("/value.txt"));
            FileTime first = FileTime.fromMillis(1_600_000_000_000L);
            FileTime second = FileTime.fromMillis(1_600_000_010_000L);
            FileTime third = FileTime.fromMillis(1_600_000_020_000L);
            Files.setAttribute(fs.getPath("/alias/../value.txt"), "basic:lastModifiedTime", first);
            assertEquals(first, Files.getLastModifiedTime(fs.getPath("/real/value.txt")));
            assertEquals(rootTime, Files.getLastModifiedTime(fs.getPath("/value.txt")));
            Files.setAttribute(fs.getPath("/dirlink/filelink"), "basic:lastModifiedTime", second);
            assertEquals(second, Files.getLastModifiedTime(fs.getPath("/real/value.txt")));
            Files.setAttribute(fs.getPath("/dirlink/filelink"), "basic:lastModifiedTime", third,
                    LinkOption.NOFOLLOW_LINKS);
            assertEquals(third, Files.getLastModifiedTime(fs.getPath("/real/filelink"), LinkOption.NOFOLLOW_LINKS));
            assertEquals(second, Files.getLastModifiedTime(fs.getPath("/real/value.txt")));
            BasicFileAttributeView view = Files.getFileAttributeView(fs.getPath("/dirlink/filelink"),
                    BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(view);
            view.setTimes(first, null, null);
            assertEquals(first, Files.getLastModifiedTime(fs.getPath("/real/filelink"), LinkOption.NOFOLLOW_LINKS));
            assertEquals(second, Files.getLastModifiedTime(fs.getPath("/real/value.txt")));
        }
    }

    /// Equal paths identify the same file without probing whether that file exists.
    @ParameterizedTest
    @MethodSource("configurations")
    void comparesEqualMissingPaths(String format, boolean update, @TempDir Path directory) throws IOException {
        Path missing;
        try (var fs = open(format, update, directory)) {
            missing = fs.getPath("/missing");
            assertTrue(Files.isSameFile(fs.getPath("/missing"), fs.getPath("/missing")));
            assertThrows(NoSuchFileException.class,
                    () -> Files.isSameFile(fs.getPath("/missing"), fs.getPath("/value.txt")));
        }
        assertTrue(Files.isSameFile(missing, missing));
    }

    /// Moving the root onto itself is a no-op, including paths that resolve to the root.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void movesRootOntoItself(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path root = fs.getPath("/");
            Files.move(root, root);
            Files.move(fs.getPath("/real/.."), root, StandardCopyOption.REPLACE_EXISTING);
            Files.move(root, fs.getPath("/real/.."), StandardCopyOption.ATOMIC_MOVE);
            assertTrue(Files.isDirectory(root));
            assertEquals("REAL", Files.readString(fs.getPath("/real/value.txt")));
            assertThrows(FileSystemException.class, () -> Files.move(root, fs.getPath("/new-root")));
            assertThrows(FileSystemException.class,
                    () -> Files.move(fs.getPath("/real"), root, StandardCopyOption.REPLACE_EXISTING));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertTrue(Files.isDirectory(fs.getPath("/")));
            assertFalse(Files.exists(fs.getPath("/new-root")));
            assertEquals("REAL", Files.readString(fs.getPath("/real/value.txt")));
        }
    }

    /// Moving a missing source fails even when source and target resolve to the same path.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void rejectsMissingSelfMove(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            assertThrows(NoSuchFileException.class,
                    () -> Files.move(fs.getPath("/missing"), fs.getPath("/missing")));
            assertThrows(NoSuchFileException.class,
                    () -> Files.move(fs.getPath("/dirlink/missing"), fs.getPath("/real/missing")));
        }
    }

    /// Copying onto the same entry through a parent link must not delete its source.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void copiesSameEntryThroughParentLink(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path source = fs.getPath("/real/value.txt");
            Path target = fs.getPath("/dirlink/value.txt");
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            assertEquals("REAL", Files.readString(source));
            Files.copy(target, source, LinkOption.NOFOLLOW_LINKS);
            assertEquals("REAL", Files.readString(target));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertEquals("REAL", Files.readString(fs.getPath("/real/value.txt")));
            assertEquals("REAL", Files.readString(fs.getPath("/dirlink/value.txt")));
        }
    }

    /// Archive globs use case-sensitive slash-separated names independently of the host platform.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void matchesArchiveNamesWithoutHostPathRules(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path name = fs.getPath("/report:2026.txt");
            Files.writeString(name, "report");
            Files.writeString(fs.getPath("/README.TXT"), "uppercase");
            Path multiline = fs.getPath("/line\nbreak.txt");
            Files.writeString(multiline, "multiline");
            assertTrue(fs.getPathMatcher("glob:*.txt").matches(name.getFileName()));
            assertTrue(fs.getPathMatcher("glob:**.txt").matches(multiline));
            assertFalse(fs.getPathMatcher("glob:*.txt").matches(fs.getPath("README.TXT")));
            var entries = new HashSet<Path>();
            try (var stream = Files.newDirectoryStream(fs.getPath("/"), "*.txt")) {
                stream.forEach(entries::add);
            }
            assertTrue(entries.contains(name));
            assertTrue(entries.contains(multiline));
            assertFalse(entries.contains(fs.getPath("/README.TXT")));
            var recursiveEntries = new HashSet<Path>();
            try (var stream = Files.newDirectoryStream(fs.getPath("/"), "**.txt")) {
                stream.forEach(recursiveEntries::add);
            }
            assertEquals(entries, recursiveEntries);
        }
    }

    /// Directory filters may mutate archive entries without holding a non-upgradable read lock.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void directoryFiltersCanMutateEntries(String format, @TempDir Path directory) throws Exception {
        var task = new FutureTask<Boolean>(() -> {
            try (var fs = open(format, true, directory);
                 var stream = Files.newDirectoryStream(fs.getPath("/real"), entry -> {
                     if (entry.getFileName().toString().equals("value.txt")) {
                         Files.delete(entry);
                         Files.writeString(entry.resolveSibling("created.txt"), "CREATED");
                     }
                     return true;
                 })) {
                for (Path ignored : stream) {
                    // Exhaust the snapshot, applying its filter lazily.
                }
                assertFalse(Files.exists(fs.getPath("/real/value.txt")));
                assertEquals("CREATED", Files.readString(fs.getPath("/real/created.txt")));
            }
            try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
                assertFalse(Files.exists(fs.getPath("/real/value.txt")));
                assertEquals("CREATED", Files.readString(fs.getPath("/real/created.txt")));
            }
            return true;
        });
        Thread worker = new Thread(task, "archive-directory-filter-test");
        worker.setDaemon(true);
        worker.start();
        task.get(10, TimeUnit.SECONDS);
    }

    /// Crosses archive providers with read-only and update sessions.
    private static Stream<Arguments> configurations() {
        return Stream.of("ar", "tar", "zip", "7z")
                .flatMap(format -> Stream.of(false, true).map(update -> Arguments.of(format, update)));
    }

    /// Crosses writable formats with operations that must resolve parents before selecting an entry.
    private static Stream<Arguments> mutations() {
        return Stream.of("ar", "tar", "zip", "7z").flatMap(format ->
                Stream.of("stream", "channel", "delete", "move", "directory", "link")
                        .map(operation -> Arguments.of(format, operation)));
    }

    /// Changes only the component-resolved target and retains that result after committing the archive.
    @ParameterizedTest
    @MethodSource("mutations")
    void resolvesMutationParents(String format, String operation, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path target = fs.getPath("/alias/../value.txt");
            Path created = fs.getPath("/alias/../created");
            switch (operation) {
                case "stream" -> Files.writeString(target, "CHANGED");
                case "channel" -> {
                    try (var channel = Files.newByteChannel(target,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        channel.write(ByteBuffer.wrap("CHANGED".getBytes(StandardCharsets.UTF_8)));
                    }
                }
                case "delete" -> Files.delete(target);
                case "move" -> Files.move(target, created);
                case "directory" -> Files.createDirectory(created);
                case "link" -> Files.createSymbolicLink(created, fs.getPath("value.txt"));
                default -> throw new AssertionError(operation);
            }
            assertMutationResult(fs, operation);
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertMutationResult(fs, operation);
        }
    }

    /// Checks both the intended mutation and the unaffected lexical neighbor.
    private static void assertMutationResult(ArkivoFileSystem fs, String operation) throws IOException {
        assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
        assertFalse(Files.exists(fs.getPath("/created"), LinkOption.NOFOLLOW_LINKS));
        Path value = fs.getPath("/real/value.txt");
        Path created = fs.getPath("/real/created");
        switch (operation) {
            case "stream", "channel" -> assertEquals("CHANGED", Files.readString(value));
            case "delete" -> assertFalse(Files.exists(value));
            case "move" -> {
                assertFalse(Files.exists(value));
                assertEquals("REAL", Files.readString(created));
            }
            case "directory" -> assertTrue(Files.isDirectory(created));
            case "link" -> {
                assertTrue(Files.isSymbolicLink(created));
                assertEquals("REAL", Files.readString(created));
            }
            default -> throw new AssertionError(operation);
        }
    }

    /// Follows terminal links for file bodies but preserves them for exclusive creation, moves, and deletion.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar", "zip", "7z"})
    void appliesMutationLinkPolicies(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Path link = fs.getPath("/real/filelink");
            Files.writeString(link, "TARGET");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            Path broken = fs.getPath("/broken");
            assertThrows(FileAlreadyExistsException.class,
                    () -> Files.newOutputStream(broken, StandardOpenOption.CREATE_NEW).close());
            Files.writeString(broken, "CREATED");
            assertTrue(Files.isSymbolicLink(broken));
            assertEquals("CREATED", Files.readString(fs.getPath("/absent")));
            Files.move(link, fs.getPath("/moved-link"));
            assertTrue(Files.isSymbolicLink(fs.getPath("/moved-link")));
            Files.delete(fs.getPath("/moved-link"));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            assertThrows(NoSuchFileException.class,
                    () -> Files.writeString(fs.getPath("/missing/../value.txt"), "BAD"));
            assertThrows(NotDirectoryException.class,
                    () -> Files.delete(fs.getPath("/value.txt/../value.txt")));
            assertThrows(FileSystemLoopException.class,
                    () -> Files.writeString(fs.getPath("/cycle/../value.txt"), "BAD"));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
            assertEquals("TARGET", Files.readString(fs.getPath("/real/value.txt")));
            assertTrue(Files.isSymbolicLink(fs.getPath("/broken")));
            assertEquals("CREATED", Files.readString(fs.getPath("/absent")));
            assertFalse(Files.exists(fs.getPath("/real/filelink"), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fs.getPath("/moved-link"), LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// Retains implicit-parent creation after resolving existing links without cancelling missing parents.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "tar"})
    void createsImplicitParentsBelowResolvedLinks(String format, @TempDir Path directory) throws IOException {
        try (var fs = open(format, true, directory)) {
            Files.writeString(fs.getPath("/alias/../new-parent/child"), "CREATED");
            Files.createDirectory(fs.getPath("/alias/../new-directory/child"));
            Files.createSymbolicLink(fs.getPath("/alias/../new-links/link"), fs.getPath("../value.txt"));
            assertThrows(NoSuchFileException.class,
                    () -> Files.writeString(fs.getPath("/missing/nested/../../value.txt"), "BAD"));
            assertFalse(Files.exists(fs.getPath("/missing")));
        }
        try (var fs = ArkivoFormats.openFileSystem(format, directory.resolve("paths." + format))) {
            assertEquals("CREATED", Files.readString(fs.getPath("/real/new-parent/child")));
            assertTrue(Files.isDirectory(fs.getPath("/real/new-directory/child")));
            assertEquals("REAL", Files.readString(fs.getPath("/real/new-links/link")));
            assertFalse(Files.exists(fs.getPath("/new-parent")));
            assertFalse(Files.exists(fs.getPath("/new-directory")));
            assertFalse(Files.exists(fs.getPath("/new-links")));
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
        }
    }

    /// Resolves both hard-link paths while retaining TAR's restriction to regular-file targets.
    @Test
    void resolvesTarHardLinkParents(@TempDir Path directory) throws IOException {
        try (var fs = open("tar", true, directory)) {
            Files.createLink(fs.getPath("/alias/../hard"), fs.getPath("/alias/../value.txt"));
            assertEquals("REAL", Files.readString(fs.getPath("/real/hard")));
            assertFalse(Files.exists(fs.getPath("/hard")));
            assertThrows(FileSystemException.class,
                    () -> Files.createLink(fs.getPath("/rejected"), fs.getPath("/real/filelink")));
        }
        try (var fs = ArkivoFormats.openFileSystem("tar", directory.resolve("paths.tar"))) {
            assertEquals("REAL", Files.readString(fs.getPath("/real/hard")));
            assertEquals("ROOT", Files.readString(fs.getPath("/value.txt")));
            assertFalse(Files.exists(fs.getPath("/rejected")));
        }
    }

    /// Keeps dot components and symbolic-link lookup semantics when paths round-trip through provider URIs.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesUriPathComponents(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory);
             var registered = fs.provider().newFileSystem(fs.getPath("/").toUri(), Map.of())) {
            for (String name : List.of("/alias/../value.txt", "alias/.././value.txt", "/dirlink/./value.txt")) {
                Path path = fs.getPath(name);
                Path restored = fs.provider().getPath(path.toUri());
                assertEquals(registered, restored.getFileSystem());
                assertEquals(path.toAbsolutePath().toString(), restored.toString());
                assertEquals("REAL", Files.readString(restored));
            }
            Path missing = fs.getPath("/missing/../value.txt");
            Path restored = fs.provider().getPath(missing.toUri());
            assertEquals(missing.toString(), restored.toString());
            assertThrows(NoSuchFileException.class, () -> Files.readString(restored));
        }
    }

    /// Escapes delimiter-like source names without confusing literal percent escapes with exclamation marks.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesUriSourceDelimiters(String format, boolean update, @TempDir Path directory) throws IOException {
        for (String name : List.of("bang!", "literal%21", "space !", "bang!/nested!", "bang!inside")) {
            Path parent = Files.createDirectories(directory.resolve(name));
            try (var fs = open(format, update, parent);
                 var registered = fs.provider().newFileSystem(URI.create(fs.getPath("/").toUri()
                         .toASCIIString().replace("%21inside", "!inside")), Map.of())) {
                Path entry = registered.getPath("/real/value.txt");
                assertEquals(entry, fs.provider().getPath(entry.toUri()));
                assertEquals("REAL", Files.readString(fs.provider().getPath(entry.toUri())));
                assertEquals(registered, fs.provider().getFileSystem(entry.toUri()));
            }
        }
    }

    /// Expands links before processing parent components, including parent components in link targets.
    @ParameterizedTest
    @MethodSource("configurations")
    void resolvesLinksBeforeParentComponents(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("/alias/../value.txt", "alias/../value.txt", "/indirect", "/absolute")) {
                Path path = fs.getPath(name);
                assertEquals(fs.getPath("/real/value.txt"), path.toRealPath());
                assertEquals("REAL", Files.readString(path));
            }
            assertThrows(NoSuchFileException.class, () -> fs.getPath("/missing/../value.txt").toRealPath());
            assertThrows(NotDirectoryException.class, () -> fs.getPath("/value.txt/../value.txt").toRealPath());
            assertThrows(NotDirectoryException.class, () -> fs.getPath("/value.txt/.").toRealPath());
            assertEquals(fs.getPath("/value.txt"), fs.getPath("/../../value.txt").toRealPath());
        }
    }

    /// Checks the target of a link while preserving the ability to test the link entry itself.
    @ParameterizedTest
    @MethodSource("configurations")
    void followsLinksForAccessChecks(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            Path broken = fs.getPath("/broken");
            assertFalse(Files.exists(broken));
            assertFalse(Files.isReadable(broken));
            assertTrue(Files.exists(broken, LinkOption.NOFOLLOW_LINKS));
            assertThrows(NoSuchFileException.class, () -> fs.provider().checkAccess(broken));
            Path child = fs.getPath("/dirlink/value.txt");
            assertTrue(Files.exists(child));
            assertTrue(Files.isReadable(child));
            fs.provider().checkAccess(child, AccessMode.READ);
            assertThrows(FileSystemLoopException.class, () -> fs.provider().checkAccess(fs.getPath("/cycle")));
            assertThrows(FileSystemLoopException.class, () -> fs.getPath("/cycle/value.txt").toRealPath());
            assertThrows(FileSystemLoopException.class, () -> fs.getPath("/cycle/../value.txt").toRealPath());
        }
    }

    /// Leaves only the terminal link unfollowed when reading attributes or link targets.
    @ParameterizedTest
    @MethodSource("configurations")
    void resolvesParentsForNoFollowOperations(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            Path child = fs.getPath("/dirlink/value.txt");
            assertTrue(Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile());
            assertEquals(4L, Files.readAttributes(child, "basic:size", LinkOption.NOFOLLOW_LINKS).get("size"));
            @Nullable BasicFileAttributeView view = Files.getFileAttributeView(child, BasicFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            assertNotNull(view);
            assertEquals(4L, view.readAttributes().size());
            Path link = fs.getPath("/dirlink/filelink");
            assertTrue(Files.isSymbolicLink(link));
            assertEquals(fs.getPath("value.txt"), Files.readSymbolicLink(link));
            assertTrue(Files.readAttributes(link, BasicFileAttributes.class).isRegularFile());
            @Nullable BasicFileAttributeView missingView = Files.getFileAttributeView(
                    fs.getPath("/dirlink/absent"), BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertNotNull(missingView);
            assertThrows(NoSuchFileException.class, missingView::readAttributes);
        }
    }

    /// Preserves lexical link names in real paths without incorrectly cancelling a link followed by a parent.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesLinksInNoFollowRealPaths(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("/alias/../value.txt", "/dirlink/value.txt", "/broken")) {
                assertEquals(fs.getPath(name), fs.getPath(name).toRealPath(LinkOption.NOFOLLOW_LINKS));
            }
            assertEquals(fs.getPath("/value.txt"), fs.getPath("/real/../value.txt").toRealPath(LinkOption.NOFOLLOW_LINKS));
            assertThrows(NoSuchFileException.class,
                    () -> fs.getPath("/missing/../value.txt").toRealPath(LinkOption.NOFOLLOW_LINKS));
            assertThrows(NotDirectoryException.class,
                    () -> fs.getPath("/value.txt/..").toRealPath(LinkOption.NOFOLLOW_LINKS));
        }
    }

    /// Rejects an unmatched parent traversal rather than returning a path that reads the root entry.
    @ParameterizedTest
    @MethodSource("configurations")
    void rejectsAmbiguousRelativeParentTraversal(String format, boolean update, @TempDir Path directory)
            throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String prefix : List.of("", "/")) {
                Path base = fs.getPath(prefix + "real/sub/..");
                Path target = fs.getPath(prefix + "real/value.txt");
                assertEquals("REAL", Files.readString(target));
                assertEquals("ROOT", Files.readString(base.resolve("../../value.txt")));
                assertThrows(IllegalArgumentException.class, () -> base.relativize(target));
                Path child = base.resolve("value.txt");
                assertEquals(fs.getPath("value.txt"), base.relativize(child));
                assertTrue(Files.isSameFile(child, target));
            }
        }
    }

    /// Delivers paths relative to the original directory to both filters and iterators.
    @ParameterizedTest
    @MethodSource("configurations")
    void preservesDirectoryPathSpelling(String format, boolean update, @TempDir Path directory) throws IOException {
        try (var fs = open(format, update, directory)) {
            for (String name : List.of("real", "./real", "/dirlink", "dirlink", "/alias/..")) {
                Path parent = fs.getPath(name);
                var seen = new HashSet<Path>();
                var returned = new HashSet<Path>();
                try (var children = Files.newDirectoryStream(parent, child -> {
                    assertEquals(parent.resolve(child.getFileName()), child);
                    assertEquals(child.getFileName(), parent.relativize(child));
                    seen.add(child);
                    return true;
                })) {
                    children.forEach(returned::add);
                }
                assertEquals(Set.of(parent.resolve("sub"), parent.resolve("value.txt"), parent.resolve("filelink")), returned);
                assertEquals(returned, seen);
            }
        }
    }

    /// Creates a small archive whose lexical and link-resolved parent directories differ.
    private static ArkivoFileSystem open(String format, boolean update, Path directory) throws IOException {
        Path archive = directory.resolve("paths." + format);
        try (var fs = ArkivoFormats.createFileSystem(format, archive)) {
            Files.createDirectories(fs.getPath("/real/sub"));
            Files.writeString(fs.getPath("/value.txt"), "ROOT");
            Files.writeString(fs.getPath("/real/value.txt"), "REAL");
            Files.createSymbolicLink(fs.getPath("/alias"), fs.getPath("real/sub"));
            Files.createSymbolicLink(fs.getPath("/dirlink"), fs.getPath("real"));
            Files.createSymbolicLink(fs.getPath("/real/filelink"), fs.getPath("value.txt"));
            Files.createSymbolicLink(fs.getPath("/broken"), fs.getPath("absent"));
            Files.createSymbolicLink(fs.getPath("/cycle"), fs.getPath("cycle"));
            Files.createSymbolicLink(fs.getPath("/indirect"), fs.getPath("alias/../value.txt"));
            Files.createSymbolicLink(fs.getPath("/absolute"), fs.getPath("/alias/../value.txt"));
        }
        return update ? ArkivoFormats.updateFileSystem(format, archive) : ArkivoFormats.openFileSystem(format, archive);
    }
}
