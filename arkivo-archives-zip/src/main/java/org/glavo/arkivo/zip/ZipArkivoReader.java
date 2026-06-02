// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoName;
import org.glavo.arkivo.ArkivoReader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/// Reads an existing ZIP archive without modifying it.
@NotNullByDefault
public final class ZipArkivoReader implements ArkivoReader<ZipInfo> {
    /// The archive channel.
    private final SeekableByteChannel channel;

    /// Whether closing this reader should close the archive channel.
    private final boolean closeChannel;

    /// Creates a ZIP reader over the given channel.
    private ZipArkivoReader(SeekableByteChannel channel, boolean closeChannel) {
        this.channel = channel;
        this.closeChannel = closeChannel;
    }

    /// Opens a ZIP archive for read-only random access.
    public static ZipArkivoReader open(Path path) throws IOException {
        return new ZipArkivoReader(Files.newByteChannel(path, StandardOpenOption.READ), true);
    }

    /// Opens a ZIP archive channel for read-only random access.
    public static ZipArkivoReader open(SeekableByteChannel channel) {
        return new ZipArkivoReader(channel, false);
    }

    /// Returns all known ZIP items.
    @Override
    public @Unmodifiable List<ZipInfo> infos() throws IOException {
        throw notImplemented();
    }

    /// Returns the first ZIP item with the given name.
    @Override
    public @Nullable ZipInfo first(ArkivoName name) throws IOException {
        throw notImplemented();
    }

    /// Returns all ZIP items with the given name.
    @Override
    public @Unmodifiable List<ZipInfo> all(ArkivoName name) throws IOException {
        throw notImplemented();
    }

    /// Opens a channel for reading the ZIP item contents.
    @Override
    public ReadableByteChannel openChannel(ZipInfo info) throws IOException {
        throw notImplemented();
    }

    /// Closes the reader and its owned channel.
    @Override
    public void close() throws IOException {
        if (closeChannel) {
            channel.close();
        }
    }

    /// Returns the placeholder exception used until ZIP reading is implemented.
    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("ZIP archive reading is not implemented yet");
    }
}
