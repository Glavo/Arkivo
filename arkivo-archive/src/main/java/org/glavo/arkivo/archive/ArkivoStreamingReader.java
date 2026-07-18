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
///
/// A reader is stateful and not safe for concurrent use. It begins before the first entry; metadata and body operations
/// are legal only after `next()` returns true and before the next advance. A reader created by a format factory owns its
/// archive source. Closing the reader closes the current body first and then releases format-specific source resources;
/// neither close nor advance drains an unread body unless the body implementation itself does so.
@NotNullByDefault
public abstract class ArkivoStreamingReader implements Closeable {
    /// Whether the cursor currently identifies an entry.
    private boolean entryAvailable;

    /// Body channel opened for the current entry, or null before body access.
    private @Nullable ReadableByteChannel currentChannel;

    /// Whether this reader rejects all further cursor operations.
    private boolean closed;

    /// Creates a streaming archive reader positioned before its first entry.
    protected ArkivoStreamingReader() {
    }

    /// Advances to the next archive entry and returns whether an entry is available.
    ///
    /// Any body channel opened for the previous entry is closed before the format-specific parser advances. When
    /// advancing or body cleanup fails, the cursor is left without a current entry. After this method returns false,
    /// further calls may be used to confirm or retry end-of-input according to the format implementation.
    ///
    /// @return {@code true} if the cursor now identifies an entry, or {@code false} at logical archive end
    /// @throws IOException if the previous body cannot be closed or the next entry cannot be parsed
    public final boolean next() throws IOException {
        requireOpen();
        entryAvailable = false;
        closeCurrentChannel();
        entryAvailable = advance();
        return entryAvailable;
    }

    /// Advances the format-specific parser to its next entry.
    ///
    /// The base class calls this only while the reader is open, has cleared its current-entry state, and has successfully
    /// closed the preceding body. Implementations return false at logical archive end and must leave any new current
    /// metadata available to [#readCurrentAttributes(Class)] and [#openCurrentChannel()].
    ///
    /// @return {@code true} if an entry was selected, or {@code false} at logical archive end
    /// @throws IOException if the format-specific cursor cannot advance
    protected abstract boolean advance() throws IOException;

    /// Reads format-independent metadata for the current entry.
    ///
    /// Implementations may parse and cache the requested metadata lazily. The returned attributes must remain valid
    /// after the reader advances or closes.
    ///
    /// @return an immutable snapshot of the current entry's format-independent attributes
    /// @throws IOException if metadata cannot be decoded or the reader is closed
    public final ArchiveEntryAttributes readAttributes() throws IOException {
        return readAttributes(ArchiveEntryAttributes.class);
    }

    /// Reads the current entry metadata as a supported attribute type.
    ///
    /// Implementations may parse and cache the requested metadata lazily. The returned attributes must remain valid
    /// after the reader advances or closes.
    ///
    /// @param <A> the requested attribute type
    /// @param type the supported attribute interface or implementation class
    /// @return an immutable snapshot of the current entry's requested attributes
    /// @throws IOException if metadata cannot be decoded or the reader is closed
    /// @throws UnsupportedOperationException if {@code type} is not supported by this format
    public final <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException {
        requireCurrentEntry();
        return readCurrentAttributes(Objects.requireNonNull(type, "type"));
    }

    /// Reads current format-specific attributes.
    ///
    /// The requested cursor position is current and no lifecycle lock is held by the base class. The result must not
    /// depend on reader-owned mutable storage after this call returns.
    ///
    /// @param <A> the requested attribute type
    /// @param type the attribute interface or implementation class requested by the caller
    /// @return an immutable snapshot of the current entry's requested attributes
    /// @throws IOException if format-specific metadata cannot be decoded
    /// @throws UnsupportedOperationException if {@code type} is not supported
    protected abstract <A extends BasicFileAttributes> A readCurrentAttributes(Class<A> type) throws IOException;

    /// Opens the current entry body as a readable channel.
    ///
    /// The body may be opened at most once for each cursor position. Closing the returned channel does not close this
    /// reader. Advancing or closing the reader closes the body channel when the caller has not already done so. An entry
    /// whose body channel was closed cannot be reopened before advancing.
    ///
    /// @return the current entry's caller-closeable body channel
    /// @throws IOException if the body cannot be opened or the reader is closed
    /// @throws IllegalStateException if no entry is current or its body has already been opened
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
    ///
    /// @return the current entry's caller-closeable body stream
    /// @throws IOException if the body cannot be opened or the reader is closed
    /// @throws IllegalStateException if no entry is current or its body has already been opened
    public final InputStream openInputStream() throws IOException {
        return StreamChannelAdapters.inputStream(openChannel());
    }

    /// Opens the current format-specific entry body.
    ///
    /// The base class takes ownership of the returned channel and closes it on advance or reader close. Implementations
    /// may return a channel that drives the shared parser and therefore need not support reads after the cursor advances.
    ///
    /// @return the format-specific body channel for the current entry
    /// @throws IOException if the body channel cannot be opened
    protected abstract ReadableByteChannel openCurrentChannel() throws IOException;

    /// Marks this reader closed and releases its current body and format-specific resources.
    ///
    /// The reader rejects cursor operations as soon as closing starts, even if cleanup fails. Body-close failure remains
    /// primary and a reader-cleanup failure is suppressed on it. Repeated calls allow incomplete cleanup to be retried.
    ///
    /// @throws IOException if the current body or format-specific reader resources cannot be closed
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
    ///
    /// @throws IOException if format-specific resources cannot be released
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
