// Copyright (c) 2026 Glavo
// Portions adapted from Go's archive/zip tests; see LICENSES/Go-BSD-3-Clause.txt.
// SPDX-License-Identifier: MPL-2.0 AND BSD-3-Clause

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.ArchiveMetadataDecoder;
import org.glavo.arkivo.archive.zip.ZipArchiveOptions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays Go ZIP compatibility fixtures with upstream plaintext and timestamp expectations.
@NotNullByDefault
final class GoZipCorpusTest {
    /// Returns plaintext expectations for ZIP records produced by several independent writers.
    private static Stream<Arguments> contents() throws IOException {
        return Stream.of(
                Arguments.of("test.zip", List.of("test.txt", "gophercolor16x16.png"),
                        List.of(bytes("This is a test text file.\n"), Files.readAllBytes(fixture("gophercolor16x16.png")))),
                Arguments.of("test-prefix.zip", List.of("test.txt", "gophercolor16x16.png"),
                        List.of(bytes("This is a test text file.\n"), Files.readAllBytes(fixture("gophercolor16x16.png")))),
                Arguments.of("dd.zip", List.of("filename"), List.of(bytes("This is a test textfile.\n"))),
                Arguments.of("go-with-datadesc-sig.zip", List.of("foo.txt", "bar.txt"),
                        List.of(bytes("foo\n"), bytes("bar\n"))),
                Arguments.of("go-no-datadesc-sig.zip.base64", List.of("foo.txt", "bar.txt"),
                        List.of(bytes("foo\n"), bytes("bar\n"))),
                Arguments.of("crc32-not-streamed.zip", List.of("foo.txt", "bar.txt"),
                        List.of(bytes("foo\n"), bytes("bar\n"))),
                Arguments.of("zip64.zip", List.of("README"), List.of(bytes("This small file is in ZIP64 format.\n"))),
                Arguments.of("zip64-2.zip", List.of("README"), List.of(bytes("This small file is in ZIP64 format.\n"))),
                Arguments.of("winxp.zip", List.of("hello", "dir/bar", "dir/empty/", "readonly"),
                        List.of(bytes("world \r\n"), bytes("foo \r\n"), bytes(""), bytes("important \r\n"))),
                Arguments.of("unix.zip", List.of("hello", "dir/bar", "dir/empty/", "readonly"),
                        List.of(bytes("world \r\n"), bytes("foo \r\n"), bytes(""), bytes("important \r\n")))
        );
    }

    /// Checks physical order, full bodies, repeated EOF, and reverse-order indexed reads.
    @ParameterizedTest(name = "{0}")
    @MethodSource("contents")
    void readsExpectedContents(String name, @Unmodifiable List<String> names,
                              @Unmodifiable List<byte @Unmodifiable []> bodies, @TempDir Path directory)
            throws IOException {
        Path archive = fixture(name);
        if (name.endsWith(".base64")) {
            archive = directory.resolve("decoded.zip");
            Files.write(archive, Base64.getMimeDecoder().decode(Files.readAllBytes(fixture(name))));
        }
        try (var reader = ZipArkivoStreamingReader.open(archive)) {
            for (int index = 0; index < names.size(); index++) {
                assertTrue(reader.next());
                assertEquals(names.get(index), reader.readAttributes().path());
                if (names.get(index).endsWith("/")) {
                    assertTrue(reader.readAttributes().isDirectory());
                } else {
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(bodies.get(index), input.readAllBytes());
                        assertEquals(-1, input.read());
                    }
                }
            }
            assertFalse(reader.next());
        }
        // This upstream prefix fixture also has bytes after its declared end record.
        if (name.equals("test-prefix.zip")) {
            try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
                assertThrows(IOException.class, () -> ArchiveCorpusAssertions.readFileSystem(fileSystem));
            }
            return;
        }
        try (var fileSystem = ZipArkivoFileSystem.open(archive)) {
            for (int index = names.size() - 1; index >= 0; index--) {
                Path path = fileSystem.getPath("/" + names.get(index));
                if (names.get(index).endsWith("/")) {
                    assertTrue(Files.isDirectory(path));
                } else {
                    assertArrayEquals(bodies.get(index), Files.readAllBytes(path));
                }
            }
        }
    }

    /// Returns absolute timestamps asserted by Go for NTFS, Unix, and DOS time records.
    private static Stream<Arguments> timestamps() {
        return Stream.of(
                Arguments.of("time-7zip.zip", "test.txt", "2017-11-01T04:11:57.244817900Z"),
                Arguments.of("time-infozip.zip", "test.txt", "2017-11-01T04:11:57Z"),
                Arguments.of("time-osx.zip", "test.txt", "2017-11-01T04:11:57Z"),
                Arguments.of("time-winrar.zip", "test.txt", "2017-11-01T04:11:57.244817900Z"),
                Arguments.of("time-winzip.zip", "test.txt", "2017-11-01T04:11:57.244Z"),
                Arguments.of("time-go.zip", "test.txt", "2017-11-01T04:11:57Z"),
                Arguments.of("time-22738.zip", "file", "2000-01-01T00:00:00Z"));
    }

    /// Preserves indexed timestamp precision while local-only readers use DOS time when no local extra field exists.
    @ParameterizedTest(name = "{0}")
    @MethodSource("timestamps")
    void preservesExtendedTimes(String name, String path, String timestamp) throws IOException {
        FileTime expected = FileTime.from(Instant.parse(timestamp));
        try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
            assertEquals(expected, Files.getLastModifiedTime(fileSystem.getPath("/" + path)));
            assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/" + path)));
        }
        try (var reader = ZipArkivoStreamingReader.open(fixture(name))) {
            assertTrue(reader.next());
            assertEquals(path, reader.readAttributes().path());
            FileTime localExpected = switch (name) {
                case "time-7zip.zip", "time-winzip.zip" -> FileTime.from(
                        LocalDateTime.of(2017, 10, 31, 21, 11, 58).atZone(ZoneId.systemDefault()).toInstant());
                case "time-winrar.zip" -> FileTime.from(
                        LocalDateTime.of(2017, 10, 31, 21, 11, 56).atZone(ZoneId.systemDefault()).toInstant());
                default -> expected;
            };
            assertEquals(localExpected, reader.readAttributes().lastModifiedTime());
            assertFalse(reader.next());
        }
    }

    /// Resolves real producer UTF-8 names with automatic decoding and an explicit charset override.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"utf8-7zip.zip", "utf8-infozip.zip", "utf8-osx.zip", "utf8-winrar.zip", "utf8-winzip.zip"})
    void readsUnicodeNames(String name) throws IOException {
        for (var options : List.of(ZipArchiveOptions.READ_DEFAULTS,
                ZipArchiveOptions.READ_DEFAULTS.withLegacyMetadataDecoder(
                        ArchiveMetadataDecoder.forCharset(StandardCharsets.UTF_8)))) {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name), options)) {
                assertArrayEquals(new byte[0], Files.readAllBytes(fileSystem.getPath("/\u4e16\u754c")));
            }
            try (var reader = ZipArkivoStreamingReader.open(fixture(name), options)) {
                assertTrue(reader.next());
                assertEquals("\u4e16\u754c", reader.readAttributes().path());
                assertFalse(reader.next());
            }
        }
    }

    /// Rejects incomplete or contradictory directory records rather than selecting an earlier hidden end record.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"readme.notzip", "comment-truncated.zip", "test-baddirsz.zip", "dupdir.zip"})
    void rejectsInvalidDirectory(String name) {
        assertThrows(IOException.class, () -> {
            try (var fileSystem = ZipArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Reads a stored Unix link target without following it outside the archive root.
    @Test
    void preservesSymbolicLink() throws IOException {
        try (var fileSystem = ZipArkivoFileSystem.open(fixture("symlink.zip"))) {
            Path path = fileSystem.getPath("/symlink");
            assertTrue(Files.isSymbolicLink(path));
            assertEquals("../target", Files.readSymbolicLink(path).toString());
            assertThrows(IOException.class, () -> Files.readAllBytes(path));
        }
    }

    /// Encodes the ASCII plaintext specified by the upstream tests.
    private static byte @Unmodifiable [] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    /// Resolves a resource from the verified source archive prepared by Gradle.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.go.testDataDirectory")))
                .resolve("src/archive/zip/testdata").resolve(name);
    }
}
