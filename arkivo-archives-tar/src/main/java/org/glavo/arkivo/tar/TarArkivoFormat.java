// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Map;

/// Describes TAR archive streaming support provided by Arkivo.
@NotNullByDefault
public final class TarArkivoFormat implements ArkivoFormat {
    /// The stable TAR format name.
    public static final String NAME = "tar";

    /// The shared TAR format instance.
    private static final TarArkivoFormat INSTANCE = new TarArkivoFormat();

    /// Creates a TAR format descriptor.
    public TarArkivoFormat() {
    }

    /// Returns the shared TAR format descriptor.
    public static TarArkivoFormat instance() {
        return INSTANCE;
    }

    /// Returns the stable TAR format name.
    @Override
    public String name() {
        return NAME;
    }

    /// Opens a TAR archive as a file system.
    public TarArkivoFileSystem open(Path path) throws IOException {
        return TarArkivoFileSystem.open(path);
    }

    /// Opens a TAR archive as a file system with provider environment options.
    public TarArkivoFileSystem open(Path path, Map<String, ?> environment) throws IOException {
        return TarArkivoFileSystem.open(path, environment);
    }

    /// Opens a streaming TAR reader from an input stream.
    public TarArkivoStreamingReader openStreamingReader(InputStream source) {
        return TarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming TAR reader from a readable channel.
    public TarArkivoStreamingReader openStreamingReader(ReadableByteChannel source) {
        return TarArkivoStreamingReader.open(source);
    }

    /// Opens a streaming TAR writer over an output stream.
    public TarArkivoStreamingWriter openStreamingWriter(OutputStream output) {
        return TarArkivoStreamingWriter.open(output);
    }

    /// Opens a streaming TAR writer over a writable channel.
    public TarArkivoStreamingWriter openStreamingWriter(WritableByteChannel output) {
        return TarArkivoStreamingWriter.open(output);
    }
}
