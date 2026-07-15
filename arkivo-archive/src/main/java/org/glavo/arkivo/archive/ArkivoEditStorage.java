// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.glavo.arkivo.archive.internal.ArkivoEditStorageSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Stores decoded snapshots and new or replacement entry content owned by an archive file system.
@NotNullByDefault
public interface ArkivoEditStorage extends AutoCloseable {
    /// The expected size value used when the entry size is not known before writing.
    long UNKNOWN_SIZE = -1L;

    /// Returns an edit storage that keeps staged content in memory.
    static ArkivoEditStorage memory() {
        return ArkivoEditStorageSupport.memory();
    }

    /// Returns an edit storage that keeps staged content in temporary files under the given directory.
    static ArkivoEditStorage temporaryFiles(Path directory) {
        return ArkivoEditStorageSupport.temporaryFiles(directory);
    }

    /// Returns an edit storage that keeps small staged content in memory and larger content in temporary files.
    static ArkivoEditStorage hybrid(long memoryThreshold, Path directory) {
        return ArkivoEditStorageSupport.hybrid(memoryThreshold, directory);
    }

    /// Creates storage for one new or replacement archive entry body.
    ArkivoStoredContent createContent(String path, long expectedSize) throws IOException;

    /// Closes this storage and releases resources that are not owned by individual stored content objects.
    @Override
    void close() throws IOException;
}
