// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArkivoStreamingWriter;
import org.glavo.arkivo.archive.cpio.internal.CPIOArkivoStreamingWriterImpl;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/// Writes CPIO entries to a forward-only stream.
///
/// Entry bodies are staged until their final size and optional CRC checksum can be written before the body. The edit
/// storage configured by `CPIOArchiveOptions.Create` selects the staging policy; default factories use temporary files
/// under the system temporary directory. The writer owns and closes the selected storage.
///
/// Only one entry may be pending. Closing its `ArkivoStreamingWriter.Entry` commits the fixed body or an empty regular
/// file; a regular-file body owns completion until it closes successfully. Metadata views are configurable only before
/// body opening or entry close. Writer close commits a pending entry, emits the `TRAILER!!!` record, pads to the
/// configured block size, and closes the owned output. Once close begins, entry operations remain closed after a
/// failure; another `close()` call retries incomplete finalization and cleanup.
@NotNullByDefault
public abstract sealed class CPIOArkivoStreamingWriter extends ArkivoStreamingWriter
        permits CPIOArkivoStreamingWriterImpl {
    /// Creates a streaming CPIO writer base instance.
    protected CPIOArkivoStreamingWriter() {
    }

    /// Creates a streaming CPIO writer that writes to an archive path.
    ///
    /// @param path the archive path to create or truncate
    /// @return a writer that owns the path output and its body storage
    /// @throws IOException if the path output cannot be opened
    public static CPIOArkivoStreamingWriter create(Path path) throws IOException {
        return create(path, CPIOArchiveOptions.CREATE_DEFAULTS);
    }

    /// Creates a configured streaming CPIO writer that writes to an archive path.
    ///
    /// @param path the archive path to create or truncate
    /// @param options the dialect, byte order, metadata, padding, and storage configuration
    /// @return a writer that owns the path output and selected body storage
    /// @throws IOException if the path output cannot be opened
    public static CPIOArkivoStreamingWriter create(Path path, CPIOArchiveOptions.Create options) throws IOException {
        Path checkedPath = Objects.requireNonNull(path, "path");
        CPIOArchiveOptions.Create checkedOptions = Objects.requireNonNull(options, "options");
        OutputStream target = Files.newOutputStream(checkedPath);
        try {
            return open(target, checkedOptions);
        } catch (IOException | RuntimeException | Error exception) {
            try {
                target.close();
            } catch (IOException closeException) {
                exception.addSuppressed(closeException);
            }
            throw exception;
        }
    }

    /// Opens a streaming CPIO writer over an output stream.
    ///
    /// @param target the archive output; ownership transfers to the returned writer
    /// @return a writer using the default creation configuration
    /// @throws IOException if default body storage cannot be opened
    public static CPIOArkivoStreamingWriter open(OutputStream target) throws IOException {
        return open(target, CPIOArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a configured streaming CPIO writer over an output stream.
    ///
    /// @param target the archive output; ownership transfers to the returned writer
    /// @param options the dialect, byte order, metadata, padding, and storage configuration
    /// @return a writer that owns `target` and the selected body storage
    /// @throws IOException if selected body storage cannot be opened
    public static CPIOArkivoStreamingWriter open(
            OutputStream target,
            CPIOArchiveOptions.Create options
    ) throws IOException {
        return new CPIOArkivoStreamingWriterImpl(
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(options, "options")
        );
    }

    /// Opens a streaming CPIO writer over a writable channel.
    ///
    /// @param target the archive output channel; ownership transfers to the returned writer
    /// @return a writer using the default creation configuration
    /// @throws IOException if default body storage cannot be opened
    public static CPIOArkivoStreamingWriter open(WritableByteChannel target) throws IOException {
        return open(target, CPIOArchiveOptions.CREATE_DEFAULTS);
    }

    /// Opens a configured streaming CPIO writer over a writable channel.
    ///
    /// @param target the archive output channel; ownership transfers to the returned writer
    /// @param options the dialect, byte order, metadata, padding, and storage configuration
    /// @return a writer that owns `target` and the selected body storage
    /// @throws IOException if selected body storage cannot be opened
    public static CPIOArkivoStreamingWriter open(
            WritableByteChannel target,
            CPIOArchiveOptions.Create options
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        return open(StreamChannelAdapters.outputStream(target), options);
    }
}
