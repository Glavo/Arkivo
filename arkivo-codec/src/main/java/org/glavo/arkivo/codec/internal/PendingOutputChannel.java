// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Copies complete internal encoder writes into owned storage until caller output space is available.
///
/// This channel owns no external resource and never retains a supplied source buffer. The owning encoder controls the
/// lifetime of pending bytes through [#clear()].
@NotNullByDefault
public final class PendingOutputChannel implements WritableByteChannel {
    /// Initial pending-output capacity.
    private static final int INITIAL_CAPACITY = 8192;

    /// Pending bytes between `start` and `end`.
    private byte[] bytes = new byte[INITIAL_CAPACITY];

    /// First pending byte not yet drained.
    private int start;

    /// Position following the final pending byte.
    private int end;

    /// Whether the channel remains open for writes.
    private boolean open = true;

    /// Creates an empty pending-output channel.
    public PendingOutputChannel() {
    }

    /// Copies every remaining source byte into owned storage.
    ///
    /// @param source the source buffer, advanced to its limit
    /// @return the number of copied bytes
    /// @throws ClosedChannelException if this channel is closed
    /// @throws NullPointerException if {@code source} is {@code null}
    @Override
    public int write(ByteBuffer source) throws ClosedChannelException {
        Objects.requireNonNull(source, "source");
        if (!open) {
            throw new ClosedChannelException();
        }
        int length = source.remaining();
        ensureCapacity(length);
        source.get(bytes, end, length);
        end += length;
        return length;
    }

    /// Returns whether this channel remains open for writes.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this channel without discarding bytes already pending.
    @Override
    public void close() {
        open = false;
    }

    /// Returns whether bytes await caller output space.
    ///
    /// @return {@code true} if at least one byte remains pending
    public boolean hasRemaining() {
        return start < end;
    }

    /// Copies as many pending bytes as fit in a caller-owned target.
    ///
    /// @param target the target buffer, advanced by the copied bytes
    /// @throws NullPointerException if {@code target} is {@code null}
    public void drainTo(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        int length = Math.min(target.remaining(), end - start);
        target.put(bytes, start, length);
        start += length;
        if (start == end) {
            start = 0;
            end = 0;
        }
    }

    /// Discards every pending byte while retaining allocated storage for reuse.
    public void clear() {
        start = 0;
        end = 0;
    }

    /// Makes room for one complete write while retaining pending bytes.
    private void ensureCapacity(int additionalLength) {
        int remaining = end - start;
        if (additionalLength <= bytes.length - end) {
            return;
        }
        if (additionalLength <= bytes.length - remaining) {
            System.arraycopy(bytes, start, bytes, 0, remaining);
            start = 0;
            end = remaining;
            return;
        }

        int required = Math.addExact(remaining, additionalLength);
        int capacity = bytes.length;
        while (capacity < required) {
            int growth = capacity >>> 1;
            capacity = capacity > Integer.MAX_VALUE - growth
                    ? required
                    : Math.max(capacity + growth, required);
        }
        byte[] expanded = new byte[capacity];
        System.arraycopy(bytes, start, expanded, 0, remaining);
        bytes = expanded;
        start = 0;
        end = remaining;
    }
}
