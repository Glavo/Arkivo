// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Describes an archive format that can stream entries to transactional multi-volume output.
@NotNullByDefault
public interface ArkivoVolumeStreamingWriterFormat extends ArkivoStreamingWriterFormat {
    /// Opens a multi-volume streaming writer with the requested maximum physical volume size.
    default ArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize
    ) throws IOException {
        return openStreamingWriter(target, splitSize, ArchiveOptions.EMPTY);
    }

    /// Opens a configured multi-volume streaming writer with the requested maximum physical volume size.
    ///
    /// A successful writer owns the output transaction opened from the target. Closing the writer commits the final
    /// archive; setup or finalization failure rolls back unpublished output.
    ArkivoStreamingWriter openStreamingWriter(
            ArkivoVolumeTarget target,
            long splitSize,
            ArchiveOptions options
    ) throws IOException;
}
