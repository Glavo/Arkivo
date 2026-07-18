// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Adapts an input stream without losing close-retry behavior after an I/O failure.
@NotNullByDefault
public final class RetryingInputStreamChannel implements ReadableByteChannel {
    /// The caller-provided input stream.
    private final InputStream source;

    /// Whether this adapter remains open.
    private boolean open = true;

    /// Creates a retry-safe channel adapter.
    ///
    /// @param source the input stream owned and closed by this channel
    public RetryingInputStreamChannel(InputStream source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Performs one input-stream read and preserves zero-progress results for the channel caller.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!open) {
            throw new ClosedChannelException();
        }
        if (target.isReadOnly()) {
            throw new IllegalArgumentException("target must be writable");
        }
        if (!target.hasRemaining()) {
            return 0;
        }
        if (target.hasArray()) {
            int position = target.position();
            int read = source.read(
                    target.array(),
                    target.arrayOffset() + position,
                    target.remaining()
            );
            if (read > 0) {
                target.position(position + read);
            }
            return read;
        }

        byte[] bytes = new byte[Math.min(target.remaining(), 8192)];
        int read = source.read(bytes);
        if (read > 0) {
            target.put(bytes, 0, read);
        }
        return read;
    }

    /// Returns whether this adapter remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes the stream and commits adapter closure only after success.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        source.close();
        open = false;
    }
}
