// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArkivoVolumeSource;
import org.glavo.arkivo.archive.rar.RarArkivoEntryAttributes;
import org.glavo.arkivo.archive.rar.RarArkivoFileSystem;
import org.glavo.arkivo.archive.rar.RarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies RAR4 split entries against the offsets and text in libarchive's test_read_format_rar.c.
@NotNullByDefault
final class RarMultiVolumeCorpusTest {
    /// The size of each HTML body in the three upstream volume sets.
    private static final int CONTENT_SIZE = 20111;

    /// Original text at selected offsets, including reads spanning physical volume boundaries.
    private static final @Unmodifiable List<Snippet> SNIPPETS = List.of(
            new Snippet(20047, "d. \n</P>\n<P STYLE=\"margin-bottom: 0in\"><BR>\n</P>\n</BODY>\n</HTML>"),
            new Snippet(0, "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0 Transitional//EN\">\n<"),
            new Snippet(10054, "mplify writing such tests,\ntry to use platform-independent codin"),
            new Snippet(6860, "lString</TT> in the example above)\ngenerate detailed log message"),
            new Snippet(13752, "SS=\"western\">make check</TT> will usually run\n\tall of the tests."),
            new Snippet(7027, "\nfailures. \n</P>\n<H1 CLASS=\"western\"><A NAME=\"Life_cycle_of_a_te"),
            new Snippet(14086, "LE=\"margin-bottom: 0in\">DO use runtime tests for platform\n\tfeatu"),
            new Snippet(969, "rough test suite is essential\nboth for verifying new ports and f"),
            new Snippet(8029, "m: 0in\">Creates a temporary directory\n\twhose name matches the na"),
            new Snippet(15089, "lt\ninput file and verify the results. These use <TT CLASS=\"weste"),
            new Snippet(13164, "ertEqualInt,\n\tassertEqualString, assertEqualMem to test equalit"));

    /// Symbolic links in the ten-volume fixture, mapped to their stored relative targets.
    private static final @Unmodifiable Map<String, String> LINKS = Map.of(
            "testdir/testsymlink5", "testsubdir/LibarchiveAddingTest.html",
            "testdir/testsymlink6", "testsubdir/LibarchiveAddingTest2.html",
            "testsymlink", "testdir/LibarchiveAddingTest.html",
            "testsymlink2", "testdir/LibarchiveAddingTest2.html",
            "testsymlink3", "testdir/testsubdir/LibarchiveAddingTest.html",
            "testsymlink4", "testdir/testsubdir/LibarchiveAddingTest2.html");

    /// Explicit directory entries in the ten-volume fixture.
    private static final @Unmodifiable Set<String> DIRECTORIES = Set.of(
            "testdir/testemptysubdir", "testdir/testsubdir", "testdir", "testemptydir");

    /// An independently specified range of the original HTML file.
    ///
    /// @param offset the zero-based byte offset
    /// @param text the exact ASCII content at that offset
    @NotNullByDefault
    private record Snippet(int offset, String text) {
    }

    /// One upstream volume set and its regular files in physical entry order.
    ///
    /// @param name the fixture stem after `test_rar_multivolume_`
    /// @param volumeCount the number of physical volumes
    /// @param files the regular entry paths in archive order
    @NotNullByDefault
    private record Fixture(String name, int volumeCount, @Unmodifiable List<String> files) {
    }

    /// Returns the three independently produced stored-entry volume sets.
    private static Stream<Fixture> fixtures() {
        return Stream.of(
                new Fixture("single_file", 3, List.of("LibarchiveAddingTest.html")),
                new Fixture("multiple_files", 6, List.of("LibarchiveAddingTest2.html", "LibarchiveAddingTest.html")),
                new Fixture("uncompressed_files", 10, List.of("testdir/LibarchiveAddingTest2.html",
                        "testdir/testsubdir/LibarchiveAddingTest2.html", "LibarchiveAddingTest2.html",
                        "testdir/LibarchiveAddingTest.html", "testdir/testsubdir/LibarchiveAddingTest.html",
                        "LibarchiveAddingTest.html")));
    }

    /// Combines volume discovery and buffer representations for each fixture.
    private static Stream<Arguments> readCases() {
        return fixtures().flatMap(fixture -> Stream.of(false, true).flatMap(discover ->
                Stream.of(false, true).map(direct -> Arguments.of(fixture, discover, direct))));
    }

    /// Omits the first, middle, or final volume from each explicit volume list.
    private static Stream<Arguments> missingVolumeCases() {
        return fixtures().flatMap(fixture -> Stream.of(0, fixture.volumeCount() / 2, fixture.volumeCount() - 1)
                .map(index -> Arguments.of(fixture, index)));
    }

    /// Combines unopened and partially read entry bodies with each volume set.
    private static Stream<Arguments> skipCases() {
        return fixtures().flatMap(fixture -> Stream.of(false, true)
                .map(partial -> Arguments.of(fixture, partial)));
    }

    /// Reads every entry and seeks across volume boundaries without sharing entry-channel positions.
    @ParameterizedTest(name = "{0}, discover={1}, direct={2}")
    @MethodSource("readCases")
    void readsSplitEntries(Fixture fixture, boolean discover, boolean direct, @TempDir Path directory)
            throws IOException {
        List<Path> volumes = prepareVolumes(fixture, directory);
        try (var reader = discover ? RarArkivoStreamingReader.open(volumes.get(0))
                : RarArkivoStreamingReader.open(ArkivoVolumeSource.of(volumes))) {
            Set<String> seen = new HashSet<>();
            List<String> regularFiles = new ArrayList<>();
            while (reader.next()) {
                var attributes = reader.readAttributes(RarArkivoEntryAttributes.class);
                // Streaming metadata retains stored separators; libarchive's expected names use slashes.
                String name = attributes.path().replace('\\', '/');
                assertTrue(seen.add(name), name);
                assertFalse(attributes.isEncrypted(), name);
                if (attributes.isRegularFile()) {
                    regularFiles.add(name);
                    assertEquals(CONTENT_SIZE, attributes.size(), name);
                    try (var input = reader.openInputStream()) {
                        assertBody(input.readAllBytes(), name);
                        assertEquals(-1, input.read());
                    }
                } else if (attributes.isSymbolicLink()) {
                    assertTrue(fixture.volumeCount() == 10 && LINKS.containsKey(name), name);
                    assertEquals(LINKS.get(name), attributes.linkName(), name);
                } else {
                    assertTrue(attributes.isDirectory(), name);
                    assertTrue(fixture.volumeCount() == 10 && DIRECTORIES.contains(name), name);
                }
            }
            assertEquals(fixture.files(), regularFiles);
            Set<String> expectedNames = new HashSet<>(fixture.files());
            if (fixture.volumeCount() == 10) {
                expectedNames.addAll(LINKS.keySet());
                expectedNames.addAll(DIRECTORIES);
            }
            assertEquals(expectedNames, seen);
        }
        try (var fileSystem = discover ? RarArkivoFileSystem.open(volumes.get(0))
                : RarArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))) {
            for (int index = fixture.files().size() - 1; index >= 0; index--) {
                String name = fixture.files().get(index);
                Path path = fileSystem.getPath(name);
                assertEquals(CONTENT_SIZE, Files.size(path));
                try (var first = Files.newByteChannel(path); var second = Files.newByteChannel(path)) {
                    for (var snippet : SNIPPETS) {
                        long secondPosition = second.position();
                        assertSnippet(first, snippet, direct);
                        assertEquals(secondPosition, second.position());
                        long firstPosition = first.position();
                        assertSnippet(second, SNIPPETS.get(1), !direct);
                        assertEquals(firstPosition, first.position());
                    }
                    long previousPosition = first.position();
                    assertThrows(IllegalArgumentException.class, () -> first.position(-1));
                    assertEquals(previousPosition, first.position());
                    for (long position : new long[]{CONTENT_SIZE, CONTENT_SIZE + 40L}) {
                        first.position(position);
                        assertEquals(-1, first.read(ByteBuffer.allocate(1)));
                        assertEquals(position, first.position());
                    }
                    assertSnippet(first, SNIPPETS.get(0), direct);
                }
            }
            if (fixture.volumeCount() == 10) {
                for (var link : LINKS.entrySet()) {
                    Path path = fileSystem.getPath(link.getKey());
                    assertTrue(Files.isSymbolicLink(path));
                    assertEquals(link.getValue(), Files.readSymbolicLink(path).toString());
                    assertEquals(CONTENT_SIZE, Files.readAllBytes(path).length);
                    try (var channel = Files.newByteChannel(path)) {
                        for (var snippet : SNIPPETS) {
                            assertSnippet(channel, snippet, direct);
                        }
                    }
                }
                for (String name : DIRECTORIES) {
                    assertTrue(Files.isDirectory(fileSystem.getPath(name)), name);
                }
            }
        }
    }

    /// Advances across split bodies without requiring the caller to consume them first.
    @ParameterizedTest(name = "{0}, partial={1}")
    @MethodSource("skipCases")
    void skipsSplitEntries(Fixture fixture, boolean partial, @TempDir Path directory) throws IOException {
        List<Path> volumes = prepareVolumes(fixture, directory);
        try (var reader = RarArkivoStreamingReader.open(ArkivoVolumeSource.of(volumes))) {
            int regularCount = 0;
            int totalCount = 0;
            while (reader.next()) {
                totalCount++;
                var attributes = reader.readAttributes();
                if (attributes.isRegularFile()) {
                    assertEquals(fixture.files().get(regularCount), attributes.path().replace('\\', '/'));
                    regularCount++;
                    if (regularCount > 1 && regularCount == fixture.files().size()) {
                        try (var input = reader.openInputStream()) {
                            assertBody(input.readAllBytes(), attributes.path());
                        }
                    } else if (partial) {
                        try (var input = reader.openInputStream()) {
                            assertArrayEquals("<!DOC".getBytes(StandardCharsets.US_ASCII), input.readNBytes(5));
                        }
                    }
                }
            }
            assertEquals(fixture.files().size(), regularCount);
            assertEquals(fixture.volumeCount() == 10 ? 16 : regularCount, totalCount);
        }
    }

    /// Rejects incomplete volume sets through indexed and sequential reading rather than returning truncated content.
    @ParameterizedTest(name = "{0}, omittedVolume={1}")
    @MethodSource("missingVolumeCases")
    void rejectsMissingVolumes(Fixture fixture, int omitted, @TempDir Path directory) throws IOException {
        List<Path> volumes = new ArrayList<>(prepareVolumes(fixture, directory));
        volumes.remove(omitted);
        assertThrows(IOException.class, () -> {
            try (var reader = RarArkivoStreamingReader.open(ArkivoVolumeSource.of(volumes))) {
                while (reader.next()) {
                    if (reader.readAttributes().isRegularFile()) {
                        try (var input = reader.openInputStream()) {
                            input.readAllBytes();
                        }
                    }
                }
            }
        });
        assertThrows(IOException.class, () -> {
            try (var fileSystem = RarArkivoFileSystem.open(ArkivoVolumeSource.of(volumes))) {
                for (String name : fixture.files()) {
                    Files.readAllBytes(fileSystem.getPath(name));
                }
            }
        });
    }

    /// Checks the complete length and all independently specified ranges of one decoded body.
    private static void assertBody(byte[] body, String name) {
        assertEquals(CONTENT_SIZE, body.length, name);
        for (var snippet : SNIPPETS) {
            byte[] expected = snippet.text().getBytes(StandardCharsets.US_ASCII);
            assertArrayEquals(expected, Arrays.copyOfRange(body, snippet.offset(),
                    snippet.offset() + expected.length), name + " at " + snippet.offset());
        }
    }

    /// Reads a bounded buffer region and checks that neither surrounding bytes nor the limit changes.
    private static void assertSnippet(SeekableByteChannel channel, Snippet snippet, boolean direct) throws IOException {
        byte[] expected = snippet.text().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(expected.length + 6)
                : ByteBuffer.allocate(expected.length + 6);
        for (int index = 0; index < buffer.capacity(); index++) {
            buffer.put(index, (byte) 0x5a);
        }
        buffer.position(3).limit(expected.length + 3);
        channel.position(snippet.offset());
        while (buffer.hasRemaining()) {
            assertTrue(channel.read(buffer) > 0);
        }
        assertEquals(expected.length + 3, buffer.limit());
        assertEquals(snippet.offset() + expected.length, channel.position());
        byte[] actual = new byte[expected.length];
        buffer.position(3).get(actual);
        assertArrayEquals(expected, actual, "offset " + snippet.offset());
        buffer.clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, buffer.get(index));
            assertEquals((byte) 0x5a, buffer.get(buffer.capacity() - 1 - index));
        }
    }

    /// Decodes pinned text fixtures into temporary volumes, retaining their discovery-compatible names.
    private static @Unmodifiable List<Path> prepareVolumes(Fixture fixture, Path directory) throws IOException {
        Path corpus = Path.of(Objects.requireNonNull(System.getProperty("arkivo.libarchive.testDataDirectory")))
                .resolve("fixtures");
        List<Path> paths = new ArrayList<>();
        for (int number = 1; number <= fixture.volumeCount(); number++) {
            String suffix = fixture.volumeCount() == 10 && number < 10 ? "0" + number : Integer.toString(number);
            String name = "test_rar_multivolume_" + fixture.name() + ".part" + suffix + ".rar";
            Path path = directory.resolve(name);
            Files.write(path, LibarchiveUuDecoder.decode(corpus.resolve(name + ".uu")));
            paths.add(path);
        }
        return List.copyOf(paths);
    }
}
