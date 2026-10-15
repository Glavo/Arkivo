// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;

/// Reads and configures 7z-specific attributes for an archive entry.
///
/// [#readAttributes()] returns a stable snapshot. Mutators are supported by update-enabled file systems and by the
/// current pending streaming-writer entry; they fail for read-only file systems and once the pending entry's body
/// opens or entry close begins, even if completion fails. Compression and filter changes affect the next encoding
/// of the entry body and do not change the bytes exposed through an already-open body channel.
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

    /// Sets the non-null timestamps after checking that all supplied values are representable.
    ///
    /// Times are stored as unsigned 64-bit counts of 100-nanosecond intervals since 1601-01-01T00:00:00Z.
    /// Fractional intervals are truncated toward the past. An out-of-range argument leaves all timestamps unchanged.
    ///
    /// @param lastModifiedTime the modification time, or `null` to leave it unchanged
    /// @param lastAccessTime the access time, or `null` to leave it unchanged
    /// @param createTime the creation time, or `null` to leave it unchanged
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalArgumentException if a supplied time is outside the unsigned FILETIME range
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    @Override
    void setTimes(@Nullable FileTime lastModifiedTime, @Nullable FileTime lastAccessTime, @Nullable FileTime createTime)
            throws IOException;

    /// Sets the raw Windows file attributes stored for a pending streaming or update entry.
    ///
    /// Passing `UNKNOWN_WINDOWS_ATTRIBUTES` clears the property. Unix mode bits occupy the high 16 bits.
    ///
    /// @param windowsAttributes the raw value, or [SevenZipArkivoEntryAttributes#UNKNOWN_WINDOWS_ATTRIBUTES] to clear it
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    void setWindowsAttributes(int windowsAttributes) throws IOException;

    /// Sets the compression used by this pending streaming or update entry instead of the writer default.
    ///
    /// Compression is applied only when the entry has a non-empty data stream.
    ///
    /// @param compression the compression override for the entry's next encoding
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    void setCompression(SevenZipCompression compression) throws IOException;

    /// Sets a preprocessing filter for this pending streaming or update entry instead of the writer default.
    ///
    /// The filter is applied only when the entry has a non-empty data stream.
    ///
    /// @param filter the sole preprocessing filter override
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    void setFilter(SevenZipFilter filter) throws IOException;

    /// Sets preprocessing filters for this pending streaming or update entry instead of the writer default.
    ///
    /// Filters run in list order and are applied only when the entry has a non-empty data stream.
    ///
    /// @param filters the preprocessing filter-chain override
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    void setFilters(SevenZipFilterChain filters) throws IOException;

    /// Disables the default preprocessing filter chain for this pending streaming or update entry.
    ///
    /// @throws IOException if the writable file-system entry cannot be updated
    /// @throws IllegalStateException if the streaming entry's body has been opened or entry close has begun
    void clearFilter() throws IOException;
}
