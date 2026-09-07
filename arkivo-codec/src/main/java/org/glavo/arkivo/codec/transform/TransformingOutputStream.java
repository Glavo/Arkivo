// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Applies a stateful preprocessing filter before forwarding bytes to a compression coder.
///
/// This stream is stateful and not safe for concurrent use. Writes may retain an incomplete transform suffix while
/// forwarding each committed prefix to the downstream stream. A transform that fills the bounded working buffer without
/// committing a prefix causes `IOException`.
///
/// [#flush()] flushes only bytes already committed by the transform. [#finish()] forwards the incomplete suffix
/// unchanged, leaves the downstream stream open, and permanently ends writes through this wrapper. [#close()] finishes
/// once and owns the downstream stream: a finish failure does not prevent a close attempt, and a failed downstream close
/// can be retried by calling `close` again.
///
/// A transform or downstream-write failure is terminal. Later writes, flushes, and finish calls rethrow that failure
/// without invoking the transform or writing buffered bytes again. Argument validation failures do not enter this state.
@NotNullByDefault
public final class TransformingOutputStream extends OutputStream {
    /// The downstream compression stream.
    private final OutputStream output;

    /// The committed-prefix and lookahead state shared by all transform adapters.
    private final TransformBuffer buffer;

    /// The reusable single-byte write buffer.
    private final byte[] singleByte = new byte[1];

    /// A deferred output or filter failure.
    private @Nullable Throwable failure;

    /// Whether all pending filter bytes have been forwarded.
    private boolean finished;

    /// Whether this stream has stopped accepting writes.
    private boolean closed;

    /// Whether the downstream stream has closed successfully.
    private boolean outputClosed;

    /// Creates a filtering output stream that owns the downstream coder stream.
    ///
    /// @param output the downstream coder stream to write and close
    /// @param transform the stateful transform to apply before writing to `output`
    public TransformingOutputStream(OutputStream output, ByteTransform transform) {
        this.output = Objects.requireNonNull(output, "output");
        this.buffer = new TransformBuffer(transform);
    }

    /// Writes one unfiltered byte.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Writes unfiltered bytes.
    ///
    /// The range is validated before lifecycle state. On failure, some bytes may already have entered the transform or
    /// downstream stream and are not rolled back.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureWritable();
        try {
            while (length > 0) {
                ByteBuffer writable = buffer.input();
                int copied = Math.min(length, writable.remaining());
                writable.put(bytes, offset, copied);
                offset += copied;
                length -= copied;
                buffer.transform();
                writeReady();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        }
    }

    /// Flushes the downstream coder while retaining an incomplete transform tail.
    ///
    /// This method remains valid after a successful [#finish()] but not after [#close()].
    @Override
    public void flush() throws IOException {
        ensureOpen();
        rethrow(failure);
        output.flush();
    }

    /// Forwards the incomplete tail unchanged without closing the downstream coder.
    ///
    /// This method is idempotent after success. Later writes fail with `IOException`.
    ///
    /// @throws IOException if this stream is closed, a prior failure is pending, or the tail cannot be written
    public void finish() throws IOException {
        ensureOpen();
        if (finished) {
            return;
        }
        rethrow(failure);
        try {
            buffer.finish();
            writeReady();
            finished = true;
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        }
    }

    /// Finishes the filter and closes the downstream compression stream.
    ///
    /// The stream remains closed for writes even if finishing or downstream close throws. When both fail, the close
    /// failure is suppressed on the finish failure; later calls retry only downstream closure.
    @Override
    public void close() throws IOException {
        @Nullable Throwable closeFailure = null;
        if (!closed) {
            try {
                finish();
            } catch (IOException | RuntimeException | Error exception) {
                closeFailure = exception;
            }
            closed = true;
        }
        if (!outputClosed) {
            try {
                output.close();
                outputClosed = true;
            } catch (IOException | RuntimeException | Error exception) {
                if (closeFailure == null) {
                    closeFailure = exception;
                } else if (closeFailure != exception) {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        rethrow(closeFailure);
    }

    /// Forwards the committed prefix and consumes it only after a successful downstream write.
    private void writeReady() throws IOException {
        ByteBuffer ready = buffer.output();
        output.write(ready.array(), ready.position(), ready.remaining());
        ready.position(ready.limit());
    }

    /// Requires this stream to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("Byte filter stream has already finished");
        }
        rethrow(failure);
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows an operation or close-time failure with its original type.
    private static void rethrow(@Nullable Throwable throwable) throws IOException {
        if (throwable == null) {
            return;
        }
        if (throwable instanceof IOException exception) {
            throw exception;
        }
        if (throwable instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) throwable;
    }
}
