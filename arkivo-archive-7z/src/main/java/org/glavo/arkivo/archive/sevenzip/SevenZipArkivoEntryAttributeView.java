// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Reads and configures 7z-specific attributes for an archive entry.
///
/// [#readAttributes()] returns a stable snapshot. Mutators are supported by update-enabled file systems and by the
/// current pending streaming-writer entry; they fail for read-only file systems and after a pending entry has been
/// committed. Compression and filter changes affect the next encoding of the entry body and do not change the bytes
/// exposed through an already-open body channel.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "7z";
    }

    /// Reads a stable snapshot of the 7z-specific entry attributes.
    @Override
    SevenZipArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the raw Windows file attributes stored for a pending streaming or update entry.
    ///
    /// Passing `UNKNOWN_WINDOWS_ATTRIBUTES` clears the property. Unix mode bits occupy the high 16 bits.
    ///
    /// @param windowsAttributes the raw value, or [SevenZipArkivoEntryAttributes#UNKNOWN_WINDOWS_ATTRIBUTES] to clear it
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the pending streaming entry has already been committed
    void setWindowsAttributes(int windowsAttributes) throws IOException;

    /// Sets the compression used by this pending streaming or update entry instead of the writer default.
    ///
    /// Compression is applied only when the entry has a non-empty data stream.
    ///
    /// @param compression the compression override for the entry's next encoding
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the pending streaming entry has already been committed
    void setCompression(SevenZipCompression compression) throws IOException;

    /// Sets a preprocessing filter for this pending streaming or update entry instead of the writer default.
    ///
    /// The filter is applied only when the entry has a non-empty data stream.
    ///
    /// @param filter the sole preprocessing filter override
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the pending streaming entry has already been committed
    void setFilter(SevenZipFilter filter) throws IOException;

    /// Sets preprocessing filters for this pending streaming or update entry instead of the writer default.
    ///
    /// Filters run in list order and are applied only when the entry has a non-empty data stream.
    ///
    /// @param filters the preprocessing filter-chain override
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the pending streaming entry has already been committed
    void setFilters(SevenZipFilterChain filters) throws IOException;

    /// Disables the default preprocessing filter chain for this pending streaming or update entry.
    ///
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the pending streaming entry has already been committed
    void clearFilter() throws IOException;
}
