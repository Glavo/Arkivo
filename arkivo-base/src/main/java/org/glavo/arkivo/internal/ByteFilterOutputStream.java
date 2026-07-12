// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Applies a stateful preprocessing filter before forwarding bytes to a compression coder.
@NotNullByDefault
public final class ByteFilterOutputStream extends OutputStream {
    /// The bounded working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The downstream compression stream.
    private final OutputStream output;

    /// The stateful in-place filter transform.
    private final ByteFilterTransform transform;

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

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates a filtering output stream over a downstream coder.
    public ByteFilterOutputStream(OutputStream output, ByteFilterTransform transform) {
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

    /// Flushes the downstream coder while retaining an incomplete instruction tail.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
        output.flush();
    }

    /// Forwards the incomplete tail unchanged without closing the downstream coder.
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
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        @Nullable Throwable closeFailure = null;
        try {
            finish();
        } catch (IOException | RuntimeException | Error exception) {
            closeFailure = exception;
        }
        closed = true;
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (closeFailure == null) {
                closeFailure = exception;
            } else {
                closeFailure.addSuppressed(exception);
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
