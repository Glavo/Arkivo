// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoStreamingWriter;
import org.glavo.arkivo.ar.internal.ArArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Writes AR members to a forward-only stream.
@NotNullByDefault
public abstract sealed class ArArkivoStreamingWriter extends ArkivoStreamingWriter
        permits ArArkivoStreamingWriterImpl {
    /// Creates a streaming AR writer base instance.
    protected ArArkivoStreamingWriter() {
    }

    /// Creates a streaming AR writer that writes to an archive path.
    public static ArArkivoStreamingWriter create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(Files.newOutputStream(path));
    }

    /// Opens a streaming AR writer over an output stream.
    public static ArArkivoStreamingWriter open(OutputStream output) {
        return new ArArkivoStreamingWriterImpl(Objects.requireNonNull(output, "output"));
    }

    /// Opens a streaming AR writer over a writable channel.
    public static ArArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output));
    }
}
