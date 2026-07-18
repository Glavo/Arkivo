// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileAttributeView;
import java.util.Objects;

/// Writes archive entries to a forward-only target.
///
/// Each begin method returns a handle that keeps metadata configuration and entry completion scoped to one pending
/// entry. Closing a handle commits an entry without a body. Opening a body transfers completion to the returned stream
/// or channel; the writer remains occupied until that body closes successfully.
///
/// Entry operations throw `ClosedChannelException` after closing begins, including after a failed close attempt.
@NotNullByDefault
public abstract class ArkivoStreamingWriter implements Closeable {
    /// Sequence number used to invalidate completed entry handles.
    private long entrySequence;

    /// Handle for the pending entry, or null when another entry may begin.
    private @Nullable CurrentEntry currentEntry;

    /// Whether this writer rejects all further entry operations.
    private boolean closed;

    /// Creates a streaming archive writer base instance.
    protected ArkivoStreamingWriter() {
    }

    /// Begins a pending regular file entry for the given logical archive path.
    public final Entry beginFile(String path) throws IOException {
        return beginEntry(path, this::beginFileEntry);
    }

    /// Begins a pending directory entry for the given logical archive path.
    public final Entry beginDirectory(String path) throws IOException {
        return beginEntry(path, this::beginDirectoryEntry);
    }

    /// Begins a pending symbolic link entry for the given logical archive path and target.
    public final Entry beginSymbolicLink(String path, String target) throws IOException {
        String checkedTarget = Objects.requireNonNull(target, "target");
        return beginEntry(path, checkedPath -> beginSymbolicLinkEntry(checkedPath, checkedTarget));
    }

    /// Begins one subclass-defined pending entry and returns its scoped handle.
    protected final Entry beginCustomEntry(String path, EntryInitializer initializer) throws IOException {
        return beginEntry(path, initializer);
    }

    /// Begins one pending entry through the given initializer.
    private Entry beginEntry(String path, EntryInitializer initializer) throws IOException {
        String checkedPath = Objects.requireNonNull(path, "path");
        EntryInitializer checkedInitializer = Objects.requireNonNull(initializer, "initializer");
        requireOpen();
        if (currentEntry != null) {
            throw new IllegalStateException("An archive entry is already pending");
        }
        checkedInitializer.initialize(checkedPath);
        entrySequence++;
        CurrentEntry entry = new CurrentEntry(checkedPath, entrySequence);
        currentEntry = entry;
        return entry;
    }

    /// Begins a format-specific regular file entry.
    protected abstract void beginFileEntry(String path) throws IOException;

    /// Begins a format-specific directory entry.
    protected abstract void beginDirectoryEntry(String path) throws IOException;

    /// Begins a format-specific symbolic link entry.
    protected abstract void beginSymbolicLinkEntry(String path, String target) throws IOException;

    /// Returns a format-specific pending entry attribute view.
    protected abstract <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type);

    /// Commits the current format-specific entry without a body channel.
    protected abstract void finishCurrentEntry() throws IOException;

    /// Opens the current format-specific entry body.
    protected abstract WritableByteChannel openCurrentChannel() throws IOException;

    /// Marks this writer closed, finishes the archive stream, and releases format-specific resources.
    ///
    /// Repeated calls allow format-specific cleanup to finish after an earlier close failure.
    @Override
    public final void close() throws IOException {
        closed = true;

        @Nullable Throwable failure = null;
        CurrentEntry entry = currentEntry;
        if (entry != null) {
            try {
                entry.closeFromWriter();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }

        try {
            closeWriter();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }

        CurrentEntry remainingEntry = currentEntry;
        if (remainingEntry != null) {
            remainingEntry.invalidate();
            currentEntry = null;
            entrySequence++;
        }

        if (failure != null) {
            throwCloseFailure(failure);
        }
    }

    /// Finishes the format-specific archive and releases its resources.
    ///
    /// Implementations must tolerate repeated calls after successful cleanup and retry incomplete cleanup after failure.
    protected abstract void closeWriter() throws IOException;

    /// Requires a still-current pending entry on an open writer.
    private void requireCurrentEntry(CurrentEntry entry, long sequence) throws ClosedChannelException {
        requireOpen();
        if (entry.isClosedForOperations()) {
            throw new ClosedChannelException();
        }
        if (entry != currentEntry || sequence != entrySequence) {
            throw new IllegalStateException("Archive entry is no longer pending");
        }
        if (!entry.isConfigurable()) {
            throw new IllegalStateException("Archive entry body is already open");
        }
    }

    /// Rethrows one failure captured while closing the entry and format writer.
    private static void throwCloseFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Requires this writer to remain open for entry operations.
    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Initializes one format-specific pending entry for a checked logical path.
    @NotNullByDefault
    @FunctionalInterface
    protected interface EntryInitializer {
        /// Initializes one pending entry.
        void initialize(String path) throws IOException;
    }

    /// Configures and commits one pending forward-only archive entry.
    @NotNullByDefault
    public interface Entry extends Closeable {
        /// Returns the normalized archive-local path supplied when the entry began.
        String path();

        /// Returns a supported mutable attribute view for this entry, or null when unsupported.
        <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) throws IOException;

        /// Opens this entry body as a writable channel and commits its metadata.
        ///
        /// The writer cannot begin another entry until the returned channel closes successfully.
        WritableByteChannel openChannel() throws IOException;

        /// Opens this entry body as an output stream and commits its metadata.
        ///
        /// The writer cannot begin another entry until the returned stream closes successfully.
        OutputStream openOutputStream() throws IOException;

        /// Commits this entry without a body.
        ///
        /// Repeated calls retry an incomplete commit and otherwise have no effect.
        @Override
        void close() throws IOException;
    }

    /// Delegates one pending entry handle to its writer cursor.
    @NotNullByDefault
    private final class CurrentEntry implements Entry {
        /// Pending archive-local path.
        private final String path;

        /// Cursor sequence identifying this pending entry.
        private final long sequence;

        /// Whether committing this handle without a body has begun.
        private boolean closing;

        /// Body channel that owns completion after opening, or null before a body opens.
        private @Nullable EntryBodyChannel bodyChannel;

        /// Whether this handle has completed successfully or was invalidated with the writer.
        private boolean completed;

        /// Creates a handle for one pending cursor position.
        private CurrentEntry(String path, long sequence) {
            this.path = path;
            this.sequence = sequence;
        }

        /// Returns the pending path.
        @Override
        public String path() {
            return path;
        }

        /// Returns one supported pending attribute view.
        @Override
        public <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) throws IOException {
            requireCurrentEntry(this, sequence);
            return currentAttributeView(Objects.requireNonNull(type, "type"));
        }

        /// Commits the pending entry without a body.
        @Override
        public void close() throws IOException {
            if (completed) {
                return;
            }

            EntryBodyChannel body = bodyChannel;
            if (body != null) {
                body.close();
                return;
            }

            if (!closing) {
                requireCurrentEntry(this, sequence);
                closing = true;
            }
            finishCurrentEntry();
            complete();
        }

        /// Opens the pending body.
        @Override
        public WritableByteChannel openChannel() throws IOException {
            requireCurrentEntry(this, sequence);
            WritableByteChannel channel = Objects.requireNonNull(openCurrentChannel(), "channel");
            EntryBodyChannel body = new EntryBodyChannel(this, channel);
            bodyChannel = body;
            return body;
        }

        /// Opens the pending body as a stream.
        @Override
        public OutputStream openOutputStream() throws IOException {
            return StreamChannelAdapters.outputStream(openChannel());
        }

        /// Marks this handle complete and releases the writer for another entry.
        private void complete() {
            completed = true;
            currentEntry = null;
            entrySequence++;
        }

        /// Completes this entry after its body channel closes successfully.
        private void completeBody(EntryBodyChannel body) {
            if (body != bodyChannel) {
                throw new IllegalStateException("Archive entry body is no longer current");
            }
            if (completed) {
                return;
            }
            complete();
        }

        /// Invalidates this entry after its parent writer finishes closing.
        private void invalidate() {
            closing = true;
            completed = true;
        }

        /// Closes or commits this entry while its parent writer is closing.
        private void closeFromWriter() throws IOException {
            if (completed) {
                return;
            }

            EntryBodyChannel body = bodyChannel;
            if (body != null) {
                body.close();
                return;
            }

            closing = true;
            finishCurrentEntry();
            complete();
        }

        /// Returns whether this handle still accepts metadata configuration or one body open.
        private boolean isConfigurable() {
            return !closing && bodyChannel == null;
        }

        /// Returns whether this handle has begun or completed closing.
        private boolean isClosedForOperations() {
            return closing || completed;
        }
    }

    /// Keeps one opened entry current until its format-specific body closes successfully.
    @NotNullByDefault
    private static final class EntryBodyChannel implements WritableByteChannel {
        /// Entry completed by this body.
        private final CurrentEntry entry;

        /// Format-specific body channel.
        private final WritableByteChannel delegate;

        /// Whether closing has begun, including after an unsuccessful close attempt.
        private boolean closing;

        /// Whether the delegate closed successfully and completed its entry.
        private boolean closed;

        /// Creates a body channel that owns completion of the given entry.
        private EntryBodyChannel(CurrentEntry entry, WritableByteChannel delegate) {
            this.entry = entry;
            this.delegate = delegate;
        }

        /// Writes bytes to the format-specific body.
        @Override
        public int write(ByteBuffer source) throws IOException {
            Objects.requireNonNull(source, "source");
            if (closing) {
                throw new ClosedChannelException();
            }
            return delegate.write(source);
        }

        /// Returns whether this channel still accepts writes.
        @Override
        public boolean isOpen() {
            return !closing && delegate.isOpen();
        }

        /// Closes the format-specific body and releases the writer for another entry.
        ///
        /// A repeated call retries delegate cleanup after an earlier close failure.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closing = true;
            delegate.close();
            closed = true;
            entry.completeBody(this);
        }
    }
}
