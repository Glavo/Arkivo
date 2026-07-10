// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar;

import org.glavo.arkivo.ArkivoEditStorage;
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
///
/// Regular member bodies with no configured size are staged until their final size can be written into the AR header.
/// Default factories use temporary-file storage under the system temporary directory; overloads accepting
/// `ArkivoEditStorage` let callers select another policy. The writer owns and closes its body storage. A member with a
/// size configured through `ArArkivoEntryAttributeView` is written directly without staging.
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

    /// Creates a streaming AR writer that writes to an archive path and owns the given body storage.
    public static ArArkivoStreamingWriter create(Path path, ArkivoEditStorage bodyStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(Files.newOutputStream(path), bodyStorage);
    }

    /// Opens a streaming AR writer over an output stream.
    public static ArArkivoStreamingWriter open(OutputStream output) {
        return new ArArkivoStreamingWriterImpl(Objects.requireNonNull(output, "output"));
    }

    /// Opens a streaming AR writer over an output stream and owns the given body storage.
    public static ArArkivoStreamingWriter open(OutputStream output, ArkivoEditStorage bodyStorage) {
        return new ArArkivoStreamingWriterImpl(
                Objects.requireNonNull(output, "output"),
                Objects.requireNonNull(bodyStorage, "bodyStorage")
        );
    }

    /// Opens a streaming AR writer over a writable channel.
    public static ArArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output));
    }

    /// Opens a streaming AR writer over a writable channel and owns the given body storage.
    public static ArArkivoStreamingWriter open(WritableByteChannel output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        return open(Channels.newOutputStream(output), bodyStorage);
    }
}
