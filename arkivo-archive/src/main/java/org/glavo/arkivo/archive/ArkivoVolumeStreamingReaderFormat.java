// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Describes an archive format that can stream entries from a multi-volume source.
@NotNullByDefault
public interface ArkivoVolumeStreamingReaderFormat extends ArkivoStreamingReaderFormat {
    /// Opens a multi-volume streaming reader with default options.
    default ArkivoStreamingReader openStreamingReader(ArkivoVolumeSource source) throws IOException {
        return openStreamingReader(source, ArchiveReadOptions.DEFAULT);
    }

    /// Opens a configured multi-volume streaming reader and takes ownership of the source when successful.
    ///
    /// The reader closes all channels it opens and the volume source itself. A format may process physical volume
    /// boundaries rather than treating the volumes as byte-for-byte concatenated storage.
    ArkivoStreamingReader openStreamingReader(
            ArkivoVolumeSource source,
            ArchiveReadOptions options
    ) throws IOException;
}
