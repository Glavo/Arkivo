// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays node-tar's parser fixtures through indexed and fragmented sequential TAR readers.
@NotNullByDefault
final class NodeTarCorpusTest {
    /// The physical entry counts come from node-tar's `test/fixtures/parse/*.json` expectations.
    @ParameterizedTest
    @CsvSource({"file,1", "dir,1", "utf8,3", "body-byte-counts,4", "emptypax,2", "global-header,1",
            "long-paths,32", "long-pax,1", "next-file-has-long,2"})
    void readsParserFixtures(String name, int count) throws IOException {
        Path archive = fixture(name);
        var expected = ArchiveCorpusAssertions.readTarReference(archive);
        assertEquals(count, expected.size());
        try (var fs = TarArkivoFileSystem.open(archive)) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fs), expected);
        }
        byte[] bytes = Files.readAllBytes(archive);
        for (int chunk : new int[]{1, 511, 8192}) {
            try (var reader = TarArkivoStreamingReader.open(new FragmentedInputStream(bytes, chunk))) {
                assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader), "chunk=" + chunk);
            }
        }
    }

    /// Verifies the Unicode names and body lengths recorded by the upstream parser, independently of Commons.
    @Test
    void decodesPaxUnicodeNames() throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture("utf8")))) {
            List<String> names = List.of("\u03a9.txt", "\ud83c\udf1f.txt",
                    "long-path/r/e/a/l/l/y/-/d/e/e/p/-/f/o/l/d/e/r/-/p/a/t/h/\u03a9.txt");
            List<Integer> sizes = List.of(2, 106, 2);
            for (int i = 0; i < names.size(); i++) {
                assertTrue(reader.next());
                var attributes = reader.readAttributes();
                assertEquals(names.get(i), attributes.path());
                assertEquals(sizes.get(i).longValue(), attributes.size());
                try (var input = reader.openInputStream()) {
                    assertEquals(sizes.get(i).intValue(), input.readAllBytes().length);
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Resolves the upstream hard-link chain without treating its zero-sized header as an empty file.
    @Test
    void resolvesHardAndSymbolicLinks() throws IOException {
        try (var fs = TarArkivoFileSystem.open(fixture("links"))) {
            byte[] expected = Files.readAllBytes(fs.getPath("hardlink-1"));
            assertEquals(26, expected.length);
            assertArrayEquals(expected, Files.readAllBytes(fs.getPath("hardlink-2")));
            assertArrayEquals(expected, Files.readAllBytes(fs.getPath("symlink")));
            assertEquals(fs.getPath("hardlink-2"), Files.readSymbolicLink(fs.getPath("symlink")));
        }
    }

    /// Arkivo rejects headers that node-tar reports as invalid instead of silently skipping them.
    @ParameterizedTest
    @ValueSource(strings = {"bad-cksum", "path-missing"})
    void rejectsInvalidHeaders(String name) throws IOException {
        byte[] bytes = Files.readAllBytes(fixture(name));
        assertThrows(IOException.class, () -> {
            try (var fs = TarArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fs);
            }
        });
        for (int chunk : new int[]{1, 8192}) {
            assertThrows(IOException.class, () -> {
                try (var reader = TarArkivoStreamingReader.open(new FragmentedInputStream(bytes, chunk))) {
                    ArchiveCorpusAssertions.readStreaming(reader);
                }
            });
        }
    }

    /// Resolves a parser fixture from the verified upstream source archive.
    private static Path fixture(String name) {
        Path path = Path.of(Objects.requireNonNull(System.getProperty("arkivo.node-tar.testDataDirectory")))
                .resolve("test/fixtures/tars").resolve(name + ".tar");
        assertTrue(Files.isRegularFile(path), path.toString());
        return path;
    }

    /// Limits every bulk read without changing bytes or EOF behavior.
    @NotNullByDefault
    private static final class FragmentedInputStream extends ByteArrayInputStream {
        /// The maximum bytes returned by a bulk read.
        private final int chunk;

        /// Creates an independent cursor over one fixture.
        private FragmentedInputStream(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            return super.read(buffer, offset, Math.min(chunk, length));
        }
    }
}
