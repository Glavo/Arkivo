// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/// Reads archive entries from a forward-only source.
///
/// Each call to `nextEntry()` returns a scoped handle for one entry. Advancing closes the previous handle, including
/// any body channel opened through it. A handle may also be closed explicitly before advancing.
///
/// Entry operations throw `ClosedChannelException` after closing begins, including after a failed close attempt.
@NotNullByDefault
public abstract class ArkivoStreamingReader implements Closeable {
    /// Sequence number used to invalidate handles when cursor state changes.
    private long entrySequence;

    /// Handle for the current entry, or null before the first entry and after end of input.
    private @Nullable CurrentEntry currentEntry;

    /// Whether this reader rejects all further entry operations.
    private boolean closed;

    /// Creates a streaming archive reader base instance.
    protected ArkivoStreamingReader() {
    }

    /// Advances and returns a handle for the next archive entry, or null at end of input.
    ///
    /// Any previous handle is closed before the format-specific parser advances.
    public final @Nullable Entry nextEntry() throws IOException {
        requireOpen();
        closeCurrentEntry();
        entrySequence++;
        if (!advance()) {
            return null;
        }

        CurrentEntry entry = new CurrentEntry(entrySequence);
        currentEntry = entry;
        return entry;
    }

    /// Advances the format-specific parser to its next entry.
    protected abstract boolean advance() throws IOException;

    /// Reads current format-specific attributes.
    protected abstract <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException;

    /// Opens the current format-specific entry body.
    protected abstract ReadableByteChannel openCurrentChannel() throws IOException;

    /// Marks this reader closed and releases its format-specific resources.
    ///
    /// Repeated calls allow format-specific cleanup to finish after an earlier close failure.
    @Override
    public final void close() throws IOException {
        closed = true;
        @Nullable Throwable failure = null;
        try {
            closeCurrentEntry();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        } finally {
            entrySequence++;
            currentEntry = null;
        }

        try {
            closeReader();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }

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

    /// Releases format-specific reader resources.
    ///
    /// Implementations must tolerate repeated calls after successful cleanup and retry incomplete cleanup after failure.
    protected abstract void closeReader() throws IOException;

    /// Closes the current entry handle before advancing or closing this reader.
    private void closeCurrentEntry() throws IOException {
        CurrentEntry entry = currentEntry;
        if (entry != null) {
            entry.close();
            currentEntry = null;
        }
    }

    /// Requires a still-current entry handle on an open reader.
    private void requireCurrentEntry(CurrentEntry entry, long sequence) throws ClosedChannelException {
        requireOpen();
        if (entry.isClosing()) {
            throw new ClosedChannelException();
        }
        if (entry != currentEntry || sequence != entrySequence) {
            throw new IllegalStateException("Archive entry is no longer current");
        }
    }

    /// Requires this reader to remain open for entry operations.
    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Provides metadata and body access for one current forward-only entry.
    @NotNullByDefault
    public interface Entry extends Closeable {
        /// Returns the normalized archive-local path.
        String path() throws IOException;

        /// Returns format-independent attributes.
        ArchiveEntryAttributes attributes() throws IOException;

        /// Returns attributes as a supported format-specific view.
        <A extends BasicFileAttributes> A attributes(Class<A> type) throws IOException;

        /// Opens the entry body as a readable channel.
        ReadableByteChannel openChannel() throws IOException;

        /// Opens the entry body as an input stream.
        InputStream openInputStream() throws IOException;

        /// Closes this handle and any body channel opened through it.
        ///
        /// Closing a handle does not advance the reader. Repeated calls retry incomplete body cleanup and otherwise
        /// have no effect.
        @Override
        void close() throws IOException;
    }

    /// Delegates one entry handle to the reader cursor that created it.
    @NotNullByDefault
    private final class CurrentEntry implements Entry {
        /// Cursor sequence identifying this entry.
        private final long sequence;

        /// Body channel opened through this handle, or null before body access.
        private @Nullable ReadableByteChannel bodyChannel;

        /// Whether closing this handle has begun.
        private boolean closing;

        /// Whether this handle has closed successfully.
        private boolean closed;

        /// Creates a handle for one current cursor position.
        private CurrentEntry(long sequence) {
            this.sequence = sequence;
        }

        /// Returns the current entry path.
        @Override
        public String path() throws IOException {
            return attributes().path();
        }

        /// Returns format-independent current entry attributes.
        @Override
        public ArchiveEntryAttributes attributes() throws IOException {
            return attributes(ArchiveEntryAttributes.class);
        }

        /// Returns one supported current entry attribute view.
        @Override
        public <A extends BasicFileAttributes> A attributes(Class<A> type) throws IOException {
            requireCurrentEntry(this, sequence);
            return readCurrentAttributes(Objects.requireNonNull(type, "type"));
        }

        /// Opens the current entry body.
        @Override
        public ReadableByteChannel openChannel() throws IOException {
            requireCurrentEntry(this, sequence);
            if (bodyChannel != null) {
                throw new IllegalStateException("Archive entry body is already open");
            }
            ReadableByteChannel channel = Objects.requireNonNull(openCurrentChannel(), "channel");
            bodyChannel = channel;
            return channel;
        }

        /// Opens the current entry body as a stream.
        @Override
        public InputStream openInputStream() throws IOException {
            return StreamChannelAdapters.inputStream(openChannel());
        }

        /// Closes the body channel opened through this handle.
        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closing = true;
            ReadableByteChannel channel = bodyChannel;
            if (channel != null) {
                channel.close();
            }
            closed = true;
        }

        /// Returns whether this handle rejects further entry operations.
        private boolean isClosing() {
            return closing;
        }
    }
}
