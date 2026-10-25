// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.internal.ForceCloseable;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Replays archive probe bytes before continuing from and owning a forward-only source.
@NotNullByDefault
public final class PrefixReplayReadableByteChannel implements ReadableByteChannel, ForceCloseable {
    /// Bytes consumed during archive probing.
    private final @UnmodifiableView ByteBuffer prefix;

    /// Source positioned after the probe prefix.
    private final ReadableByteChannel source;

    /// Whether this replay channel remains open.
    private volatile boolean open = true;

    /// Whether further reads and graceful source cleanup have been disabled.
    private volatile boolean abortRequested;

    /// Creates a replay channel that owns its source.
    ///
    /// @param prefix the remaining probe bytes to replay through an independent buffer view
    /// @param source the channel, positioned after the probe bytes, whose ownership is transferred to this channel
    public PrefixReplayReadableByteChannel(ByteBuffer prefix, ReadableByteChannel source) {
        this.prefix = Objects.requireNonNull(prefix, "prefix").asReadOnlyBuffer();
        this.source = Objects.requireNonNull(source, "source");
    }

    /// Reads probe bytes before continuing from the source.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (target.isReadOnly()) {
            throw new IllegalArgumentException("Target buffer is read-only");
        }
        if (!target.hasRemaining()) {
            return 0;
        }
        if (prefix.hasRemaining()) {
            int count = Math.min(prefix.remaining(), target.remaining());
            target.put(target.position(), prefix, prefix.position(), count);
            target.position(target.position() + count);
            prefix.position(prefix.position() + count);
            return count;
        }
        return source.read(target);
    }

    /// Returns whether this replay channel and its source remain open and cancellation has not been requested.
    @Override
    public boolean isOpen() {
        return open && !abortRequested && source.isOpen();
    }

    /// Closes the source and commits closure only after success, retrying cancellation if it was requested.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        if (abortRequested) {
            forceClose();
            return;
        }
        source.close();
        if (!abortRequested) open = false;
    }

    /// Cancels the owned source without consuming the prefix or finishing unread content.
    /// Failed cancellation remains retryable through either close method; reads remain disabled.
    @Override
    public void forceClose() throws IOException {
        abortRequested = true;
        if (open) {
            ForceCloseable.forceClose(source);
            open = false;
        }
    }

    /// Requires this replay channel and its source to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!isOpen()) {
            throw new ClosedChannelException();
        }
    }
}
