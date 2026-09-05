// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies old GNU and GNU PAX sparse TAR expansion, validation, and file system integration.
@NotNullByDefault
final class TarArkivoSparseTest {
    /// The expanded content represented by the standard sparse test map.
    private static final byte[] EXPANDED_CONTENT = {
            0, 0, 'a', 'b', 'c', 0, 0, 0, 'd', 'e', 0, 0
    };

    /// The packed non-hole bytes represented by the standard sparse test map.
    private static final byte[] PACKED_CONTENT = {'a', 'b', 'c', 'd', 'e'};

    /// Compares mixed reads and skips with an expanded model for empty, all-hole, and multi-extent files.
    @ParameterizedTest
    @ValueSource(strings = {"old-gnu", "pax-0.0", "pax-0.1", "pax-1.0"})
    void matchesExpandedModelAcrossSparseRepresentations(String format) throws IOException {
        for (int logicalSize : new int[]{0, 1, 8193, 16387}) {
            List<OldGnuSparseBlock> blocks = logicalSize == 16387
                    ? List.of(
                    new OldGnuSparseBlock(0, 1),
                    new OldGnuSparseBlock(1, 510),
                    new OldGnuSparseBlock(512, 2),
                    new OldGnuSparseBlock(8191, 3),
                    new OldGnuSparseBlock(16383, 2)
            ) : List.of();
            byte[] expected = new byte[logicalSize];
            ByteArrayOutputStream packed = new ByteArrayOutputStream();
            for (OldGnuSparseBlock block : blocks) {
                for (long index = block.offset(); index < block.offset() + block.size(); index++) {
                    byte value = (byte) (index * 37 + 11);
                    expected[(int) index] = value;
                    packed.write(value);
                }
            }
            byte[] archive = sparseArchive(format, blocks, logicalSize, packed.toByteArray());
            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next());
                assertEquals(logicalSize, reader.readAttributes(BasicFileAttributes.class).size());
                try (InputStream body = reader.openInputStream()) {
                    Random random = new Random(0x5A125EL);
                    int position = 0;
                    while (position < logicalSize) {
                        assertEquals(0, body.read(new byte[3], 1, 0));
                        assertEquals(0, body.skip(-1));
                        int requested = Math.min(1 + random.nextInt(1025), logicalSize - position);
                        switch (random.nextInt(3)) {
                            case 0 -> {
                                long skipped = body.skip(requested);
                                assertTrue(skipped > 0 && skipped <= requested);
                                position += (int) skipped;
                            }
                            case 1 -> {
                                assertEquals(Byte.toUnsignedInt(expected[position]), body.read());
                                position++;
                            }
                            default -> {
                                byte[] target = new byte[requested + 4];
                                Arrays.fill(target, (byte) 0x6D);
                                int count = body.read(target, 2, requested);
                                assertTrue(count > 0 && count <= requested);
                                byte[] expectedTarget = new byte[target.length];
                                Arrays.fill(expectedTarget, (byte) 0x6D);
                                System.arraycopy(expected, position, expectedTarget, 2, count);
                                assertArrayEquals(expectedTarget, target);
                                position += count;
                            }
                        }
                    }
                    assertEquals(-1, body.read());
                    assertEquals(-1, body.read(new byte[5], 1, 3));
                    assertEquals(0, body.read(new byte[5], 1, 0));
                    assertEquals(0, body.skip(Long.MAX_VALUE));
                }
                assertFollowingRegularEntry(reader);
            }

            for (boolean direct : new boolean[]{false, true}) {
                try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                        new ByteArrayInputStream(archive)
                )) {
                    assertTrue(reader.next());
                    try (ReadableByteChannel body = reader.openChannel()) {
                        int position = 0;
                        int iteration = 0;
                        while (position < logicalSize) {
                            int capacity = new int[]{1, 511, 512, 513, 8191, 8192, 8193}[iteration++ % 7];
                            ByteBuffer storage = direct
                                    ? ByteBuffer.allocateDirect(capacity + 8)
                                    : ByteBuffer.allocate(capacity + 8);
                            for (int index = 0; index < storage.capacity(); index++) {
                                storage.put(index, (byte) 0x6D);
                            }
                            ByteBuffer target = storage.slice(2, capacity + 4);
                            target.position(2).limit(2 + capacity).mark();
                            int count = body.read(target);
                            assertTrue(count > 0 && count <= Math.min(capacity, logicalSize - position));
                            assertEquals(2 + count, target.position());
                            assertEquals(2 + capacity, target.limit());
                            target.reset();
                            assertEquals(2, target.position());
                            for (int index = 0; index < storage.capacity(); index++) {
                                byte value = index >= 4 && index < 4 + count
                                        ? expected[position + index - 4] : (byte) 0x6D;
                                assertEquals(value, storage.get(index));
                            }
                            position += count;
                        }
                        assertEquals(-1, body.read(ByteBuffer.allocate(1)));
                        assertEquals(0, body.read(ByteBuffer.allocate(0)));
                    }
                    assertFollowingRegularEntry(reader);
                }
            }
        }
    }

    /// Verifies advancing inside holes or packed extents drains only the remaining physical body and padding.
    @ParameterizedTest
    @ValueSource(strings = {"old-gnu", "pax-0.0", "pax-0.1", "pax-1.0"})
    void advancesAtEverySparseBoundary(String format) throws IOException {
        byte[] archive = sparseArchive(format, List.of(
                new OldGnuSparseBlock(2, 3), new OldGnuSparseBlock(8, 2)
        ), EXPANDED_CONTENT.length, PACKED_CONTENT);
        for (int position = 0; position <= EXPANDED_CONTENT.length; position++) {
            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                    new ByteArrayInputStream(archive)
            )) {
                assertTrue(reader.next());
                InputStream body = reader.openInputStream();
                assertArrayEquals(Arrays.copyOf(EXPANDED_CONTENT, position), body.readNBytes(position));
                assertFollowingRegularEntry(reader);
                assertThrows(IOException.class, body::read);
                body.close();
            }
        }
    }

    /// Verifies completed holes and physical reads remain visible when a later sparse source read fails.
    @ParameterizedTest
    @ValueSource(strings = {"old-gnu", "pax-0.0", "pax-0.1", "pax-1.0"})
    void preservesSparseProgressBeforeSourceFailure(String format) throws IOException {
        byte[] archive = sparseArchive(format, List.of(
                new OldGnuSparseBlock(2, 3), new OldGnuSparseBlock(8, 2)
        ), EXPANDED_CONTENT.length, PACKED_CONTENT);
        int packedOffset = findSequence(archive, PACKED_CONTENT);
        for (int accepted : new int[]{0, 2, 3, 4}) {
            for (Throwable failure : new Throwable[]{
                    new IOException("sparse source failed"),
                    new IllegalStateException("sparse source failed"),
                    new AssertionError("sparse source failed")
            }) {
                for (boolean direct : new boolean[]{false, true}) {
                    FailingInputStream source = new FailingInputStream(archive);
                    try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                            source, TarArchiveOptions.READ_DEFAULTS.withoutCompression()
                    )) {
                        assertTrue(reader.next());
                        source.failAt(packedOffset + accepted, failure);
                        try (ReadableByteChannel body = reader.openChannel()) {
                            ByteArrayOutputStream actual = new ByteArrayOutputStream();
                            ByteBuffer target = direct ? ByteBuffer.allocateDirect(16) : ByteBuffer.allocate(16);
                            Throwable observed = assertThrows(failure.getClass(), () -> {
                                for (int attempt = 0; attempt < EXPANDED_CONTENT.length + 1; attempt++) {
                                    target.clear();
                                    int count = body.read(target);
                                    assertTrue(count > 0);
                                    assertEquals(count, target.position());
                                    byte[] fragment = new byte[count];
                                    target.flip().get(fragment);
                                    actual.writeBytes(fragment);
                                }
                            });
                            assertSame(failure, observed);
                            int logicalProgress = accepted < 3 ? 2 + accepted : 5 + accepted;
                            assertArrayEquals(Arrays.copyOf(EXPANDED_CONTENT, logicalProgress), actual.toByteArray());
                            assertEquals(0, target.position());
                            assertEquals(16, target.limit());
                            while (true) {
                                target.clear();
                                int count = body.read(target);
                                if (count < 0) {
                                    break;
                                }
                                assertTrue(count > 0);
                                byte[] fragment = new byte[count];
                                target.flip().get(fragment);
                                actual.writeBytes(fragment);
                            }
                            assertArrayEquals(EXPANDED_CONTENT, actual.toByteArray());
                        }
                        assertFollowingRegularEntry(reader);
                    }
                }
            }
        }
    }

    /// Encodes one sparse layout in the requested GNU representation and appends a regular entry.
    private static byte[] sparseArchive(
            String format, @Unmodifiable List<OldGnuSparseBlock> blocks, int logicalSize, byte[] packed
    ) throws IOException {
        if (format.equals("old-gnu")) {
            return oldGnuSparseArchive(blocks, logicalSize, packed, true);
        }
        List<Map.Entry<String, String>> records = new ArrayList<>();
        records.add(Map.entry("GNU.sparse.name", "value.bin"));
        if (format.equals("pax-1.0")) {
            records.add(Map.entry("GNU.sparse.major", "1"));
            records.add(Map.entry("GNU.sparse.minor", "0"));
            records.add(Map.entry("GNU.sparse.realsize", Integer.toString(logicalSize)));
            StringBuilder map = new StringBuilder().append(blocks.size()).append('\n');
            for (OldGnuSparseBlock block : blocks) {
                map.append(block.offset()).append('\n').append(block.size()).append('\n');
            }
            return paxSparseArchive(records, sparseVersion10Body(map.toString(), packed), true);
        }
        records.add(Map.entry("GNU.sparse.size", Integer.toString(logicalSize)));
        records.add(Map.entry("GNU.sparse.numblocks", Integer.toString(blocks.size())));
        if (format.equals("pax-0.0")) {
            for (OldGnuSparseBlock block : blocks) {
                records.add(Map.entry("GNU.sparse.offset", Long.toString(block.offset())));
                records.add(Map.entry("GNU.sparse.numbytes", Long.toString(block.size())));
            }
        } else if (format.equals("pax-0.1")) {
            StringBuilder map = new StringBuilder();
            for (OldGnuSparseBlock block : blocks) {
                if (!map.isEmpty()) {
                    map.append(',');
                }
                map.append(block.offset()).append(',').append(block.size());
            }
            records.add(Map.entry("GNU.sparse.map", map.toString()));
        } else {
            throw new IllegalArgumentException("Unknown sparse representation: " + format);
        }
        return paxSparseArchive(records, packed, true);
    }

    /// Verifies the regular entry immediately following a generated sparse body and the archive terminator.
    private static void assertFollowingRegularEntry(TarArkivoStreamingReader reader) throws IOException {
        assertTrue(reader.next());
        assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
        try (InputStream body = reader.openInputStream()) {
            assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
        }
        assertFalse(reader.next());
    }

    /// Verifies GNU sparse format 0.0 repeated PAX map records.
    @Test
    void readsPaxSparseVersion00() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.offset", "2"),
                        Map.entry("GNU.sparse.numbytes", "3"),
                        Map.entry("GNU.sparse.offset", "8"),
                        Map.entry("GNU.sparse.numbytes", "2")
                ),
                PACKED_CONTENT,
                false
        );
        assertSparseArchive(archive);
    }

    /// Verifies GNU sparse format 0.1 comma-separated maps and long logical holes.
    @Test
    void readsPaxSparseVersion01WithLongHole() throws IOException {
        long dataOffset = 5_000_000_000L;
        long logicalSize = dataOffset + 2L;
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", Long.toString(logicalSize)),
                        Map.entry("GNU.sparse.numblocks", "1"),
                        Map.entry("GNU.sparse.name", "large.bin"),
                        Map.entry("GNU.sparse.map", dataOffset + ",1")
                ),
                new byte[]{'x'},
                false
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            BasicFileAttributes attributes = reader.readAttributes(BasicFileAttributes.class);
            assertEquals(logicalSize, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                body.skipNBytes(dataOffset);
                assertEquals('x', body.read());
                assertEquals(0, body.read());
                assertEquals(0L, body.skip(1L));
                assertEquals(-1, body.read());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies GNU sparse 1.0 body maps and advancing after a partially consumed sparse entry.
    @Test
    void readsPaxSparseVersion10AndSkipsRemainingPackedData() throws IOException {
        byte[] sparseBody = sparseVersion10Body("2\n2\n3\n8\n2\n", PACKED_CONTENT);
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.major", "1"),
                        Map.entry("GNU.sparse.minor", "0"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.realsize", "12")
                ),
                sparseBody,
                true
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("value.bin", attributes.path());
            assertEquals(EXPANDED_CONTENT.length, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{0, 0, 'a', 'b'}, body.readNBytes(4));
            }

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies direct channel reads preserve sparse expansion across small buffers and the following entry boundary.
    @Test
    void readsSparseBodyThroughDirectChannel() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.map", "2,3,8,2")
                ),
                PACKED_CONTENT,
                true
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            try (ReadableByteChannel body = reader.openChannel()) {
                assertEquals(0, body.read(ByteBuffer.allocateDirect(0)));
                while (true) {
                    ByteBuffer target = ByteBuffer.allocateDirect(3);
                    int count = body.read(target);
                    if (count < 0) {
                        break;
                    }
                    assertTrue(count > 0);
                    target.flip();
                    byte[] bytes = new byte[target.remaining()];
                    target.get(bytes);
                    actual.writeBytes(bytes);
                }
            }
            assertArrayEquals(EXPANDED_CONTENT, actual.toByteArray());

            assertTrue(reader.next());
            assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies sparse stream navigation crosses holes and packed extents without exposing packed layout details.
    @Test
    void navigatesSparseInputStreamBoundaries() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.map", "2,3,8,2")
                ),
                PACKED_CONTENT,
                true
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertEquals(0, body.read());
                assertEquals(0L, body.skip(-1L));
                assertEquals(0L, body.skip(0L));

                body.skipNBytes(2L);
                assertEquals('b', body.read());

                body.skipNBytes(4L);
                assertEquals(1L, body.skip(1L));
                assertEquals('e', body.read());

                assertEquals(2L, body.skip(Long.MAX_VALUE));
                assertEquals(0L, body.skip(1L));
                assertEquals(-1, body.read());
            }

            assertTrue(reader.next());
            assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies skipping packed sparse data reports physical truncation after a logical hole.
    @Test
    void reportsTruncatedSparsePackedSkip() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.map", "2,3,8,2")
                ),
                PACKED_CONTENT,
                false
        );
        int bodyOffset = findSequence(archive, PACKED_CONTENT);
        byte[] truncated = Arrays.copyOf(archive, bodyOffset);

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(truncated)
        )) {
            assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertEquals(2L, body.skip(2L));
                EOFException failure = assertThrows(EOFException.class, () -> body.skip(1L));
                assertEquals("Unexpected end of GNU sparse entry data", failure.getMessage());
            }
        }
    }

    /// Verifies sparse channel reads distinguish transient zero progress from truncated packed data.
    @Test
    void reportsSparsePackedDataFailures() throws IOException {
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.name", "value.bin"),
                        Map.entry("GNU.sparse.map", "2,3,8,2")
                ),
                PACKED_CONTENT,
                false
        );

        FailingInputStream zeroProgressSource = new FailingInputStream(archive);
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(zeroProgressSource)) {
            assertTrue(reader.next());
            try (ReadableByteChannel body = reader.openChannel()) {
                assertDirectRead(body, new byte[]{0, 0});
                zeroProgressSource.returnZeroFromNextBulkRead();
                IOException stalled = assertThrows(
                        IOException.class,
                        () -> body.read(ByteBuffer.allocateDirect(1))
                );
                assertEquals("Readable channel made no progress", stalled.getMessage());
                assertDirectRead(body, new byte[]{'a', 'b', 'c'});
            }
        }

        int bodyOffset = findSequence(archive, PACKED_CONTENT);
        byte[] truncated = Arrays.copyOf(archive, bodyOffset + 2);
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(truncated)
        )) {
            assertTrue(reader.next());
            try (ReadableByteChannel body = reader.openChannel()) {
                assertDirectRead(body, new byte[]{0, 0});
                assertDirectRead(body, new byte[]{'a', 'b'});
                IOException failure = assertThrows(
                        IOException.class,
                        () -> body.read(ByteBuffer.allocateDirect(1))
                );
                assertEquals("Unexpected end of GNU sparse entry data", failure.getMessage());
            }
        }
    }

    /// Verifies old GNU sparse headers with more than one chained extension header.
    @Test
    void readsOldGnuSparseExtensionChain() throws IOException {
        ArrayList<OldGnuSparseBlock> blocks = new ArrayList<>();
        byte[] packedContent = new byte[26];
        byte[] expandedContent = new byte[54];
        for (int index = 0; index < packedContent.length; index++) {
            int offset = index * 2 + 1;
            byte value = (byte) ('a' + index);
            blocks.add(new OldGnuSparseBlock(offset, 1L));
            packedContent[index] = value;
            expandedContent[offset] = value;
        }
        byte[] archive = oldGnuSparseArchive(blocks, expandedContent.length, packedContent, false);

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("value.bin", attributes.path());
            assertEquals((byte) 'S', attributes.typeFlag());
            assertTrue(attributes.isRegularFile());
            assertFalse(attributes.isOther());
            assertEquals(expandedContent.length, attributes.size());
            assertEquals(FileTime.from(Instant.ofEpochSecond(11L)), attributes.lastAccessTime());
            assertEquals(FileTime.from(Instant.ofEpochSecond(11L)), attributes.recordedLastAccessTime());
            assertEquals(FileTime.from(Instant.ofEpochSecond(12L)), attributes.recordedStatusChangeTime());
            assertNull(attributes.recordedCreationTime());
            assertEquals(FileTime.from(Instant.ofEpochSecond(10L)), attributes.creationTime());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(expandedContent, body.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies advancing after only part of an old GNU sparse logical body was consumed.
    @Test
    void advancesAfterPartiallyConsumedOldGnuSparseEntry() throws IOException {
        byte[] archive = oldGnuSparseArchive(
                List.of(
                        new OldGnuSparseBlock(2L, 3L),
                        new OldGnuSparseBlock(8L, 2L)
                ),
                EXPANDED_CONTENT.length,
                PACKED_CONTENT,
                true
        );

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(new byte[]{0, 0, 'a', 'b'}, body.readNBytes(4));
            }

            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            assertEquals("next.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals("next".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Verifies logical file sizes, random-access expansion, and independent cursors for all sparse representations.
    @ParameterizedTest
    @ValueSource(strings = {"old-gnu", "pax-0.0", "pax-0.1", "pax-1.0"})
    void exposesExpandedSparseContentThroughFileSystem(String format, @TempDir Path directory) throws IOException {
        for (byte[] expected : new byte[][]{new byte[0], new byte[8193], EXPANDED_CONTENT}) {
            boolean hasData = expected.length == EXPANDED_CONTENT.length;
            byte[] archive = sparseArchive(format, hasData ? List.of(
                    new OldGnuSparseBlock(2, 3), new OldGnuSparseBlock(8, 2)
            ) : List.of(), expected.length, hasData ? PACKED_CONTENT : new byte[0]);
            Path archivePath = directory.resolve("sparse-" + expected.length + ".tar");
            Files.write(archivePath, archive);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.open(archivePath)) {
                Path entry = fileSystem.getPath("/value.bin");
                assertEquals(expected.length, Files.size(entry));
                assertArrayEquals(expected, Files.readAllBytes(entry));
                for (boolean direct : new boolean[]{false, true}) {
                    try (SeekableByteChannel body = Files.newByteChannel(entry);
                         SeekableByteChannel independent = Files.newByteChannel(entry)) {
                        assertEquals(expected.length, body.size());
                        for (int position : new int[]{expected.length + 1, expected.length, 10, 8, 5, 4, 2, 1, 0}) {
                            body.position(position);
                            ByteBuffer target = direct ? ByteBuffer.allocateDirect(7) : ByteBuffer.allocate(7);
                            int count = body.read(target);
                            if (position >= expected.length) {
                                assertEquals(-1, count);
                                assertEquals(position, body.position());
                            } else {
                                assertTrue(count > 0 && count <= Math.min(7, expected.length - position));
                                assertEquals(position + count, body.position());
                                byte[] actual = new byte[count];
                                target.flip().get(actual);
                                assertArrayEquals(Arrays.copyOfRange(expected, position, position + count), actual);
                            }
                            assertEquals(0, independent.position());
                            assertEquals(expected.length, body.size());
                        }
                    }
                }
                assertEquals("next", Files.readString(fileSystem.getPath("/next.txt")));
            }
        }
    }

    /// Verifies that a file system update rewrites expanded old GNU sparse entries as valid regular files.
    @Test
    void normalizesOldGnuSparseEntryDuringFileSystemUpdate() throws IOException {
        byte[] archive = oldGnuSparseArchive(
                List.of(
                        new OldGnuSparseBlock(2L, 3L),
                        new OldGnuSparseBlock(8L, 2L)
                ),
                EXPANDED_CONTENT.length,
                PACKED_CONTENT,
                false
        );
        Path archivePath = Files.createTempFile("arkivo-old-gnu-sparse-update-", ".tar");
        try {
            Files.write(archivePath, archive);
            try (TarArkivoFileSystem fileSystem = TarArkivoFileSystem.update(archivePath)) {
                Files.writeString(fileSystem.getPath("/added.txt"), "added", StandardCharsets.UTF_8);
            }

            try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(Files.newInputStream(archivePath))) {
                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
                assertEquals("value.bin", attributes.path());
                assertEquals((byte) '0', attributes.typeFlag());
                try (InputStream body = reader.openInputStream()) {
                    assertArrayEquals(EXPANDED_CONTENT, body.readAllBytes());
                }

                org.junit.jupiter.api.Assertions.assertTrue(reader.next());
                assertEquals("added.txt", reader.readAttributes(TarArkivoEntryAttributes.class).path());
                try (InputStream body = reader.openInputStream()) {
                    assertEquals("added", new String(body.readAllBytes(), StandardCharsets.UTF_8));
                }
                org.junit.jupiter.api.Assertions.assertFalse(reader.next());
            }
        } finally {
            Files.deleteIfExists(archivePath);
        }
    }

    /// Verifies rejection of overlapping sparse blocks and mismatched packed sizes.
    @Test
    void rejectsInvalidPaxSparseMaps() throws IOException {
        byte[] overlapping = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "2"),
                        Map.entry("GNU.sparse.map", "2,4,5,1")
                ),
                PACKED_CONTENT,
                false
        );
        IOException overlapException = assertThrows(IOException.class, () -> readFirstEntry(overlapping));
        assertTrue(overlapException.getMessage().contains("overlap"));

        byte[] sizeMismatch = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", "1"),
                        Map.entry("GNU.sparse.map", "2,4")
                ),
                PACKED_CONTENT,
                false
        );
        IOException sizeException = assertThrows(IOException.class, () -> readFirstEntry(sizeMismatch));
        assertTrue(sizeException.getMessage().contains("packed size mismatch"));

        byte[] oversizedCount = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.size", "12"),
                        Map.entry("GNU.sparse.numblocks", Integer.toString(Integer.MAX_VALUE)),
                        Map.entry("GNU.sparse.map", "2,3")
                ),
                PACKED_CONTENT,
                false
        );
        IOException countException = assertThrows(IOException.class, () -> readFirstEntry(oversizedCount));
        assertTrue(countException.getMessage().contains("wrong block count"));
    }

    /// Verifies rejection of non-null GNU sparse 1.0 map padding.
    @Test
    void rejectsInvalidPaxSparseVersion10Padding() throws IOException {
        byte[] sparseBody = sparseVersion10Body("0\n", new byte[0]);
        sparseBody[2] = 1;
        byte[] archive = paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.major", "1"),
                        Map.entry("GNU.sparse.minor", "0"),
                        Map.entry("GNU.sparse.realsize", "0")
                ),
                sparseBody,
                false
        );
        IOException exception = assertThrows(IOException.class, () -> readFirstEntry(archive));
        assertTrue(exception.getMessage().contains("padding"));
    }

    /// Verifies rejection of malformed and physically truncated GNU sparse 1.0 body maps.
    @Test
    void rejectsMalformedPaxSparseVersion10BodyMaps() throws IOException {
        assertSparseVersion10BodyRejected(
                new byte[0],
                "Unexpected end of GNU sparse map"
        );
        assertSparseVersion10BodyRejected(
                "\n".getBytes(StandardCharsets.US_ASCII),
                "GNU sparse block count is empty"
        );
        assertSparseVersion10BodyRejected(
                "x\n".getBytes(StandardCharsets.US_ASCII),
                "Invalid GNU sparse block count"
        );
        assertSparseVersion10BodyRejected(
                "/\n".getBytes(StandardCharsets.US_ASCII),
                "Invalid GNU sparse block count"
        );
        assertSparseVersion10BodyRejected(
                "9223372036854775808\n".getBytes(StandardCharsets.US_ASCII),
                "GNU sparse block count is too large"
        );
        assertSparseVersion10BodyRejected(
                "2147483648\n".getBytes(StandardCharsets.US_ASCII),
                "GNU sparse block count is too large"
        );
        assertSparseVersion10BodyRejected(
                "1\n0\n".getBytes(StandardCharsets.US_ASCII),
                "Unexpected end of GNU sparse map"
        );
        assertSparseVersion10BodyRejected(
                "0\n".getBytes(StandardCharsets.US_ASCII),
                "Unexpected end of GNU sparse map padding"
        );

        byte[] complete = paxSparseVersion10Archive(sparseVersion10Body("0\n", new byte[0]));
        int bodyOffset = findSequence(
                complete,
                "GNUSparseFile/entry".getBytes(StandardCharsets.US_ASCII)
        ) + 512;

        IOException numberTruncation = assertThrows(
                IOException.class,
                () -> readFirstEntry(Arrays.copyOf(complete, bodyOffset + 1))
        );
        assertEquals("Unexpected end of GNU sparse map", numberTruncation.getMessage());

        IOException paddingTruncation = assertThrows(
                IOException.class,
                () -> readFirstEntry(Arrays.copyOf(complete, bodyOffset + 3))
        );
        assertEquals("Unexpected end of GNU sparse map padding", paddingTruncation.getMessage());
    }

    /// Verifies rejection of malformed old GNU maps and truncated extension headers.
    @Test
    void rejectsInvalidOldGnuSparseEntries() throws IOException {
        byte[] overlapping = oldGnuSparseArchive(
                List.of(
                        new OldGnuSparseBlock(2L, 4L),
                        new OldGnuSparseBlock(5L, 1L)
                ),
                12L,
                PACKED_CONTENT,
                false
        );
        IOException overlapException = assertThrows(IOException.class, () -> readFirstEntry(overlapping));
        assertTrue(overlapException.getMessage().contains("overlap"));

        byte[] sizeMismatch = oldGnuSparseArchive(
                List.of(new OldGnuSparseBlock(2L, 4L)),
                12L,
                PACKED_CONTENT,
                false
        );
        IOException sizeException = assertThrows(IOException.class, () -> readFirstEntry(sizeMismatch));
        assertTrue(sizeException.getMessage().contains("packed size mismatch"));

        byte[] withExtension = oldGnuSparseArchive(
                List.of(
                        new OldGnuSparseBlock(0L, 1L),
                        new OldGnuSparseBlock(2L, 1L),
                        new OldGnuSparseBlock(4L, 1L),
                        new OldGnuSparseBlock(6L, 1L),
                        new OldGnuSparseBlock(8L, 1L)
                ),
                10L,
                PACKED_CONTENT,
                false
        );
        byte[] truncated = Arrays.copyOf(withExtension, 612);
        IOException extensionException = assertThrows(IOException.class, () -> readFirstEntry(truncated));
        assertTrue(extensionException.getMessage().contains("extension header"));
    }

    /// Reads and verifies the standard sparse archive fixture.
    private static void assertSparseArchive(byte[] archive) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            org.junit.jupiter.api.Assertions.assertTrue(reader.next());
            TarArkivoEntryAttributes attributes = reader.readAttributes(TarArkivoEntryAttributes.class);
            assertEquals("value.bin", attributes.path());
            assertEquals(EXPANDED_CONTENT.length, attributes.size());
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(EXPANDED_CONTENT, body.readAllBytes());
            }
            org.junit.jupiter.api.Assertions.assertFalse(reader.next());
        }
    }

    /// Reads and verifies one exact direct-buffer fragment from a sparse entry channel.
    private static void assertDirectRead(ReadableByteChannel channel, byte[] expected) throws IOException {
        ByteBuffer target = ByteBuffer.allocateDirect(expected.length);
        assertEquals(expected.length, channel.read(target));
        target.flip();
        byte[] actual = new byte[target.remaining()];
        target.get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Returns the first byte offset at which one nonempty sequence occurs.
    private static int findSequence(byte[] source, byte[] sequence) {
        if (sequence.length == 0) {
            throw new IllegalArgumentException("sequence must not be empty");
        }
        outer:
        for (int offset = 0; offset <= source.length - sequence.length; offset++) {
            for (int index = 0; index < sequence.length; index++) {
                if (source[offset + index] != sequence[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        throw new AssertionError("Sequence is absent from generated TAR fixture");
    }

    /// Advances one reader to its first entry.
    private static void readFirstEntry(byte[] archive) throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            reader.next();
        }
    }

    /// Verifies that one GNU sparse 1.0 body is rejected with the expected diagnostic.
    private static void assertSparseVersion10BodyRejected(byte[] sparseBody, String expectedMessage) throws IOException {
        IOException exception = assertThrows(
                IOException.class,
                () -> readFirstEntry(paxSparseVersion10Archive(sparseBody))
        );
        assertEquals(expectedMessage, exception.getMessage());
    }

    /// Creates a GNU sparse 1.0 archive with the supplied complete stored body.
    private static byte[] paxSparseVersion10Archive(byte[] sparseBody) throws IOException {
        return paxSparseArchive(
                List.of(
                        Map.entry("GNU.sparse.major", "1"),
                        Map.entry("GNU.sparse.minor", "0"),
                        Map.entry("GNU.sparse.realsize", "0")
                ),
                sparseBody,
                false
        );
    }

    /// Creates a PAX sparse archive and optionally appends a regular entry.
    private static byte[] paxSparseArchive(
            @Unmodifiable List<Map.Entry<String, String>> paxRecords,
            byte[] storedBody,
            boolean appendRegularEntry
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writePaxHeader(output, paxRecords);
        writeHeader(output, "GNUSparseFile/entry", storedBody.length, '0');
        writeBody(output, storedBody);
        if (appendRegularEntry) {
            byte[] content = "next".getBytes(StandardCharsets.UTF_8);
            writeHeader(output, "next.txt", content.length, '0');
            writeBody(output, content);
        }
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    /// Creates an old GNU sparse archive and optionally appends a regular entry.
    private static byte[] oldGnuSparseArchive(
            @Unmodifiable List<OldGnuSparseBlock> blocks,
            long logicalSize,
            byte[] storedBody,
            boolean appendRegularEntry
    ) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeOldGnuSparseHeader(output, blocks, logicalSize, storedBody.length);

        int blockIndex = 4;
        while (blockIndex < blocks.size()) {
            byte[] extension = new byte[512];
            int extensionEnd = Math.min(blockIndex + 21, blocks.size());
            for (int index = blockIndex; index < extensionEnd; index++) {
                writeOldGnuSparseBlock(extension, (index - blockIndex) * 24, blocks.get(index));
            }
            blockIndex = extensionEnd;
            extension[504] = blockIndex < blocks.size() ? (byte) 1 : 0;
            output.write(extension);
        }

        writeBody(output, storedBody);
        if (appendRegularEntry) {
            byte[] content = "next".getBytes(StandardCharsets.UTF_8);
            writeHeader(output, "next.txt", content.length, '0');
            writeBody(output, content);
        }
        output.write(new byte[1024]);
        return output.toByteArray();
    }

    /// Writes one old GNU sparse main header.
    private static void writeOldGnuSparseHeader(
            ByteArrayOutputStream output,
            @Unmodifiable List<OldGnuSparseBlock> blocks,
            long logicalSize,
            int storedSize
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, "value.bin");
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0L);
        writeOctal(header, 116, 8, 0L);
        writeOctal(header, 124, 12, storedSize);
        writeOctal(header, 136, 12, 10L);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = 'S';
        writeRawString(header, 257, 6, "ustar ");
        header[263] = ' ';
        writeOctal(header, 345, 12, 11L);
        writeOctal(header, 357, 12, 12L);
        writeOctal(header, 369, 12, 0L);
        int mainBlockCount = Math.min(4, blocks.size());
        for (int index = 0; index < mainBlockCount; index++) {
            writeOldGnuSparseBlock(header, 386 + index * 24, blocks.get(index));
        }
        header[482] = blocks.size() > 4 ? (byte) 1 : 0;
        writeOctal(header, 483, 12, logicalSize);

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes one fixed-width old GNU sparse descriptor.
    private static void writeOldGnuSparseBlock(
            byte[] target,
            int offset,
            OldGnuSparseBlock block
    ) {
        writeOctal(target, offset, 12, block.offset());
        writeOctal(target, offset + 12, 12, block.size());
    }

    /// Creates a GNU sparse 1.0 body containing a padded textual map and packed data.
    private static byte[] sparseVersion10Body(String map, byte[] packedContent) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] mapBytes = map.getBytes(StandardCharsets.US_ASCII);
        output.write(mapBytes);
        output.write(new byte[(512 - mapBytes.length % 512) % 512]);
        output.write(packedContent);
        return output.toByteArray();
    }

    /// Writes one PAX extended header with ordered records that may repeat keys.
    private static void writePaxHeader(
            ByteArrayOutputStream output,
            @Unmodifiable List<Map.Entry<String, String>> records
    ) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> record : records) {
            body.write(paxRecord(record.getKey(), record.getValue()));
        }
        byte[] bodyBytes = body.toByteArray();
        writeHeader(output, "PaxHeaders/entry", bodyBytes.length, 'x');
        writeBody(output, bodyBytes);
    }

    /// Returns one encoded PAX key-value record.
    private static byte[] paxRecord(String key, String value) {
        String payload = key + "=" + value + "\n";
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int digits = 1;
        while (true) {
            int length = digits + 1 + payloadBytes.length;
            int actualDigits = Integer.toString(length).length();
            if (actualDigits == digits) {
                return (length + " " + payload).getBytes(StandardCharsets.UTF_8);
            }
            digits = actualDigits;
        }
    }

    /// Writes a TAR body followed by 512-byte record padding.
    private static void writeBody(ByteArrayOutputStream output, byte[] content) throws IOException {
        output.write(content);
        output.write(new byte[(512 - content.length % 512) % 512]);
    }

    /// Writes one minimal checksummed USTAR header.
    private static void writeHeader(
            ByteArrayOutputStream output,
            String path,
            int size,
            int typeFlag
    ) throws IOException {
        byte[] header = new byte[512];
        writeString(header, 0, 100, path);
        writeOctal(header, 100, 8, 0644);
        writeOctal(header, 108, 8, 0);
        writeOctal(header, 116, 8, 0);
        writeOctal(header, 124, 12, size);
        writeOctal(header, 136, 12, 0);
        for (int index = 148; index < 156; index++) {
            header[index] = ' ';
        }
        header[156] = (byte) typeFlag;
        writeString(header, 257, 6, "ustar");
        writeRawString(header, 263, 2, "00");

        int checksum = 0;
        for (byte value : header) {
            checksum += Byte.toUnsignedInt(value);
        }
        writeChecksum(header, checksum);
        output.write(header);
    }

    /// Writes a null-terminated string field.
    private static void writeString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length >= length) {
            throw new IllegalArgumentException("value is too long");
        }
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /// Writes a fixed-width string field.
    private static void writeRawString(byte[] target, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != length) {
            throw new IllegalArgumentException("value must match the field length");
        }
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    /// Writes a zero-terminated octal number field.
    private static void writeOctal(byte[] target, int offset, int length, long value) {
        String text = Long.toOctalString(value);
        int start = offset + length - text.length() - 1;
        for (int index = offset; index < start; index++) {
            target[index] = '0';
        }
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, start, bytes.length);
    }

    /// Writes the USTAR checksum field.
    private static void writeChecksum(byte[] header, int checksum) {
        byte[] bytes = String.format("%06o", checksum).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, 148, bytes.length);
        header[154] = 0;
        header[155] = ' ';
    }

    /// Stores one old GNU sparse data extent used by archive fixtures.
    ///
    /// @param offset the absolute logical file offset
    /// @param size   the number of packed bytes
    @NotNullByDefault
    private record OldGnuSparseBlock(long offset, long size) {
        /// Creates an old GNU sparse block fixture.
        private OldGnuSparseBlock {
            if (offset < 0L || size < 0L) {
                throw new IllegalArgumentException("Sparse block values must not be negative");
            }
        }
    }

    /// Provides an in-memory archive stream with an armed stall or recoverable failure boundary.
    @NotNullByDefault
    private static final class FailingInputStream extends InputStream {
        /// The immutable archive bytes.
        private final byte @Unmodifiable [] bytes;

        /// The next physical byte offset.
        private int position;

        /// The offset of the armed source failure, or a negative value when unarmed.
        private int failureOffset = -1;

        /// The failure to emit once at the configured boundary.
        private @Nullable Throwable failure;

        /// Whether the next nonempty bulk read must return zero.
        private boolean zeroProgressPending;

        /// Creates a stream over the supplied archive bytes.
        private FailingInputStream(byte[] archive) {
            bytes = archive.clone();
        }

        /// Arms a failure before reading the byte at the supplied absolute offset.
        private void failAt(int offset, Throwable failure) {
            if (offset < position || offset > bytes.length) {
                throw new IllegalArgumentException("Failure offset is outside the unread source range");
            }
            this.failureOffset = offset;
            this.failure = failure;
        }

        /// Emits and clears an armed failure when its boundary is reached.
        private void checkFailure() throws IOException {
            if (position == failureOffset && failure != null) {
                Throwable pending = failure;
                failure = null;
                if (pending instanceof IOException exception) {
                    throw exception;
                }
                if (pending instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) pending;
            }
        }

        /// Reads one unsigned byte after checking the armed failure boundary.
        @Override
        public int read() throws IOException {
            checkFailure();
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        /// Arms one zero-progress result for the next nonempty bulk read.
        private void returnZeroFromNextBulkRead() {
            zeroProgressPending = true;
        }

        /// Returns zero once when armed, otherwise reads no farther than the failure boundary.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (zeroProgressPending) {
                zeroProgressPending = false;
                return 0;
            }
            checkFailure();
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            if (failure != null) {
                count = Math.min(count, failureOffset - position);
            }
            System.arraycopy(bytes, position, buffer, offset, count);
            position += count;
            return count;
        }
    }
}
