// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies failure aggregation and terminal transitions of internal streaming ZIP entry wrappers.
@NotNullByDefault
final class ZipStreamingEntryLifecycleTest {
    /// Verifies that known-size entry close preserves validation failure when delegate close also fails.
    @Test
    void knownSizeClosePreservesValidationFailure() throws Exception {
        byte[] content = "known size entry close failure".getBytes(StandardCharsets.UTF_8);
        InputStream input = new ZipArkivoStreamingReaderImpl.KnownSizeEntryInputStream(
                new CloseFailingInputStream(content),
                crc32(content) ^ 1L,
                content.length
        );

        IOException exception = assertThrows(IOException.class, input::close);
        assertTrue(exception.getMessage().contains("ZIP entry data does not match local header"));
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime known-size entry drain failures still close the delegate stream.
    @Test
    void knownSizeCloseReleasesDelegateAfterRuntimeDrainFailure() throws Exception {
        byte[] content = "known size runtime drain failure".getBytes(StandardCharsets.UTF_8);
        RuntimeReadFailingInputStream delegate = new RuntimeReadFailingInputStream(content, 0);
        InputStream input = new ZipArkivoStreamingReaderImpl.KnownSizeEntryInputStream(
                delegate,
                crc32(content),
                content.length
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertTrue(delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that setup cleanup runtime failures do not replace the setup failure.
    @Test
    void setupFailureSuppressesRuntimeCloseFailure() throws Exception {
        IOException failure = new IOException("setup failed");

        newReader().closeEntryAfterFailedSetup(new RuntimeCloseFailingInputStream(), failure);

        assertEquals(1, failure.getSuppressed().length);
        assertEquals(IllegalStateException.class, failure.getSuppressed()[0].getClass());
        assertEquals("close failed", failure.getSuppressed()[0].getMessage());
    }

    /// Verifies that current-entry close preserves drain failure when delegate close fails at runtime.
    @Test
    void currentEntryCloseSuppressesRuntimeDelegateFailure() throws Exception {
        ZipArkivoStreamingReaderImpl reader = newReader();
        InputStream input = new ZipArkivoStreamingReaderImpl.CurrentEntryInputStream(
                reader,
                new ReadAndRuntimeCloseFailingInputStream()
        );

        IOException exception = assertThrows(IOException.class, input::close);

        assertEquals("read failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals(IllegalStateException.class, exception.getSuppressed()[0].getClass());
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that runtime stored-descriptor failures leave the entry wrapper terminal.
    @Test
    void storedDescriptorRuntimeFailureFinishesEntry() throws Exception {
        ZipArkivoStreamingReaderImpl reader = newReader();
        InputStream input = reader.new StoredDataDescriptorInputStream(
                new PushbackInputStream(new RuntimeReadFailingInputStream(dataDescriptorSignature(), 4), 32),
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::read);

        assertEquals("read failed", exception.getMessage());
        assertEquals(-1, input.read());
        input.close();
    }

    /// Verifies that runtime encrypted stored-descriptor failures leave the entry wrapper terminal.
    @Test
    void encryptedStoredDescriptorRuntimeFailureFinishesEntry() throws Exception {
        byte[] password = "secret".getBytes(StandardCharsets.UTF_8);
        ZipArkivoStreamingReaderImpl reader = newReader();
        InputStream input = reader.new EncryptedStoredDataDescriptorInputStream(
                new PushbackInputStream(new RuntimeReadFailingInputStream(dataDescriptorSignature(), 4), 32),
                traditionalDecryptor(password),
                false
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::read);

        assertEquals("read failed", exception.getMessage());
        assertEquals(-1, input.read());
        input.close();
    }

    /// Creates an otherwise empty streaming reader used as an entry-wrapper owner.
    private static ZipArkivoStreamingReaderImpl newReader() {
        return new ZipArkivoStreamingReaderImpl(
                Channels.newChannel(InputStream.nullInputStream()),
                ZipArkivoFileSystemConfig.DEFAULTS
        );
    }

    /// Creates a traditional ZIP decryptor with a valid encrypted header.
    private static ZipTraditionalCrypto.Decryptor traditionalDecryptor(byte @Unmodifiable [] password)
            throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        try (OutputStream output = ZipTraditionalCrypto.openEncryptingStream(header, password, 0)) {
            // Closing writes the complete traditional-encryption header.
        }
        return ZipTraditionalCrypto.openDecryptor(new ByteArrayInputStream(header.toByteArray()), password, 0);
    }

    /// Returns the ZIP data descriptor signature bytes.
    private static byte @Unmodifiable [] dataDescriptorSignature() {
        return new byte[]{0x50, 0x4b, 0x07, 0x08};
    }

    /// Returns the unsigned ZIP CRC-32 value of the given content.
    private static long crc32(byte @Unmodifiable [] content) {
        CRC32 crc32 = new CRC32();
        crc32.update(content);
        return crc32.getValue();
    }

    /// Input stream that always fails when closed.
    @NotNullByDefault
    private static final class CloseFailingInputStream extends ByteArrayInputStream {
        /// Creates a close-failing input stream with the given bytes.
        private CloseFailingInputStream(byte @Unmodifiable [] bytes) {
            super(bytes);
        }

        /// Always reports a close failure.
        @Override
        public void close() throws IOException {
            throw new IOException("close failed");
        }
    }

    /// Input stream that fails at runtime when closed.
    @NotNullByDefault
    private static final class RuntimeCloseFailingInputStream extends InputStream {
        /// Creates a runtime close-failing input stream.
        private RuntimeCloseFailingInputStream() {
        }

        /// Reports end of input.
        @Override
        public int read() {
            return -1;
        }

        /// Always reports a runtime close failure.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }

    /// Input stream that fails reads with I/O and close at runtime.
    @NotNullByDefault
    private static final class ReadAndRuntimeCloseFailingInputStream extends InputStream {
        /// Creates a read- and close-failing input stream.
        private ReadAndRuntimeCloseFailingInputStream() {
        }

        /// Always reports an I/O read failure.
        @Override
        public int read() throws IOException {
            throw new IOException("read failed");
        }

        /// Always fails non-empty reads with I/O.
        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            throw new IOException("read failed");
        }

        /// Always reports a runtime close failure.
        @Override
        public void close() {
            throw new IllegalStateException("close failed");
        }
    }

    /// Input stream that fails reads at a configured offset and records closure.
    @NotNullByDefault
    private static final class RuntimeReadFailingInputStream extends InputStream {
        /// Source bytes owned by this stream.
        private final byte @Unmodifiable [] content;

        /// First source offset where reads fail.
        private final int failureOffset;

        /// Current source position.
        private int position;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a read-failing stream over a private source copy.
        private RuntimeReadFailingInputStream(byte @Unmodifiable [] content, int failureOffset) {
            if (failureOffset < 0 || failureOffset > content.length) {
                throw new IllegalArgumentException("failureOffset is out of range");
            }
            this.content = content.clone();
            this.failureOffset = failureOffset;
        }

        /// Reads one byte or reports the configured runtime failure.
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

        /// Records that this stream has been closed.
        @Override
        public void close() {
            closed = true;
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }
    }
}
