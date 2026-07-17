// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests WinZip AES encryption and decryption streams.
@NotNullByDefault
public final class ZipAesCryptoTest {
    /// The password used for WinZip AES test streams.
    private static final byte[] PASSWORD = "secret".getBytes(StandardCharsets.UTF_8);

    /// Verifies that a closed WinZip AES decrypting stream rejects further reads.
    @Test
    public void decryptingStreamReadAfterCloseIsRejected() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] content = "authenticated content".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(aes, content);

        InputStream input = ZipAesCrypto.openDecryptingStream(
                new ByteArrayInputStream(encrypted),
                aes,
                PASSWORD,
                encrypted.length
        );
        assertArrayEquals(content, input.readAllBytes());

        input.close();
        assertThrows(IOException.class, input::read);
        assertThrows(IOException.class, () -> input.read(new byte[1]));
        input.close();
    }

    /// Verifies that authentication failure during close still marks the decrypting stream as closed.
    @Test
    public void decryptingStreamFailedCloseMarksStreamClosed() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] encrypted = encrypt(aes, "tampered content".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 1;

        InputStream input = ZipAesCrypto.openDecryptingStream(
                new ByteArrayInputStream(encrypted),
                aes,
                PASSWORD,
                encrypted.length
        );

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals("WinZip AES authentication failed", exception.getMessage());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Verifies that authentication failure is preserved when the wrapped stream also fails to close.
    @Test
    public void decryptingStreamCloseFailureIsSuppressedAfterAuthenticationFailure() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] encrypted = encrypt(aes, "tampered close failure".getBytes(StandardCharsets.UTF_8));
        encrypted[encrypted.length - 1] ^= 1;
        CloseFailingOnceInputStream delegate = new CloseFailingOnceInputStream(encrypted);

        InputStream input = ZipAesCrypto.openDecryptingStream(
                delegate,
                aes,
                PASSWORD,
                encrypted.length
        );

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals("WinZip AES authentication failed", exception.getMessage());
        assertEquals(1, exception.getSuppressed().length);
        assertEquals("close failed", exception.getSuppressed()[0].getMessage());
        assertThrows(IOException.class, input::read);
        assertEquals(false, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        input.close();

        assertEquals(true, delegate.closed());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that close failures still mark the decrypting stream closed and allow cleanup retry.
    @Test
    public void decryptingStreamCloseFailureAllowsCleanupRetry() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] content = "authenticated close failure".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(aes, content);
        CloseFailingOnceInputStream delegate = new CloseFailingOnceInputStream(encrypted);

        InputStream input = ZipAesCrypto.openDecryptingStream(
                delegate,
                aes,
                PASSWORD,
                encrypted.length
        );
        assertArrayEquals(content, input.readAllBytes());

        IOException exception = assertThrows(IOException.class, input::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, input::read);
        assertEquals(false, delegate.closed());
        assertEquals(1, delegate.closeCount());

        input.close();
        input.close();

        assertEquals(true, delegate.closed());
        assertEquals(2, delegate.closeCount());
    }

    /// Verifies that authentication code write failures can be retried on close.
    @Test
    public void encryptingStreamAuthenticationCodeWriteFailureAllowsCloseRetry() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] content = "retry authentication code".getBytes(StandardCharsets.UTF_8);
        AuthenticationWriteFailingOutputStream delegate = new AuthenticationWriteFailingOutputStream();
        OutputStream output = ZipAesCrypto.openEncryptingStream(delegate, aes, PASSWORD);
        output.write(content);
        delegate.failNextWrite();

        IOException exception = assertThrows(IOException.class, output::close);
        assertEquals("write failed", exception.getMessage());
        assertThrows(IOException.class, () -> output.write(1));
        assertEquals(1, delegate.failedWriteCount());

        output.close();
        output.close();

        byte[] encrypted = delegate.toByteArray();
        try (InputStream input = ZipAesCrypto.openDecryptingStream(
                new ByteArrayInputStream(encrypted),
                aes,
                PASSWORD,
                encrypted.length
        )) {
            assertArrayEquals(content, input.readAllBytes());
        }
    }

    /// Verifies that runtime drain failures still close the wrapped stream.
    @Test
    public void decryptingStreamRuntimeDrainFailureClosesWrappedStream() throws IOException {
        ZipAesExtraField aes = ZipAesExtraField.forEncryption(ZipEncryption.WINZIP_AES_256, ZipConstants.STORED_METHOD);
        byte[] encrypted = encrypt(aes, "runtime drain failure".getBytes(StandardCharsets.UTF_8));
        ReadFailingCloseTrackingInputStream delegate = new ReadFailingCloseTrackingInputStream(
                encrypted,
                aes.saltSize() + aes.passwordVerifierSize()
        );

        InputStream input = ZipAesCrypto.openDecryptingStream(
                delegate,
                aes,
                PASSWORD,
                encrypted.length
        );

        RuntimeException exception = assertThrows(RuntimeException.class, input::close);
        assertEquals("read failed", exception.getMessage());
        assertEquals(true, delegate.closed());
        assertThrows(IOException.class, input::read);
        input.close();
    }

    /// Encrypts content with the given WinZip AES metadata.
    private static byte[] encrypt(ZipAesExtraField aes, byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream encrypted = ZipAesCrypto.openEncryptingStream(output, aes, PASSWORD)) {
            encrypted.write(content);
        }
        return output.toByteArray();
    }

    /// Input stream that fails its first close call while otherwise exposing bytes from memory.
    @NotNullByDefault
    private static final class CloseFailingOnceInputStream extends ByteArrayInputStream {
        /// Whether this stream has been closed.
        private boolean closed;

        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing input stream with the given bytes.
        private CloseFailingOnceInputStream(byte[] bytes) {
            super(bytes);
        }

        /// Fails once, then records the stream as closed.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            closed = true;
            super.close();
        }

        /// Returns whether this stream has been closed.
        private boolean closed() {
            return closed;
        }

        /// Returns the number of close calls.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Output stream that can fail the next array write before storing bytes.
    @NotNullByDefault
    private static final class AuthenticationWriteFailingOutputStream extends OutputStream {
        /// The stored output bytes.
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        /// Whether the next array write should fail.
        private boolean failNextWrite;

        /// The number of failed writes.
        private int failedWriteCount;

        /// Creates an output stream that can fail a configured array write.
        private AuthenticationWriteFailingOutputStream() {
        }

        /// Writes one byte.
        @Override
        public void write(int value) {
            output.write(value);
        }

        /// Configures the next array write to fail.
        private void failNextWrite() {
            failNextWrite = true;
        }

        /// Writes bytes unless the next write has been configured to fail.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (failNextWrite) {
                failNextWrite = false;
                failedWriteCount++;
                throw new IOException("write failed");
            }
            output.write(bytes, offset, length);
        }

        /// Returns the number of failed writes.
        private int failedWriteCount() {
            return failedWriteCount;
        }

        /// Returns the stored output bytes.
        private byte[] toByteArray() {
            return output.toByteArray();
        }
    }

    /// Fails reads at a configured offset and records whether it was closed.
    @NotNullByDefault
    private static final class ReadFailingCloseTrackingInputStream extends ByteArrayInputStream {
        /// The first offset where reads fail.
        private final int failOffset;

        /// Whether this stream has been closed.
        private boolean closed;

        /// Creates a read-failing stream with the given bytes and failure offset.
        private ReadFailingCloseTrackingInputStream(byte[] bytes, int failOffset) {
            super(bytes);
            if (failOffset < 0 || failOffset > bytes.length) {
                throw new IllegalArgumentException("failOffset is out of range");
            }
            this.failOffset = failOffset;
        }

        /// Reads one byte unless the failure offset has been reached.
        @Override
        public synchronized int read() {
            if (pos >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            return super.read();
        }

        /// Reads bytes unless the failure offset has been reached.
        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            if (pos >= failOffset) {
                throw new IllegalStateException("read failed");
            }
            return super.read(bytes, offset, Math.min(length, failOffset - pos));
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
