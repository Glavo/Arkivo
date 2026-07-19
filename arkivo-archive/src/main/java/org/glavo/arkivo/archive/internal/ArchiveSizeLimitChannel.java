// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Enforces a decoded archive byte limit without retaining caller buffers.
@NotNullByDefault
public class ArchiveSizeLimitChannel implements ReadableByteChannel {
    /// The owned decoded archive channel.
    private final ReadableByteChannel delegate;

    /// The configured non-negative maximum byte count.
    private final long maximum;

    /// The number of bytes returned by successful delegate reads.
    private long count;

    /// The terminal limit failure, or `null` while the limit has not been exceeded.
    private @Nullable ArkivoReadLimitException failure;

    /// Creates a limiting channel over an owned delegate.
    ///
    /// @param delegate the decoded channel whose ownership transfers to this wrapper
    /// @param maximum the non-negative decoded byte limit
    protected ArchiveSizeLimitChannel(ReadableByteChannel delegate, long maximum) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (maximum < 0L) {
            throw new IllegalArgumentException("maximum must not be negative");
        }
        this.maximum = maximum;
    }

    /// Wraps a channel when a decoded archive size limit is configured.
    ///
    /// The returned wrapper implements [InterruptibleChannel] when `delegate` does.
    ///
    /// @param delegate the channel whose ownership transfers to the result
    /// @param maximum the non-negative decoded byte limit, or a negative value to disable limiting
    /// @return `delegate` when the limit is disabled, otherwise an owning limit wrapper
    public static ReadableByteChannel wrap(ReadableByteChannel delegate, long maximum) {
        Objects.requireNonNull(delegate, "delegate");
        if (maximum < 0L) {
            return delegate;
        }
        return delegate instanceof InterruptibleChannel
                ? new InterruptibleArchiveSizeLimitChannel(delegate, maximum)
                : new ArchiveSizeLimitChannel(delegate, maximum);
    }

    /// Reads decoded bytes and accounts for partial progress before reporting a limit failure.
    ///
    /// @param target the destination buffer
    /// @return the number of bytes read, possibly zero, or `-1` at end of input
    /// @throws ArkivoReadLimitException if this read makes the decoded byte count exceed the configured maximum
    /// @throws IOException if the delegate read fails
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        @Nullable ArkivoReadLimitException previousFailure = failure;
        if (previousFailure != null) {
            throw previousFailure;
        }
        int read = delegate.read(target);
        if (read > 0) {
            long actual = count > Long.MAX_VALUE - read ? Long.MAX_VALUE : count + read;
            count = actual;
            if (actual > maximum) {
                ArkivoReadLimitException exception = new ArkivoReadLimitException(
                        ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE,
                        maximum,
                        actual,
                        null
                );
                failure = exception;
                throw exception;
            }
        }
        return read;
    }

    /// Returns whether the owned delegate remains open.
    ///
    /// @return `true` while the delegate is open
    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    /// Closes the owned delegate.
    ///
    /// @throws IOException if delegate cleanup fails
    @Override
    public void close() throws IOException {
        delegate.close();
    }

    /// Preserves the interruptible-channel marker of an interruptible delegate.
    @NotNullByDefault
    private static final class InterruptibleArchiveSizeLimitChannel
            extends ArchiveSizeLimitChannel implements InterruptibleChannel {
        /// Creates an interruptible decoded-size wrapper.
        private InterruptibleArchiveSizeLimitChannel(ReadableByteChannel delegate, long maximum) {
            super(delegate, maximum);
        }
    }
}
