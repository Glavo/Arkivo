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
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies retryable CPIO streaming-reader cursor cleanup.
@NotNullByDefault
final class CPIOStreamingReaderLifecycleTest {
    /// Verifies single-byte reads preserve unsigned values and an entry body cannot be reopened.
    @Test
    void readsSingleBytesFromOneShotEntryBody() throws IOException {
        byte[] content = {0, 0x7f, (byte) 0x80, (byte) 0xff};
        byte[] archive = writeFileArchive(content);

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

    /// Verifies a failed body drain can continue from its exact progress on a repeated close.
    @Test
    void retriesEntryBodyCloseAfterSourceFailure() throws IOException {
        byte[] content = new byte[32];
        byte[] archive = writeFileArchive(content);
        int bodyOffset = indexOf(archive, content);
        assertTrue(bodyOffset >= 0);
        FailOnceInputStream source = new FailOnceInputStream(archive, bodyOffset + 5);

        try (CPIOArkivoStreamingReader reader = CPIOArkivoStreamingReader.open(source)) {
            assertTrue(reader.next());
            InputStream body = reader.openInputStream();
            assertThrows(IOException.class, body::close);
            body.close();
            assertFalse(reader.next());
        }
    }

    /// Writes one CRC-protected regular file.
    private static byte[] writeFileArchive(byte @Unmodifiable [] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CPIOArkivoStreamingWriter writer = CPIOArkivoStreamingWriter.open(
                output,
                CPIOArchiveOptions.CREATE_DEFAULTS.withDialect(CPIODialect.NEW_ASCII_CRC)
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

        /// Reusable storage for single-byte reads.
        private final byte[] singleByte = new byte[1];

        /// Current source offset.
        private int offset;

        /// Whether the configured failure has already been emitted.
        private boolean failed;

        /// Creates one fail-once source over a private byte-array copy.
        private FailOnceInputStream(byte @Unmodifiable [] source, int failureOffset) {
            this.source = Objects.requireNonNull(source, "source").clone();
            if (failureOffset < 0 || failureOffset > source.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.failureOffset = failureOffset;
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
                throw new IOException("Injected CPIO source failure");
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
    }
}
