// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.ar;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.ArkivoEditStorage;
import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.ar.internal.ArArkivoStreamingWriterImpl;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
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
///
/// Only one entry may be pending. Closing its `ArkivoStreamingWriter.Entry` commits its fixed or empty body; opening a
/// caller-writable body transfers completion to that body, and another entry cannot begin until the body closes
/// successfully. Metadata views are configurable only while their entry remains pending. Closing the writer commits
/// any pending entry, finishes the archive, and closes the owned output and body storage. Once close begins, entry
/// operations remain closed even if finalization fails; another `close()` call retries incomplete cleanup.
@NotNullByDefault
public abstract sealed class ArArkivoStreamingWriter extends ArkivoStreamingWriter
        permits ArArkivoStreamingWriterImpl {
    /// Creates a streaming AR writer base instance.
    protected ArArkivoStreamingWriter() {
    }

    /// Creates a streaming AR writer that writes to an archive path.
    ///
    /// An existing regular file is truncated when the output stream is opened.
    ///
    /// @param path the archive path to create or replace
    /// @return a writer that owns its path output and default body storage
    /// @throws IOException if the path output cannot be opened
    public static ArArkivoStreamingWriter create(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        return open(Files.newOutputStream(path));
    }

    /// Creates a streaming AR writer that writes to an archive path and owns the given body storage.
    ///
    /// An existing regular file is truncated when the output stream is opened. Ownership of `bodyStorage` transfers
    /// only after the path output opens successfully.
    ///
    /// @param path the archive path to create or replace
    /// @param bodyStorage the storage used for bodies whose size is not declared before writing
    /// @return a writer that owns its path output and `bodyStorage`
    /// @throws IOException if the path output cannot be opened
    public static ArArkivoStreamingWriter create(Path path, ArkivoEditStorage bodyStorage) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(Files.newOutputStream(path), bodyStorage);
    }

    /// Opens a streaming AR writer over an output stream.
    ///
    /// @param output the archive output; ownership transfers to the returned writer
    /// @return a writer using owned default body storage
    public static ArArkivoStreamingWriter open(OutputStream output) {
        Objects.requireNonNull(output, "output");
        return open(StreamChannelAdapters.writableChannel(output));
    }

    /// Opens a streaming AR writer over an output stream and owns the given body storage.
    ///
    /// @param output the archive output; ownership transfers to the returned writer
    /// @param bodyStorage the storage used for bodies whose size is not declared before writing
    /// @return a writer that owns `output` and `bodyStorage`
    public static ArArkivoStreamingWriter open(OutputStream output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(bodyStorage, "bodyStorage");
        return open(StreamChannelAdapters.writableChannel(output), bodyStorage);
    }

    /// Opens a streaming AR writer over a writable channel.
    ///
    /// @param output the archive output channel; ownership transfers to the returned writer
    /// @return a writer using owned default body storage
    public static ArArkivoStreamingWriter open(WritableByteChannel output) {
        Objects.requireNonNull(output, "output");
        return new ArArkivoStreamingWriterImpl(StreamChannelAdapters.outputStream(output));
    }

    /// Opens a streaming AR writer over a writable channel and owns the given body storage.
    ///
    /// @param output the archive output channel; ownership transfers to the returned writer
    /// @param bodyStorage the storage used for bodies whose size is not declared before writing
    /// @return a writer that owns `output` and `bodyStorage`
    public static ArArkivoStreamingWriter open(WritableByteChannel output, ArkivoEditStorage bodyStorage) {
        Objects.requireNonNull(output, "output");
        return new ArArkivoStreamingWriterImpl(
                StreamChannelAdapters.outputStream(output),
                Objects.requireNonNull(bodyStorage, "bodyStorage")
        );
    }
}
