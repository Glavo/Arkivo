// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.attribute.FileAttributeView;

/// Writes archive entries to a forward-only stream.
@NotNullByDefault
public abstract class ArkivoStreamingWriter implements Closeable {
    /// Creates a streaming archive writer base instance.
    protected ArkivoStreamingWriter() {
    }

    /// Begins a pending regular file entry for the given logical archive path text.
    public abstract void beginFile(String path) throws IOException;

    /// Begins a pending directory entry for the given logical archive path text.
    public abstract void beginDirectory(String path) throws IOException;

    /// Begins a pending symbolic link entry for the given logical archive path and target path text.
    public abstract void beginSymbolicLink(String path, String target) throws IOException;

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    public abstract <V extends FileAttributeView> @Nullable V attributeView(Class<V> type);

    /// Commits the current pending entry without opening a body channel.
    public abstract void endEntry() throws IOException;

    /// Opens a writable channel for the current pending entry and commits its metadata.
    public abstract WritableByteChannel openChannel() throws IOException;

    /// Opens an output stream for the current pending entry and commits its metadata.
    public OutputStream openOutputStream() throws IOException {
        return StreamChannelAdapters.outputStream(openChannel());
    }

    /// Closes this streaming writer and finishes the archive stream.
    @Override
    public abstract void close() throws IOException;
}
