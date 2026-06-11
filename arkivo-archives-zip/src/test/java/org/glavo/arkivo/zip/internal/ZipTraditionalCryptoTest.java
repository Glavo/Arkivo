// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

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

/// Tests traditional ZIP encryption and decryption streams.
@NotNullByDefault
public final class ZipTraditionalCryptoTest {
    /// The password used for traditional ZIP test streams.
    private static final byte[] PASSWORD = "secret".getBytes(StandardCharsets.UTF_8);

    /// The traditional ZIP password verification byte used by test streams.
    private static final int VERIFICATION_BYTE = 0x42;

    /// Verifies that a closed traditional ZIP decrypting stream rejects further reads.
    @Test
    public void decryptingStreamReadAfterCloseIsRejected() throws IOException {
        byte[] content = "encrypted content".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = encrypt(content);

        InputStream input = ZipTraditionalCrypto.openDecryptingStream(
                new ByteArrayInputStream(encrypted),
                PASSWORD,
                VERIFICATION_BYTE
        );
        assertArrayEquals(content, input.readAllBytes());

        input.close();
        assertThrows(IOException.class, input::read);
        assertThrows(IOException.class, () -> input.read(new byte[1]));
        input.close();
    }

    /// Verifies that close failures still mark the traditional ZIP decrypting stream closed and allow cleanup retry.
    @Test
    public void decryptingStreamCloseFailureAllowsCleanupRetry() throws IOException {
        CloseFailingOnceInputStream delegate =
                new CloseFailingOnceInputStream(encrypt("content".getBytes(StandardCharsets.UTF_8)));
        InputStream input = ZipTraditionalCrypto.openDecryptingStream(
                delegate,
                PASSWORD,
                VERIFICATION_BYTE
        );

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

    /// Verifies that a closed traditional ZIP encrypting stream rejects further writes.
    @Test
    public void encryptingStreamWriteAfterCloseIsRejected() throws IOException {
        OutputStream output = ZipTraditionalCrypto.openEncryptingStream(
                new ByteArrayOutputStream(),
                PASSWORD,
                VERIFICATION_BYTE
        );
        output.write(1);

        output.close();
        assertThrows(IOException.class, () -> output.write(2));
        assertThrows(IOException.class, () -> output.write(new byte[]{3}));
        output.close();
    }

    /// Verifies that close failures still mark the traditional ZIP encrypting stream closed and allow cleanup retry.
    @Test
    public void encryptingStreamCloseFailureAllowsCleanupRetry() throws IOException {
        CloseFailingOnceOutputStream delegate = new CloseFailingOnceOutputStream();
        OutputStream output = ZipTraditionalCrypto.openEncryptingStream(
                delegate,
                PASSWORD,
                VERIFICATION_BYTE
        );

        IOException exception = assertThrows(IOException.class, output::close);
        assertEquals("close failed", exception.getMessage());
        assertThrows(IOException.class, () -> output.write(1));
        assertEquals(false, delegate.closed());
        assertEquals(1, delegate.closeCount());

        output.close();
        output.close();

        assertEquals(true, delegate.closed());
        assertEquals(2, delegate.closeCount());
    }

    /// Encrypts content with traditional ZIP encryption.
    private static byte[] encrypt(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream encrypted = ZipTraditionalCrypto.openEncryptingStream(output, PASSWORD, VERIFICATION_BYTE)) {
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

    /// Output stream that fails its first close call while otherwise storing bytes in memory.
    @NotNullByDefault
    private static final class CloseFailingOnceOutputStream extends ByteArrayOutputStream {
        /// Whether this stream has been closed.
        private boolean closed;

        /// The number of close calls.
        private int closeCount;

        /// Creates a close-failing output stream.
        private CloseFailingOnceOutputStream() {
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
}
