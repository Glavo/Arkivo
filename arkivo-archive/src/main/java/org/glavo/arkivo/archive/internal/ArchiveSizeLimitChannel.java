// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArkivoReadLimitException;
import org.glavo.arkivo.archive.ArkivoReadLimitKind;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
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

    /// The number of bytes delivered by delegate reads, including progress made before a delegate failure.
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
    /// The delegate receives at most the remaining allowance plus one probe byte. If that probe exceeds the limit,
    /// the target position reflects every byte read before the exception and its original limit is restored. Bytes
    /// placed in the target before a delegate failure remain accounted. If those bytes exceed the limit after an I/O
    /// failure other than [AsynchronousCloseException], the limit failure is reported with the delegate failure
    /// suppressed. Asynchronous closure (including interruption), runtime failures, and errors remain primary and
    /// carry the newly latched limit failure as suppressed context.
    /// After a limit failure, nonempty reads keep reporting that failure while the channel remains open. Empty reads
    /// are delegated without probing; reads after delegate closure throw [ClosedChannelException].
    ///
    /// @param target the destination buffer
    /// @return the number of bytes read, possibly zero, or `-1` at end of input
    /// @throws ArkivoReadLimitException if a nonempty read exceeds the configured maximum or a previous read exceeded it
    /// @throws IOException if the delegate read fails
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!delegate.isOpen()) {
            throw new ClosedChannelException();
        }
        if (!target.hasRemaining()) {
            return delegate.read(target);
        }
        @Nullable ArkivoReadLimitException previousFailure = failure;
        if (previousFailure != null) {
            throw previousFailure;
        }
        int initialPosition = target.position();
        int read;
        try {
            read = readWithinProbeBoundary(target);
        } catch (IOException | RuntimeException | Error exception) {
            int partialRead = target.position() - initialPosition;
            if (partialRead > 0) {
                @Nullable ArkivoReadLimitException limitFailure = account(partialRead);
                if (limitFailure != null) {
                    if (exception instanceof IOException && !(exception instanceof AsynchronousCloseException)) {
                        limitFailure.addSuppressed(exception);
                        throw limitFailure;
                    }
                    exception.addSuppressed(limitFailure);
                }
            }
            throw exception;
        }
        if (read > 0) {
            @Nullable ArkivoReadLimitException limitFailure = account(read);
            if (limitFailure != null) {
                throw limitFailure;
            }
        }
        return read;
    }

    /// Accounts for decoded bytes and returns the newly latched limit failure, if any.
    ///
    /// @param read the positive number of newly delivered bytes
    /// @return the newly latched failure, or `null` if the decoded byte count remains within the limit
    private @Nullable ArkivoReadLimitException account(int read) {
        boolean exceeded = read > maximum - count;
        long actual = count > Long.MAX_VALUE - read ? Long.MAX_VALUE : count + read;
        count = actual;
        if (!exceeded) {
            return null;
        }
        ArkivoReadLimitException exception = new ArkivoReadLimitException(
                ArkivoReadLimitKind.DECODED_ARCHIVE_SIZE,
                maximum,
                actual,
                null
        );
        failure = exception;
        return exception;
    }

    /// Reads no farther than one byte beyond the remaining allowance while preserving the caller's buffer limit.
    ///
    /// @param target the destination buffer
    /// @return the delegate read result
    /// @throws IOException if the delegate read fails
    private int readWithinProbeBoundary(ByteBuffer target) throws IOException {
        long remainingAllowance = maximum - count;
        long maximumRead = remainingAllowance == Long.MAX_VALUE
                ? Long.MAX_VALUE
                : remainingAllowance + 1L;
        int requested = (int) Math.min((long) target.remaining(), maximumRead);
        if (requested == target.remaining()) {
            return delegate.read(target);
        }

        int originalLimit = target.limit();
        target.limit(target.position() + requested);
        try {
            return delegate.read(target);
        } finally {
            target.limit(originalLimit);
        }
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
