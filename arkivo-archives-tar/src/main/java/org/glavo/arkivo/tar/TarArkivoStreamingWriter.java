// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar;

import org.glavo.arkivo.ArkivoStreamingWriter;
import org.glavo.arkivo.tar.internal.TarArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Writes TAR entries to a forward-only stream.
@NotNullByDefault
public abstract sealed class TarArkivoStreamingWriter extends ArkivoStreamingWriter
        permits TarArkivoStreamingWriterImpl {
    /// Creates a streaming TAR writer base instance.
    protected TarArkivoStreamingWriter() {
    }

    /// Creates a streaming TAR writer that writes to an archive path.
    public static TarArkivoStreamingWriter create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(Files.newOutputStream(path));
    }

    /// Opens a streaming TAR writer over an output stream.
    public static TarArkivoStreamingWriter open(OutputStream output) {
        return new TarArkivoStreamingWriterImpl(Objects.requireNonNull(output, "output"));
    }

    /// Opens a streaming TAR writer over a writable channel.
    public static TarArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output));
    }
}
