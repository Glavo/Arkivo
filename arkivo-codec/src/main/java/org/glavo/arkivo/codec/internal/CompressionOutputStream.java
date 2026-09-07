// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Writes compressed output through a channel and coordinates compression and downstream flushes.
///
/// Close attempts channel closure before flushing a borrowed downstream stream. Both operations are attempted
/// even if channel closure fails. The channel failure remains primary and a distinct downstream failure is suppressed.
@NotNullByDefault
public final class CompressionOutputStream extends OutputStream {
    /// The backing writable channel.
    private final WritableByteChannel target;

    /// Downstream stream that receives explicit flushes.
    private final OutputStream downstream;

    /// Whether close must flush a downstream stream retained by the channel.
    private final boolean flushDownstreamOnClose;

    /// The reusable single-byte source.
    private final byte[] singleByte = new byte[1];

    /// Whether this adapter remains open.
    private boolean open = true;

    /// Creates a stream that owns the channel and flushes its downstream stream.
    ///
    /// Explicit flush attempts both compression and downstream flush. Close flushes the downstream stream only
    /// when the channel borrows it. A failed close can be retried without losing either failure's identity.
    ///
    /// @param target the channel owned by this stream
    /// @param downstream the stream receiving compressed bytes
    /// @param ownership whether the channel owns or borrows the downstream stream
    public CompressionOutputStream(
            WritableByteChannel target,
            OutputStream downstream,
            ResourceOwnership ownership
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.flushDownstreamOnClose = Objects.requireNonNull(ownership, "ownership") == ResourceOwnership.BORROWED;
    }

    /// Writes one byte to the channel.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Writes all requested bytes and rejects a zero-progress channel.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        ByteBuffer source = ByteBuffer.wrap(bytes, offset, length);
        while (source.hasRemaining()) {
            if (target.write(source) == 0) {
                throw new IOException("Writable channel made no progress");
            }
        }
    }

    /// Flushes a codec encoder when the backing channel exposes compression flush semantics.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        @Nullable Throwable failure = null;
        try {
            if (target instanceof CompressingWritableByteChannel.Flushable encoder) {
                encoder.flush();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            downstream.flush();
        } catch (IOException | RuntimeException | Error exception) {
            failure = mergeFailure(failure, exception);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    /// Closes the channel, flushes a retained downstream stream, and commits closure only after success.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            target.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        if (flushDownstreamOnClose) {
            try {
                downstream.flush();
            } catch (IOException | RuntimeException | Error exception) {
                failure = mergeFailure(failure, exception);
            }
        }
        if (failure != null) {
            rethrow(failure);
        }
        open = false;
    }

    /// Requires this adapter to remain open.
    private void ensureOpen() throws IOException {
        if (!open) {
            throw new IOException("Stream closed");
        }
    }

    /// Adds a secondary failure as suppressed and retains the primary failure.
    private static Throwable mergeFailure(@Nullable Throwable primary, Throwable secondary) {
        if (primary == null) {
            return secondary;
        }
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
        return primary;
    }

    /// Rethrows a stream lifecycle failure with its original checked or unchecked type.
    private static void rethrow(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }
}
