// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Map;

/// Describes an archive format that can expose multiple physical volumes as one file system.
@NotNullByDefault
public interface ArkivoVolumeFileSystemFormat extends ArkivoFileSystemFormat {
    /// Opens a read-only file system from an owned volume source.
    default ArkivoFileSystem open(ArkivoVolumeSource source) throws IOException {
        return open(source, Map.of());
    }

    /// Opens a read-only file system from an owned volume source with environment options.
    ArkivoFileSystem open(
            ArkivoVolumeSource source,
            Map<String, ?> environment
    ) throws IOException;
}
