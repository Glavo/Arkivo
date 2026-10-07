// Copyright (c) 2026 Glavo
// Portions adapted from Go's archive/tar tests; see LICENSES/Go-BSD-3-Clause.txt.
// SPDX-License-Identifier: MPL-2.0 AND BSD-3-Clause

package org.glavo.arkivo.all;

import org.glavo.arkivo.all.commonscompress.ArchiveCorpusAssertions;
import org.glavo.arkivo.archive.tar.TarArkivoEntryAttributes;
import org.glavo.arkivo.archive.tar.TarArkivoFileSystem;
import org.glavo.arkivo.archive.tar.TarArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies Go TAR fixtures against independent decoding and upstream metadata expectations.
@NotNullByDefault
final class GoTarCorpusTest {
    /// Compares complete plaintext and entry kinds for GNU, V7, USTAR, STAR, and PAX records.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gnu.tar", "star.tar", "v7.tar", "ustar.tar", "pax.tar",
            "pax-pos-size-file.tar", "pax-records.tar", "gnu-long-nul.tar", "ustar-file-devs.tar",
            "ustar-file-reg.tar", "trailing-slash.tar", "gnu-nil-sparse-data.tar",
            "gnu-nil-sparse-hole.tar", "pax-nil-sparse-data.tar", "pax-nil-sparse-hole.tar"})
    void readsCompleteBodies(String name) throws IOException {
        var expected = ArchiveCorpusAssertions.readTarReference(fixture(name));
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture(name)))) {
            assertEquals(expected, ArchiveCorpusAssertions.readStreaming(reader));
        }
        try (var fileSystem = TarArkivoFileSystem.open(fixture(name))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(ArchiveCorpusAssertions.readFileSystem(fileSystem), expected);
        }
    }

    /// Returns the modification times and permission modes asserted by Go for the two short text files.
    private static Stream<Arguments> dialectMetadata() {
        return Stream.of(Arguments.of("gnu.tar", 0640, 1244428340L, 1244436044L),
                Arguments.of("star.tar", 0640, 1244592783L, 1244592783L),
                Arguments.of("v7.tar", 0444, 1244593104L, 1244593104L));
    }

    /// Checks complete bodies against uncompressed upstream files and verifies numeric ownership and times.
    @ParameterizedTest(name = "{0}")
    @MethodSource("dialectMetadata")
    void preservesDialectMetadata(String name, int mode, long firstTime, long secondTime) throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture(name)));
             var fileSystem = TarArkivoFileSystem.open(fixture(name))) {
            for (int index = 0; index < 2; index++) {
                String path = index == 0 ? "small.txt" : "small2.txt";
                long timestamp = index == 0 ? firstTime : secondTime;
                assertTrue(reader.next());
                var attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                assertEquals(path, attributes.path());
                assertEquals(mode, attributes.mode());
                assertEquals(73025, attributes.userId());
                assertEquals(5000, attributes.groupId());
                assertEquals(Instant.ofEpochSecond(timestamp), attributes.lastModifiedTime().toInstant());
                byte[] expected = Files.readAllBytes(fixture(path));
                try (var input = reader.openInputStream()) {
                    assertArrayEquals(expected, input.readAllBytes());
                    assertEquals(-1, input.read());
                }
                var indexed = Files.readAttributes(fileSystem.getPath("/" + path), TarArkivoEntryAttributes.class);
                assertEquals(attributes.mode(), indexed.mode());
                assertEquals(attributes.userId(), indexed.userId());
                assertEquals(attributes.groupId(), indexed.groupId());
                assertEquals(attributes.lastModifiedTime(), indexed.lastModifiedTime());
                assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/" + path)));
            }
            assertFalse(reader.next());
        }
    }

    /// Rejects the malformed PAX and numeric headers identified by Go's reader regressions.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"pax-bad-hdr-file.tar", "pax-bad-mtime-file.tar", "pax-nul-xattrs.tar",
            "pax-nul-path.tar", "neg-size.tar", "issue10968.tar", "issue11169.tar", "issue12435.tar"})
    void rejectsMalformedHeaders(String name) {
        assertThrows(IOException.class, () -> {
            try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture(name)))) {
                ArchiveCorpusAssertions.readStreaming(reader);
            }
        });
        assertThrows(IOException.class, () -> {
            try (var fileSystem = TarArkivoFileSystem.open(fixture(name))) {
                ArchiveCorpusAssertions.readFileSystem(fileSystem);
            }
        });
    }

    /// Returns the effective names and targets from Go's consecutive extension-header tests.
    private static Stream<Arguments> extensionHeaders() {
        return Stream.of(Arguments.of("gnu-multi-hdrs.tar", "GNU2/GNU2/long-path-name", "GNU4/GNU4/long-linkpath-name"),
                Arguments.of("pax-multi-hdrs.tar", "bar", "PAX4/PAX4/long-linkpath-name"));
    }

    /// Applies the last effective extension fields before publishing link metadata.
    @ParameterizedTest(name = "{0}")
    @MethodSource("extensionHeaders")
    void appliesConsecutiveExtensionHeaders(String name, String path, String target) throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture(name)))) {
            assertTrue(reader.next());
            var attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals(path, attributes.path());
            assertTrue(attributes.isSymbolicLink());
            assertEquals(target, attributes.linkName());
            assertFalse(reader.next());
        }
        try (var fileSystem = TarArkivoFileSystem.open(fixture(name))) {
            assertEquals(target, Files.readSymbolicLink(fileSystem.getPath("/" + path)).toString());
        }
    }

    /// Reads hole-only and data-only sparse members across nonsequential direct-buffer accesses.
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"gnu-nil-sparse-data.tar", "gnu-nil-sparse-hole.tar",
            "pax-nil-sparse-data.tar", "pax-nil-sparse-hole.tar"})
    void seeksAcrossSparseExtents(String name) throws IOException {
        try (var fileSystem = TarArkivoFileSystem.open(fixture(name));
             var channel = Files.newByteChannel(fileSystem.getPath("/sparse.db"))) {
            assertEquals(1000, channel.size());
            ByteBuffer output = ByteBuffer.allocateDirect(19);
            for (int offset : new int[]{999, 0, 511, 17, 981}) {
                channel.position(offset);
                output.clear().limit(Math.min(output.capacity(), 1000 - offset));
                while (output.hasRemaining()) {
                    assertTrue(channel.read(output) > 0);
                }
                output.flip();
                int position = offset;
                while (output.hasRemaining()) {
                    int expected = name.contains("-hole") ? 0 : '0' + position % 10;
                    assertEquals(expected, output.get());
                    position++;
                }
            }
            channel.position(1000);
            assertEquals(-1, channel.read(output.clear()));
        }
    }

    /// Retains PAX nanoseconds and keeps inode status-change time distinct from creation time.
    @Test
    void preservesPaxNanoseconds() throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture("pax.tar")))) {
            assertTrue(reader.next());
            var attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            var expected = Instant.ofEpochSecond(1350244992, 23960108);
            assertEquals(expected, attributes.lastModifiedTime().toInstant());
            assertEquals(expected, Objects.requireNonNull(attributes.recordedLastAccessTime()).toInstant());
            assertEquals(expected, Objects.requireNonNull(attributes.recordedStatusChangeTime()).toInstant());
            assertNull(attributes.recordedCreationTime());
        }
    }

    /// Checks all four sparse dialects against Go's logical sizes, CRCs, and byte-level read expectations.
    @Test
    void readsAllSparseDialects() throws IOException {
        String[] names = {"sparse-gnu", "sparse-posix-0.0", "sparse-posix-0.1", "sparse-posix-1.0", "end"};
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture("sparse-formats.tar")));
             var fileSystem = TarArkivoFileSystem.open(fixture("sparse-formats.tar"))) {
            for (int index = 0; index < names.length; index++) {
                assertTrue(reader.next());
                assertEquals(names[index], reader.readAttributes().path());
                byte[] body;
                try (var input = reader.openInputStream()) {
                    body = input.readAllBytes();
                }
                assertEquals(index == 4 ? 4 : 200, body.length);
                CRC32 crc = new CRC32();
                crc.update(body);
                assertEquals(index == 4 ? 0x8eb179baL : 0x5375e1d2L, crc.getValue());
                assertArrayEquals(body, Files.readAllBytes(fileSystem.getPath("/" + names[index])));
                if (index < 4) {
                    assertArrayEquals(new byte[]{0, 'G', 0, 'o', 0, 'G', 0, 'o'}, java.util.Arrays.copyOf(body, 8));
                }
            }
            assertFalse(reader.next());
        }
    }

    /// Reads empty ownership fields without interpreting unused GNU bytes as USTAR device fields.
    @Test
    void readsEmptyOwnershipFields() throws IOException {
        try (var reader = TarArkivoStreamingReader.open(Files.newInputStream(fixture("nil-uid.tar")));
             var fileSystem = TarArkivoFileSystem.open(fixture("nil-uid.tar"))) {
            assertTrue(reader.next());
            var attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("P1050238.JPG.log", attributes.path());
            assertEquals(0, attributes.userId());
            assertEquals(0, attributes.groupId());
            assertEquals(14, attributes.size());
            assertEquals(Instant.ofEpochSecond(1365454838), attributes.lastModifiedTime().toInstant());
            byte[] expected = "44,44,POWERON\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            try (var input = reader.openInputStream()) {
                assertArrayEquals(expected, input.readAllBytes());
            }
            assertFalse(reader.next());
            assertArrayEquals(expected, Files.readAllBytes(fileSystem.getPath("/P1050238.JPG.log")));
        }
    }

    /// Resolves a resource from the verified source archive prepared by Gradle.
    private static Path fixture(String name) {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.go.testDataDirectory")))
                .resolve("src/archive/tar/testdata").resolve(name);
    }
}
