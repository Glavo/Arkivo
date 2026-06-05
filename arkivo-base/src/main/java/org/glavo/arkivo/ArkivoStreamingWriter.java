// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttributeView;

/// Writes archive entries to a forward-only stream.
@NotNullByDefault
public abstract class ArkivoStreamingWriter implements Closeable {
    /// Creates a streaming archive writer base instance.
    protected ArkivoStreamingWriter() {
    }

    /// Begins a pending entry for the given logical archive path.
    public abstract void beginEntry(Path path) throws IOException;

    /// Returns an attribute view used to configure the current pending entry before it is committed.
    public abstract <V extends FileAttributeView> @Nullable V attributeView(Class<V> type);

    /// Creates the current pending entry as a directory and commits its metadata.
    public abstract void createDirectory() throws IOException;

    /// Opens a writable channel for the current pending entry and commits its metadata.
    public abstract WritableByteChannel openChannel() throws IOException;

    /// Opens an output stream for the current pending entry and commits its metadata.
    public OutputStream openOutputStream() throws IOException {
        return Channels.newOutputStream(openChannel());
    }

    /// Creates a directory entry for the given logical archive path.
    public void createDirectory(Path path) throws IOException {
        beginEntry(path);
        createDirectory();
    }

    /// Opens a writable channel for the next regular file entry at the given logical archive path.
    public WritableByteChannel openChannel(Path path) throws IOException {
        beginEntry(path);
        return openChannel();
    }

    /// Opens an output stream for the next regular file entry at the given logical archive path.
    public OutputStream openOutputStream(Path path) throws IOException {
        beginEntry(path);
        return openOutputStream();
    }

    /// Closes this streaming writer and finishes the archive stream.
    @Override
    public abstract void close() throws IOException;
}
