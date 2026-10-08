// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.internal.ReadOnlyByteArrayChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests archive-comment boundaries, preservation, and truncation using JDK-produced ZIP files.
@NotNullByDefault
final class ZipArchiveCommentTest {
    /// Entry bytes independently checked before and after archive updates.
    private static final byte @Unmodifiable [] CONTENT = "archive comment payload".getBytes(StandardCharsets.US_ASCII);

    /// Bytes belonging to the caller after one complete streaming archive.
    private static final byte @Unmodifiable [] TRAILER = {0x50, 0x4b, 3, 4, 17, 31, 47};

    /// Combines comment boundaries with both methods and empty/nonempty archives.
    private static Stream<Arguments> comments() {
        return IntStream.of(0, 1, 21, 22, 23, 1024, 32768, 65535).boxed().flatMap(length ->
                IntStream.of(ZipEntry.STORED, ZipEntry.DEFLATED).boxed().flatMap(method ->
                        IntStream.of(0, 2).mapToObj(entries -> Arguments.of(length, method, entries))));
    }

    /// Selects every truncation within the fixed end record, also cutting short and maximum-length comments.
    private static Stream<Arguments> truncations() {
        return IntStream.of(0, 23, 65535).boxed().flatMap(length ->
                IntStream.of(ZipEntry.STORED, ZipEntry.DEFLATED).boxed().flatMap(method ->
                        IntStream.rangeClosed(1, 22).mapToObj(cut -> Arguments.of(length, method, cut))));
    }

    /// Keeps signature-shaped comment bytes opaque while reading and rewriting the surrounding archive.
    @ParameterizedTest(name = "length={0}, method={1}, entries={2}")
    @MethodSource("comments")
    void readsAndPreservesComment(int length, int method, int entries, @TempDir Path directory)
            throws IOException {
        String comment = comment(length);
        byte[] archive = archive(comment, method, entries);
        Path path = directory.resolve("comment.zip");
        Files.write(path, archive);
        assertJdkContents(path, comment, entries, false);
        try (var fileSystem = ZipArkivoFileSystem.open(new ReadOnlyByteArrayChannel(archive))) {
            assertEntries(fileSystem, entries);
        }
        for (int chunk : new int[]{1, 7, 8192}) {
            byte[] framed = Arrays.copyOf(archive, archive.length + TRAILER.length);
            System.arraycopy(TRAILER, 0, framed, archive.length, TRAILER.length);
            var source = new ShortInput(framed, chunk);
            try (var reader = ZipArkivoStreamingReader.open(source)) {
                for (int index = 0; index < entries; index++) {
                    assertTrue(reader.next());
                    try (var input = reader.openInputStream()) {
                        assertArrayEquals(CONTENT, input.readAllBytes());
                    }
                }
                assertFalse(reader.next());
                if (method == ZipEntry.STORED || entries == 0) {
                    assertEquals(archive.length, source.consumed());
                    assertArrayEquals(TRAILER, source.readNBytes(TRAILER.length));
                } else {
                    // Descriptor-based decoding can read ahead into the reader's private pushback buffer.
                    assertTrue(source.consumed() >= archive.length);
                }
            }
        }
        try (var fileSystem = ZipArkivoFileSystem.update(path)) {
            assertEntries(fileSystem, entries);
            Files.write(fileSystem.getPath("/added.bin"), CONTENT);
        }
        assertJdkContents(path, comment, entries, true);
        try (var fileSystem = ZipArkivoFileSystem.open(path)) {
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/added.bin")));
            for (int index = 0; index < entries; index++) {
                assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/entry" + index)));
            }
        }
    }

    /// Rejects an incomplete archive ending after delivering an otherwise complete streaming entry.
    @ParameterizedTest(name = "length={0}, method={1}, missing={2}")
    @MethodSource("truncations")
    void rejectsTruncatedCommentOrEndRecord(int length, int method, int cut) throws IOException {
        byte[] complete = archive(comment(length), method, 1);
        byte[] truncated = Arrays.copyOf(complete, complete.length - cut);
        var channel = new ReadOnlyByteArrayChannel(truncated);
        try (var fileSystem = ZipArkivoFileSystem.open(channel)) {
            assertThrows(IOException.class, () -> {
                try (var entries = Files.list(fileSystem.getPath("/"))) {
                    entries.toList();
                }
            });
        }
        assertFalse(channel.isOpen());
        var source = new ShortInput(truncated, 7);
        try (var reader = ZipArkivoStreamingReader.open(source)) {
            assertTrue(reader.next());
            try (var input = reader.openInputStream()) {
                assertArrayEquals(CONTENT, input.readAllBytes());
            }
            assertThrows(IOException.class, reader::next);
            assertEquals(truncated.length, source.consumed());
        }
    }

    /// Builds an ASCII comment with misleading end signatures but no complete embedded end record.
    private static String comment(int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) 'c');
        if (length >= 4) {
            for (int offset : new int[]{0, (length - 4) / 2, length - 4}) {
                bytes[offset] = 0x50;
                bytes[offset + 1] = 0x4b;
                bytes[offset + 2] = 5;
                bytes[offset + 3] = 6;
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    /// Creates a ZIP with known entry bodies and a comment whose encoded size equals its character count.
    private static byte[] archive(String comment, int method, int entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            output.setComment(comment);
            CRC32 crc = new CRC32();
            crc.update(CONTENT);
            for (int index = 0; index < entries; index++) {
                var entry = new ZipEntry("entry" + index);
                entry.setMethod(method);
                entry.setTime(1700000000000L);
                if (method == ZipEntry.STORED) {
                    entry.setSize(CONTENT.length);
                    entry.setCrc(crc.getValue());
                }
                output.putNextEntry(entry);
                output.write(CONTENT);
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    /// Checks that indexing neither invents entries from comment signatures nor loses real entries.
    private static void assertEntries(ZipArkivoFileSystem fileSystem, int count) throws IOException {
        try (var entries = Files.list(fileSystem.getPath("/"))) {
            assertEquals(count, entries.count());
        }
        for (int index = 0; index < count; index++) {
            assertArrayEquals(CONTENT, Files.readAllBytes(fileSystem.getPath("/entry" + index)));
        }
    }

    /// Verifies the exact comment and each body using an independent ZIP parser.
    private static void assertJdkContents(Path path, String comment, int count, boolean added) throws IOException {
        try (var zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            assertEquals(comment.isEmpty() ? null : comment, zip.getComment());
            assertEquals(count + (added ? 1 : 0), zip.size());
            for (var entry : zip.stream().toList()) {
                try (var input = zip.getInputStream(entry)) {
                    assertArrayEquals(CONTENT, input.readAllBytes());
                }
            }
        }
    }

    /// Limits reads and skips independently of the producer's record boundaries.
    @NotNullByDefault
    private static final class ShortInput extends ByteArrayInputStream {
        /// Maximum progress per bulk read or skip.
        private final int chunk;

        /// Exposes the supplied bytes with bounded progress.
        private ShortInput(byte[] bytes, int chunk) {
            super(bytes);
            this.chunk = chunk;
        }

        /// Returns the number of bytes consumed, including skipped bytes.
        private int consumed() {
            return pos;
        }

        /// Reads at most one configured chunk.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            return super.read(bytes, offset, Math.min(length, chunk));
        }

        /// Skips at most one configured chunk.
        @Override
        public synchronized long skip(long count) {
            return super.skip(Math.min(count, chunk));
        }
    }
}
