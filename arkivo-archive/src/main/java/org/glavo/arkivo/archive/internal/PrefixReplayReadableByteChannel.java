// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Replays archive probe bytes before continuing from and owning a forward-only source.
@NotNullByDefault
public final class PrefixReplayReadableByteChannel implements ReadableByteChannel {
    /// Bytes consumed during archive probing.
    private final ByteBuffer prefix;

    /// Source positioned after the probe prefix.
    private final ReadableByteChannel source;

    /// Whether this replay channel remains open.
    private boolean open = true;

    /// Creates a replay channel that owns its source.
    ///
    /// @param prefix the remaining probe bytes to replay through an independent buffer view
    /// @param source the channel, positioned after the probe bytes, whose ownership is transferred to this channel
    public PrefixReplayReadableByteChannel(ByteBuffer prefix, ReadableByteChannel source) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").slice().asReadOnlyBuffer();
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Reads probe bytes before continuing from the source.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        if (prefix.hasRemaining()) {
            int count = Math.min(prefix.remaining(), target.remaining());
            ByteBuffer chunk = prefix.slice();
            chunk.limit(count);
            target.put(chunk);
            prefix.position(prefix.position() + count);
            return count;
        }
        return source.read(target);
    }

    /// Returns whether this replay channel and its source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes the source and commits closure only after success.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        source.close();
        open = false;
    }

    /// Requires this replay channel and its source to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }
}
