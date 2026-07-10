// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides 7z-specific attributes for an archive entry.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "7z";
    }

    /// Reads the 7z-specific entry attributes.
    @Override
    SevenZipArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the raw Windows file attributes stored for a pending streaming entry.
    ///
    /// Passing `UNKNOWN_WINDOWS_ATTRIBUTES` clears the property. Unix mode bits occupy the high 16 bits.
    void setWindowsAttributes(int windowsAttributes) throws IOException;

    /// Sets the compression used by this pending streaming entry instead of the writer default.
    ///
    /// Compression is applied only when the entry has a non-empty data stream.
    void setCompression(SevenZipCompression compression) throws IOException;

    /// Sets a preprocessing filter for this pending streaming entry instead of the writer default.
    ///
    /// The filter is applied only when the entry has a non-empty data stream.
    void setFilter(SevenZipFilter filter) throws IOException;

    /// Disables the writer's default preprocessing filter for this pending streaming entry.
    void clearFilter() throws IOException;
}
