// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;

/// Reads ZIP items sequentially from a non-seekable source.
@NotNullByDefault
public final class ZipArkivoStreamingReader implements Closeable {
    /// The source channel.
    private final ReadableByteChannel source;

    /// Whether closing this reader should close the source channel.
    private final boolean closeChannel;

    /// Creates a streaming ZIP reader over the given source channel.
    private ZipArkivoStreamingReader(ReadableByteChannel source, boolean closeChannel) {
        this.source = source;
        this.closeChannel = closeChannel;
    }

    /// Opens a streaming ZIP reader over the given source channel.
    public static ZipArkivoStreamingReader open(ReadableByteChannel source) {
        return new ZipArkivoStreamingReader(source, false);
    }

    /// Returns the next ZIP item in source order.
    public @Nullable ZipInfo next() throws IOException {
        throw new UnsupportedOperationException("Streaming ZIP archive reading is not implemented yet");
    }

    /// Opens a channel for reading the current ZIP item contents.
    public ReadableByteChannel openChannel() throws IOException {
        throw new UnsupportedOperationException("Streaming ZIP archive reading is not implemented yet");
    }

    /// Closes the reader and its owned channel.
    @Override
    public void close() throws IOException {
        if (closeChannel) {
            source.close();
        }
    }
}
