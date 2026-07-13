// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;

/// Reads archive entries from a forward-only stream.
@NotNullByDefault
public abstract class ArkivoStreamingReader implements Closeable {
    /// Creates a streaming archive reader base instance.
    protected ArkivoStreamingReader() {
    }

    /// Advances to the next archive entry and returns whether an entry is available.
    public abstract boolean next() throws IOException;

    /// Reads the current archive entry attributes as the requested attribute type.
    public abstract <A extends BasicFileAttributes> A readAttributes(Class<A> type) throws IOException;

    /// Opens a readable channel for the current file entry.
    public abstract ReadableByteChannel openChannel() throws IOException;

    /// Opens an input stream for the current file entry.
    public InputStream openInputStream() throws IOException {
        return StreamChannelAdapters.inputStream(openChannel());
    }

    /// Closes this streaming reader.
    @Override
    public abstract void close() throws IOException;
}
