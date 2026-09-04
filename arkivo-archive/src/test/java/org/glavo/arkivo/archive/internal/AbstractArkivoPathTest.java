// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemLoopException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotLinkException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies shared archive path parsing, composition, comparison, and URI behavior.
@NotNullByDefault
final class AbstractArkivoPathTest {
    /// Verifies separators, component access, parents, roots, subpaths, and iteration.
    @Test
    void parsesAndExposesPathComponents() {
        TestFileSystem fileSystem = new TestFileSystem(URI.create("file:///tmp/archive.zip"));
        TestPath path = fileSystem.getPath("/alpha//beta/", "gamma");

        assertSame(fileSystem, path.getFileSystem());
        assertTrue(path.isAbsolute());
        assertEquals("/alpha/beta/gamma", path.toString());
        assertEquals("alpha/beta/gamma", path.archivePath());
        assertEquals(fileSystem.getPath("/"), path.getRoot());
        assertEquals(fileSystem.getPath("gamma"), path.getFileName());
        assertEquals(fileSystem.getPath("/alpha/beta"), path.getParent());
        assertEquals(3, path.getNameCount());
        assertEquals(fileSystem.getPath("alpha"), path.getName(0));
        assertEquals(fileSystem.getPath("beta/gamma"), path.subpath(1, 3));

        ArrayList<Path> elements = new ArrayList<>();
        path.forEach(elements::add);
        assertEquals(
                List.of(fileSystem.getPath("alpha"), fileSystem.getPath("beta"), fileSystem.getPath("gamma")),
                elements
        );
        Iterator<Path> iterator = path.iterator();
        assertEquals(fileSystem.getPath("alpha"), iterator.next());
        assertThrows(UnsupportedOperationException.class, iterator::remove);

        TestPath root = fileSystem.getPath("////");
        assertEquals("/", root.toString());
        assertNull(root.getFileName());
        assertNull(root.getParent());
        assertEquals(0, root.getNameCount());

        TestPath empty = fileSystem.getPath("");
        assertEquals("", empty.toString());
        assertFalse(empty.isAbsolute());
        assertNull(empty.getRoot());
        assertNull(empty.getFileName());
        assertNull(empty.getParent());
    }

    /// Verifies invalid component indexes and subpath ranges are rejected.
    @Test
    void validatesComponentRanges() {
        TestPath path = new TestFileSystem(null).getPath("a/b/c");

        assertThrows(IllegalArgumentException.class, () -> path.getName(-1));
        assertThrows(IllegalArgumentException.class, () -> path.getName(3));
        assertThrows(IllegalArgumentException.class, () -> path.subpath(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> path.subpath(0, 0));
        assertThrows(IllegalArgumentException.class, () -> path.subpath(2, 2));
        assertThrows(IllegalArgumentException.class, () -> path.subpath(2, 4));
        assertThrows(IllegalArgumentException.class, () -> path.subpath(3, 3));
    }

    /// Verifies dot normalization, absolute replacement, empty resolution, and sibling composition.
    @Test
    void normalizesAndResolvesPaths() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        assertEquals("/a/d", fileSystem.getPath("/a/./b/../c/../d/..").resolve("d").normalize().toString());
        assertEquals("../../b", fileSystem.getPath("../../a/../b").normalize().toString());
        assertEquals("/", fileSystem.getPath("/../../a/..").normalize().toString());

        TestPath base = fileSystem.getPath("/a/b");
        TestPath empty = fileSystem.getPath("");
        TestPath replacement = fileSystem.getPath("/replacement");
        assertSame(base, base.resolve(empty));
        assertSame(replacement, base.resolve(replacement));
        assertEquals(fileSystem.getPath("/a/b/c/d"), base.resolve("c/d"));
        assertEquals(fileSystem.getPath("/a/c"), base.resolveSibling("c"));
        assertEquals(fileSystem.getPath("sibling"), fileSystem.getPath("single").resolveSibling("sibling"));
        assertEquals(fileSystem.getPath("child"), fileSystem.getPath("", "child"));
        assertSame(base, base.toAbsolutePath());
        assertEquals(fileSystem.getPath("/relative/path"), fileSystem.getPath("relative/path").toAbsolutePath());
    }

    /// Verifies prefix and suffix matching observes root type, complete absolute paths, and file-system identity.
    @Test
    void matchesPrefixesAndSuffixes() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        TestPath path = fileSystem.getPath("/a/b/c");

        assertTrue(path.startsWith(fileSystem.getPath("/a")));
        assertTrue(path.startsWith("/a/b"));
        assertFalse(path.startsWith("a"));
        assertFalse(path.startsWith("/a/c"));
        assertFalse(path.startsWith("/a/b/c/d"));
        assertTrue(path.endsWith(fileSystem.getPath("b/c")));
        assertTrue(path.endsWith("c"));
        assertTrue(path.endsWith("/a/b/c"));
        assertFalse(path.endsWith("/b/c"));
        assertFalse(path.endsWith("a/b"));
        assertFalse(path.endsWith("a/b/c/d"));
        assertFalse(fileSystem.getPath("a/b/c").endsWith("/a/b/c"));

        TestFileSystem otherFileSystem = new TestFileSystem(null);
        assertFalse(path.startsWith(otherFileSystem.getPath("/a")));
        assertFalse(path.endsWith(otherFileSystem.getPath("c")));
        assertFalse(path.startsWith(Path.of("/a")));
        assertFalse(path.endsWith(Path.of("c")));
    }

    /// Verifies relative paths are inverse compositions for paths with matching root types.
    @Test
    void relativizesCompatiblePaths() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        TestPath source = fileSystem.getPath("/a/b/c");
        TestPath target = fileSystem.getPath("/a/d/e");

        assertEquals(fileSystem.getPath("../../d/e"), source.relativize(target));
        assertEquals(target, source.resolve(source.relativize(target)).normalize());
        assertEquals(fileSystem.getPath("../../b/c"), target.relativize(source));
        assertEquals(fileSystem.getPath(""), source.relativize(source));
        assertThrows(IllegalArgumentException.class, () -> source.relativize(fileSystem.getPath("relative")));
    }

    /// Verifies composition and comparison reject paths from another implementation or file-system instance.
    @Test
    void enforcesPathProviderIdentity() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        TestFileSystem otherFileSystem = new TestFileSystem(null);
        TestPath path = fileSystem.getPath("/a/b");
        TestPath same = fileSystem.getPath("/a/b");
        TestPath other = otherFileSystem.getPath("/a/b");

        assertEquals(path, same);
        assertTrue(path.equals(path));
        assertEquals(path.hashCode(), same.hashCode());
        assertEquals(0, path.compareTo(same));
        assertFalse(path.equals(null));
        assertFalse(path.equals(other));
        assertFalse(path.equals(fileSystem.getPath("a/b")));
        assertFalse(path.equals(fileSystem.getPath("/a/c")));
        assertFalse(path.equals(Path.of("/a/b")));
        assertThrows(ProviderMismatchException.class, () -> path.resolve(other));
        assertThrows(ProviderMismatchException.class, () -> path.relativize(other));
        assertThrows(ProviderMismatchException.class, () -> path.resolve(Path.of("other")));
        assertThrows(ClassCastException.class, () -> path.compareTo(other));
        assertThrows(ClassCastException.class, () -> path.compareTo(Path.of("/a/b")));
        assertSame(path, TestPath.checked(path, fileSystem));
        assertThrows(ProviderMismatchException.class, () -> TestPath.checked(other, fileSystem));
        assertTrue(fileSystem.getPath("a").compareTo(fileSystem.getPath("b")) < 0);
    }

    /// Verifies URI conversion makes paths absolute and percent-encodes each archive name segment.
    @Test
    void convertsPathsToEncodedUris() {
        TestFileSystem fileSystem = new TestFileSystem(URI.create("file:///tmp/archive%20name.zip"));
        TestPath path = fileSystem.getPath("dir", "a b", "#hash", "é");

        assertEquals(
                "arkivo+test:file:///tmp/archive%20name.zip!/dir/a%20b/%23hash/%C3%A9",
                path.toUri().toASCIIString()
        );
        assertEquals("arkivo+test:file:///tmp/archive%20name.zip!/", fileSystem.getPath("/").toUri().toString());
        assertThrows(UnsupportedOperationException.class, () -> new TestFileSystem(null).getPath("entry").toUri());
    }

    /// Verifies real paths expand relative, absolute, and chained symbolic-link targets with remaining names.
    @Test
    void resolvesRealPathsThroughSymbolicLinks() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(null);
        fileSystem.addFile("/target/leaf");
        fileSystem.addFile("/destination/tail");
        fileSystem.addFile("/final");
        fileSystem.addSymbolicLink("/root/relative", "../target");
        fileSystem.addSymbolicLink("/absolute", "/destination");
        fileSystem.addSymbolicLink("/chain-a", "chain-b");
        fileSystem.addSymbolicLink("/chain-b", "/final");

        assertEquals(
                fileSystem.getPath("/target/leaf"),
                fileSystem.getPath("/root/relative/leaf").toRealPath()
        );
        assertEquals(
                fileSystem.getPath("/destination/tail"),
                fileSystem.getPath("/absolute/tail").toRealPath()
        );
        assertEquals(fileSystem.getPath("/final"), fileSystem.getPath("chain-a").toRealPath());
    }

    /// Verifies no-follow lookup, missing targets, option validation, and symbolic-link loop detection.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void enforcesRealPathValidationAndLinkLimit() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(null);
        fileSystem.addSymbolicLink("/links/value", "../missing");

        assertEquals(
                fileSystem.getPath("/links/value"),
                fileSystem.getPath("links/./value").toRealPath(LinkOption.NOFOLLOW_LINKS)
        );
        assertThrows(NoSuchFileException.class, () -> fileSystem.getPath("/links/value").toRealPath());
        assertThrows(NoSuchFileException.class, () -> fileSystem.getPath("/absent").toRealPath());
        assertThrows(
                NullPointerException.class,
                () -> fileSystem.getPath("/").toRealPath((LinkOption[]) null)
        );
        assertThrows(
                NullPointerException.class,
                () -> fileSystem.getPath("/").toRealPath(new LinkOption[]{null})
        );

        fileSystem.addSymbolicLink("/loop-a", "/loop-b");
        fileSystem.addSymbolicLink("/loop-b", "/loop-a");
        FileSystemLoopException failure = assertThrows(
                FileSystemLoopException.class,
                () -> fileSystem.getPath("/loop-a").toRealPath()
        );
        assertEquals("/loop-a", failure.getFile());
    }

    /// Verifies parsed component lists are copied and archive paths reject watch registration.
    @Test
    void snapshotsNamesAndRejectsWatchRegistration() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        ArrayList<String> names = new ArrayList<>(List.of("a", "b"));
        TestPath path = new TestPath(fileSystem, true, names);
        names.clear();

        assertEquals("/a/b", path.toString());
        assertThrows(
                UnsupportedOperationException.class,
                () -> path.register(null, new WatchEvent.Kind<?>[0])
        );
    }

    /// Provides the minimal file-system services needed by lexical path operations.
    @NotNullByDefault
    private static final class TestFileSystem extends FileSystem {
        /// Backing archive URI exposed by paths, or `null` when URI conversion is unavailable.
        private final @Nullable URI archiveUri;

        /// Provider implementing controlled real-path lookups for this file system.
        private final TestProvider provider;

        /// Whether this test file system remains open.
        private boolean open = true;

        /// Creates a file system with an optional backing archive URI.
        private TestFileSystem(@Nullable URI archiveUri) {
            this.archiveUri = archiveUri;
            this.provider = new TestProvider(this);
        }

        /// Returns the provider used by path operations.
        @Override
        public FileSystemProvider provider() {
            return provider;
        }

        /// Adds one existing regular file for real-path validation.
        private void addFile(String path) {
            provider.addFile(path);
        }

        /// Adds one symbolic link and its target for real-path validation.
        private void addSymbolicLink(String path, String target) {
            provider.addSymbolicLink(path, target);
        }

        /// Closes this test file system.
        @Override
        public void close() {
            open = false;
        }

        /// Returns whether this test file system remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Returns that this test file system is read-only.
        @Override
        public boolean isReadOnly() {
            return true;
        }

        /// Returns the archive path separator.
        @Override
        public String getSeparator() {
            return "/";
        }

        /// Returns the single archive root path.
        @Override
        public @Unmodifiable Iterable<Path> getRootDirectories() {
            return List.of(getPath("/"));
        }

        /// Returns no file stores.
        @Override
        public @Unmodifiable Iterable<FileStore> getFileStores() {
            return List.of();
        }

        /// Returns the basic attribute-view name.
        @Override
        public @Unmodifiable Set<String> supportedFileAttributeViews() {
            return Set.of("basic");
        }

        /// Creates an immutable test path from text components.
        @Override
        public TestPath getPath(String first, String... more) {
            return new TestPath(this, first, more);
        }

        /// Rejects matcher construction because it is outside these path tests.
        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            throw new UnsupportedOperationException("Test path matchers are not supported");
        }

        /// Returns the default user-principal lookup service.
        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return java.nio.file.FileSystems.getDefault().getUserPrincipalLookupService();
        }

        /// Rejects watch-service construction.
        @Override
        public WatchService newWatchService() {
            throw new UnsupportedOperationException("Test watch services are not supported");
        }
    }

    /// Provides controlled symbolic-link and existence lookups for test paths.
    @NotNullByDefault
    private static final class TestProvider extends FileSystemProvider {
        /// File system served by this provider.
        private final TestFileSystem fileSystem;

        /// Existing normalized absolute paths.
        private final Set<String> existingPaths = new HashSet<>();

        /// Symbolic-link targets keyed by normalized absolute link path.
        private final Map<String, TestPath> symbolicLinks = new HashMap<>();

        /// Creates a provider for one test file system.
        private TestProvider(TestFileSystem fileSystem) {
            this.fileSystem = fileSystem;
        }

        /// Adds one existing regular file.
        private void addFile(String path) {
            existingPaths.add(normalizedPath(fileSystem.getPath(path)));
        }

        /// Adds one existing symbolic link.
        private void addSymbolicLink(String path, String target) {
            String normalizedPath = normalizedPath(fileSystem.getPath(path));
            existingPaths.add(normalizedPath);
            symbolicLinks.put(normalizedPath, fileSystem.getPath(target));
        }

        /// Returns one normalized absolute test path as text.
        private String normalizedPath(Path path) {
            return TestPath.checked(path, fileSystem).toAbsolutePath().normalize().toString();
        }

        /// Returns the synthetic provider scheme.
        @Override
        public String getScheme() {
            return "arkivo-test";
        }

        /// Rejects URI-based file-system creation.
        @Override
        public FileSystem newFileSystem(URI uri, Map<String, ?> environment) {
            throw unsupported();
        }

        /// Rejects URI-based file-system lookup.
        @Override
        public FileSystem getFileSystem(URI uri) {
            throw unsupported();
        }

        /// Rejects URI-based path lookup.
        @Override
        public Path getPath(URI uri) {
            throw unsupported();
        }

        /// Rejects channel creation.
        @Override
        public SeekableByteChannel newByteChannel(
                Path path,
                Set<? extends OpenOption> options,
                FileAttribute<?>... attributes
        ) {
            throw unsupported();
        }

        /// Rejects directory iteration.
        @Override
        public DirectoryStream<Path> newDirectoryStream(
                Path directory,
                DirectoryStream.Filter<? super Path> filter
        ) {
            throw unsupported();
        }

        /// Rejects directory creation.
        @Override
        public void createDirectory(Path directory, FileAttribute<?>... attributes) {
            throw unsupported();
        }

        /// Rejects deletion.
        @Override
        public void delete(Path path) {
            throw unsupported();
        }

        /// Rejects copying.
        @Override
        public void copy(Path source, Path target, CopyOption... options) {
            throw unsupported();
        }

        /// Rejects moving.
        @Override
        public void move(Path source, Path target, CopyOption... options) {
            throw unsupported();
        }

        /// Rejects file-identity lookup.
        @Override
        public boolean isSameFile(Path first, Path second) {
            throw unsupported();
        }

        /// Rejects hidden-state lookup.
        @Override
        public boolean isHidden(Path path) {
            throw unsupported();
        }

        /// Rejects file-store lookup.
        @Override
        public FileStore getFileStore(Path path) {
            throw unsupported();
        }

        /// Rejects generic access checks.
        @Override
        public void checkAccess(Path path, AccessMode... modes) {
            throw unsupported();
        }

        /// Returns no attribute views because real-path lookup requests attributes directly.
        @Override
        public <V extends FileAttributeView> @Nullable V getFileAttributeView(
                Path path,
                Class<V> type,
                LinkOption... options
        ) {
            return null;
        }

        /// Returns fixed basic attributes for a known path.
        @Override
        public <A extends BasicFileAttributes> A readAttributes(
                Path path,
                Class<A> type,
                LinkOption... options
        ) throws IOException {
            if (type != BasicFileAttributes.class) {
                throw unsupported();
            }
            String normalizedPath = normalizedPath(path);
            if (!existingPaths.contains(normalizedPath)) {
                throw new NoSuchFileException(normalizedPath);
            }
            return type.cast(TestBasicFileAttributes.INSTANCE);
        }

        /// Rejects string-based attribute lookup.
        @Override
        public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) {
            throw unsupported();
        }

        /// Rejects generic attribute mutation.
        @Override
        public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
            throw unsupported();
        }

        /// Returns the configured target for a symbolic link.
        @Override
        public Path readSymbolicLink(Path link) throws IOException {
            String normalizedPath = normalizedPath(link);
            TestPath target = symbolicLinks.get(normalizedPath);
            if (target == null) {
                throw new NotLinkException(normalizedPath);
            }
            return target;
        }

        /// Creates the standard unsupported-operation failure for unused provider methods.
        private static UnsupportedOperationException unsupported() {
            return new UnsupportedOperationException("Operation is outside the real-path test fixture");
        }
    }

    /// Supplies fixed metadata for paths known by the real-path test provider.
    @NotNullByDefault
    private enum TestBasicFileAttributes implements BasicFileAttributes {
        /// Shared immutable attribute instance.
        INSTANCE;

        /// Epoch timestamp returned by every time accessor.
        private static final FileTime EPOCH = FileTime.fromMillis(0L);

        /// Returns the epoch modification time.
        @Override
        public FileTime lastModifiedTime() {
            return EPOCH;
        }

        /// Returns the epoch access time.
        @Override
        public FileTime lastAccessTime() {
            return EPOCH;
        }

        /// Returns the epoch creation time.
        @Override
        public FileTime creationTime() {
            return EPOCH;
        }

        /// Returns that the synthetic path is a regular file.
        @Override
        public boolean isRegularFile() {
            return true;
        }

        /// Returns that the synthetic path is not a directory.
        @Override
        public boolean isDirectory() {
            return false;
        }

        /// Returns that the synthetic attributes do not classify the path as a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return false;
        }

        /// Returns that the synthetic path has no other file type.
        @Override
        public boolean isOther() {
            return false;
        }

        /// Returns an empty synthetic file size.
        @Override
        public long size() {
            return 0L;
        }

        /// Returns no synthetic file key.
        @Override
        public @Nullable Object fileKey() {
            return null;
        }
    }

    /// Implements one concrete path type over the lexical test file system.
    @NotNullByDefault
    private static final class TestPath extends AbstractArkivoPath<TestFileSystem> {
        /// Creates a path from text components.
        private TestPath(TestFileSystem fileSystem, String first, String... more) {
            super(fileSystem, first, more);
        }

        /// Creates a path from parsed components.
        private TestPath(TestFileSystem fileSystem, boolean absolute, List<String> names) {
            super(fileSystem, absolute, names);
        }

        /// Returns a checked test path owned by the expected file system.
        private static TestPath checked(Path path, TestFileSystem fileSystem) {
            return requirePath(path, fileSystem, TestPath.class);
        }

        /// Creates another test path.
        @Override
        protected TestPath createPath(boolean absolute, List<String> names) {
            return new TestPath(getFileSystem(), absolute, names);
        }

        /// Returns the test provider URI scheme.
        @Override
        protected String uriScheme() {
            return "arkivo+test";
        }

        /// Returns the optional backing archive URI.
        @Override
        protected @Nullable URI archiveUri() {
            return getFileSystem().archiveUri;
        }
    }
}
