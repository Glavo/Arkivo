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
    ///
    /// @return a new memory-backed storage
    static ArkivoEditStorage memory() {
        return ArkivoEditStorageSupport.memory();
    }

    /// Returns an edit storage that keeps staged content in temporary files under the given directory.
    ///
    /// @param directory the directory in which temporary content files are created
    /// @return a new temporary-file-backed storage
    static ArkivoEditStorage temporaryFiles(Path directory) {
        return ArkivoEditStorageSupport.temporaryFiles(directory);
    }

    /// Returns storage that selects memory or temporary files according to each entry's expected size.
    ///
    /// Content with a known expected size at or below `memoryThreshold` is stored in memory. Larger or unknown
    /// expected sizes select temporary files. The choice is made by [#createContent(String, long)] and does not change
    /// as content grows; the threshold is not a limit on actual memory use.
    ///
    /// @param memoryThreshold the non-negative maximum expected size, in bytes, that selects memory storage
    /// @param directory the directory in which file-backed content is created
    /// @return a new hybrid storage
    /// @throws IllegalArgumentException if {@code memoryThreshold} is negative
    static ArkivoEditStorage hybrid(long memoryThreshold, Path directory) {
        return ArkivoEditStorageSupport.hybrid(memoryThreshold, directory);
    }

    /// Creates storage for one new or replacement archive entry body.
    ///
    /// @param path the normalized archive-local entry path used to identify the content
    /// @param expectedSize the expected non-negative byte count, or {@link #UNKNOWN_SIZE} if unknown
    /// @return an independently closeable stored-content object owned by the caller
    /// @throws IOException if backing storage cannot be allocated
    /// @throws IllegalArgumentException if {@code expectedSize} is less than {@link #UNKNOWN_SIZE}
    ArkivoStoredContent createContent(String path, long expectedSize) throws IOException;

    /// Closes this storage and releases resources that are not owned by individual stored content objects.
    @Override
    void close() throws IOException;
}
