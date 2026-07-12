// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.filter;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Applies a stateful preprocessing filter while reading decoded bytes.
@NotNullByDefault
public final class ByteFilterInputStream extends InputStream {
    /// The bounded working-buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// The upstream decoded coder stream.
    private final InputStream input;

    /// The stateful in-place filter transform.
    private final ByteFilterTransform transform;

    /// The filter working buffer containing ready and pending bytes.
    private final byte[] buffer = new byte[BUFFER_SIZE];

    /// The reusable single-byte read buffer.
    private final byte[] singleByte = new byte[1];

    /// The first ready byte in `buffer`.
    private int position;

    /// The number of transformed bytes ready to return.
    private int ready;

    /// The number of trailing bytes awaiting more input.
    private int pending;

    /// A deferred source or filter failure.
    private @Nullable IOException failure;

    /// Whether the upstream stream reached end-of-input.
    private boolean endReached;

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates a filtering input stream over an upstream coder.
    public ByteFilterInputStream(InputStream input, ByteFilterTransform transform) {
        this.input = Objects.requireNonNull(input, "input");
        this.transform = Objects.requireNonNull(transform, "transform");
    }

    /// Reads one filtered byte.
    @Override
    public int read() throws IOException {
        int count = read(singleByte, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    /// Reads filtered bytes into the destination array.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        if (failure != null) {
            throw failure;
        }

        try {
            int total = 0;
            while (true) {
                int copied = Math.min(ready, length);
                System.arraycopy(buffer, position, bytes, offset, copied);
                position += copied;
                ready -= copied;
                offset += copied;
                length -= copied;
                total += copied;

                if (position + ready + pending == buffer.length) {
                    compact();
                }
                if (length == 0 || endReached) {
                    return total == 0 && endReached ? -1 : total;
                }

                int writePosition = position + ready + pending;
                int count = input.read(buffer, writePosition, buffer.length - writePosition);
                if (count < 0) {
                    endReached = true;
                    ready = pending;
                    pending = 0;
                } else if (count == 0) {
                    int value = input.read();
                    if (value < 0) {
                        endReached = true;
                        ready = pending;
                        pending = 0;
                    } else {
                        buffer[writePosition] = (byte) value;
                        filterPending(1);
                    }
                } else {
                    filterPending(count);
                }
            }
        } catch (IOException exception) {
            failure = exception;
            throw exception;
        }
    }

    /// Returns the filtered bytes available without another upstream read.
    @Override
    public int available() throws IOException {
        ensureOpen();
        if (failure != null) {
            throw failure;
        }
        return ready;
    }

    /// Closes the upstream coder stream.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        input.close();
    }

    /// Adds newly read bytes and transforms the largest complete prefix.
    private void filterPending(int added) throws IOException {
        pending += added;
        int transformed = transform.transform(buffer, position, pending);
        if (transformed < 0 || transformed > pending) {
            throw new IOException("Byte filter returned an invalid transformed byte count");
        }
        ready = transformed;
        pending -= transformed;
        if (ready == 0 && position + pending == buffer.length) {
            throw new IOException("Byte filter made no progress with a full buffer");
        }
    }

    /// Moves ready and pending bytes to the beginning of the working buffer.
    private void compact() {
        System.arraycopy(buffer, position, buffer, 0, ready + pending);
        position = 0;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
