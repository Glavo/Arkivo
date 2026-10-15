// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.all;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoFormats;
import org.glavo.arkivo.archive.ArkivoStreamingReader;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.zip.ZipArkivoEntryAttributeView;
import org.glavo.arkivo.archive.zip.ZipMethod;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /// Reading and automatic body or reader closure must not suppress the same source failure on itself.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "cpio", "rar", "tar", "zip", "zip-stored"})
    void preservesReadFailureDuringAutomaticCleanup(String format) throws IOException {
        byte[] archive;
        if (format.equals("zip-stored")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var writer = ArkivoFormats.openStreamingWriter("zip", Channels.newChannel(output))) {
                var entry = writer.beginFile("first.bin");
                entry.attributeView(ZipArkivoEntryAttributeView.class).setMethod(ZipMethod.STORED);
                try (var body = entry.openOutputStream()) {
                    body.write(FIRST_CONTENT);
                }
            }
            archive = output.toByteArray();
        } else {
            archive = createArchive(format);
        }
        for (boolean stream : new boolean[]{false, true}) {
            for (boolean bodyScope : new boolean[]{false, true}) {
                for (Throwable failure : new Throwable[]{new IOException("source read failed"),
                        new IllegalStateException("source read failed"), new AssertionError("source read failed")}) {
                    var source = new FragmentingReadableByteChannel(archive);
                    ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(
                            format.equals("zip-stored") ? "zip" : format, source);
                    try {
                        assertTrue(reader.next());
                        Throwable actual;
                        if (stream) {
                            InputStream body = reader.openInputStream();
                            source.readFailure = failure;
                            actual = assertThrows(failure.getClass(), () -> {
                                try (var resource = bodyScope ? body : reader) {
                                    body.readAllBytes();
                                }
                            });
                        } else {
                            ReadableByteChannel body = reader.openChannel();
                            source.readFailure = failure;
                            actual = assertThrows(failure.getClass(), () -> {
                                try (var resource = bodyScope ? body : reader) {
                                    ByteBuffer buffer = ByteBuffer.allocateDirect(1024);
                                    while (body.read(buffer) >= 0) buffer.clear();
                                }
                            });
                        }
                        assertSame(failure, actual);
                        if (format.equals("tar")) {
                            // TAR body close does not drain unread data and succeeds after the read failure.
                            assertEquals(0, actual.getSuppressed().length);
                        } else {
                            assertTrue(actual.getSuppressed().length > 0);
                            if (!format.equals("zip")) {
                                assertSame(failure, rootCause(actual.getSuppressed()[0]));
                            }
                        }
                    } finally {
                        source.readFailure = null;
                        reader.close();
                        reader.close();
                    }
                    assertFalse(source.isOpen());
                    assertEquals(1, source.closeCount());
                }
            }
        }
    }

    /// Returns the original failure at the end of nested adapter cleanup wrappers.
    private static Throwable rootCause(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }

    /// A cleanup retry must not replace the first close failure with a try-with-resources self-suppression error.
    @ParameterizedTest
    @ValueSource(strings = {"ar", "cpio", "rar", "tar", "zip"})
    void preservesRepeatedSourceCloseFailure(String format) throws IOException {
        for (boolean openBody : new boolean[]{false, true}) {
            for (int failureKind = 0; failureKind < 3; failureKind++) {
                Throwable failure = switch (failureKind) {
                    case 0 -> new IOException("source close failed");
                    case 1 -> new IllegalStateException("source close failed");
                    default -> new AssertionError("source close failed");
                };
                var source = new FragmentingReadableByteChannel(createArchive(format));
                source.closeFailure = failure;
                source.closeFailuresRemaining = 2;
                ArkivoStreamingReader reader = ArkivoFormats.openStreamingReader(format, source);
                try {
                    assertTrue(reader.next());
                    if (openBody) {
                        assertEquals(1, reader.openChannel().read(ByteBuffer.allocate(1)));
                    }
                    Throwable actual = assertThrows(failure.getClass(), () -> {
                        try (reader) {
                            reader.close();
                        }
                    });
                    assertSame(failure, actual);
                    assertEquals(1, actual.getSuppressed().length);
                    assertSame(failure, rootCause(actual.getSuppressed()[0]));
                    assertThrows(ClosedChannelException.class, reader::next);
                    assertThrows(ClosedChannelException.class, reader::readAttributes);
                    reader.close();
                    reader.close();
                    assertFalse(source.isOpen());
                    assertEquals(1, source.closeCount());
                } finally {
                    reader.close();
                }
            }
        }
    }

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

        /// The injected failure reused by unsuccessful close attempts, or null when cleanup succeeds.
        private @Nullable Throwable closeFailure;

        /// Number of close attempts that still have to fail.
        private int closeFailuresRemaining;

        /// The failure reused by every nonempty read after it has been armed.
        private @Nullable Throwable readFailure;

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
            if (readFailure instanceof IOException exception) throw exception;
            if (readFailure instanceof RuntimeException exception) throw exception;
            if (readFailure instanceof Error exception) throw exception;
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
        public void close() throws IOException {
            if (open) {
                if (closeFailuresRemaining > 0) {
                    closeFailuresRemaining--;
                    Throwable failure = Objects.requireNonNull(closeFailure);
                    if (failure instanceof IOException exception) {
                        throw exception;
                    }
                    if (failure instanceof RuntimeException exception) {
                        throw exception;
                    }
                    throw (Error) failure;
                }
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
