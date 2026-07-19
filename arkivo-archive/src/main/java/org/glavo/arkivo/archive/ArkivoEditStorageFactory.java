// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/// Creates operation-owned storage for decoded, new, or replacement archive entry content.
///
/// An archive operation invokes [#open()] at most once and owns the returned storage. Closing the operation closes that
/// storage. Implementations must return an independently closeable storage from every invocation and must support
/// concurrent invocations when the factory is shared by multiple operations.
@FunctionalInterface
@NotNullByDefault
public interface ArkivoEditStorageFactory {
    /// Opens storage for one archive operation.
    ///
    /// @return new independently closeable storage owned by the calling operation
    /// @throws IOException if the storage cannot be opened
    ArkivoEditStorage open() throws IOException;

    /// Returns a reusable factory for memory-backed storage.
    ///
    /// @return a factory that creates a new memory-backed storage for each operation
    static ArkivoEditStorageFactory memory() {
        return ArkivoEditStorage::memory;
    }

    /// Returns a reusable factory for temporary-file-backed storage under the given directory.
    ///
    /// @param directory the directory in which each storage creates temporary content files
    /// @return a factory that creates a new temporary-file-backed storage for each operation
    static ArkivoEditStorageFactory temporaryFiles(Path directory) {
        Path checkedDirectory = Objects.requireNonNull(directory, "directory");
        return () -> ArkivoEditStorage.temporaryFiles(checkedDirectory);
    }

    /// Returns a reusable factory for storage that spills large content from memory to temporary files.
    ///
    /// @param memoryThreshold the non-negative maximum expected size retained in memory
    /// @param directory the directory in which larger temporary content files are created
    /// @return a factory that creates new hybrid storage for each operation
    /// @throws IllegalArgumentException if `memoryThreshold` is negative
    static ArkivoEditStorageFactory hybrid(long memoryThreshold, Path directory) {
        if (memoryThreshold < 0L) {
            throw new IllegalArgumentException("memoryThreshold must not be negative");
        }
        Path checkedDirectory = Objects.requireNonNull(directory, "directory");
        return () -> ArkivoEditStorage.hybrid(memoryThreshold, checkedDirectory);
    }
}
