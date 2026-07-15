// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/// Manages access modes, change tracking, and completion for a staged random-access body.
@NotNullByDefault
public final class StagedSeekableByteChannel implements SeekableByteChannel {
    /// Validates a proposed write before bytes are passed to the staged storage channel.
    @FunctionalInterface
    @NotNullByDefault
    public interface WriteValidator {
        /// Validates a write at the given position with the given offered byte count.
        void validate(long position, int byteCount) throws IOException;
    }

    /// Completes or discards staged state after the storage channel has been closed.
    @FunctionalInterface
    @NotNullByDefault
    public interface CompletionHandler {
        /// Handles channel completion; `commit` is true only for a successfully closed changed writable body.
        void complete(StagedSeekableByteChannel channel, boolean commit) throws IOException;
    }

    /// The seekable channel opened over staged storage.
    private final SeekableByteChannel channel;

    /// Whether reads are allowed.
    private final boolean readable;

    /// Whether writes are allowed.
    private final boolean writable;

    /// Whether every write is forced to the current end.
    private final boolean append;

    /// The optional format-specific write validator.
    private final @Nullable WriteValidator writeValidator;

    /// The format-specific completion callback.
    private final CompletionHandler completionHandler;

    /// Whether staged bytes have changed or must otherwise be committed.
    private boolean changed;

    /// Whether this wrapper remains open.
    private boolean open = true;

    /// Creates a staged random-access channel with no additional write validation.
    public StagedSeekableByteChannel(
            SeekableByteChannel channel,
            boolean readable,
            boolean writable,
            boolean append,
            boolean forceCommit,
            CompletionHandler completionHandler
    ) throws IOException {
        this(channel, readable, writable, append, forceCommit, null, completionHandler);
    }

    /// Creates a staged random-access channel with optional format-specific write validation.
    public StagedSeekableByteChannel(
            SeekableByteChannel channel,
            boolean readable,
            boolean writable,
            boolean append,
            boolean forceCommit,
            @Nullable WriteValidator writeValidator,
            CompletionHandler completionHandler
    ) throws IOException {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.readable = readable;
        this.writable = writable;
        this.append = append;
        this.writeValidator = writeValidator;
        this.completionHandler = Objects.requireNonNull(completionHandler, "completionHandler");
        if (append) {
            channel.position(channel.size());
        }
        this.changed = forceCommit;
    }

    /// Reads staged bytes from the current position.
    @Override
    public int read(ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        ensureOpen();
        if (!readable) {
            throw new NonReadableChannelException();
        }
        return channel.read(destination);
    }

    /// Writes staged bytes at the current position or at the end in append mode.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        if (append) {
            channel.position(channel.size());
        }
        WriteValidator validator = writeValidator;
        if (validator != null) {
            validator.validate(channel.position(), source.remaining());
        }
        int count = channel.write(source);
        changed |= count != 0;
        return count;
    }

    /// Returns the current staged position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        return channel.position();
    }

    /// Changes the current staged position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        channel.position(newPosition);
        return this;
    }

    /// Returns the current staged size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        return channel.size();
    }

    /// Truncates staged content and records a change when the size shrinks.
    @Override
    public SeekableByteChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        long previousSize = channel.size();
        channel.truncate(newSize);
        if (newSize < previousSize) {
            changed = true;
        }
        return this;
    }

    /// Returns whether this wrapper remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes staged storage and invokes the completion handler exactly once.
    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        @Nullable Throwable failure = null;
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            completionHandler.complete(this, failure == null && writable && changed);
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        throwFailure(failure);
    }

    /// Requires this wrapper to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Adds a secondary failure as suppressed when a primary failure already exists.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable exception) {
        if (failure == null) {
            return exception;
        }
        if (failure != exception) {
            failure.addSuppressed(exception);
        }
        return failure;
    }

    /// Throws an accumulated failure with its original category.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }
}
