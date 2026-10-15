// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Random;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies streaming ZIP reader cleanup and failure-preservation behavior.
@NotNullByDefault
final class ZipStreamingReaderLifecycleTest {
    /// Delivers completed stored bytes before reporting a later source failure, without losing decryption state.
    @ParameterizedTest
    @EnumSource(ZipEncryption.class)
    void preservesStoredDescriptorReadProgress(ZipEncryption encryption) throws IOException {
        byte[] content = new byte[100];
        new Random(37L).nextBytes(content);
        var password = ArkivoPasswordProvider.fixed(new byte[]{'p'});
        byte[] archive = storedDescriptorArchive(encryption, content);
        ByteBuffer header = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
        int bodyOffset = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
        assertTrue((header.getShort(6) & 8) != 0, "The entry must use a data descriptor");
        for (boolean direct : new boolean[]{false, true}) {
            for (Throwable failure : new Throwable[]{new IOException("source failed"),
                    new IllegalStateException("source failed"), new AssertionError("source failed")}) {
                var source = new ReadFailingOnceInputStream(archive, bodyOffset + 64, failure);
                try (var reader = ZipArkivoStreamingReader.open(source,
                        ZipArchiveOptions.READ_DEFAULTS.withPasswordProvider(password))) {
                    assertTrue(reader.next());
                    var body = reader.openChannel();
                    ByteBuffer buffer = direct ? ByteBuffer.allocateDirect(160) : ByteBuffer.allocate(160);
                    buffer.position(3).limit(153).mark();
                    int first = body.read(buffer);
                    assertTrue(first > 0 && first < content.length);
                    assertEquals(3 + first, buffer.position());
                    int position = buffer.position();
                    assertEquals(0, body.read(ByteBuffer.allocate(0)));
                    assertSame(failure, assertThrows(failure.getClass(), () -> body.read(buffer)));
                    assertEquals(position, buffer.position());
                    assertEquals(153, buffer.limit());
                    while (body.read(buffer) >= 0) {
                        // Continue after the single source failure and validate the descriptor at EOF.
                    }
                    assertEquals(3 + content.length, buffer.position());
                    buffer.reset();
                    byte[] actual = new byte[content.length];
                    buffer.get(actual);
                    assertArrayEquals(content, actual);
                    body.close();
                    assertFalse(reader.next());
                }
            }
        }
    }

    /// Reports a deferred stored-body failure when the caller reads one byte, closes, or advances instead of bulk reading.
    @ParameterizedTest
    @ValueSource(strings = {"read-one", "close", "next", "reader-close"})
    void reportsDeferredStoredReadFailure(String operation) throws IOException {
        byte[] content = new byte[100];
        for (ZipEncryption encryption : ZipEncryption.values()) {
            byte[] archive = storedDescriptorArchive(encryption, content);
            ByteBuffer header = ByteBuffer.wrap(archive).order(ByteOrder.LITTLE_ENDIAN);
            int bodyOffset = 30 + Short.toUnsignedInt(header.getShort(26)) + Short.toUnsignedInt(header.getShort(28));
            for (Throwable failure : new Throwable[]{new IOException("source failed"),
                    new IllegalStateException("source failed"), new AssertionError("source failed")}) {
                var source = new ReadFailingOnceInputStream(archive, bodyOffset + 64, failure);
                try (var reader = ZipArkivoStreamingReader.open(source, ZipArchiveOptions.READ_DEFAULTS
                        .withPasswordProvider(ArkivoPasswordProvider.fixed(new byte[]{'p'})))) {
                    assertTrue(reader.next());
                    InputStream body = reader.openInputStream();
                    byte[] buffer = new byte[150];
                    assertTrue(body.read(buffer) > 0);
                    assertEquals(0, body.read(buffer, 0, 0));
                    assertSame(failure, assertThrows(failure.getClass(), () -> {
                        switch (operation) {
                            case "read-one" -> body.read();
                            case "close" -> body.close();
                            case "next" -> reader.next();
                            case "reader-close" -> reader.close();
                            default -> throw new AssertionError(operation);
                        }
                    }));
                    reader.close();
                    reader.close();
                }
            }
        }
    }

    /// Stops consuming compressed input after a size limit fails, including nested close-time cleanup.
    @ParameterizedTest
    @ValueSource(strings = {"read", "close", "next", "reader-close", "next-unopened"})
    void stopsDrainingAfterSizeLimitFailure(String operation) throws IOException {
        byte[] content = new byte[256 * 1024];
        new Random(17L).nextBytes(content);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipArkivoStreamingWriter writer = ZipArkivoStreamingWriter.open(archive)) {
            try (var output = writer.beginFile("large.bin").openOutputStream()) {
                output.write(content);
            }
        }
        for (boolean total : new boolean[]{false, true}) {
            ArchiveReadLimits limits = total
                    ? ArchiveReadLimits.builder().maximumTotalEntrySize(8L).build()
                    : ArchiveReadLimits.builder().maximumEntrySize(8L).build();
            ByteArrayInputStream source = new ByteArrayInputStream(archive.toByteArray());
            ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source,
                    new ZipArchiveOptions.Read(ArchiveReadOptions.DEFAULT.withLimits(limits)));
            try {
                assertTrue(reader.next());
                if (operation.equals("next-unopened")) {
                    assertThrows(ArkivoReadLimitException.class, reader::next);
                } else {
                    InputStream input = reader.openInputStream();
                    assertThrows(ArkivoReadLimitException.class, () -> {
                        switch (operation) {
                            case "read" -> input.readAllBytes();
                            case "close" -> input.close();
                            case "next" -> reader.next();
                            case "reader-close" -> reader.close();
                            default -> throw new AssertionError(operation);
                        }
                    });
                    int remaining = source.available();
                    try {
                        input.close();
                    } catch (ArkivoReadLimitException expected) {
                    }
                    assertEquals(remaining, source.available(), "Entry close must not consume input after rejection");
                }
                assertTrue(source.available() > content.length / 2, "Limit failure must not drain the entry");
                int remaining = source.available();
                reader.close();
                reader.close();
                assertEquals(remaining, source.available());
            } finally {
                reader.close();
            }
        }
    }

    /// Keeps a size-limit failure primary while allowing a failed source close to be retried without further reads.
    @Test
    void retriesSourceCloseWithoutDrainingRejectedEntry() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (var writer = ZipArkivoStreamingWriter.open(archive)) {
            try (var output = writer.beginFile("large.bin").openOutputStream()) {
                byte[] bytes = new byte[64 * 1024];
                new Random(19L).nextBytes(bytes);
                output.write(bytes);
            }
        }
        var source = new CloseFailingOnceInputStream(archive.toByteArray());
        var options = new ZipArchiveOptions.Read(ArchiveReadOptions.DEFAULT
                .withLimits(ArchiveReadLimits.builder().maximumEntrySize(8L).build()));
        try (var reader = ZipArkivoStreamingReader.open(source, options)) {
            assertTrue(reader.next());
            var input = reader.openInputStream();
            assertThrows(ArkivoReadLimitException.class, input::readAllBytes);
            int remaining = source.available();
            ArkivoReadLimitException failure = assertThrows(ArkivoReadLimitException.class, reader::close);
            assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                    .anyMatch(exception -> "close failed".equals(exception.getMessage())));
            assertEquals(remaining, source.available());
            reader.close();
            reader.close();
            assertEquals(2, source.closeCount());
            assertTrue(source.closed());
            assertEquals(remaining, source.available());
        }
    }

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
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void closePreservesSharedEntryAndSourceFailure(boolean opened) throws IOException {
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
        @Nullable InputStream entryInput = opened ? reader.openInputStream() : null;

        IOException exception = assertThrows(IOException.class, reader::close);

        assertSame(sharedFailure, exception);
        assertEquals(opened ? 2 : 1, exception.getSuppressed().length);
        Throwable sourceCleanup = exception.getSuppressed()[opened ? 1 : 0];
        assertEquals(IOException.class, sourceCleanup.getClass());
        assertSame(sharedFailure, sourceCleanup.getCause());
        if (entryInput != null) {
            assertNotSame(sharedFailure, exception.getSuppressed()[0]);
            assertEquals("ZIP entry data does not match local header", exception.getSuppressed()[0].getMessage());
            assertThrows(IOException.class, entryInput::read);
        }
        assertEquals(1, source.closeCount());
        int failurePosition = source.position;

        reader.close();
        assertTrue(source.closed());
        assertEquals(2, source.closeCount());
        reader.close();
        assertEquals(2, source.closeCount());
        assertEquals(failurePosition, source.position);
    }

    /// Retries source cleanup without reconstructing an unopened encrypted entry after setup has failed.
    @Test
    void retriesCloseAfterUnopenedEntrySetupFailure() throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        var options = ZipArchiveOptions.CREATE_DEFAULTS
                .withDefaultEncryption(ZipEncryption.ZIP_CRYPTO)
                .withPasswordProvider(ArkivoPasswordProvider.fixed(new byte[]{'p'}));
        try (var writer = ZipArkivoStreamingWriter.open(archive, options)) {
            try (var body = writer.beginFile("secret").openOutputStream()) {
                body.write(new byte[]{1, 2, 3});
            }
        }
        CloseFailingOnceInputStream source = new CloseFailingOnceInputStream(archive.toByteArray());
        ZipArkivoStreamingReader reader = ZipArkivoStreamingReader.open(source);
        assertTrue(reader.next());
        IOException failure = assertThrows(IOException.class, reader::close);
        assertEquals("ZIP entry requires a password: secret", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
        int remaining = source.available();
        reader.close();
        reader.close();
        assertTrue(source.closed());
        assertEquals(2, source.closeCount());
        assertEquals(remaining, source.available());
        assertThrows(IOException.class, reader::next);
    }

    /// Creates a stored entry with a signed descriptor and the selected encryption, using password `p` when needed.
    private static byte[] storedDescriptorArchive(ZipEncryption encryption, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var writer = ZipArkivoStreamingWriter.open(output, ZipArchiveOptions.CREATE_DEFAULTS
                .withDefaultEncryption(encryption)
                .withPasswordProvider(ArkivoPasswordProvider.fixed(new byte[]{'p'})))) {
            var entry = writer.beginFile("file");
            entry.attributeView(ZipArkivoEntryAttributeView.class).setMethod(ZipMethod.STORED);
            try (var body = entry.openOutputStream()) {
                body.write(content);
            }
        }
        return output.toByteArray();
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

    /// Fails one read at an exact byte boundary without consuming input in the failing call.
    @NotNullByDefault
    private static final class ReadFailingOnceInputStream extends InputStream {
        /// The complete source bytes.
        private final byte @Unmodifiable [] bytes;

        /// The boundary at which to throw the configured failure once.
        private final int failureOffset;

        /// The failure to deliver without replacing its identity or type.
        private final Throwable failure;

        /// The next source byte to read.
        private int position;

        /// Whether the configured failure has been delivered.
        private boolean failed;

        /// Creates a source with a one-shot failure boundary.
        private ReadFailingOnceInputStream(byte @Unmodifiable [] bytes, int failureOffset, Throwable failure) {
            this.bytes = bytes;
            this.failureOffset = failureOffset;
            this.failure = failure;
        }

        /// Reads one byte or reports the configured failure.
        @Override
        public int read() throws IOException {
            if (!failed && position == failureOffset) {
                failed = true;
                if (failure instanceof IOException exception) {
                    throw exception;
                } else if (failure instanceof RuntimeException exception) {
                    throw exception;
                } else {
                    throw (Error) failure;
                }
            }
            return position < bytes.length ? Byte.toUnsignedInt(bytes[position++]) : -1;
        }

        /// Reads a batch without crossing the configured failure boundary.
        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) {
                return 0;
            }
            int first = read();
            if (first < 0) {
                return -1;
            }
            target[offset] = (byte) first;
            int count = Math.min(length - 1, (failed ? bytes.length : failureOffset) - position);
            System.arraycopy(bytes, position, target, offset + 1, count);
            position += count;
            return count + 1;
        }
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
