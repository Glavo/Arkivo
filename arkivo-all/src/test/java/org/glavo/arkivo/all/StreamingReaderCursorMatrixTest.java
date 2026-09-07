// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies cursor advancement and body ownership across every installed forward-only archive format.
@NotNullByDefault
final class StreamingReaderCursorMatrixTest {
    /// Installed formats that support forward-only reading of generated two-entry fixtures.
    private static final @Unmodifiable List<String> STREAMING_FORMATS =
            List.of("ar", "cpio", "rar", "tar", "zip");

    /// Content of the entry that is skipped or consumed only partially.
    private static final byte @Unmodifiable [] FIRST_CONTENT =
            ("first-body-" + "0123456789".repeat(2048)).getBytes(StandardCharsets.UTF_8);

    /// Content of the entry read after cursor advancement.
    private static final byte @Unmodifiable [] SECOND_CONTENT =
            "second-body".getBytes(StandardCharsets.UTF_8);

    /// Verifies every reader advances past both unopened and partially consumed entry bodies.
    @Test
    void advancesPastUnreadAndPartiallyReadBodiesAcrossEveryStreamingFormat() throws IOException {
        for (String formatName : STREAMING_FORMATS) {
            assertCursorAdvance(formatName, false);
            assertCursorAdvance(formatName, true);
        }
    }

    /// Verifies bulk body operations stop before padding, empty entries, and the following entry header.
    @ParameterizedTest
    @ValueSource(strings = {"skip", "skipNBytes", "readNBytes", "transferTo"})
    void bulkReadsStayWithinEntryBoundaries(String operation) throws IOException {
        for (String formatName : STREAMING_FORMATS) {
            for (int length : new int[]{0, 1, 511, 512, 513, 8193}) {
                String context = formatName + " " + operation + " length=" + length;
                byte[] firstContent = Arrays.copyOf(FIRST_CONTENT, length);
                Map<String, byte[]> entries = new LinkedHashMap<>();
                entries.put("first.bin", firstContent);
                entries.put("empty.bin", new byte[0]);
                entries.put("second.bin", SECOND_CONTENT);
                FragmentingReadableByteChannel source =
                        new FragmentingReadableByteChannel(createArchive(formatName, entries));
                try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                        formatName, source, ArchiveReadOptions.DEFAULT)) {
                    assertTrue(reader.next(), context);
                    ArchiveEntryAttributes firstAttributes = reader.readAttributes();
                    assertEquals("first.bin", firstAttributes.path(), context);
                    InputStream first = reader.openInputStream();
                    switch (operation) {
                        case "skip":
                            long skipped = 0L;
                            for (int calls = 0; calls <= length; calls++) {
                                long count = first.skip(Long.MAX_VALUE);
                                assertTrue(count >= 0L, context);
                                if (count == 0L) {
                                    break;
                                }
                                skipped += count;
                                assertTrue(skipped <= length, context);
                            }
                            assertEquals(length, skipped, context);
                            break;
                        case "skipNBytes":
                            assertThrows(EOFException.class, () -> first.skipNBytes((long) length + 1L), context);
                            break;
                        case "readNBytes":
                            assertArrayEquals(firstContent, first.readNBytes(length + 1), context);
                            break;
                        case "transferTo":
                            ByteArrayOutputStream transferred = new ByteArrayOutputStream();
                            assertEquals(length, first.transferTo(transferred), context);
                            assertArrayEquals(firstContent, transferred.toByteArray(), context);
                            break;
                        default:
                            throw new AssertionError(operation);
                    }
                    assertEquals(-1, first.read(), context);
                    assertEquals(0L, first.skip(Long.MAX_VALUE), context);
                    assertThrows(IllegalStateException.class, reader::openChannel, context);

                    assertTrue(reader.next(), context);
                    assertEquals("empty.bin", reader.readAttributes().path(), context);
                    InputStream empty = reader.openInputStream();
                    first.close();
                    first.close();
                    assertThrows(IOException.class, first::read, context);
                    assertEquals(-1, empty.read(), context);
                    assertEquals(0L, empty.skip(Long.MAX_VALUE), context);

                    assertTrue(reader.next(), context);
                    assertEquals("second.bin", reader.readAttributes().path(), context);
                    try (InputStream second = reader.openInputStream()) {
                        empty.close();
                        empty.close();
                        assertThrows(IOException.class, empty::read, context);
                        assertArrayEquals(SECOND_CONTENT, second.readAllBytes(), context);
                    }
                    assertFalse(reader.next(), context);
                    assertEquals("first.bin", firstAttributes.path(), context);
                }
                assertFalse(source.isOpen(), context);
                assertEquals(1, source.closeCount(), context);
            }
        }
    }

    /// Verifies one format with either an unopened or a partially consumed first body.
    private static void assertCursorAdvance(String formatName, boolean partiallyRead) throws IOException {
        String context = formatName + (partiallyRead ? "/partial" : "/unopened");
        FragmentingReadableByteChannel source = new FragmentingReadableByteChannel(createArchive(formatName));
        try (ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                formatName,
                source,
                ArchiveReadOptions.DEFAULT
        )) {
            assertTrue(reader.next(), context);
            ArchiveEntryAttributes firstAttributes = reader.readAttributes();
            assertEquals("first.bin", firstAttributes.path(), context);
            assertTrue(firstAttributes.isRegularFile(), context);

            @Nullable ReadableByteChannel firstBody = null;
            if (partiallyRead) {
                firstBody = reader.openChannel();
                ByteBuffer firstByte = ByteBuffer.allocate(1);
                assertEquals(1, firstBody.read(firstByte), context);
                firstByte.flip();
                assertEquals(Byte.toUnsignedInt(FIRST_CONTENT[0]), Byte.toUnsignedInt(firstByte.get()), context);
            }

            assertTrue(reader.next(), context);
            if (firstBody != null) {
                assertFalse(firstBody.isOpen(), context);
            }
            assertEquals("first.bin", firstAttributes.path(), context);

            ArchiveEntryAttributes secondAttributes = reader.readAttributes();
            assertEquals("second.bin", secondAttributes.path(), context);
            assertTrue(secondAttributes.isRegularFile(), context);
            try (InputStream body = reader.openInputStream()) {
                assertArrayEquals(SECOND_CONTENT, body.readAllBytes(), context);
            }
            assertFalse(reader.next(), context);
        }
        assertFalse(source.isOpen(), context);
        assertEquals(1, source.closeCount(), context);
    }

    /// Creates a two-entry archive for the named streaming format.
    private static byte @Unmodifiable [] createArchive(String formatName) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("first.bin", FIRST_CONTENT);
        entries.put("second.bin", SECOND_CONTENT);
        return createArchive(formatName, entries);
    }

    /// Creates an archive whose regular files follow the supplied map's iteration order.
    private static byte @Unmodifiable [] createArchive(String formatName, Map<String, byte[]> entries)
            throws IOException {
        if ("rar".equals(formatName)) {
            return ArchiveTestFixtures.createRar4Archive(entries);
        }

        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ArkivoStreamingWriter writer = ArkivoFormats.openStreamingWriter(
                formatName,
                Channels.newChannel(archive)
        )) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                writeEntry(writer, entry.getKey(), entry.getValue());
            }
        }
        return archive.toByteArray();
    }

    /// Writes one regular-file entry and completes it by closing its body.
    private static void writeEntry(
            ArkivoStreamingWriter writer,
            String path,
            byte @Unmodifiable [] content
    ) throws IOException {
        ArkivoStreamingWriter.Entry entry = writer.beginFile(path);
        try (var body = entry.openOutputStream()) {
            body.write(content);
        }
    }

    /// Serves immutable bytes through bounded short reads and records source ownership release.
    @NotNullByDefault
    private static final class FragmentingReadableByteChannel implements ReadableByteChannel {
        /// Maximum bytes returned by one read operation.
        private static final int MAXIMUM_READ_SIZE = 7;

        /// Read-only view of the remaining archive bytes.
        private final @UnmodifiableView ByteBuffer content;

        /// Whether this source remains open.
        private boolean open = true;

        /// Number of effective close operations.
        private int closeCount;

        /// Creates a source over a defensive copy of the supplied archive bytes.
        private FragmentingReadableByteChannel(byte[] content) {
            this.content = ByteBuffer.wrap(Objects.requireNonNull(content, "content").clone())
                    .asReadOnlyBuffer();
        }

        /// Reads at most seven bytes into the target buffer.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!open) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (!content.hasRemaining()) {
                return -1;
            }

            int count = Math.min(MAXIMUM_READ_SIZE, Math.min(content.remaining(), target.remaining()));
            @UnmodifiableView ByteBuffer chunk = content.slice();
            chunk.limit(count);
            target.put(chunk);
            content.position(content.position() + count);
            return count;
        }

        /// Returns whether this source remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this source idempotently and records the effective transition.
        @Override
        public void close() {
            if (open) {
                open = false;
                closeCount++;
            }
        }

        /// Returns the number of effective close operations.
        private int closeCount() {
            return closeCount;
        }
    }
}
