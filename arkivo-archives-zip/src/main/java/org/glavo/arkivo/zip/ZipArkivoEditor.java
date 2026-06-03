// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEditor;
import org.glavo.arkivo.ArkivoReader;
import org.glavo.arkivo.ArkivoVolumeSink;
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

/// Edits an existing ZIP archive.
@NotNullByDefault
public final class ZipArkivoEditor implements ArkivoEditor<ZipArkivoEntry, ZipArkivoEntryOptions> {
    /// The archive channel.
    private final @Nullable SeekableByteChannel channel;

    /// The split archive input volumes.
    private final @Nullable ArkivoVolumeSource sourceVolumes;

    /// The split archive output volumes.
    private final @Nullable ArkivoVolumeSink targetVolumes;

    /// The ZIP edit options.
    private final ZipEditOptions options;

    /// The resource owned by this editor.
    private final @Nullable Closeable ownedResource;

    /// Creates a ZIP editor over the given channel.
    private ZipArkivoEditor(
            @Nullable SeekableByteChannel channel,
            @Nullable ArkivoVolumeSource sourceVolumes,
            @Nullable ArkivoVolumeSink targetVolumes,
            ZipEditOptions options,
            @Nullable Closeable ownedResource
    ) {
        this.channel = channel;
        this.sourceVolumes = sourceVolumes;
        this.targetVolumes = targetVolumes;
        this.options = options;
        this.ownedResource = ownedResource;
    }

    /// Opens an existing ZIP archive for editing.
    public static ZipArkivoEditor open(Path path) throws IOException {
        return open(path, ZipEditOptions.defaults());
    }

    /// Opens an existing ZIP archive for editing with explicit edit options.
    public static ZipArkivoEditor open(Path path, ZipEditOptions options) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        return new ZipArkivoEditor(channel, null, null, options, channel);
    }

    /// Opens an existing ZIP archive channel for editing.
    public static ZipArkivoEditor open(SeekableByteChannel channel) {
        return open(channel, ZipEditOptions.defaults());
    }

    /// Opens an existing ZIP archive channel for editing with explicit edit options.
    public static ZipArkivoEditor open(SeekableByteChannel channel, ZipEditOptions options) {
        return new ZipArkivoEditor(channel, null, null, options, null);
    }

    /// Opens split ZIP archive volumes for copy-on-write editing.
    public static ZipArkivoEditor open(ArkivoVolumeSource sourceVolumes, ArkivoVolumeSink targetVolumes) {
        return open(sourceVolumes, targetVolumes, ZipEditOptions.defaults());
    }

    /// Opens split ZIP archive volumes for copy-on-write editing with explicit edit options.
    public static ZipArkivoEditor open(
            ArkivoVolumeSource sourceVolumes,
            ArkivoVolumeSink targetVolumes,
            ZipEditOptions options
    ) {
        return new ZipArkivoEditor(null, sourceVolumes, targetVolumes, options, null);
    }

    /// Adds a new ZIP entry from the given channel.
    @Override
    public void add(ReadableByteChannel source, ZipArkivoEntryOptions options) throws IOException {
        throw notImplemented();
    }

    /// Replaces an existing ZIP entry with the given decoded path.
    @Override
    public void replace(String path, ReadableByteChannel source, ZipArkivoEntryOptions options) throws IOException {
        throw notImplemented();
    }

    /// Replaces an existing ZIP entry with the given raw encoded path.
    @Override
    public void replace(byte @Unmodifiable [] rawPath, ReadableByteChannel source, ZipArkivoEntryOptions options) throws IOException {
        throw notImplemented();
    }

    /// Removes ZIP entries with the given decoded path.
    @Override
    public void remove(String path) throws IOException {
        throw notImplemented();
    }

    /// Removes ZIP entries with the given raw encoded path.
    @Override
    public void remove(byte @Unmodifiable [] rawPath) throws IOException {
        throw notImplemented();
    }

    /// Returns a read-only view of the current ZIP archive state.
    @Override
    public ArkivoReader<ZipArkivoEntry> reader() throws IOException {
        throw notImplemented();
    }

    /// Commits the accumulated ZIP archive changes.
    @Override
    public void commit() throws IOException {
        throw notImplemented();
    }

    /// Closes the editor and its owned channel.
    @Override
    public void close() throws IOException {
        if (ownedResource != null) {
            ownedResource.close();
        }
    }

    /// Returns the placeholder exception used until ZIP editing is implemented.
    private static UnsupportedOperationException notImplemented() {
        return new UnsupportedOperationException("ZIP archive editing is not implemented yet");
    }
}
