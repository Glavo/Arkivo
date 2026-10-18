// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.zip.ZipArkivoFileSystem;
import org.glavo.arkivo.archive.zip.ZipArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays yauzl archives against the original plaintext files distributed with its tests.
@NotNullByDefault
final class YauzlCorpusTest {
    /// Checks independent expected names and bytes through indexed and sequential ZIP readers.
    @ParameterizedTest
    @ValueSource(strings = {"big-compression", "big-uncompressed", "cygwin-info-zip", "deflate", "directories",
            "empty", "everything", "linux-info-zip", "NtfsTimestamp", "streaming",
            "unicode", "unix-epoch", "windows-7-zip", "windows-compressed-folder", "zip64"})
    void readsOriginalPlaintext(String name) throws IOException {
        Map<String, byte[]> expected = plaintext(name);
        Path archive = fixture("success/" + name + ".zip");
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            try (var paths = Files.walk(fs.getPath("/"))) {
                assertEquals(expected.keySet(), paths.filter(Files::isRegularFile)
                        .map(path -> fs.getPath("/").relativize(path).toString()).collect(Collectors.toSet()));
            }
            for (var entry : expected.entrySet()) {
                assertArrayEquals(entry.getValue(), Files.readAllBytes(fs.getPath(entry.getKey())), entry.getKey());
            }
        }
        try (var reader = ZipArkivoStreamingReader.open(archive)) {
            var seen = new HashSet<String>();
            while (reader.next()) {
                var attributes = reader.readAttributes();
                if (attributes.isDirectory()) continue;
                String path = attributes.path();
                assertTrue(expected.containsKey(path), path);
                assertTrue(seen.add(path), path);
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(expected.get(path), input.readAllBytes(), path);
                }
            }
            assertEquals(expected.keySet(), seen);
        }
    }

    /// Decodes the later complete Unicode record but still rejects the original fixture's malformed local extra tail.
    @Test
    void distinguishesUnicodeNameFromMalformedLocalMetadata() throws IOException {
        Path archive = fixture("success/unicode-path-extra-field.zip");
        try (var fs = ZipArkivoFileSystem.open(archive)) {
            try (var entries = Files.newDirectoryStream(fs.getPath("/"))) {
                var iterator = entries.iterator();
                assertTrue(iterator.hasNext());
                assertEquals("\u4e03\u4e2a\u623f\u95f4.txt", iterator.next().getFileName().toString());
                assertFalse(iterator.hasNext());
            }
            // The local extra length includes the first two bytes of the following descriptor signature.
            assertThrows(IOException.class, () -> Files.readAllBytes(fs.getPath("\u4e03\u4e2a\u623f\u95f4.txt")));
        }
        assertThrows(IOException.class, () -> {
            try (var reader = ZipArkivoStreamingReader.open(archive)) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
    }

    /// Reads nonaligned ranges in both directions, including the exact end of a compressed or stored entry.
    @ParameterizedTest
    @ValueSource(strings = {"big-compression", "big-uncompressed"})
    void readsIndependentRanges(String name) throws IOException {
        byte[] expected = plaintext(name).get("0x21000");
        try (var fs = ZipArkivoFileSystem.open(fixture("success/" + name + ".zip"));
             var first = Files.newByteChannel(fs.getPath("0x21000"));
             var second = Files.newByteChannel(fs.getPath("0x21000"))) {
            for (int start : new int[]{0, 65537, expected.length - 7, 13, expected.length}) {
                first.position(start);
                ByteBuffer result = ByteBuffer.allocate(Math.min(113, expected.length - start));
                while (result.hasRemaining()) assertTrue(first.read(result) > 0);
                assertArrayEquals(Arrays.copyOfRange(expected, start, start + result.capacity()), result.array());
                assertEquals(0, second.position());
                if (first.position() == expected.length) assertEquals(-1, first.read(ByteBuffer.allocate(1)));
            }
        }
    }

    /// Requires structural and body corruption to fail even when local-record validation is deferred until reading.
    @ParameterizedTest
    @ValueSource(strings = {
            "compressed uncompressed size mismatch for stored file 2147483647 5",
            "End of central directory record signature not found Either not a zip file or file is truncated",
            "End of central directory record signature not found Either not a zip file or file is truncated_1",
            "file data overflows file bounds 63 2147483647 308",
            "invalid central directory file header signature 0x1014b50",
            "invalid code lengths set", "invalid local file header signature 0x3034b50",
            "invalid zip64 end of central directory record signature",
            "not enough bytes in the stream expected 2048576 got only 1000000",
            "too many bytes in the stream expected 82496 got at least 98304",
            "too many length or distance symbols", "unexpected EOF",
            "zip64 extended information extra field does not include compressed size",
            "zip64 extended information extra field does not include relative header offset",
            "zip64 extended information extra field does not include uncompressed size"})
    void rejectsMalformedArchives(String name) {
        assertThrows(IOException.class, () -> {
            try (var fs = ZipArkivoFileSystem.open(fixture("failure/" + name + ".zip"))) {
                ArchiveCorpusAssertions.readFileSystem(fs);
            }
        });
    }

    /// Expands the upstream ASCII fixture names and generated empty files as specified by `test/test.js`.
    private static Map<String, byte[]> plaintext(String name) throws IOException {
        Path directory = fixture("success/" + name);
        Map<String, byte[]> result = new TreeMap<>();
        try (var files = Files.walk(directory)) {
            var iterator = files.filter(Files::isRegularFile).iterator();
            while (iterator.hasNext()) {
                Path file = iterator.next();
                if (file.getFileName().toString().startsWith(".")) continue;
                String path = directory.relativize(file).toString().replace('\\', '/')
                        .replace("Turmion Katilot", "Turmion K\u00e4til\u00f6t")
                        .replace("Mista veri pakenee", "Mist\u00e4 veri pakenee")
                        .replace("qi ge fangjian", "\u4e03\u4e2a\u623f\u95f4");
                result.put(path, Files.readAllBytes(file));
            }
        }
        if (name.equals("everything")) {
            for (int i = 0; i < 1000; i++) {
                result.put("a/b/" + String.format(java.util.Locale.ROOT, "%03d", i), new byte[0]);
            }
        }
        assertFalse(result.isEmpty() && !name.equals("empty"));
        return result;
    }

    /// Resolves a file in the checksum-verified upstream source archive.
    private static Path fixture(String name) {
        Path path = Path.of(Objects.requireNonNull(System.getProperty("arkivo.yauzl.testDataDirectory")))
                .resolve("test").resolve(name);
        assertTrue(Files.exists(path), path.toString());
        return path;
    }
}
