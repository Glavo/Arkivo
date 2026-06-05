// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;

/// Traverses ZIP entry paths in storage order.
@NotNullByDefault
public interface ZipArkivoEntryStream extends Closeable {
    /// Returns the next ZIP entry path, or `null` when traversal is complete.
    @Nullable Path next() throws IOException;

    /// Opens a readable channel for the current ZIP entry.
    ReadableByteChannel openChannel() throws IOException;

    /// Opens an input stream for the current ZIP entry.
    default InputStream openInputStream() throws IOException {
        return Channels.newInputStream(openChannel());
    }

    /// Closes this entry stream.
    @Override
    void close() throws IOException;
}
