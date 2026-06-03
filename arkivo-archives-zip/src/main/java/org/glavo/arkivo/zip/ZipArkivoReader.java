// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoReader;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/// Reads an existing ZIP archive without modifying it.
@NotNullByDefault
public final class ZipArkivoReader implements ArkivoReader<ZipArkivoEntry> {
    /// The archive channel.
    private final @Nullable SeekableByteChannel channel;

    /// The split archive volume source.
    private final @Nullable ArkivoVolumeSource volumes;

    /// The ZIP read options.
    private final ZipReadOptions options;

    /// The resource owned by this reader.
    private final @Nullable Closeable ownedResource;

    /// Creates a ZIP reader over the given channel.
    private ZipArkivoReader(
            @Nullable SeekableByteChannel channel,
            @Nullable ArkivoVolumeSource volumes,
            ZipReadOptions options,
            @Nullable Closeable ownedResource
    ) {
        this.channel = channel;
        this.volumes = volumes;
        this.options = options;
        this.ownedResource = ownedResource;
    }

    /// Opens a ZIP archive for read-only random access.
    public static ZipArkivoReader open(Path path) throws IOException {
        return open(path, ZipReadOptions.defaults());
    }

    /// Opens a ZIP archive for read-only random access with explicit read options.
    public static ZipArkivoReader open(Path path, ZipReadOptions options) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ);
        return new ZipArkivoReader(channel, null, options, channel);
    }

    /// Opens a ZIP archive channel for read-only random access.
    public static ZipArkivoReader open(SeekableByteChannel channel) {
        return open(channel, ZipReadOptions.defaults());
    }

    /// Opens a ZIP archive channel for read-only random access with explicit read options.
    public static ZipArkivoReader open(SeekableByteChannel channel, ZipReadOptions options) {
        return new ZipArkivoReader(channel, null, options, null);
    }

    /// Opens split ZIP archive volumes for read-only random access.
    public static ZipArkivoReader open(ArkivoVolumeSource volumes) {
        return open(volumes, ZipReadOptions.defaults());
    }

    /// Opens split ZIP archive volumes for read-only random access with explicit read options.
    public static ZipArkivoReader open(ArkivoVolumeSource volumes, ZipReadOptions options) {
        return new ZipArkivoReader(null, volumes, options, null);
    }

    /// Returns all known ZIP entries.
    @Override
    public @Unmodifiable List<ZipArkivoEntry> entries() throws IOException {
        throw notImplemented();
    }

    /// Returns the first ZIP entry with the given decoded path.
    @Override
    public @Nullable ZipArkivoEntry first(String path) throws IOException {
        throw notImplemented();
    }

    /// Returns the first ZIP entry with the given raw encoded path.
    @Override
    public @Nullable ZipArkivoEntry first(byte @Unmodifiable [] rawPath) throws IOException {
        throw notImplemented();
    }

    /// Returns all ZIP entries with the given decoded path.
    @Override
    public @Unmodifiable List<ZipArkivoEntry> all(String path) throws IOException {
        throw notImplemented();
    }

    /// Returns all ZIP entries with the given raw encoded path.
    @Override
    public @Unmodifiable List<ZipArkivoEntry> all(byte @Unmodifiable [] rawPath) throws IOException {
        throw notImplemented();
    }

    /// Opens a channel for reading the ZIP entry contents.
    ReadableByteChannel openChannel(ZipArkivoEntry entry) throws IOException {
        throw notImplemented();
    }

    /// Closes the reader and its owned channel.
    @Override
    public void close() throws IOException {
        if (ownedResource != null) {
            ownedResource.close();
        }
    }

    /// Returns the placeholder exception used until ZIP reading is implemented.
    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("ZIP archive reading is not implemented yet");
    }
}
