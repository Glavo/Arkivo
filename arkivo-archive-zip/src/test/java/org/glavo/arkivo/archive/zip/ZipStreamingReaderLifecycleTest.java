// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies streaming ZIP reader cleanup and failure-preservation behavior.
@NotNullByDefault
final class ZipStreamingReaderLifecycleTest {
    /// Verifies reader close preserves entry validation failure when source close also fails.
    @Test
    void closePreservesEntryFailureWhenSourceCloseFails() throws IOException {
        byte[] content = "deflated descriptor close failure".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            try (var output = writer.beginFile("deflated.txt").openOutputStream()) {
                output.write(content);
            }
        }

        byte[] tampered = tamperLastDataDescriptorCrc(archive.toByteArray());
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(tampered);
        ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
        try {
            assertTrue(reader.next());
            InputStream input = reader.openInputStream();

            IOException exception = assertThrows(IOException.class, reader::close);
            assertTrue(exception.getMessage().contains("data descriptor does not match"));
            assertEquals(1, exception.getSuppressed().length);
            assertEquals("close failed", exception.getSuppressed()[0].getMessage());
            assertThrows(IOException.class, input::read);
            reader.close();
            reader.close();
            assertEquals(2, source.closeCount());
            assertTrue(source.closed());
        } finally {
            if (!source.closed()) {
                reader.close();
            }
        }
    }

    /// Verifies reader close still closes the source after a runtime entry drain failure.
    @Test
    void closeClosesSourceAfterRuntimeEntryFailure() throws IOException {
        byte[] name = "stored.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "runtime close failure".getBytes(StandardCharsets.UTF_8);
        byte[] archive = storedArchive(name, content);
        ReadFailingCloseTrackingInputStream source = new ReadFailingCloseTrackingInputStream(
                archive,
                30 + name.length
        );

        ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
        assertTrue(reader.next());
        InputStream entryInput = reader.openInputStream();

        RuntimeException exception = assertThrows(RuntimeException.class, reader::close);
        assertEquals("read failed", exception.getMessage());
        assertTrue(source.closed());
        assertThrows(IOException.class, entryInput::read);
        reader.close();
    }

    /// Verifies one shared entry-read and source-close failure remains primary without self-suppression.
    @Test
    void closePreservesSharedEntryAndSourceFailure() throws IOException {
        byte[] name = "stored.txt".getBytes(StandardCharsets.UTF_8);
        byte[] content = "shared close failure".getBytes(StandardCharsets.UTF_8);
        IOException sharedFailure = new IOException("shared read and close failure");
        ReadAndCloseFailingOnceInputStream source = new ReadAndCloseFailingOnceInputStream(
                storedArchive(name, content),
                30 + name.length,
                sharedFailure
        );
        ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
        assertTrue(reader.next());
        InputStream entryInput = reader.openInputStream();

        IOException exception = assertThrows(IOException.class, reader::close);

        assertSame(sharedFailure, exception);
        assertEquals(1, exception.getSuppressed().length);
        assertNotSame(sharedFailure, exception.getSuppressed()[0]);
        assertEquals("ZIP entry data does not match local header", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, entryInput::read);
        assertEquals(1, source.closeCount());

        reader.close();
        assertTrue(source.closed());
        assertEquals(2, source.closeCount());
    }

    /// Returns a minimal stored ZIP local record containing the supplied entry.
    private static byte[] storedArchive(
            byte @Unmodifiable [] name,
            byte @Unmodifiable [] content
    ) {
        ByteBuffer buffer = ByteBuffer.allocate(30 + name.length + content.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0x04034b50);
        buffer.putShort((short) 20);
        buffer.putShort((short) 0);
        buffer.putShort((short) ZipMethod.STORED.id());
        buffer.putShort((short) 0);
        buffer.putShort((short) 0);
        buffer.putInt((int) crc32(content));
        buffer.putInt(content.length);
        buffer.putInt(content.length);
        buffer.putShort((short) name.length);
        buffer.putShort((short) 0);
        buffer.put(name);
        buffer.put(content);
        return buffer.array();
    }

    /// Returns a copy of an archive with its last signed data-descriptor CRC modified.
    private static byte[] tamperLastDataDescriptorCrc(byte @Unmodifiable [] archive) {
        byte[] tampered = archive.clone();
        ByteBuffer buffer = ByteBuffer.wrap(tampered).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = tampered.length - 16; offset >= 0; offset--) {
            if (buffer.getInt(offset) == 0x08074b50) {
                tampered[offset + 4] ^= 1;
                return tampered;
            }
        }
        throw new AssertionError("data descriptor signature not found");
    }

    /// Returns the unsigned ZIP CRC-32 value of the given content.
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Fails its first close call and records cleanup state.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// Whether this input stream has been closed.
        private boolean closed;

        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream over the given bytes.
        private CloseFailingOnceInputStream(byte @Unmodifiable [] bytes) {
            super(bytes);
        }

        /// Fails on the first close call and records every attempt.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            closed = true;
            super.close();
        }

        /// Returns whether this input stream is closed.
        private boolean closed() {
            return closed;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Fails reads at a configured offset and records source closure.
    @NotNullByDefault
    private static final class ReadFailingCloseTrackingInputStream extends InputStream {
        /// Source bytes owned by this stream.
        private final byte @Unmodifiable [] content;

        /// First source offset where reads fail.
        private final int failureOffset;

        /// Current source position.
        private int position;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a stream over a private source copy.
        private ReadFailingCloseTrackingInputStream(byte @Unmodifiable [] content, int failureOffset) {
            if (failureOffset < 0 || failureOffset > content.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.content = Objects.requireNonNull(content, "content").clone();
            this.failureOffset = failureOffset;
        }

        /// Reads one byte or emits the configured runtime failure.
        @Override
        public int read() {
            if (position >= failureOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= content.length) {
                return -1;
            }
            return Byte.toUnsignedInt(content[position++]);
        }

        /// Reads bytes up to the configured failure offset.
        @Override
        public int read(byte[] buffer, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (position >= failureOffset) {
                throw new IllegalStateException("read failed");
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, Math.min(failureOffset, content.length) - position);
            if (count == 0) {
                throw new IllegalStateException("read failed");
            }
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }

        /// Records that the source was closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }
    }

    /// Reports one shared checked failure from entry reads and the first source-close attempt.
    @NotNullByDefault
    private static final class ReadAndCloseFailingOnceInputStream extends InputStream {
        /// Source bytes owned by this stream.
        private final byte @Unmodifiable [] content;

        /// First source offset where reads fail.
        private final int failureOffset;

        /// Failure shared by reads and the first close attempt.
        private final IOException failure;

        /// Current source position.
        private int position;

        /// Number of close attempts.
        private int closeCount;

        /// Whether source cleanup has completed.
        private boolean closed;

        /// Creates a stream with the supplied data, failure boundary, and shared exception.
        private ReadAndCloseFailingOnceInputStream(
                byte @Unmodifiable [] content,
                int failureOffset,
                IOException failure
        ) {
            if (failureOffset < 0 || failureOffset > content.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.content = content.clone();
            this.failureOffset = failureOffset;
            this.failure = failure;
        }

        /// Reads one byte or reports the shared failure at the configured boundary.
        @Override
        public int read() throws IOException {
            if (position >= failureOffset) {
                throw failure;
            }
            if (position >= content.length) {
                return -1;
            }
            return Byte.toUnsignedInt(content[position++]);
        }

        /// Reads bytes up to the configured failure boundary.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (position >= failureOffset) {
                throw failure;
            }
            if (position >= content.length) {
                return -1;
            }
            int count = Math.min(length, Math.min(failureOffset, content.length) - position);
            System.arraycopy(content, position, buffer, offset, count);
            position += count;
            return count;
        }

        /// Reports the shared failure once and completes cleanup on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw failure;
            }
            closed = true;
        }

        /// Returns the number of close attempts.
        private int closeCount() {
            return closeCount;
        }

        /// Returns whether source cleanup has completed.
        private boolean closed() {
            return closed;
        }
    }
}
