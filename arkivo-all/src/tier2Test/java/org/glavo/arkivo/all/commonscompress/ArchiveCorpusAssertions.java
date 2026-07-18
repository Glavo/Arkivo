// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all.commonscompress;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArkivoFileSystem;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Exercises archive entries through Arkivo's public file-system and streaming contracts.
@NotNullByDefault
final class ArchiveCorpusAssertions {
    /// The buffer size used while hashing entry bodies.
    private static final int BUFFER_SIZE = 16 * 1024;

    /// Prevents utility-class construction.
    private ArchiveCorpusAssertions() {
    }

    /// Reads every visible file-system entry and returns a deterministic content digest.
    static @Unmodifiable List<EntryDigest> readFileSystem(ArkivoFileSystem fileSystem) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        Path root = fileSystem.getPath("/");
        try (var paths = Files.walk(root)) {
            var iterator = paths.filter(candidate -> !candidate.equals(root)).iterator();
            while (iterator.hasNext()) {
                Path path = iterator.next();
                BasicFileAttributes attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                BodyDigest body = attributes.isRegularFile()
                        ? digest(Files.newInputStream(path))
                        : new BodyDigest(attributes.size(), 0L);
                entries.add(new EntryDigest(
                        normalizePath(root.relativize(path).toString().replace('\\', '/')),
                        attributes.isDirectory(),
                        attributes.isSymbolicLink(),
                        body.size(),
                        body.crc32()
                ));
            }
        }
        entries.sort(Comparator.comparing(EntryDigest::path));
        return List.copyOf(entries);
    }

    /// Reads every streaming entry body and returns digests in physical archive order.
    static @Unmodifiable List<EntryDigest> readStreaming(ArkivoStreamingReader reader) throws IOException {
        List<EntryDigest> entries = new ArrayList<>();
        while (reader.next()) {
            ArchiveEntryAttributes attributes = reader.readAttributes();
            BodyDigest body = attributes.isRegularFile()
                    ? digest(reader.openInputStream())
                    : new BodyDigest(attributes.size(), 0L);
            entries.add(new EntryDigest(
                    normalizePath(attributes.path()),
                    attributes.isDirectory(),
                    attributes.isSymbolicLink(),
                    body.size(),
                    body.crc32()
            ));
        }
        return List.copyOf(entries);
    }

    /// Verifies that two access paths expose identical physical entries while allowing synthesized NIO directories.
    static void assertEquivalentEntries(
            @Unmodifiable List<EntryDigest> expected,
            @Unmodifiable List<EntryDigest> actual
    ) {
        @Unmodifiable List<EntryDigest> expectedContents = expected.stream()
                .filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(EntryDigest::path))
                .toList();
        @Unmodifiable List<EntryDigest> actualContents = actual.stream()
                .filter(entry -> !entry.directory())
                .sorted(Comparator.comparing(EntryDigest::path))
                .toList();
        assertFalse(expected.isEmpty(), "archive must expose at least one entry");
        assertEquals(expectedContents, actualContents);

        @Unmodifiable List<EntryDigest> expectedDirectories = expected.stream()
                .filter(EntryDigest::directory)
                .toList();
        for (EntryDigest actualEntry : actual) {
            if (actualEntry.directory()) {
                assertTrue(
                        expectedDirectories.contains(actualEntry),
                        () -> "streaming directory is missing from the file-system view: " + actualEntry.path()
                );
            }
        }
    }

    /// Computes an unsigned CRC-32 while fully consuming and closing a stream.
    static long crc32(InputStream input) throws IOException {
        return digest(input).crc32();
    }

    /// Computes the logical byte count and unsigned CRC-32 while fully consuming and closing a stream.
    private static BodyDigest digest(InputStream input) throws IOException {
        try (input) {
            CRC32 crc32 = new CRC32();
            byte[] buffer = new byte[BUFFER_SIZE];
            long size = 0L;
            while (true) {
                int read = input.read(buffer);
                if (read < 0) {
                    return new BodyDigest(size, crc32.getValue());
                }
                if (read == 0) {
                    throw new IOException("Archive entry stream made no progress");
                }
                crc32.update(buffer, 0, read);
                size = Math.addExact(size, read);
            }
        }
    }

    /// Removes a provider-specific leading slash from an archive-local path.
    private static String normalizePath(String path) {
        int start = path.startsWith("/") ? 1 : 0;
        int end = path.length();
        while (end > start && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(start, end);
    }

    /// Describes observable entry metadata and content without retaining a body buffer.
    ///
    /// @param path         normalized archive-local path
    /// @param directory    whether the entry is a directory
    /// @param symbolicLink whether the entry is a symbolic link
    /// @param size         logical entry size
    /// @param crc32        unsigned CRC-32 for a regular file, or zero for another entry type
    @NotNullByDefault
    record EntryDigest(String path, boolean directory, boolean symbolicLink, long size, long crc32) {
    }

    /// Describes the body observations collected while consuming one entry stream.
    ///
    /// @param size  number of body bytes consumed
    /// @param crc32 unsigned CRC-32 of the consumed bytes
    @NotNullByDefault
    private record BodyDigest(long size, long crc32) {
    }
}
