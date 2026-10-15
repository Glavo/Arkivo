// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonReadableChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/// Manages access modes, change tracking, and completion for a staged random-access body.
///
/// A failure from a storage write or truncation disables further mutations. Reads and position queries remain
/// available until close. Closing discards the body and reports an `IOException` whose cause is the mutation failure;
/// cleanup failures are suppressed on that exception. Arguments rejected by this wrapper and pre-write validation
/// failures do not discard the body. Read, position, size-query, and pre-write failures do not themselves prevent
/// publication of earlier successful changes. If close would rethrow a storage or pre-write validation exception
/// already delivered by one of these operations, it wraps that exception in a new `IOException` to prevent
/// self-suppression during automatic close.
/// This channel is not safe for concurrent use.
@NotNullByDefault
public final class StagedSeekableByteChannel implements SeekableByteChannel {
    /// Validates a proposed write before bytes are passed to the staged storage channel.
    @FunctionalInterface
    @NotNullByDefault
    public interface WriteValidator {
        /// Validates a write at the given position with the given offered byte count.
        ///
        /// @param position the staged write position
        /// @param byteCount the non-negative number of offered bytes
        /// @throws IOException if the proposed write violates a format or resource constraint
        void validate(long position, int byteCount) throws IOException;
    }

    /// Completes or discards staged state after the storage channel's close operation has been attempted.
    @FunctionalInterface
    @NotNullByDefault
    public interface CompletionHandler {
        /// Handles completion; `commit` is true only for a changed writable body with no mutation or close failure.
        ///
        /// @param channel the closed staged wrapper being completed
        /// @param commit whether changed writable content should be committed rather than discarded
        /// @throws IOException if staged state cannot be committed or discarded
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

    /// The first storage mutation failure, or `null` while the body can still be committed.
    private @Nullable Throwable mutationFailure;

    /// Exceptions delivered by non-mutating operations that cleanup must not deliver again unchanged.
    private @Nullable Set<Throwable> reportedOperationFailures;

    /// Whether this wrapper remains open.
    private volatile boolean open = true;

    /// The storage-close failure retained until completion, or `null` after successful storage close.
    private @Nullable Throwable storageCloseFailure;

    /// Whether the completion handler has been invoked.
    private boolean completed;

    /// Creates a staged random-access channel with no additional write validation.
    ///
    /// @param channel the staged storage channel owned and closed by this wrapper
    /// @param readable whether reads are permitted
    /// @param writable whether writes and truncation are permitted
    /// @param append whether each write is forced to the current end
    /// @param forceCommit whether unchanged content must still be committed on successful close
    /// @param completionHandler the callback that commits or discards staged state after the channel close attempt
    /// @throws IOException if append mode cannot query or set the initial end position
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
    ///
    /// @param channel the staged storage channel owned and closed by this wrapper
    /// @param readable whether reads are permitted
    /// @param writable whether writes and truncation are permitted
    /// @param append whether each write is forced to the current end
    /// @param forceCommit whether unchanged content must still be committed on successful close
    /// @param writeValidator the pre-write validator, or {@code null} for no additional validation
    /// @param completionHandler the callback that commits or discards staged state after the channel close attempt
    /// @throws IOException if append mode cannot query or set the initial end position
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
        try {
            return channel.read(destination);
        } catch (IOException | RuntimeException | Error failure) {
            recordOperationFailure(failure);
            throw failure;
        }
    }

    /// Writes staged bytes at the current position or at the end in append mode.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        prepareWrite(source.remaining());
        try {
            int count = channel.write(source);
            changed |= count != 0;
            return count;
        } catch (IOException | RuntimeException | Error exception) {
            mutationFailure = exception;
            throw exception;
        }
    }

    /// Checks access and validates the entire offered write before storage can be changed.
    private void prepareWrite(int byteCount) throws IOException {
        try {
            ensureOpen();
            if (!writable) {
                throw new NonWritableChannelException();
            }
            ensureMutationAllowed();
            if (append) {
                channel.position(channel.size());
            }
            @Nullable WriteValidator validator = writeValidator;
            if (validator != null) {
                validator.validate(channel.position(), byteCount);
            }
        } catch (IOException | RuntimeException | Error failure) {
            recordOperationFailure(failure);
            throw failure;
        }
    }

    /// Creates an unbuffered stream owning this channel.
    ///
    /// Each nonempty write validates the entire offered range before changing storage, then writes all bytes.
    /// Failure while completing that write, including closure between partial storage writes or a storage write
    /// making no progress, prevents publication of the body. Empty writes only validate array bounds.
    /// Flushing does nothing; closing completes this channel. The stream has the same concurrency restrictions
    /// as this channel, including the coordination required by [#completeClose()].
    ///
    /// @return a stream whose closure closes this channel
    public OutputStream newOutputStream() {
        return new StagedOutputStream();
    }

    /// Keeps all partial storage writes for one stream write inside a single failure boundary.
    @NotNullByDefault
    private final class StagedOutputStream extends OutputStream {
        /// Reusable storage for single-byte writes.
        private final byte[] singleByte = new byte[1];

        /// Creates an unbuffered view of the enclosing staged channel.
        private StagedOutputStream() {
        }

        /// Writes one byte through the same validation and failure boundary as an array write.
        @Override
        public void write(int value) throws IOException {
            singleByte[0] = (byte) value;
            write(singleByte, 0, 1);
        }

        /// Writes the full array range or prevents publication if storage fails after validation.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return;
            }
            prepareWrite(length);
            ByteBuffer source = ByteBuffer.wrap(bytes, offset, length);
            try {
                while (source.hasRemaining()) {
                    ensureOpen();
                    int count = channel.write(source);
                    changed |= count != 0;
                    if (count == 0) {
                        throw new IOException("Staged write made no progress");
                    }
                }
            } catch (IOException | RuntimeException | Error exception) {
                mutationFailure = exception;
                throw exception;
            }
        }

        /// Closes storage and completes the staged body.
        @Override
        public void close() throws IOException {
            StagedSeekableByteChannel.this.close();
        }
    }

    /// Returns the current staged position.
    @Override
    public long position() throws IOException {
        ensureOpen();
        try {
            return channel.position();
        } catch (IOException | RuntimeException | Error failure) {
            recordOperationFailure(failure);
            throw failure;
        }
    }

    /// Changes the current staged position.
    @Override
    public SeekableByteChannel position(long newPosition) throws IOException {
        ensureOpen();
        try {
            channel.position(newPosition);
        } catch (IOException | RuntimeException | Error failure) {
            recordOperationFailure(failure);
            throw failure;
        }
        return this;
    }

    /// Returns the current staged size.
    @Override
    public long size() throws IOException {
        ensureOpen();
        try {
            return channel.size();
        } catch (IOException | RuntimeException | Error failure) {
            recordOperationFailure(failure);
            throw failure;
        }
    }

    /// Truncates staged content and records a change when the size shrinks.
    @Override
    public SeekableByteChannel truncate(long newSize) throws IOException {
        ensureOpen();
        if (!writable) {
            throw new NonWritableChannelException();
        }
        if (newSize < 0L) {
            throw new IllegalArgumentException("Negative size");
        }
        ensureMutationAllowed();
        long previousSize = size();
        try {
            channel.truncate(newSize);
        } catch (IOException | RuntimeException | Error exception) {
            mutationFailure = exception;
            throw exception;
        }
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

    /// Closes staged storage and invokes the completion handler exactly once, even after a mutation failure.
    ///
    /// Subsequent calls do nothing. The content owner is responsible for retrying failed storage cleanup.
    @Override
    public void close() throws IOException {
        completeClose();
    }

    /// Closes storage without publishing or discarding the staged body.
    ///
    /// Concurrent calls wait for the same storage-close attempt. Any failure is retained for [#completeClose()];
    /// this method does not report it or invoke the completion handler. The owner must subsequently complete the
    /// close under its mutation coordination, after any active storage operations have returned.
    public synchronized void closeStorage() {
        if (!open) {
            return;
        }
        open = false;
        try {
            channel.close();
        } catch (IOException | RuntimeException | Error exception) {
            storageCloseFailure = exception;
        }
    }

    /// Completes storage closure and invokes the completion handler at most once.
    ///
    /// The caller must exclude active storage operations and coordinate changes made by the completion handler.
    /// Storage is closed first if necessary. Mutation failures, storage-close failures, and completion failures are
    /// reported in that order, with later failures suppressed. Subsequent calls do nothing.
    ///
    /// @throws IOException if a mutation, storage close, or completion failed
    public synchronized void completeClose() throws IOException {
        closeStorage();
        if (completed) {
            return;
        }
        completed = true;
        @Nullable Throwable failure = mutationFailure == null ? null : discardedBodyException();
        if (storageCloseFailure != null) {
            failure = appendFailure(failure, storageCloseFailure);
        }
        try {
            completionHandler.complete(this, failure == null && writable && changed);
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        if (failure != null && reportedOperationFailures != null && reportedOperationFailures.contains(failure)) {
            failure = new IOException("Staged body cleanup repeated an earlier operation failure", failure);
        }
        reportedOperationFailures = null;
        throwFailure(failure);
    }

    /// Remembers an exception without changing whether successfully staged bytes may be published.
    private void recordOperationFailure(Throwable failure) {
        if (completed) {
            return;
        }
        if (reportedOperationFailures == null) {
            reportedOperationFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        }
        reportedOperationFailures.add(failure);
    }

    /// Requires this wrapper to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Rejects further mutations after storage may have been partially changed by a failed operation.
    private void ensureMutationAllowed() throws IOException {
        if (mutationFailure != null) {
            throw discardedBodyException();
        }
    }

    /// Creates a distinct exception so try-with-resources cannot suppress a failure onto itself.
    private IOException discardedBodyException() {
        return new IOException("Staged body cannot be committed after a failed mutation", mutationFailure);
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
