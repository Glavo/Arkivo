// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
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
@NotNullByDefault
public final class TransformingOutputStream extends OutputStream {
    /// The bounded working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The downstream compression stream.
    private final OutputStream output;

    /// The stateful in-place filter transform.
    private final ByteTransform transform;

    /// The filter working buffer.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The reusable single-byte write buffer.
    private final byte[] singleByte = new byte[1];

    /// The first pending byte in `buffer`.
    private int position;

    /// The number of bytes awaiting enough lookahead for filtering.
    private int pending;

    /// A deferred output or filter failure.
    private @Nullable IOException failure;

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
        this.transform = Objects.requireNonNull(transform, "transform");
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
        while (length > 0) {
            int copied = Math.min(length, buffer.length - position - pending);
            System.arraycopy(bytes, offset, buffer, position + pending, copied);
            offset += copied;
            length -= copied;
            pending += copied;
            filterPending();
            if (position + pending == buffer.length) {
                compact();
                if (pending == buffer.length) {
                    throw new IOException("Byte filter made no progress with a full buffer");
                }
            }
        }
    }

    /// Flushes the downstream coder while retaining an incomplete transform tail.
    ///
    /// This method remains valid after a successful [#finish()] but not after [#close()].
    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
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
        if (failure != null) {
            throw failure;
        }
        try {
            output.write(buffer, position, pending);
            position = 0;
            pending = 0;
            finished = true;
        } catch (IOException exception) {
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
                } else {
                    closeFailure.addSuppressed(exception);
                }
            }
        }
        rethrow(closeFailure);
    }

    /// Transforms and forwards the largest complete prefix of pending bytes.
    private void filterPending() throws IOException {
        int transformed = transform.transform(buffer, position, pending);
        if (transformed < 0 || transformed > pending) {
            throw new IOException("Byte filter returned an invalid transformed byte count");
        }
        try {
            output.write(buffer, position, transformed);
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }
        position += transformed;
        pending -= transformed;
    }

    /// Moves pending bytes to the beginning of the working buffer.
    private void compact() {
        System.arraycopy(buffer, position, buffer, 0, pending);
        position = 0;
    }

    /// Requires this stream to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("Byte filter stream has already finished");
        }
        if (failure != null) {
            throw failure;
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows a close-time failure with its original type.
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
