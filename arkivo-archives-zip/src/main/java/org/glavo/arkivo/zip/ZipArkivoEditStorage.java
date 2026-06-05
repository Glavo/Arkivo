// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.zip.internal.ZipArkivoEditStorageSupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;

/// Stores new or replacement ZIP entry content while an editor transaction is open.
@NotNullByDefault
public interface ZipArkivoEditStorage extends AutoCloseable {
    /// The expected size value used when the entry size is not known before writing.
    long UNKNOWN_SIZE = -1L;

    /// Returns an edit storage that keeps staged content in memory.
    static ZipArkivoEditStorage memory() {
        return ZipArkivoEditStorageSupport.memory();
    }

    /// Returns an edit storage that keeps staged content in temporary files under the given directory.
    static ZipArkivoEditStorage temporaryFiles(Path directory) {
        return ZipArkivoEditStorageSupport.temporaryFiles(directory);
    }

    /// Returns an edit storage that keeps small staged content in memory and larger content in temporary files.
    static ZipArkivoEditStorage hybrid(long memoryThreshold, Path directory) {
        return ZipArkivoEditStorageSupport.hybrid(memoryThreshold, directory);
    }

    /// Creates storage for one new or replacement ZIP entry body.
    ZipArkivoStoredContent createContent(String path, long expectedSize) throws IOException;

    /// Closes this storage and releases resources that are not owned by individual stored content objects.
    @Override
    void close() throws IOException;
}
