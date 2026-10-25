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
/// nonempty reads, positive skips, and [#available()] without reading or transforming more data. Bytes already delivered
/// to a destination array remain there. Rejected arguments do not put this stream into a failed state. Closing
/// does not drain input; it marks this stream closed and closes the upstream stream. If upstream close fails, a later
/// [#close()] retries it.
@NotNullByDefault
public final class TransformingInputStream extends InputStream {
    /// The upstream decoded coder stream.
    private final InputStream input;

    /// The committed-prefix and lookahead state shared by all transform adapters.
    private final TransformBuffer buffer;

    /// A deferred source or filter failure.
    private volatile @Nullable Throwable failure;

    /// Whether an earlier source-close attempt failed.
    private volatile boolean closeFailed;

    /// Whether the upstream stream reached end-of-input.
    private boolean endReached;

    /// Whether this stream has stopped accepting reads.
    private volatile boolean closed;

    /// Whether the upstream stream has closed successfully.
    private volatile boolean inputClosed;

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
        ensureOpen();
        rethrowFailure();
        return fill() ? Byte.toUnsignedInt(buffer.output().get()) : -1;
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

        int total = 0;
        while (length > 0 && fill()) {
            ByteBuffer ready = buffer.output();
            int copied = Math.min(ready.remaining(), length);
            ready.get(bytes, offset, copied);
            offset += copied;
            length -= copied;
            total += copied;
        }
        return total == 0 ? -1 : total;
    }

    /// Skips filtered bytes without copying them into a discard array.
    ///
    /// Skipped input still passes through the transform, preserving its state for subsequent reads.
    /// A nonpositive count returns zero even after failure or closure. If a positive skip fails, bytes already
    /// skipped remain consumed, and later nonempty reads and positive skips rethrow the retained failure.
    ///
    /// @param count the maximum number of filtered bytes to skip
    /// @return the number of bytes skipped, possibly fewer than requested at end-of-input
    /// @throws IOException if `count` is positive and the stream is closed, or reading or transforming input fails
    @Override
    public long skip(long count) throws IOException {
        if (count <= 0) {
            return 0;
        }
        ensureOpen();
        rethrowFailure();
        long remaining = count;
        while (remaining > 0 && fill()) {
            ByteBuffer ready = buffer.output();
            int skipped = (int) Math.min(ready.remaining(), remaining);
            ready.position(ready.position() + skipped);
            remaining -= skipped;
        }
        return count - remaining;
    }

    /// Obtains a committed prefix or final suffix, retaining any source or transform failure.
    ///
    /// Buffered output is returned without reading upstream. The caller checks open and failure state first.
    private boolean fill() throws IOException {
        try {
            while (!buffer.output().hasRemaining() && !endReached) {
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
            return buffer.output().hasRemaining();
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
    /// A close failure identical to the reported read failure, or any failure from a close retry, is wrapped in an
    /// `IOException` so try-with-resources can preserve the original failure without self-suppression.
    @Override
    public synchronized void close() throws IOException {
        closed = true;
        if (inputClosed) {
            return;
        }
        try {
            input.close();
            inputClosed = true;
        } catch (IOException | RuntimeException | Error exception) {
            boolean retry = closeFailed;
            closeFailed = true;
            if (exception == failure || retry) {
                throw new IOException("Transform stream source close failed after an earlier failure", exception);
            }
            throw exception;
        }
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
