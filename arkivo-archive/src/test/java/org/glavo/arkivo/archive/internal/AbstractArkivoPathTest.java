// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.util.NoSuchElementException;
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
    /// Checks lexical comparisons against component lists, including separators, roots, dots and Unicode text.
    @Test
    void comparesPathsAtComponentBoundaries() {
        TestFileSystem fs = new TestFileSystem(null);
        TestFileSystem otherFs = new TestFileSystem(null);
        @Unmodifiable List<String> texts = List.of("", "/", "//", ".", "..", "./", "/.", "/..", "a", "ab", "abc",
                "a/b", "a//b/", "a/bc", "ab/c", "/a", "/ab", "/a/b", "//a//b//", "/a/bc", "/ab/c",
                "a/./b", "a/../b", "./a", "../a", "a\\b", "a/\\b", "Aa", "BB", "/Aa", "/BB",
                "\u00e9", "e\u0301", "a/\u00e9", "\u03b4/\ud83d\ude42", "/\u03b4/\ud83d\ude42");
        Set<Path> uniquePaths = new HashSet<>();
        Set<String> uniqueTexts = new HashSet<>();
        for (String leftText : texts) {
            Path left = fs.getPath(leftText);
            @Unmodifiable List<String> leftNames = comparisonNames(left);
            uniquePaths.add(left);
            uniqueTexts.add(left.toString());
            assertThrows(NullPointerException.class, () -> left.startsWith((Path) null));
            assertThrows(NullPointerException.class, () -> left.endsWith((Path) null));
            for (String rightText : texts) {
                Path right = fs.getPath(rightText);
                @Unmodifiable List<String> rightNames = comparisonNames(right);
                int offset = leftNames.size() - rightNames.size();
                boolean equal = left.isAbsolute() == right.isAbsolute() && leftNames.equals(rightNames);
                boolean starts = rightText.isEmpty() ? left.toString().isEmpty()
                        : left.isAbsolute() == right.isAbsolute() && offset >= 0
                        && leftNames.subList(0, rightNames.size()).equals(rightNames);
                boolean ends = rightText.isEmpty() ? left.toString().isEmpty()
                        : offset >= 0 && (!right.isAbsolute() || left.isAbsolute() && offset == 0)
                        && leftNames.subList(offset, leftNames.size()).equals(rightNames);
                String context = leftText + " vs " + rightText;
                assertEquals(equal, left.equals(right), context);
                assertEquals(starts, left.startsWith(right), context);
                assertEquals(starts, left.startsWith(rightText), context);
                assertEquals(ends, left.endsWith(right), context);
                assertEquals(ends, left.endsWith(rightText), context);
                assertEquals(equal, left.compareTo(right) == 0, context);
                if (equal) assertEquals(left.hashCode(), right.hashCode(), context);
                Path foreign = otherFs.getPath(rightText);
                assertFalse(left.equals(foreign), context);
                assertFalse(left.startsWith(foreign), context);
                assertFalse(left.endsWith(foreign), context);
            }
        }
        assertEquals(uniqueTexts.size(), uniquePaths.size());
        for (Path path : uniquePaths) assertTrue(uniquePaths.contains(fs.getPath(path.toString())));
    }

    /// Returns component text for the comparison reference, treating the empty path separately from its empty name.
    private static @Unmodifiable List<String> comparisonNames(Path path) {
        ArrayList<String> names = new ArrayList<>();
        if (!path.toString().isEmpty()) {
            for (Path name : path) names.add(name.toString());
        }
        return List.copyOf(names);
    }

    /// Iterators create only requested components and retain literal names and file-system identity.
    @ParameterizedTest
    @ValueSource(strings = {"", "/", "single", "a/b/c", "/a/./../b", "/\u03b4/\ud83d\ude42"})
    void iteratesComponentsLazily(String name) {
        TestFileSystem fs = new TestFileSystem(null);
        Path path = fs.getPath(name);
        Iterator<Path> iterator = path.iterator();
        Iterator<Path> independent = path.iterator();
        assertEquals(0, fs.pathCreations);
        assertThrows(UnsupportedOperationException.class, iterator::remove);
        for (int index = 0; index < path.getNameCount(); index++) {
            int creations = fs.pathCreations;
            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());
            assertEquals(creations, fs.pathCreations);
            Path component = iterator.next();
            assertEquals(creations + (name.isEmpty() ? 0 : 1), fs.pathCreations);
            assertFalse(component.isAbsolute());
            assertSame(fs, component.getFileSystem());
            assertEquals(path.getName(index), component);
            assertThrows(UnsupportedOperationException.class, iterator::remove);
        }
        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
        assertThrows(NoSuchElementException.class, iterator::next);
        assertEquals(path.getNameCount() != 0, independent.hasNext());
        if (independent.hasNext()) {
            assertEquals(path.getName(0), independent.next());
        }
        ArrayList<Path> remaining = new ArrayList<>();
        independent.forEachRemaining(remaining::add);
        assertEquals(Math.max(0, path.getNameCount() - 1), remaining.size());
        for (int index = 0; index < remaining.size(); index++) {
            assertEquals(path.getName(index + 1), remaining.get(index));
        }
        assertFalse(independent.hasNext());
    }

    /// Sibling resolution rejects null operands even when the base has no parent.
    @ParameterizedTest
    @ValueSource(strings = {"", "/", "single", ".", "a/b", "/a/b"})
    void rejectsNullSibling(String name) {
        TestFileSystem fileSystem = new TestFileSystem(null);
        Path path = fileSystem.getPath(name);
        assertThrows(NullPointerException.class, () -> path.resolveSibling((Path) null));
        assertThrows(NullPointerException.class, () -> path.resolveSibling((String) null));
        Path sibling = fileSystem.getPath("other");
        @Nullable Path parent = path.getParent();
        assertEquals(parent == null ? sibling : parent.resolve(sibling), path.resolveSibling(sibling));
    }

    /// Dot elements contribute no parent traversal when computing relative paths.
    @Test
    void relativizesDotElementsWithoutCollapsingParentElements() {
        TestFileSystem fs = new TestFileSystem(null);
        for (String prefix : List.of("", "/")) {
            for (String base : List.of("dir", "./dir", "dir/.", "./dir/./.")) {
                for (String target : List.of("dir/source", "./dir/source", "dir/./source/.")) {
                    Path from = fs.getPath(prefix + base);
                    Path to = fs.getPath(prefix + target);
                    assertEquals(fs.getPath("source"), from.relativize(to));
                    assertEquals(to.normalize(), from.resolve(from.relativize(to)).normalize());
                }
            }
        }
        assertEquals(fs.getPath(""), fs.getPath(".").relativize(fs.getPath("")));
        assertEquals(fs.getPath(""), fs.getPath("/./.").relativize(fs.getPath("/")));
        assertEquals(fs.getPath("../other"), fs.getPath("/dir/.").relativize(fs.getPath("/other/.")));
        assertEquals(fs.getPath("../source"),
                fs.getPath("/dir/.").relativize(fs.getPath("/dir/../source")));
        assertEquals(fs.getPath("source"),
                fs.getPath("/alias/../.").relativize(fs.getPath("/alias/../source")));
    }

    /// Rejects unmatched parent elements instead of treating them as reversible child names.
    @ParameterizedTest
    @ValueSource(strings = {"a/x/..", "a/../x", "a/x/../z", "../a", "../..", "a/../."})
    void rejectsUnmatchedParentElementsInRelativeBase(String base) {
        TestFileSystem fs = new TestFileSystem(null);
        for (String prefix : List.of("", "/")) {
            Path from = fs.getPath(prefix + base);
            Path to = fs.getPath(prefix + "a/y");
            assertThrows(IllegalArgumentException.class, () -> from.relativize(to));
            assertEquals(fs.getPath("child"), from.relativize(from.resolve("child")));
            assertEquals(fs.getPath(""), from.relativize(from));
        }
    }

    /// Separator runs preserve literal names, dot elements and non-ASCII text before explicit normalization.
    @Test
    void parsesRepeatedSeparatorsWithoutChangingNames() {
        TestFileSystem fileSystem = new TestFileSystem(null);
        List<String> names = List.of("alpha", ".", "..", "\u03b4\ud83d\ude42", "back\\slash");
        for (int prefix = 0; prefix <= 3; prefix++) {
            for (int separator = 1; separator <= 4; separator++) {
                for (int suffix = 0; suffix <= 3; suffix++) {
                    String text = "/".repeat(prefix) + String.join("/".repeat(separator), names) + "/".repeat(suffix);
                    TestPath path = fileSystem.getPath(text);
                    assertEquals(prefix != 0, path.isAbsolute());
                    assertEquals(names.size(), path.getNameCount());
                    for (int index = 0; index < names.size(); index++) {
                        assertEquals(names.get(index), path.getName(index).toString());
                    }
                    assertEquals((prefix == 0 ? "" : "/") + String.join("/", names), path.toString());
                }
            }
        }
    }

    /// Entry-name extraction strips only the root marker, including for empty and dot-containing paths.
    @ParameterizedTest
    @ValueSource(strings = {"", "/", "entry", "/entry", "./a/../b", "/./a/../b", "a//b///", "/a//b///"})
    void extractsArchiveNamesWithoutNormalizingComponents(String name) {
        TestPath path = new TestFileSystem(null).getPath(name);
        String text = path.toString();
        assertEquals(path.isAbsolute() ? text.substring(1) : text, path.archivePath());
    }

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
        assertEquals(empty, empty.getFileName());
        assertNull(empty.getParent());
    }

    /// Empty relative paths expose one empty name, including results of normalization and relativization.
    @Test
    void exposesEmptyNameComponents() {
        TestFileSystem fs = new TestFileSystem(null);
        Path empty = fs.getPath("");
        for (Path path : List.of(empty, fs.getPath("a").relativize(fs.getPath("a")),
                fs.getPath(".").normalize(), fs.getPath("a/..").normalize())) {
            assertEquals(empty, path);
            assertEquals(1, path.getNameCount());
            assertEquals(empty, path.getFileName());
            assertEquals(empty, path.getName(0));
            assertEquals(empty, path.subpath(0, 1));
            Iterator<Path> iterator = path.iterator();
            assertEquals(empty, iterator.next());
            assertFalse(iterator.hasNext());
            assertThrows(IllegalArgumentException.class, () -> path.getName(1));
            assertEquals(fs.getPath("/"), path.toAbsolutePath());
            assertTrue(path.startsWith(empty));
            assertTrue(path.endsWith(empty));
        }
        for (String name : List.of("a", "/", "/a")) {
            Path path = fs.getPath(name);
            assertFalse(path.startsWith(empty));
            assertFalse(path.endsWith(empty));
            assertEquals(path, path.resolve(empty));
            assertEquals(path, empty.resolve(path));
        }
        assertEquals(fs.getPath("a/b"), empty.relativize(fs.getPath("a/b")));
        assertEquals(fs.getPath("../.."), fs.getPath("a/b").relativize(empty));
        assertEquals(0, fs.getPath("/").getNameCount());
        assertFalse(fs.getPath("/").iterator().hasNext());
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
        TestPath relative = fileSystem.getPath("relative/child");
        assertSame(relative, empty.resolve(relative));
        assertEquals(fileSystem.getPath("/relative/child"), fileSystem.getPath("/").resolve(relative));
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
        assertEquals("arkivo+test:file:///tmp/archive%20name.zip!/alias/.././a%20b/%23hash/%C3%A9",
                fileSystem.getPath("alias/.././a b/#hash/é").toUri().toASCIIString());
        assertThrows(UnsupportedOperationException.class, () -> new TestFileSystem(null).getPath("entry").toUri());
    }

    /// Checks whole-path quoting against independent segment quoting, without resolving dot elements.
    @ParameterizedTest
    @ValueSource(strings = {"file:///tmp/archive.zip", "file:///tmp/bang!/archive%20name.zip",
            "arkivo+zip:file:///tmp/outer.zip!/inner%20name.zip"})
    void preservesUriSegmentEscaping(String source) throws URISyntaxException {
        URI sourceUri = URI.create(source);
        TestFileSystem fileSystem = new TestFileSystem(sourceUri);
        ArrayList<String> inputs = new ArrayList<>(List.of("", "/", "//", "a//b/", ".././a",
                "/alias/.././value", "a:b/c:d", "!/a%20b/#?", "\\server\\name", "é/e\u0301/\ud83d\ude42"));
        for (int character = 0; character < 128; character++) {
            inputs.add("a" + (char) character + "b/c" + (char) character + "d");
        }
        String quotedSource = sourceUri.isOpaque() ? source.replace("%", "%25") : source;
        for (String input : inputs) {
            for (String prefix : List.of("", "/")) {
                Path path = fileSystem.getPath(prefix + input);
                StringBuilder quotedNames = new StringBuilder();
                for (Path name : path) {
                    if (!quotedNames.isEmpty()) quotedNames.append('/');
                    quotedNames.append(new URI(null, null, "/" + name, null).getRawPath().substring(1));
                }
                URI expected = URI.create("arkivo+test:" + quotedSource.replace("!", "%21") + "!/" + quotedNames);
                URI actual = path.toUri();
                assertEquals(expected.toString(), actual.toString(), input);
                assertEquals(expected.toASCIIString(), actual.toASCIIString(), input);
                assertEquals(path.toAbsolutePath().toUri(), actual, input);
            }
        }
    }

    /// Resolving ordinary paths checks each component once without attempting symbolic-link reads.
    @Test
    void resolvesRegularPathsWithoutExceptionDrivenLinkProbes() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(null);
        fileSystem.addFile("/parent/child/value");
        TestPath path = fileSystem.getPath("/parent/child/value");
        assertSame(path, path.normalize());
        assertSame(path, path.toRealPath());
        assertEquals(3, fileSystem.provider.attributeReads);
        assertEquals(0, fileSystem.provider.linkReads);

        fileSystem.addSymbolicLink("/parent/child/value", "/replacement");
        fileSystem.addFile("/replacement");
        assertEquals(fileSystem.getPath("/replacement"), path.toRealPath());
        assertEquals(7, fileSystem.provider.attributeReads);
        assertEquals(1, fileSystem.provider.linkReads);
    }

    /// Ordinary traversal constructs only parent prefixes and, for relative inputs, the absolute result.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 8, 32})
    void reusesResolvedPathsWithoutSkippingAttributeChecks(int depth) throws IOException {
        for (boolean absolute : new boolean[]{false, true}) {
            TestFileSystem fs = new TestFileSystem(null);
            String name = "parent/".repeat(Math.max(0, depth - 1)) + (depth == 0 ? "" : "value");
            fs.addFile("/" + name);
            TestPath path = fs.getPath((absolute ? "/" : "") + name);
            fs.pathCreations = 0;
            Path result = path.toRealPath();
            assertEquals("/" + name, result.toString());
            assertEquals(Math.max(0, depth - 1) + (absolute ? 0 : 1), fs.pathCreations);
            assertEquals(Math.max(1, depth), fs.provider.attributeReads);
            assertEquals(0, fs.provider.linkReads);
            if (absolute) assertSame(path, result);
        }
    }

    /// Expanding chained targets retains the original suffix and resolves its parent elements afterward.
    @ParameterizedTest
    @ValueSource(strings = {"outer/../value", "/outer/../value", "./outer/../value/."})
    void retainsSuffixWhenExpandingLinkTargets(String name) throws IOException {
        TestFileSystem fs = new TestFileSystem(null);
        fs.addFile("/real/sub/child");
        fs.addFile("/real/value/leaf");
        fs.addSymbolicLink("/dir/link", "../real/sub");
        fs.addSymbolicLink("/outer", "dir/link");
        assertEquals(fs.getPath("/real/value"), fs.getPath(name).toRealPath());
        assertEquals(2, fs.provider.linkReads);
    }

    /// Missing creation suffixes are retained only after the preceding link and parent components are resolved.
    @Test
    void resolvesCreationSuffixesAfterLinks() throws IOException {
        TestFileSystem fs = new TestFileSystem(null);
        fs.addFile("/real/sub/child");
        fs.addSymbolicLink("/alias", "real/sub");
        assertEquals(fs.getPath("/real/new"), fs.getPath("/alias/../new").resolvePath(true, true, false));
        assertEquals(fs.getPath("/real/sub/new/deep"),
                fs.getPath("/alias/new/deep").resolvePath(true, true, true));
        for (String name : List.of("/alias/new/deep", "/alias/new/../deep", "/alias/new/./deep")) {
            assertThrows(NoSuchFileException.class, () -> fs.getPath(name).resolvePath(true, true, false));
        }
        for (String name : List.of("/alias/new/../deep", "/alias/new/./deep")) {
            assertThrows(NoSuchFileException.class, () -> fs.getPath(name).resolvePath(true, true, true));
        }
    }

    /// Targets with no ordinary components still resolve against the correct root or containing directory.
    @ParameterizedTest
    @ValueSource(strings = {"", ".", "/", "/."})
    void resolvesEmptyAndRootLinkTargets(String target) throws IOException {
        TestFileSystem fs = new TestFileSystem(null);
        fs.addFile("/value");
        fs.addFile("/dir/value");
        fs.addSymbolicLink("/dir/link", target);
        String prefix = target.startsWith("/") ? "/" : "/dir/";
        assertEquals(fs.getPath(prefix), fs.getPath("/dir/link").toRealPath());
        assertEquals(fs.getPath(prefix + "value"), fs.getPath("/dir/link/value").toRealPath());
    }

    /// Root and no-follow lookups still verify existence without reading a link target.
    @Test
    void checksRootAndNoFollowPathsOnce() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(null);
        TestPath root = fileSystem.getPath("/");
        assertSame(root, root.toRealPath());
        assertEquals(1, fileSystem.provider.attributeReads);
        fileSystem.addSymbolicLink("/link", "/missing");
        TestPath link = fileSystem.getPath("/link");
        assertSame(link, link.toRealPath(LinkOption.NOFOLLOW_LINKS));
        assertEquals(2, fileSystem.provider.attributeReads);
        assertEquals(0, fileSystem.provider.linkReads);
    }

    /// A link replaced between metadata and target reads is rechecked for existence.
    @Test
    void rechecksLinksReplacedDuringResolution() throws IOException {
        TestFileSystem fileSystem = new TestFileSystem(null);
        TestPath link = fileSystem.getPath("/link");
        fileSystem.addSymbolicLink("/link", "/target");
        fileSystem.provider.beforeLinkRead = () -> fileSystem.provider.symbolicLinks.remove("/link");
        assertSame(link, link.toRealPath());
        assertEquals(2, fileSystem.provider.attributeReads);

        fileSystem.addSymbolicLink("/link", "/target");
        fileSystem.provider.beforeLinkRead = () -> {
            fileSystem.provider.symbolicLinks.remove("/link");
            fileSystem.provider.existingPaths.remove("/link");
        };
        assertThrows(NoSuchFileException.class, link::toRealPath);
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

        /// Number of paths constructed from already parsed components.
        private int pathCreations;

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

        /// Number of basic attribute reads requested by path resolution.
        private int attributeReads;

        /// Number of symbolic-link target reads requested by path resolution.
        private int linkReads;

        /// Optional mutation performed between the attribute lookup and target read.
        private @Nullable Runnable beforeLinkRead;

        /// Creates a provider for one test file system.
        private TestProvider(TestFileSystem fileSystem) {
            this.fileSystem = fileSystem;
            existingPaths.add("/");
        }

        /// Adds one existing regular file.
        private void addFile(String path) {
            String normalized = normalizedPath(fileSystem.getPath(path));
            existingPaths.add(normalized);
            for (int index = normalized.indexOf('/', 1); index >= 0;
                 index = normalized.indexOf('/', index + 1)) {
                existingPaths.add(normalized.substring(0, index));
            }
        }

        /// Adds one existing symbolic link.
        private void addSymbolicLink(String path, String target) {
            String normalizedPath = normalizedPath(fileSystem.getPath(path));
            addFile(path);
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
            attributeReads++;
            if (type != BasicFileAttributes.class) {
                throw unsupported();
            }
            String normalizedPath = normalizedPath(path);
            if (!existingPaths.contains(normalizedPath)) {
                throw new NoSuchFileException(normalizedPath);
            }
            return type.cast(symbolicLinks.containsKey(normalizedPath)
                    ? TestBasicFileAttributes.SYMBOLIC_LINK
                    : normalizedPath.equals("/") || existingPaths.stream().anyMatch(p -> p.startsWith(normalizedPath + "/"))
                    ? TestBasicFileAttributes.DIRECTORY : TestBasicFileAttributes.INSTANCE);
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
            linkReads++;
            if (beforeLinkRead != null) {
                beforeLinkRead.run();
            }
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
        INSTANCE,
        /// Immutable attributes identifying a directory.
        DIRECTORY,
        /// Immutable attributes identifying a symbolic link.
        SYMBOLIC_LINK;

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

        /// Returns whether the synthetic path is a regular file.
        @Override
        public boolean isRegularFile() {
            return this == INSTANCE;
        }

        /// Returns whether the synthetic path is a directory.
        @Override
        public boolean isDirectory() {
            return this == DIRECTORY;
        }

        /// Returns whether these attributes identify a symbolic link.
        @Override
        public boolean isSymbolicLink() {
            return this == SYMBOLIC_LINK;
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
            getFileSystem().pathCreations++;
            return new TestPath(getFileSystem(), absolute, names);
        }

        @Override
        protected BasicFileAttributes readEntryAttributes() throws IOException {
            return getFileSystem().provider.readAttributes(this, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }

        @Override
        protected Path readLinkTarget() throws IOException {
            return getFileSystem().provider.readSymbolicLink(this);
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
