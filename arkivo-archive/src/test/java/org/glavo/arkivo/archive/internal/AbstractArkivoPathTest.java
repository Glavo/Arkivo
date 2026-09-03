// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
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
        assertTrue(path.endsWith(fileSystem.getPath("b/c")));
        assertTrue(path.endsWith("c"));
        assertTrue(path.endsWith("/a/b/c"));
        assertFalse(path.endsWith("/b/c"));
        assertFalse(path.endsWith("a/b"));

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
        assertEquals(path.hashCode(), same.hashCode());
        assertEquals(0, path.compareTo(same));
        assertFalse(path.equals(other));
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

        /// Whether this test file system remains open.
        private boolean open = true;

        /// Creates a file system with an optional backing archive URI.
        private TestFileSystem(@Nullable URI archiveUri) {
            this.archiveUri = archiveUri;
        }

        /// Returns the default provider because provider I/O is outside these lexical tests.
        @Override
        public FileSystemProvider provider() {
            return java.nio.file.FileSystems.getDefault().provider();
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
