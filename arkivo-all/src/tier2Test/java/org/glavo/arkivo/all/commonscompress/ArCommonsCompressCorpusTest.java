// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ar.ArArkivoFileSystem;
import org.glavo.arkivo.archive.ar.ArArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies AR compatibility with the applicable Apache Commons Compress fixture corpus.
@NotNullByDefault
final class ArCommonsCompressCorpusTest {
    /// Opens every portable AR dialect through both seekable and forward-only APIs.
    @ParameterizedTest(name = "{0}")
    @MethodSource("readableArchives")
    void readsArchiveThroughFileSystemAndStreaming(String resource) throws IOException {
        Path archive = CommonsCompressTestResources.resource(resource);
        @Unmodifiable List<ArchiveCorpusAssertions.EntryDigest> seekable;
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(archive)) {
            seekable = ArchiveCorpusAssertions.readFileSystem(fileSystem);
        }

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archive))) {
            ArchiveCorpusAssertions.assertEquivalentEntries(
                    seekable,
                    ArchiveCorpusAssertions.readStreaming(reader)
            );
        }
    }

    /// Verifies BSD and GNU long-name tables expose the same names and bodies.
    @ParameterizedTest(name = "{0}")
    @MethodSource("longNameArchives")
    void readsLongNames(String resource) throws IOException {
        try (ArArkivoFileSystem fileSystem = ArArkivoFileSystem.open(
                CommonsCompressTestResources.resource(resource)
        )) {
            assertArrayEquals(
                    "Hello, world!\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/this_is_a_long_file_name.txt"))
            );
            assertArrayEquals(
                    "Bye\n".getBytes(StandardCharsets.US_ASCII),
                    Files.readAllBytes(fileSystem.getPath("/this_is_a_long_file_name_as_well.txt"))
            );
        }
    }

    /// Verifies COMPRESS-661 reaches stable EOF after reading an ordinary text member.
    @Test
    void readsCompress661TextArchive() throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org", "apache", "commons", "compress", "COMPRESS-661", "testARofText.ar"
        );
        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archive))) {
            assertTrue(reader.next());
            assertEquals(
                    "Test d'indexation de Txt\nhttp://www.apache.org\n",
                    new String(reader.openInputStream().readAllBytes(), StandardCharsets.UTF_8)
            );
            assertFalse(reader.next());
            assertFalse(reader.next());
        }
    }

    /// Rejects malformed numeric fields and invalid GNU or BSD name-table lengths.
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedArchives")
    void rejectsMalformedArchive(String fileName) throws IOException {
        Path archive = CommonsCompressTestResources.resource(
                "org", "apache", "commons", "compress", "ar", "number_parsing", fileName
        );
        assertThrows(IOException.class, () -> {
            try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(Files.newInputStream(archive))) {
                while (reader.next()) {
                    reader.readAttributes();
                    reader.openInputStream().readAllBytes();
                }
            }
        });
    }

    /// Returns the valid AR corpus that exercises independently produced dialects.
    private static Stream<String> readableArchives() {
        return Stream.of(
                "archives/FreeBSD.ar",
                "archives/SunOS.ar",
                "bla.ar",
                "longfile_bsd.ar",
                "longfile_gnu.ar",
                "longpath/minotaur.ar",
                "org/apache/commons/compress/COMPRESS-661/testARofText.ar"
        );
    }

    /// Returns the equivalent BSD and GNU long-name samples.
    private static Stream<String> longNameArchives() {
        return Stream.of("longfile_bsd.ar", "longfile_gnu.ar");
    }

    /// Returns all malformed AR fixtures from the Commons Compress numeric parsing suite.
    private static Stream<String> malformedArchives() {
        return Stream.of(
                "bad_group-fail.ar",
                "bad_length-fail.ar",
                "bad_long_namelen_bsd-fail.ar",
                "bad_long_namelen_gnu1-fail.ar",
                "bad_long_namelen_gnu2-fail.ar",
                "bad_long_namelen_gnu3-fail.ar",
                "bad_modified-fail.ar",
                "bad_table_length_gnu-fail.ar",
                "bad_user-fail.ar"
        );
    }
}
