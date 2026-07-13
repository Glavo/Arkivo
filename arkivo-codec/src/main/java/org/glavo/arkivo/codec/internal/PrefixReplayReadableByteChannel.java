// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Replays an owned prefix before continuing from a readable channel.
@NotNullByDefault
public final class PrefixReplayReadableByteChannel implements ReadableByteChannel {
    /// Bytes consumed while probing the backing channel.
    private final ByteBuffer prefix;

    /// The channel positioned immediately after the replay prefix.
    private final ReadableByteChannel source;

    /// Whether closing this channel closes the source.
    private final ChannelOwnership ownership;

    /// Whether this channel remains open.
    private boolean open = true;

    /// Creates a prefix-replaying channel.
    public PrefixReplayReadableByteChannel(
            ByteBuffer prefix,
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").slice().asReadOnlyBuffer();
        this.source = Objects.requireNonNull(source, "source");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    /// Reads replay bytes before reading from the backing source.
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

    /// Returns whether this channel and its backing source remain open.
    @Override
    public boolean isOpen() {
        return open && source.isOpen();
    }

    /// Closes this channel and optionally closes the backing source.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        if (ownership == ChannelOwnership.CLOSE) {
            source.close();
        }
        open = false;
    }

    /// Requires this channel and its source to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }
}
