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

/// Reads archive entries from a forward-only source through a stateful cursor.
///
/// `next()` positions the cursor without materializing an entry object. Metadata and body access operate on the
/// current cursor position and may perform format-specific work lazily. Advancing closes any body channel opened for
/// the previous entry before the format-specific parser moves to the next entry.
@NotNullByDefault
public abstract class ArkivoStreamingReader implements Closeable {
    /// Whether the cursor currently identifies an entry.
    private boolean entryAvailable;

    /// Body channel opened for the current entry, or null before body access.
    private @Nullable ReadableByteChannel currentChannel;

    /// Whether this reader rejects all further cursor operations.
    private boolean closed;

    /// Creates a streaming archive reader base instance.
    protected ArkivoStreamingReader() {
    }

    /// Advances to the next archive entry and returns whether an entry is available.
    ///
    /// Any body channel opened for the previous entry is closed before the format-specific parser advances. When
    /// advancing or body cleanup fails, the cursor is left without a current entry.
    public final boolean next() throws IOException {
        requireOpen();
        entryAvailable = false;
        closeCurrentChannel();
        entryAvailable = advance();
        return entryAvailable;
    }

    /// Advances the format-specific parser to its next entry.
    protected abstract boolean advance() throws IOException;

    /// Reads format-independent metadata for the current entry.
    ///
    /// Implementations may parse and cache the requested metadata lazily. The returned attributes must remain valid
    /// after the reader advances or closes.
    public final ArchiveEntryAttributes readAttributes() throws IOException {
        return readAttributes(ArchiveEntryAttributes.class);
    }

    /// Reads the current entry metadata as a supported attribute type.
    ///
    /// Implementations may parse and cache the requested metadata lazily. The returned attributes must remain valid
    /// after the reader advances or closes.
    public final <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        requireCurrentEntry();
        return readCurrentAttributes(Objects.requireNonNull(type, "type"));
    }

    /// Reads current format-specific attributes.
    protected abstract <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException;

    /// Opens the current entry body as a readable channel.
    ///
    /// The body may be opened at most once for each cursor position. Closing the returned channel does not close this
    /// reader. Advancing or closing the reader closes the body channel when the caller has not already done so.
    public final ReadableByteChannel openChannel() throws IOException {
        requireCurrentEntry();
        if (currentChannel != null) {
            throw new IllegalStateException("Archive entry body is already open");
        }

        ReadableByteChannel channel = Objects.requireNonNull(openCurrentChannel(), "channel");
        currentChannel = channel;
        return channel;
    }

    /// Opens the current entry body as an input stream.
    ///
    /// Closing the returned stream closes only the current entry body, not this reader.
    public final InputStream openInputStream() throws IOException {
        return StreamChannelAdapters.inputStream(openChannel());
    }

    /// Opens the current format-specific entry body.
    protected abstract ReadableByteChannel openCurrentChannel() throws IOException;

    /// Marks this reader closed and releases its current body and format-specific resources.
    ///
    /// Repeated calls allow incomplete cleanup to be retried after an earlier close failure.
    @Override
    public final void close() throws IOException {
        closed = true;
        entryAvailable = false;

        @Nullable Throwable failure = null;
        try {
            closeCurrentChannel();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }

        try {
            closeReader();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else if (failure != exception) {
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

    /// Closes the body channel opened for the previous cursor position.
    private void closeCurrentChannel() throws IOException {
        ReadableByteChannel channel = currentChannel;
        if (channel != null) {
            channel.close();
            currentChannel = null;
        }
    }

    /// Requires this reader to be positioned at an entry.
    private void requireCurrentEntry() throws ClosedChannelException {
        requireOpen();
        if (!entryAvailable) {
            throw new IllegalStateException("Archive reader is not positioned at an entry");
        }
    }

    /// Requires this reader to remain open for cursor operations.
    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }
}
