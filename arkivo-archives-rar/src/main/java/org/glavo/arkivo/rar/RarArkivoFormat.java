// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar;

import org.glavo.arkivo.ArkivoFormat;
import org.glavo.arkivo.ArkivoSeekableChannelSource;
import org.glavo.arkivo.ArkivoVolumeSource;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.Map;

/// Describes RAR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class RarArkivoFormat implements ArkivoFormat {
    /// The stable RAR format name.
    public static final String NAME = "rar";

    /// The shared RAR format instance.
    private static final RarArkivoFormat INSTANCE = new RarArkivoFormat();

    /// Creates a RAR format descriptor.
    public RarArkivoFormat() {
    }

    /// Returns the shared RAR format descriptor.
    public static RarArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable RAR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a RAR archive file system.
    public RarArkivoFileSystem open(Path path) throws IOException {
        return RarArkivoFileSystem.open(path);
    }

    /// Opens a RAR archive file system with environment options.
    public RarArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return RarArkivoFileSystem.open(path, environment);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public RarArkivoFileSystem open(ArkivoSeekableChannelSource source) throws IOException {
        return RarArkivoFileSystem.open(source);
    }

    /// Opens a read-only RAR archive file system from a repeatable seekable channel source with environment options.
    ///
    /// The returned file system owns the source after this method returns successfully and closes it with the file system.
    public RarArkivoFileSystem open(ArkivoSeekableChannelSource source, Map<String, ?> environment) throws IOException {
        return RarArkivoFileSystem.open(source, environment);
    }

    /// Opens a multi-volume RAR archive file system.
    public RarArkivoFileSystem open(ArkivoVolumeSource volumes) throws IOException {
        return RarArkivoFileSystem.open(volumes);
    }

    /// Opens a multi-volume RAR archive file system with environment options.
    public RarArkivoFileSystem open(ArkivoVolumeSource volumes, Map<String, ?> environment) throws IOException {
        return RarArkivoFileSystem.open(volumes, environment);
    }

    /// Opens a streaming RAR reader from an input stream.
    public RarArkivoStreamingReader openStreamingReader(InputStream source) {
        return RarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming RAR reader from a readable channel.
    public RarArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return RarArkivoStreamingReader.open(source);
    }
}
