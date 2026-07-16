// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Accepts normalized 7z entries without exposing file-system path operations to streaming writers.
@NotNullByDefault
interface SevenZipArchiveEntrySink extends Closeable {
    /// Creates a path-backed 7z entry sink.
    static SevenZipArchiveEntrySink create(Path path, SevenZipArkivoFileSystemConfig config) throws IOException {
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(path, "path"),
                null,
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens a 7z entry sink over an owned writable channel.
    static SevenZipArchiveEntrySink open(
            WritableByteChannel output,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                new SevenZipSingleVolumeTarget(Objects.requireNonNull(output, "output")),
                Long.MAX_VALUE,
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens a 7z entry sink over a transactional volume target.
    static SevenZipArchiveEntrySink open(
            ArkivoVolumeTarget target,
            long splitSize,
            SevenZipArkivoFileSystemConfig config
    ) throws IOException {
        return new SevenZipArkivoFileSystemImpl(
                SevenZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(target, "target"),
                splitSize,
                Objects.requireNonNull(config, "config")
        );
    }

    /// Returns whether this sink can still accept entries.
    boolean isOpen();

    /// Opens the body stream for a regular file entry.
    OutputStream openFile(String entryName, SevenZipEntryWriteMetadata metadata) throws IOException;

    /// Writes a directory entry.
    void writeDirectory(String entryName, SevenZipEntryWriteMetadata metadata) throws IOException;

    /// Writes a symbolic-link entry.
    void writeSymbolicLink(
            String entryName,
            String target,
            SevenZipEntryWriteMetadata metadata
    ) throws IOException;
}
