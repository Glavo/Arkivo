// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoVolumeSink;
import org.glavo.arkivo.ArkivoWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;

/// Writes ZIP items sequentially to a target channel.
@NotNullByDefault
public final class ZipArkivoStreamingWriter implements ArkivoWriter<ZipInfoSpec> {
    /// The target channel.
    private final @Nullable WritableByteChannel target;

    /// The split archive volume sink.
    private final @Nullable ArkivoVolumeSink volumes;

    /// The ZIP write options.
    private final ZipWriteOptions options;

    /// Whether closing this writer should close the target channel.
    private final boolean closeChannel;

    /// Creates a streaming ZIP writer over the given target channel.
    private ZipArkivoStreamingWriter(
            @Nullable WritableByteChannel target,
            @Nullable ArkivoVolumeSink volumes,
            ZipWriteOptions options,
            boolean closeChannel
    ) {
        this.target = target;
        this.volumes = volumes;
        this.options = options;
        this.closeChannel = closeChannel;
    }

    /// Opens a streaming ZIP writer over the given target channel.
    public static ZipArkivoStreamingWriter open(WritableByteChannel target) {
        return open(target, ZipWriteOptions.defaults());
    }

    /// Opens a streaming ZIP writer over the given target channel with explicit write options.
    public static ZipArkivoStreamingWriter open(WritableByteChannel target, ZipWriteOptions options) {
        return new ZipArkivoStreamingWriter(target, null, options, false);
    }

    /// Opens a streaming ZIP writer over split archive volumes.
    public static ZipArkivoStreamingWriter open(ArkivoVolumeSink volumes) {
        return open(volumes, ZipWriteOptions.defaults());
    }

    /// Opens a streaming ZIP writer over split archive volumes with explicit write options.
    public static ZipArkivoStreamingWriter open(ArkivoVolumeSink volumes, ZipWriteOptions options) {
        return new ZipArkivoStreamingWriter(null, volumes, options, false);
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
            assert target != null;
            target.close();
        }
    }
}
