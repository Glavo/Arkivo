// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies retryable CPIO streaming-reader cursor cleanup.
@NotNullByDefault
final class CPIOStreamingReaderLifecycleTest {
    /// Final body bytes are delivered before a later padding read can fail.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deliversBodyBeforePaddingFailure(boolean direct) throws IOException {
        byte[] content = {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc};
        for (CPIODialect dialect : List.of(CPIODialect.NEW_ASCII, CPIODialect.NEW_ASCII_CRC,
                CPIODialect.OLD_BINARY)) {
            for (CPIOBinaryByteOrder byteOrder : CPIOBinaryByteOrder.values()) {
                byte[] archive = writeFileArchive(content, CPIOArchiveOptions.CREATE_DEFAULTS
                        .withDialect(dialect).withBinaryByteOrder(byteOrder));
                int bodyOffset = indexOf(archive, content);
                assertTrue(bodyOffset >= 0);
                for (Throwable failure : List.of(new IOException("padding failed"),
                        new IllegalStateException("padding failed"), new AssertionError("padding failed"))) {
                    FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + content.length, failure);
                    try (var reader = CPIOArkivoStreamingReader.open(source)) {
                        assertTrue(reader.next());
                        ReadableByteChannel body = reader.openChannel();
                        ByteBuffer target = direct ? ByteBuffer.allocateDirect(10) : ByteBuffer.allocate(10);
                        target.position(2).limit(8).mark();
                        assertEquals(content.length, body.read(target));
                        assertEquals(5, target.position());
                        assertEquals(8, target.limit());
                        byte[] actual = new byte[content.length];
                        target.duplicate().position(2).get(actual);
                        assertArrayEquals(content, actual);
                        assertEquals(bodyOffset + content.length, source.offset);
                        assertSame(failure, assertThrows(failure.getClass(), () -> body.read(target)));
                        assertEquals(5, target.position());
                        assertEquals(8, target.limit());
                        assertEquals(-1, body.read(target));
                        assertEquals(2, target.reset().position());
                        body.close();
                        assertFalse(reader.next());
                    }
                    assertTrue(source.closed);
                    assertEquals(1, source.closeCalls);
                }
            }
        }
    }

    /// CRC validation still runs at EOF, body close, and cursor advance after all body bytes have been delivered.
    @ParameterizedTest
    @ValueSource(strings = {"read", "close", "next"})
    void validatesChecksumAfterFinalBodyRead(String completion) throws IOException {
        byte[] content = {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc};
        byte[] archive = writeFileArchive(content,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC));
        int bodyOffset = indexOf(archive, content);
        assertTrue(bodyOffset >= 0);
        archive[bodyOffset] ^= 1;
        var reader = CPIOArkivoStreamingReader.open(new ByteArrayInputStream(archive));
        try {
            assertTrue(reader.next());
            ReadableByteChannel body = reader.openChannel();
            ByteBuffer target = ByteBuffer.allocateDirect(8);
            assertEquals(content.length, body.read(target));
            IOException failure = switch (completion) {
                case "read" -> assertThrows(IOException.class, () -> body.read(target));
                case "close" -> assertThrows(IOException.class, body::close);
                default -> assertThrows(IOException.class, reader::next);
            };
            assertEquals("CPIO entry data checksum mismatch", failure.getMessage());
            assertEquals(content.length, target.position());
        } finally {
            assertThrows(IOException.class, reader::close);
            reader.close();
        }
    }

    /// Verifies single-byte reads preserve unsigned values and an entry body cannot be reopened.
    @Test
    void readsSingleBytesFromOneShotEntryBody() throws IOException {
        byte[] content = {0, 0x7f, (byte) 0x80, (byte) 0xff};
        byte[] archive = writeFileArchive(content, CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC));

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(
                new ByteArrayInputStream(archive)
        )) {
            assertTrue(reader.next());
            InputStream body = reader.openInputStream();

            IllegalStateException activeBodyFailure = assertThrows(
                    IllegalStateException.class,
                    reader::openInputStream
            );
            assertEquals("Archive entry body is already open", activeBodyFailure.getMessage());
            assertEquals(0, body.read());
            assertEquals(0x7f, body.read());
            assertEquals(0x80, body.read());
            assertEquals(0xff, body.read());
            assertEquals(-1, body.read());

            body.close();
            body.close();
            IllegalStateException closedBodyFailure = assertThrows(
                    IllegalStateException.class,
                    reader::openInputStream
            );
            assertEquals("Archive entry body is already open", closedBodyFailure.getMessage());
            assertFalse(reader.next());
        }
    }

    /// Verifies body and alignment-padding drains resume after failures without losing checksum or cursor progress.
    @Test
    void retriesEntryBodyCloseAfterSourceFailure() throws IOException {
        for (CPIODialect dialect : CPIODialect.values()) {
            List<CPIOBinaryByteOrder> byteOrders = dialect == CPIODialect.OLD_BINARY
                    ? List.of(CPIOBinaryByteOrder.values()) : List.of(CPIOBinaryByteOrder.BIG_ENDIAN);
            int alignment = switch (dialect) {
                case NEW_ASCII, NEW_ASCII_CRC -> 4;
                case OLD_BINARY -> 2;
                case OLD_ASCII -> 1;
            };
            for (CPIOBinaryByteOrder byteOrder : byteOrders) {
                for (int length : new int[]{31, 32, 33}) {
                    byte[] content = new byte[length];
                    for (int index = 0; index < content.length; index++) {
                        content[index] = (byte) (0x80 + index * 3);
                    }
                    byte[] archive = writeFileArchive(content, CPIOArchiveOptions.CREATE_DEFAULTS
                            .withDialect(dialect).withBinaryByteOrder(byteOrder));
                    int bodyOffset = indexOf(archive, content);
                    assertTrue(bodyOffset >= 0);
                    int padding = (alignment - length % alignment) % alignment;
                    List<Integer> failureOffsets = new ArrayList<>(List.of(0, length / 2, length - 1));
                    if (padding > 0) {
                        failureOffsets.add(length);
                    }
                    if (padding > 1) {
                        failureOffsets.add(length + padding - 1);
                    }
                    for (int relativeOffset : failureOffsets) {
                        for (Throwable failure : List.of(new IOException("injected source failure"),
                                new IllegalStateException("injected source failure"), new AssertionError("injected source failure"))) {
                            FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + relativeOffset, failure);
                            try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(source)) {
                                assertTrue(reader.next());
                                InputStream body = reader.openInputStream();
                                assertSame(failure, assertThrows(failure.getClass(), body::close));
                                assertEquals(bodyOffset + relativeOffset, source.offset);
                                body.close();
                                assertEquals(bodyOffset + length + padding, source.offset);
                                body.close();
                                assertEquals(bodyOffset + length + padding, source.offset);
                                assertFalse(reader.next());
                                assertFalse(reader.next());
                            }
                        }
                    }
                }
            }
        }
    }

    /// Retries reader cleanup without validating discarded body state or reading a closed source.
    @Test
    void retriesReaderCloseAfterDrainFailure() throws IOException {
        byte[] content = new byte[33];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (0x80 + index);
        }
        for (CPIODialect dialect : CPIODialect.values()) {
            int alignment = switch (dialect) {
                case NEW_ASCII, NEW_ASCII_CRC -> 4;
                case OLD_BINARY -> 2;
                case OLD_ASCII -> 1;
            };
            byte[] archive = writeFileArchive(content, CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(dialect));
            int bodyOffset = indexOf(archive, content);
            assertTrue(bodyOffset >= 0);
            int padding = (alignment - content.length % alignment) % alignment;
            List<Integer> failureOffsets = new ArrayList<>(List.of(1, 16, content.length - 1));
            if (padding > 0) {
                failureOffsets.add(content.length);
            }
            if (padding > 1) {
                failureOffsets.add(content.length + padding - 1);
            }
            for (int relativeOffset : failureOffsets) {
                for (boolean failClose : new boolean[]{false, true}) {
                    for (Throwable failure : List.of(new IOException("drain failed"),
                            new IllegalStateException("drain failed"), new AssertionError("drain failed"))) {
                        FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + relativeOffset, failure);
                        source.failClose = failClose;
                        CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(source);
                        assertTrue(reader.next());
                        ReadableByteChannel body = reader.openChannel();
                        assertEquals(1, body.read(ByteBuffer.allocate(1)));
                        assertSame(failure, assertThrows(failure.getClass(), reader::close));
                        assertEquals(bodyOffset + relativeOffset, source.offset);
                        assertTrue(source.closed);
                        assertEquals(1, source.closeCalls);
                        assertEquals(failClose ? 1 : 0, failure.getSuppressed().length);
                        if (failClose) {
                            assertEquals("source close failed", failure.getSuppressed()[0].getMessage());
                        }
                        reader.close();
                        assertFalse(body.isOpen());
                        reader.close();
                        body.close();
                        assertEquals(bodyOffset + relativeOffset, source.offset);
                        assertEquals(failClose ? 2 : 1, source.closeCalls);
                        assertThrows(IOException.class, reader::next);
                    }
                }
            }
        }
    }

    /// Verifies retrying a failed drain still detects corruption both before and after the failure boundary.
    @Test
    void validatesChecksumAfterRecoverableDrainFailure() throws IOException {
        byte[] content = new byte[33];
        for (int index = 0; index < content.length; index++) {
            content[index] = (byte) (0xa0 + index);
        }
        byte[] valid = writeFileArchive(content, CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC));
        int bodyOffset = indexOf(valid, content);
        assertTrue(bodyOffset >= 0);
        for (int corruptOffset : new int[]{0, content.length - 1}) {
            for (Throwable failure : List.of(new IOException("drain failed"),
                    new IllegalStateException("drain failed"), new AssertionError("drain failed"))) {
                byte[] archive = valid.clone();
                archive[bodyOffset + corruptOffset] ^= 1;
                FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + 16, failure);
                CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(source);
                try {
                    assertTrue(reader.next());
                    InputStream body = reader.openInputStream();
                    assertSame(failure, assertThrows(failure.getClass(), body::close));
                    IOException checksumFailure = assertThrows(IOException.class, body::close);
                    assertEquals("CPIO entry data checksum mismatch", checksumFailure.getMessage());
                    assertEquals(bodyOffset + content.length, source.offset);
                    assertThrows(IOException.class, reader::next);
                } finally {
                    assertThrows(IOException.class, reader::close);
                    assertTrue(source.closed);
                    reader.close();
                    reader.close();
                    assertEquals(1, source.closeCalls);
                }
            }
        }
    }

    /// Writes one regular file using the requested CPIO wire-format settings.
    private static byte[] writeFileArchive(byte @Unmodifiable [] content, CPIOArchiveOptions.Create options) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                output,
                options
        )) {
            try (OutputStream body = writer.beginFile("payload.bin").openOutputStream()) {
                body.write(content);
            }
        }
        return output.toByteArray();
    }

    /// Finds the first occurrence of one byte sequence in another.
    private static int indexOf(byte @Unmodifiable [] haystack, byte @Unmodifiable [] needle) {
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return offset;
        }
        return -1;
    }

    /// Supplies bytes while injecting one recoverable read failure at an exact source offset.
    @NotNullByDefault
    private static final class FailOnceInputStream extends InputStream {
        /// Immutable source bytes owned by this stream.
        private final byte @Unmodifiable [] source;

        /// Source offset at which one failure is injected.
        private final int failureOffset;

        /// Checked or unchecked failure emitted at the selected offset.
        private final Throwable failure;

        /// Reusable storage for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// Current source offset.
        private int offset;

        /// Whether the configured failure has already been emitted.
        private boolean failed;

        /// Whether the owning reader has attempted source closure.
        private boolean closed;

        /// Whether the next close attempt fails after marking this source closed.
        private boolean failClose;

        /// Number of attempted source closures.
        private int closeCalls;

        /// Creates one fail-once source over a private byte-array copy.
        private FailOnceInputStream(byte @Unmodifiable [] source, int failureOffset, Throwable failure) {
            this.source = Objects.requireNonNull(source, "source").clone();
            if (failureOffset < 0 || failureOffset > source.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.failureOffset = failureOffset;
            this.failure = Objects.requireNonNull(failure, "failure");
        }

        /// Reads one byte or emits the configured failure.
        @Override
        public int read() throws IOException {
            int read = read(singleByte, 0, 1);
            return read < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads up to the failure boundary and fails once when that boundary is reached.
        @Override
        public int read(byte[] bytes, int targetOffset, int length) throws IOException {
            Objects.checkFromIndexSize(targetOffset, length, bytes.length);
            if (!failed && offset == failureOffset) {
                failed = true;
                if (failure instanceof IOException exception) {
                    throw exception;
                }
                if (failure instanceof RuntimeException exception) {
                    throw exception;
                }
                throw (Error) failure;
            }
            if (length == 0) {
                return 0;
            }
            if (offset == source.length) {
                return -1;
            }
            int count = Math.min(length, source.length - offset);
            if (!failed && offset < failureOffset) {
                count = Math.min(count, failureOffset - offset);
            }
            System.arraycopy(source, offset, bytes, targetOffset, count);
            offset += count;
            return count;
        }

        /// Records source closure and optionally reports one cleanup failure without reading more data.
        @Override
        public void close() throws IOException {
            closeCalls++;
            closed = true;
            if (failClose) {
                failClose = false;
                throw new IOException("source close failed");
            }
        }
    }
}
