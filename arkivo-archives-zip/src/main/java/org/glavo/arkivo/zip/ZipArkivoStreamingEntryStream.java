// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoStreamingEntryStreamImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

/// Reads ZIP entries from a streaming reader in storage order.
@NotNullByDefault
public sealed interface ZipArkivoStreamingEntryStream extends Closeable permits ZipArkivoStreamingEntryStreamImpl {
    /// Returns the next ZIP entry attributes, or `null` when no entries remain.
    @Nullable ZipArkivoEntryAttributes next() throws IOException;

    /// Opens a readable channel for the current file entry.
    ReadableByteChannel openChannel() throws IOException;

    /// Opens an input stream for the current file entry.
    default InputStream openInputStream() throws IOException {
        return Channels.newInputStream(openChannel());
    }

    /// Closes this streaming entry stream.
    @Override
    void close() throws IOException;
}
