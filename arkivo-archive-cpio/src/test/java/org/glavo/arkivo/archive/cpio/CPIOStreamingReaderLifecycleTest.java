// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies retryable CPIO streaming-reader cursor cleanup.
@NotNullByDefault
final class CPIOStreamingReaderLifecycleTest {
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

        /// Records source closure without performing further reads.
        @Override
        public void close() {
            closed = true;
        }
    }
}
