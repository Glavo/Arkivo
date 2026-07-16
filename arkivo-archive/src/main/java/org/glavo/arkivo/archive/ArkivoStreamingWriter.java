// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileAttributeView;
import java.util.Objects;

/// Writes archive entries to a forward-only target.
///
/// Each begin method returns a handle that keeps metadata configuration and entry completion scoped to one pending
/// entry. The writer-level cursor methods remain available for compact state-machine loops.
///
/// Entry operations throw `ClosedChannelException` after closing begins, including after a failed close attempt.
@NotNullByDefault
public abstract class ArkivoStreamingWriter implements Closeable {
    /// Sequence number used to invalidate completed entry handles.
    private long entrySequence;

    /// Whether one pending entry can still be configured or committed.
    private boolean entryActive;

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
        if (entryActive) {
            throw new IllegalStateException("An archive entry is already pending");
        }
        checkedInitializer.initialize(checkedPath);
        entrySequence++;
        entryActive = true;
        return new CurrentEntry(checkedPath, entrySequence);
    }

    /// Begins a format-specific regular file entry.
    protected abstract void beginFileEntry(String path) throws IOException;

    /// Begins a format-specific directory entry.
    protected abstract void beginDirectoryEntry(String path) throws IOException;

    /// Begins a format-specific symbolic link entry.
    protected abstract void beginSymbolicLinkEntry(String path, String target) throws IOException;

    /// Returns an attribute view used to configure the current pending entry.
    public final <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) throws IOException {
        requireCurrentEntry(entrySequence);
        return currentAttributeView(Objects.requireNonNull(type, "type"));
    }

    /// Returns a format-specific pending entry attribute view.
    protected abstract <V extends FileAttributeView> @Nullable V currentAttributeView(Class<V> type);

    /// Commits the current pending entry without opening a body channel.
    public final void endEntry() throws IOException {
        requireCurrentEntry(entrySequence);
        finishCurrentEntry();
        entryActive = false;
        entrySequence++;
    }

    /// Commits the current format-specific entry without a body channel.
    protected abstract void finishCurrentEntry() throws IOException;

    /// Opens a writable channel for the current pending entry and commits its metadata.
    public final WritableByteChannel openChannel() throws IOException {
        requireCurrentEntry(entrySequence);
        WritableByteChannel channel = openCurrentChannel();
        entryActive = false;
        entrySequence++;
        return channel;
    }

    /// Opens the current format-specific entry body.
    protected abstract WritableByteChannel openCurrentChannel() throws IOException;

    /// Opens an output stream for the current pending entry and commits its metadata.
    public final OutputStream openOutputStream() throws IOException {
        return StreamChannelAdapters.outputStream(openChannel());
    }

    /// Marks this writer closed, finishes the archive stream, and releases format-specific resources.
    ///
    /// Repeated calls allow format-specific cleanup to finish after an earlier close failure.
    @Override
    public final void close() throws IOException {
        closed = true;
        try {
            closeWriter();
        } finally {
            entryActive = false;
            entrySequence++;
        }
    }

    /// Finishes the format-specific archive and releases its resources.
    ///
    /// Implementations must tolerate repeated calls after successful cleanup and retry incomplete cleanup after failure.
    protected abstract void closeWriter() throws IOException;

    /// Requires a still-current pending entry on an open writer.
    private void requireCurrentEntry(long sequence) throws ClosedChannelException {
        requireOpen();
        if (!entryActive || sequence != entrySequence) {
            throw new IllegalStateException("Archive entry is no longer pending");
        }
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
    public interface Entry {
        /// Returns the normalized archive-local path supplied when the entry began.
        String path();

        /// Returns a supported mutable attribute view for this entry, or null when unsupported.
        <V extends FileAttributeView> @Nullable V attributeView(Class<V> type) throws IOException;

        /// Commits this entry without a body.
        void commit() throws IOException;

        /// Opens this entry body as a writable channel and commits its metadata.
        WritableByteChannel openChannel() throws IOException;

        /// Opens this entry body as an output stream and commits its metadata.
        OutputStream openOutputStream() throws IOException;
    }

    /// Delegates one pending entry handle to its writer cursor.
    @NotNullByDefault
    private final class CurrentEntry implements Entry {
        /// Pending archive-local path.
        private final String path;

        /// Cursor sequence identifying this pending entry.
        private final long sequence;

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
            requireCurrentEntry(sequence);
            return currentAttributeView(Objects.requireNonNull(type, "type"));
        }

        /// Commits the pending entry without a body.
        @Override
        public void commit() throws IOException {
            requireCurrentEntry(sequence);
            finishCurrentEntry();
            entryActive = false;
            entrySequence++;
        }

        /// Opens the pending body.
        @Override
        public WritableByteChannel openChannel() throws IOException {
            requireCurrentEntry(sequence);
            WritableByteChannel channel = openCurrentChannel();
            entryActive = false;
            entrySequence++;
            return channel;
        }

        /// Opens the pending body as a stream.
        @Override
        public OutputStream openOutputStream() throws IOException {
            return StreamChannelAdapters.outputStream(openChannel());
        }
    }
}
