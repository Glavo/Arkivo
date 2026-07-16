// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArkivoVolumeTarget;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.util.Objects;

/// Accepts normalized ZIP entries without exposing file-system path operations to streaming writers.
@NotNullByDefault
interface ZipArchiveEntrySink extends Closeable {
    /// Creates a path-backed ZIP entry sink.
    static ZipArchiveEntrySink create(Path path, ZipArkivoFileSystemConfig config) throws IOException {
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(path, "path"),
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens a ZIP entry sink over an owned writable channel.
    static ZipArchiveEntrySink open(WritableByteChannel output, ZipArkivoFileSystemConfig config) {
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(output, "output"),
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens a ZIP entry sink over a transactional volume target.
    static ZipArchiveEntrySink open(
            ArkivoVolumeTarget target,
            long splitSize,
            ZipArkivoFileSystemConfig config
    ) throws IOException {
        return new ZipArkivoWritableFileSystemImpl(
                ZipArkivoFileSystemProvider.instance(),
                Objects.requireNonNull(target, "target"),
                splitSize,
                Objects.requireNonNull(config, "config")
        );
    }

    /// Opens the body stream for a regular file entry.
    OutputStream openFile(String entryName, ZipEntryWriteMetadata metadata) throws IOException;

    /// Writes a directory entry.
    void writeDirectory(String entryName, ZipEntryWriteMetadata metadata) throws IOException;

    /// Writes a complete stored entry body.
    void writeStoredEntry(String entryName, byte[] content, ZipEntryWriteMetadata metadata) throws IOException;
}
