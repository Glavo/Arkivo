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
/// The reader exposes both a low-level cursor and entry handles. A handle remains valid only until the reader advances
/// or closes.
///
/// Entry operations throw `ClosedChannelException` after closing begins, including after a failed close attempt.
@NotNullByDefault
public abstract class ArkivoStreamingReader implements Closeable {
    /// Sequence number used to invalidate handles when cursor state changes.
    private long entrySequence;

    /// Whether the cursor currently identifies an entry.
    private boolean entryAvailable;

    /// Whether this reader rejects all further entry operations.
    private boolean closed;

    /// Creates a streaming archive reader base instance.
    protected ArkivoStreamingReader() {
    }

    /// Advances to the next archive entry and returns whether an entry is available.
    public final boolean next() throws IOException {
        requireOpen();
        entrySequence++;
        entryAvailable = false;
        entryAvailable = advance();
        return entryAvailable;
    }

    /// Advances and returns a handle for the next archive entry, or null at end of input.
    public final @Nullable Entry nextEntry() throws IOException {
        return next() ? new CurrentEntry(entrySequence) : null;
    }

    /// Advances the format-specific parser to its next entry.
    protected abstract boolean advance() throws IOException;

    /// Reads the current archive entry attributes as the requested attribute type.
    public final <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        requireCurrentEntry(entrySequence);
        return readCurrentAttributes(Objects.requireNonNull(type, "type"));
    }

    /// Reads current format-specific attributes.
    protected abstract <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException;

    /// Opens a readable channel for the current file entry.
    public final ReadableByteChannel openChannel() throws IOException {
        requireCurrentEntry(entrySequence);
        return openCurrentChannel();
    }

    /// Opens the current format-specific entry body.
    protected abstract ReadableByteChannel openCurrentChannel() throws IOException;

    /// Opens an input stream for the current file entry.
    public final InputStream openInputStream() throws IOException {
        return StreamChannelAdapters.inputStream(openChannel());
    }

    /// Marks this reader closed and releases its format-specific resources.
    ///
    /// Repeated calls allow format-specific cleanup to finish after an earlier close failure.
    @Override
    public final void close() throws IOException {
        closed = true;
        try {
            closeReader();
        } finally {
            entrySequence++;
            entryAvailable = false;
        }
    }

    /// Releases format-specific reader resources.
    ///
    /// Implementations must tolerate repeated calls after successful cleanup and retry incomplete cleanup after failure.
    protected abstract void closeReader() throws IOException;

    /// Requires a still-current entry handle on an open reader.
    private void requireCurrentEntry(long sequence) throws ClosedChannelException {
        requireOpen();
        if (!entryAvailable || sequence != entrySequence) {
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
    public interface Entry {
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
    }

    /// Delegates one entry handle to the reader cursor that created it.
    @NotNullByDefault
    private final class CurrentEntry implements Entry {
        /// Cursor sequence identifying this entry.
        private final long sequence;

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
            requireCurrentEntry(sequence);
            return readCurrentAttributes(Objects.requireNonNull(type, "type"));
        }

        /// Opens the current entry body.
        @Override
        public ReadableByteChannel openChannel() throws IOException {
            requireCurrentEntry(sequence);
            return openCurrentChannel();
        }

        /// Opens the current entry body as a stream.
        @Override
        public InputStream openInputStream() throws IOException {
            return StreamChannelAdapters.inputStream(openChannel());
        }
    }
}
