// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.DosFileAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies AR streaming-reader entry ownership, body lifetime, and source cleanup behavior.
@NotNullByDefault
final class ArStreamingReaderLifecycleTest {
    /// Verifies that a member body can only be opened once.
    @Test
    void entryBodyCanOnlyBeOpenedOnce() throws IOException {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] archive = singleFileArchive("hello.txt", content);

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());

            try (var channel = reader.openChannel()) {
                IllegalStateException exception = assertThrows(IllegalStateException.class, reader::openChannel);
                assertTrue(exception.getMessage().contains("already open"));

                ByteBuffer buffer = ByteBuffer.allocate(content.length);
                assertEquals(content.length, channel.read(buffer));
                buffer.flip();
                assertEquals("hello", StandardCharsets.UTF_8.decode(buffer).toString());
            }

            assertFalse(reader.next());
        }
    }

    /// Verifies that reader close can retry source cleanup after failure.
    @Test
    void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(
                singleFileArchive("hello.txt", "hello".getBytes(StandardCharsets.UTF_8))
        );
        ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(source);
        assertTrue(reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Verifies entry streams drain unread bodies, preserve padding, and reject use after close.
    @Test
    void entryInputStreamsEnforceTheirLifecycle() throws IOException {
        byte[] archive = twoFileArchive();

        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(new ByteArrayInputStream(archive))) {
            assertTrue(reader.next());
            var first = reader.openInputStream();
            assertEquals(1, first.read());
            first.close();
            first.close();
            assertThrows(IOException.class, first::read);

            assertTrue(reader.next());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> reader.readAttributes(DosFileAttributes.class)
            );
            try (var second = reader.openInputStream()) {
                assertEquals(0, second.read(new byte[0]));
                assertEquals(4, second.read());
                assertEquals(-1, second.read());
                assertEquals(-1, second.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Verifies that advancing closes an open body and drains its unread bytes and odd-length padding.
    @Test
    void advancingClosesAndDrainsPartiallyReadMemberBody() throws IOException {
        try (ArArkivoStreamingReader reader = ArArkivoStreamingReader.open(
                new ByteArrayInputStream(twoFileArchive())
        )) {
            assertTrue(reader.next());
            var first = reader.openInputStream();
            assertEquals(1, first.read());

            assertTrue(reader.next());
            assertThrows(ClosedChannelException.class, first::read);
            first.close();

            try (var second = reader.openInputStream()) {
                assertEquals(4, second.read());
                assertEquals(-1, second.read());
            }
            assertFalse(reader.next());
        }
    }

    /// Returns a complete archive containing one regular file.
    ///
    /// @param path the archive-local member path
    /// @param content the member body
    /// @return a complete generated AR archive
    /// @throws IOException if the fixture cannot be encoded
    private static byte[] singleFileArchive(String path, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeFile(writer, path, content);
        }
        return output.toByteArray();
    }

    /// Returns a complete archive containing two regular files of different parity.
    ///
    /// @return a complete generated AR archive
    /// @throws IOException if the fixture cannot be encoded
    private static byte[] twoFileArchive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ArArkivoStreamingWriter writer = ArArkivoStreamingWriter.open(output)) {
            writeFile(writer, "first", new byte[]{1, 2, 3});
            writeFile(writer, "second", new byte[]{4});
        }
        return output.toByteArray();
    }

    /// Writes one complete regular-file member.
    ///
    /// @param writer the open streaming writer
    /// @param path the archive-local member path
    /// @param content the complete member body
    /// @throws IOException if the member cannot be written
    private static void writeFile(ArArkivoStreamingWriter writer, String path, byte[] content) throws IOException {
        try (OutputStream body = writer.beginFile(path).openOutputStream()) {
            body.write(content);
        }
    }

    /// Input stream that fails its first close call.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream over the given bytes.
        ///
        /// @param bytes the immutable test content
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records all close attempts.
        ///
        /// @throws IOException on the first close attempt
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            super.close();
        }

        /// Returns the number of close calls.
        ///
        /// @return the number of attempted closes
        private int closeCount() {
            return closeCount;
        }
    }
}
