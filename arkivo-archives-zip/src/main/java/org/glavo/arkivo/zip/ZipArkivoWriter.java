// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoVolumeSink;
import org.glavo.arkivo.ArkivoWriter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Writes a new ZIP archive.
@NotNullByDefault
public final class ZipArkivoWriter implements ArkivoWriter<ZipInfoSpec> {
    /// The target channel.
    private final @Nullable WritableByteChannel target;

    /// The split archive volume sink.
    private final @Nullable ArkivoVolumeSink volumes;

    /// The ZIP write options.
    private final ZipWriteOptions options;

    /// The resource owned by this writer.
    private final @Nullable Closeable ownedResource;

    /// Creates a ZIP writer over the given target channel.
    private ZipArkivoWriter(
            @Nullable WritableByteChannel target,
            @Nullable ArkivoVolumeSink volumes,
            ZipWriteOptions options,
            @Nullable Closeable ownedResource
    ) {
        this.target = target;
        this.volumes = volumes;
        this.options = options;
        this.ownedResource = ownedResource;
    }

    /// Opens a target path for writing a new ZIP archive.
    public static ZipArkivoWriter open(Path path) throws IOException {
        return open(path, ZipWriteOptions.defaults());
    }

    /// Opens a target path for writing a new ZIP archive with explicit write options.
    public static ZipArkivoWriter open(Path path, ZipWriteOptions options) throws IOException {
        WritableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return new ZipArkivoWriter(channel, null, options, channel);
    }

    /// Opens a target path for writing a ZIP archive with explicit open options.
    public static ZipArkivoWriter open(Path path, OpenOption... openOptions) throws IOException {
        return open(path, ZipWriteOptions.defaults(), openOptions);
    }

    /// Opens a target path for writing a ZIP archive with explicit write and open options.
    public static ZipArkivoWriter open(Path path, ZipWriteOptions options, OpenOption... openOptions) throws IOException {
        WritableByteChannel channel = Files.newByteChannel(path, openOptions);
        return new ZipArkivoWriter(channel, null, options, channel);
    }

    /// Opens a target channel for writing a ZIP archive.
    public static ZipArkivoWriter open(WritableByteChannel target) {
        return open(target, ZipWriteOptions.defaults());
    }

    /// Opens a target channel for writing a ZIP archive with explicit write options.
    public static ZipArkivoWriter open(WritableByteChannel target, ZipWriteOptions options) {
        return new ZipArkivoWriter(target, null, options, null);
    }

    /// Opens a split volume sink for writing a ZIP archive.
    public static ZipArkivoWriter open(ArkivoVolumeSink volumes) {
        return open(volumes, ZipWriteOptions.defaults());
    }

    /// Opens a split volume sink for writing a ZIP archive with explicit write options.
    public static ZipArkivoWriter open(ArkivoVolumeSink volumes, ZipWriteOptions options) {
        return new ZipArkivoWriter(null, volumes, options, null);
    }

    /// Adds a new ZIP item from a source channel.
    @Override
    public void add(ReadableByteChannel source, ZipInfoSpec spec) throws IOException {
        throw new UnsupportedOperationException("ZIP archive writing is not implemented yet");
    }

    /// Adds a file system path under the given ZIP item name.
    @Override
    public void add(Path source, ArkivoName name) throws IOException {
        throw new UnsupportedOperationException("ZIP archive writing is not implemented yet");
    }

    /// Closes the writer and its owned channel.
    @Override
    public void close() throws IOException {
        if (ownedResource != null) {
            ownedResource.close();
        }
    }
}
