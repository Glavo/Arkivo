// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Writes a forward-only archive and prevents further output after an incomplete write or body transfer.
///
/// The first failed operation throws its original exception. Later write and flush attempts throw an `IOException`
/// with that failure as its cause without accessing the target. Invalid arguments are rejected before output begins
/// and do not disable the stream. Closing always attempts target cleanup, even after an output failure; a failed close
/// may be retried, but no writes are permitted once closing begins. This stream is not safe for concurrent use.
@NotNullByDefault
public final class ArchiveOutputStream extends OutputStream {
    /// The owned output stream.
    private final OutputStream target;

    /// The first failure that left the archive incomplete, or `null` before any such failure.
    private @Nullable Throwable failure;

    /// Bytes accepted by target writes that returned normally.
    private long bytesWritten;

    /// Whether target closure has been attempted.
    private boolean closing;

    /// Whether target closure has succeeded.
    private boolean closed;

    /// Takes ownership of the given archive target.
    ///
    /// @param target the stream closed by this wrapper
    public ArchiveOutputStream(OutputStream target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    /// Returns the bytes accepted by successful writes, excluding any partial output from a failed write.
    ///
    /// @return the non-negative completed-write byte count
    public long bytesWritten() {
        return bytesWritten;
    }

    /// Rejects output after a previous failure or after closing begins.
    ///
    /// @throws IOException if output has failed or closing has begun
    public void ensureWritable() throws IOException {
        if (failure != null) {
            throw new IOException("Archive output is incomplete after an earlier failure", failure);
        }
        if (closing) {
            throw new IOException("Archive output is closed");
        }
    }

    /// Records a failure that left a partially emitted entry or archive incomplete.
    ///
    /// The first recorded failure is retained. This method does not close the target.
    ///
    /// @param exception the failure that prevents further output
    public void fail(Throwable exception) {
        Objects.requireNonNull(exception, "exception");
        if (failure == null) {
            failure = exception;
        }
    }

    /// Writes one byte and accounts for it only if the target write succeeds.
    @Override
    public void write(int value) throws IOException {
        ensureWritable();
        long nextSize = Math.addExact(bytesWritten, 1L);
        try {
            target.write(value);
            bytesWritten = nextSize;
        } catch (IOException | RuntimeException | Error exception) {
            fail(exception);
            throw exception;
        }
    }

    /// Writes the selected bytes and accounts for them only if the target write succeeds.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureWritable();
        long nextSize = Math.addExact(bytesWritten, length);
        try {
            target.write(bytes, offset, length);
            bytesWritten = nextSize;
        } catch (IOException | RuntimeException | Error exception) {
            fail(exception);
            throw exception;
        }
    }

    /// Copies exactly the declared body size after the caller has emitted its entry header.
    ///
    /// Source failures and premature end-of-input disable further output because the entry cannot be completed.
    /// The source remains caller-owned and bytes beyond the declared size are not read.
    ///
    /// @param source the body channel positioned at its first byte
    /// @param size the non-negative number of body bytes to copy
    /// @throws IOException if reading or writing fails, or the source ends before supplying `size` bytes
    /// @throws IllegalArgumentException if `size` is negative
    public void writeBody(ReadableByteChannel source, long size) throws IOException {
        Objects.requireNonNull(source, "source");
        if (size < 0L) {
            throw new IllegalArgumentException("Negative body size");
        }
        ensureWritable();
        try {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(size, 64 * 1024L));
            long remaining = size;
            while (remaining > 0L) {
                buffer.clear();
                buffer.limit((int) Math.min(remaining, buffer.capacity()));
                int count = source.read(buffer);
                if (count < 0) {
                    throw new EOFException("Archive body ended before its declared size");
                }
                if (count != 0) {
                    write(buffer.array(), 0, count);
                    remaining -= count;
                }
            }
        } catch (IOException | RuntimeException | Error exception) {
            fail(exception);
            throw exception;
        }
    }

    /// Flushes the target, disabling further output if the flush fails.
    @Override
    public void flush() throws IOException {
        ensureWritable();
        try {
            target.flush();
        } catch (IOException | RuntimeException | Error exception) {
            fail(exception);
            throw exception;
        }
    }

    /// Returns whether the target has been closed successfully.
    ///
    /// @return `true` after a successful close, otherwise `false`
    public boolean isClosed() {
        return closed;
    }

    /// Closes the target without separately flushing it or reporting an earlier output failure again.
    ///
    /// Failed target closure may be retried. Once closure succeeds, subsequent calls do nothing.
    /// If the target throws the same exception previously reported by a write or transfer, it is wrapped in an
    /// `IOException` so try-with-resources can retain both failures without self-suppression.
    @Override
    public void close() throws IOException {
        if (!closed) {
            closing = true;
            try {
                target.close();
                closed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (exception == failure) {
                    throw new IOException("Archive target close failed after output failure", exception);
                }
                throw exception;
            }
        }
    }
}
