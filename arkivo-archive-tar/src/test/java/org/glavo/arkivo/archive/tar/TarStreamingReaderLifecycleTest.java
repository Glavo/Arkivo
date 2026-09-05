// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies TAR streaming-reader closure, entry-channel lifetime, and source read-ahead boundaries.
@NotNullByDefault
final class TarStreamingReaderLifecycleTest {
    /// Verifies that streaming reader operations fail as closed after the reader is closed.
    @Test
    void readerOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive()));
        assertTrue(reader.next());

        reader.close();

        assertThrows(ClosedChannelException.class, reader::next);
        assertThrows(
                ClosedChannelException.class,
                () -> reader.readAttributes(TarArkivoEntryAttributes.class)
        );
        assertThrows(ClosedChannelException.class, reader::openChannel);
    }

    /// Verifies advancing resumes body and block-padding skips without consuming the following entry header.
    @Test
    void retriesBodyAndPaddingDrainWithoutSkippingFollowingEntry() throws IOException {
        byte[] bytes = archive();
        for (int failureOffset : new int[]{1_025, 1_028, 1_029, 1_100, 1_535}) {
            for (Throwable failure : List.of(new IOException("drain failed"),
                    new IllegalStateException("drain failed"), new AssertionError("drain failed"))) {
                FailOnceInputStream source = new FailOnceInputStream(bytes, failureOffset, failure);
                try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source)) {
                    assertTrue(reader.next());
                    assertTrue(reader.next());
                    assertEquals(1_024, source.position);
                    InputStream body = reader.openInputStream();
                    assertSame(failure, assertThrows(failure.getClass(), reader::next));
                    assertEquals(failureOffset, source.position);
                    assertThrows(ClosedChannelException.class, body::read);
                    assertTrue(reader.next());
                    assertEquals("link", reader.readAttributes(TarArkivoEntryAttributes.class).path());
                    assertEquals("dir/hello.txt", reader.readAttributes(TarArkivoEntryAttributes.class).linkName());
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Verifies that source cleanup can be retried after a close failure.
    @Test
    void readerCloseRetriesSourceCleanupAfterFailure() throws IOException {
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(archive());
        TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source);
        assertTrue(reader.next());

        IOException exception = assertThrows(IOException.class, reader::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(ClosedChannelException.class, reader::next);
        assertEquals(1, source.closeCount());

        reader.close();
        reader.close();

        assertEquals(2, source.closeCount());
    }

    /// Verifies that a closed entry channel rejects reads and leaves the reader able to advance.
    @Test
    void entryChannelOperationsAfterCloseAreRejectedAsClosed() throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive()))) {
            assertTrue(reader.next());
            assertTrue(reader.next());

            ReadableByteChannel channel = reader.openChannel();
            channel.close();

            assertFalse(channel.isOpen());
            assertThrows(ClosedChannelException.class, () -> channel.read(ByteBuffer.allocate(1)));
            assertTrue(reader.next());
            assertEquals("link", reader.readAttributes(TarArkivoEntryAttributes.class).path());
        }
    }

    /// Verifies that advancing closes and drains a partially read body before parsing the following header.
    @Test
    void advancingClosesAndDrainsPartiallyReadEntryBody() throws IOException {
        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(new ByteArrayInputStream(archive()))) {
            assertTrue(reader.next());
            assertTrue(reader.next());

            InputStream body = reader.openInputStream();
            assertEquals('h', body.read());

            assertTrue(reader.next());
            assertEquals("link", reader.readAttributes(TarArkivoEntryAttributes.class).path());
            assertThrows(ClosedChannelException.class, body::read);
            body.close();
        }
    }

    /// Verifies that a padded final TAR block is consumed without reading the caller-owned trailer.
    @Test
    void stopsCallerOwnedSourceAtTrailerAfterPaddedFinalBlock() throws IOException {
        byte[] content = "archive body".getBytes(StandardCharsets.UTF_8);
        byte[] trailer = "Hello, world!\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(archive)) {
            writeFile(writer, "file.txt", content);
        }
        if (archive.size() > 10_240) {
            throw new AssertionError("Generated TAR fixture exceeds one record");
        }
        archive.write(new byte[10_240 - archive.size()]);
        archive.write(trailer);
        ByteArrayInputStream source = new ByteArrayInputStream(archive.toByteArray());

        try (TarArkivoStreamingReader reader = TarArkivoStreamingReader.open(source)) {
            assertTrue(reader.next());
            try (var body = reader.openInputStream()) {
                assertArrayEquals(content, body.readAllBytes());
            }
            assertFalse(reader.next());
            assertArrayEquals(trailer, source.readAllBytes());
        }
    }

    /// Returns a small TAR archive containing a directory, regular file, and symbolic link.
    ///
    /// @return a complete generated TAR stream
    /// @throws IOException if the fixture cannot be encoded
    private static byte[] archive() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (TarArkivoStreamingWriter writer = TarArkivoStreamingWriter.open(output)) {
            writer.beginDirectory("dir").close();
            writeFile(writer, "dir/hello.txt", "hello".getBytes(StandardCharsets.UTF_8));
            writer.beginSymbolicLink("link", "dir/hello.txt").close();
        }
        return output.toByteArray();
    }

    /// Writes one complete regular-file entry through the streaming writer.
    ///
    /// @param writer the open streaming writer
    /// @param path the archive-local path
    /// @param content the complete entry content
    /// @throws IOException if the entry cannot be written
    private static void writeFile(TarArkivoStreamingWriter writer, String path, byte[] content) throws IOException {
        try (OutputStream body = writer.beginFile(path).openOutputStream()) {
            body.write(content);
        }
    }

    /// Supplies a byte array while failing once at an exact read boundary.
    @NotNullByDefault
    private static final class FailOnceInputStream extends InputStream {
        /// Immutable source bytes retained for this test.
        private final byte @Unmodifiable [] bytes;

        /// Offset at which the configured failure is emitted.
        private final int failureOffset;

        /// Failure emitted at the boundary.
        private final Throwable failure;

        /// Current physical source offset.
        private int position;

        /// Whether the failure has already been emitted.
        private boolean failed;

        /// Creates a source with one recoverable boundary failure.
        private FailOnceInputStream(byte[] bytes, int failureOffset, Throwable failure) {
            this.bytes = bytes.clone();
            this.failureOffset = failureOffset;
            this.failure = failure;
        }

        /// Checks the current failure boundary before consuming input.
        private void checkFailure() throws IOException {
            if (!failed && position == failureOffset) {
                failed = true;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
        }

        /// Reads one unsigned source byte.
        @Override
        public int read() throws IOException {
            checkFailure();
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        /// Reads no farther than the configured failure boundary.
        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            checkFailure();
            if (position == bytes.length) {
                return -1;
            }
            int count = Math.min(length, bytes.length - position);
            if (!failed) {
                count = Math.min(count, failureOffset - position);
            }
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
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
