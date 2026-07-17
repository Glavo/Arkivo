// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Verifies byte-transform wrappers can retry failed endpoint closure.
@NotNullByDefault
final class TransformCloseRetryTest {
    /// The transform that immediately accepts every byte without modification.
    private static final ByteTransform IDENTITY = (buffer, offset, length) -> length;

    /// Verifies readable and writable channel wrappers retry owned endpoint closure.
    @Test
    void retriesChannelEndpoints() throws IOException {
        FailingCloseWritableChannel target = new FailingCloseWritableChannel();
        TransformingWritableByteChannel output = new TransformingWritableByteChannel(
                target,
                IDENTITY,
                ResourceOwnership.OWNED
        );
        output.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertThrows(IOException.class, output::close);
        assertFalse(output.isOpen());
        assertEquals(1, target.closeCount());
        output.close();
        output.close();
        assertEquals(2, target.closeCount());
        assertArrayEquals(new byte[]{1, 2, 3}, target.bytes());

        FailingCloseReadableChannel source = new FailingCloseReadableChannel(new byte[]{4, 5, 6});
        TransformingReadableByteChannel input = new TransformingReadableByteChannel(
                source,
                IDENTITY,
                ResourceOwnership.OWNED
        );
        assertThrows(IOException.class, input::close);
        assertFalse(input.isOpen());
        assertEquals(1, source.closeCount());
        input.close();
        input.close();
        assertEquals(2, source.closeCount());
    }

    /// Verifies stream wrappers retry endpoints without repeating transform finalization.
    @Test
    void retriesStreamEndpoints() throws IOException {
        FailingCloseOutputStream target = new FailingCloseOutputStream();
        TransformingOutputStream output = new TransformingOutputStream(target, IDENTITY);
        output.write(new byte[]{1, 2, 3});
        assertThrows(IOException.class, output::close);
        assertEquals(1, target.closeCount());
        output.close();
        output.close();
        assertEquals(2, target.closeCount());
        assertArrayEquals(new byte[]{1, 2, 3}, target.bytes());

        FailingCloseInputStream source = new FailingCloseInputStream(new byte[]{4, 5, 6});
        TransformingInputStream input = new TransformingInputStream(source, IDENTITY);
        assertThrows(IOException.class, input::close);
        assertEquals(1, source.closeCount());
        input.close();
        input.close();
        assertEquals(2, source.closeCount());
    }

    /// Implements a writable channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseWritableChannel implements WritableByteChannel {
        /// Collected bytes.
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        /// Writable delegate.
        private final WritableByteChannel delegate = Channels.newChannel(bytes);

        /// Number of close attempts.
        private int closeCount;

        /// Writes bytes to the delegate.
        @Override
        public int write(ByteBuffer source) throws IOException {
            return delegate.write(source);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }

        /// Returns collected bytes.
        private byte[] bytes() {
            return bytes.toByteArray();
        }
    }

    /// Implements a readable channel that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseReadableChannel implements ReadableByteChannel {
        /// Readable delegate.
        private final ReadableByteChannel delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a channel over bytes.
        private FailingCloseReadableChannel(byte[] bytes) {
            delegate = Channels.newChannel(new ByteArrayInputStream(bytes));
        }

        /// Reads bytes from the delegate.
        @Override
        public int read(ByteBuffer target) throws IOException {
            return delegate.read(target);
        }

        /// Returns whether the delegate remains open.
        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }

    /// Implements an output stream that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseOutputStream extends OutputStream {
        /// Collected bytes.
        private final ByteArrayOutputStream delegate = new ByteArrayOutputStream();

        /// Number of close attempts.
        private int closeCount;

        /// Writes one byte.
        @Override
        public void write(int value) {
            delegate.write(value);
        }

        /// Writes a byte range.
        @Override
        public void write(byte[] bytes, int offset, int length) {
            delegate.write(bytes, offset, length);
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }

        /// Returns collected bytes.
        private byte[] bytes() {
            return delegate.toByteArray();
        }
    }

    /// Implements an input stream that fails its first close attempt.
    @NotNullByDefault
    private static final class FailingCloseInputStream extends InputStream {
        /// Readable delegate.
        private final ByteArrayInputStream delegate;

        /// Number of close attempts.
        private int closeCount;

        /// Creates a stream over bytes.
        private FailingCloseInputStream(byte[] bytes) {
            delegate = new ByteArrayInputStream(bytes);
        }

        /// Reads one byte.
        @Override
        public int read() {
            return delegate.read();
        }

        /// Reads a byte range.
        @Override
        public int read(byte[] bytes, int offset, int length) {
            return delegate.read(bytes, offset, length);
        }

        /// Fails once and closes the delegate on retry.
        @Override
        public void close() throws IOException {
            closeCount++;
            if (closeCount == 1) {
                throw new IOException("close failed");
            }
            delegate.close();
        }

        /// Returns the close-attempt count.
        private int closeCount() {
            return closeCount;
        }
    }
}
