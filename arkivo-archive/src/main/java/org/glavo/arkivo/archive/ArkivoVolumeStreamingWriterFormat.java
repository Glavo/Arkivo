// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Describes an archive format that can stream entries to transactional multi-volume output.
@NotNullByDefault
public interface ArkivoVolumeStreamingWriterFormat extends ArkivoStreamingWriterFormat {
    /// Opens a multi-volume streaming writer with the requested maximum physical volume size.
    ///
    /// @param target the transactional destination for the output volumes
    /// @param splitSize the positive maximum physical volume size in bytes
    /// @return a new transactional multi-volume writer
    /// @throws IOException if the output transaction or writer cannot be opened
    /// @throws IllegalArgumentException if {@code splitSize} is not positive
    default ArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return openStreamingWriter(target, splitSize, ArchiveCreateOptions.DEFAULT);
    }

    /// Opens a configured multi-volume streaming writer with the requested maximum physical volume size.
    ///
    /// A successful writer owns the output transaction opened from the target. Closing the writer commits the final
    /// archive; setup or finalization failure rolls back unpublished output.
    ///
    /// @param target the transactional destination for the output volumes
    /// @param splitSize the positive maximum physical volume size in bytes
    /// @param options the archive creation options
    /// @return a new transactional multi-volume writer
    /// @throws IOException if the output transaction or writer cannot be opened
    /// @throws IllegalArgumentException if {@code splitSize} is not positive
    ArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveCreateOptions options
    ) throws IOException;
}
