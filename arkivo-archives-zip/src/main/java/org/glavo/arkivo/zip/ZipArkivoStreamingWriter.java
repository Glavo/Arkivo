// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoWriter;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

/// Writes ZIP items sequentially to a target channel.
@NotNullByDefault
public final class ZipArkivoStreamingWriter implements ArkivoWriter<ZipInfoSpec> {
    /// The target channel.
    private final WritableByteChannel target;

    /// Whether closing this writer should close the target channel.
    private final boolean closeChannel;

    /// Creates a streaming ZIP writer over the given target channel.
    private ZipArkivoStreamingWriter(WritableByteChannel target, boolean closeChannel) {
        this.target = target;
        this.closeChannel = closeChannel;
    }

    /// Opens a streaming ZIP writer over the given target channel.
    public static ZipArkivoStreamingWriter open(WritableByteChannel target) {
        return new ZipArkivoStreamingWriter(target, false);
    }

    /// Adds a new ZIP item from a source channel.
    @Override
    public void add(ReadableByteChannel source, ZipInfoSpec spec) throws IOException {
        throw new UnsupportedOperationException("Streaming ZIP archive writing is not implemented yet");
    }

    /// Adds a file system path under the given ZIP item name.
    @Override
    public void add(Path source, ArkivoName name) throws IOException {
        throw new UnsupportedOperationException("Streaming ZIP archive writing is not implemented yet");
    }

    /// Closes the writer and its owned channel.
    @Override
    public void close() throws IOException {
        if (closeChannel) {
            target.close();
        }
    }
}
