// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.transform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Applies a stateful preprocessing filter while reading decoded bytes.
///
/// This stream is stateful and not safe for concurrent use. Reads may block while obtaining enough lookahead for the
/// transform. At upstream end-of-input, an incomplete suffix retained by the transform is returned unchanged before this
/// stream reports `-1`.
///
/// An input or transform failure (`IOException`, `RuntimeException`, or `Error`) is retained and rethrown by later
/// nonempty reads and [#available()] without reading or transforming more data. Bytes already delivered to a destination
/// array remain there. Rejected arguments do not put this stream into a failed state. Closing
/// does not drain input; it marks this stream closed and closes the upstream stream. If upstream close fails, a later
/// [#close()] retries it.
@NotNullByDefault
public final class TransformingInputStream extends InputStream {
    /// The upstream decoded coder stream.
    private final InputStream input;

    /// The committed-prefix and lookahead state shared by all transform adapters.
    private final TransformBuffer buffer;

    /// The reusable single-byte read buffer.
    private final byte[] singleByte = new byte[1];

    /// A deferred source or filter failure.
    private @Nullable Throwable failure;

    /// Whether the upstream stream reached end-of-input.
    private boolean endReached;

    /// Whether this stream has stopped accepting reads.
    private boolean closed;

    /// Whether the upstream stream has closed successfully.
    private boolean inputClosed;

    /// Creates a filtering input stream that owns the upstream coder stream.
    ///
    /// @param input the upstream coder stream to read and close
    /// @param transform the stateful transform to apply to bytes read from `input`
    public TransformingInputStream(InputStream input, ByteTransform transform) {
        this.input = Objects.requireNonNull(input, "input");
        this.buffer = new TransformBuffer(transform);
    }

    /// Reads one filtered byte.
    @Override
    public int read() throws IOException {
        int count = read(singleByte, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    /// Reads filtered bytes into the destination array.
    ///
    /// A zero `length` returns zero after validating the range and open state. If an upstream bulk read returns zero,
    /// this method attempts one single-byte read to make progress.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        rethrowFailure();

        try {
            int total = 0;
            while (true) {
                ByteBuffer ready = buffer.output();
                int copied = Math.min(ready.remaining(), length);
                ready.get(bytes, offset, copied);
                offset += copied;
                length -= copied;
                total += copied;

                if (length == 0 || endReached) {
                    return total == 0 && endReached ? -1 : total;
                }

                ByteBuffer writable = buffer.input();
                int count = input.read(writable.array(), writable.position(), writable.remaining());
                if (count < 0) {
                    endReached = true;
                    buffer.finish();
                } else if (count == 0) {
                    int value = input.read();
                    if (value < 0) {
                        endReached = true;
                        buffer.finish();
                    } else {
                        writable.put((byte) value);
                        buffer.transform();
                    }
                } else {
                    writable.position(writable.position() + count);
                    buffer.transform();
                }
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        }
    }

    /// Returns the filtered bytes available without another upstream read.
    ///
    /// This value counts only transformed bytes already buffered by this stream and does not query upstream availability.
    @Override
    public int available() throws IOException {
        ensureOpen();
        rethrowFailure();
        return buffer.output().remaining();
    }

    /// Marks this stream closed and closes the upstream coder stream.
    ///
    /// Reads remain closed even if upstream close throws; calling this method again retries only upstream closure.
    @Override
    public void close() throws IOException {
        closed = true;
        if (inputClosed) {
            return;
        }
        input.close();
        inputClosed = true;
    }

    /// Rethrows a retained read failure without invoking the input or transform again.
    private void rethrowFailure() throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
