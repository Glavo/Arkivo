// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.sevenzip.SevenZipCompression;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores optional metadata applied when a forward-only 7z entry is opened.
///
/// @param lastModifiedTime the last modification time, or `null` when absent
/// @param lastAccessTime the last access time, or `null` when absent
/// @param creationTime the creation time, or `null` when absent
/// @param windowsAttributes the Windows attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when absent
/// @param compression the entry-specific compression, or `null` to use the writer default
@NotNullByDefault
record SevenZipEntryWriteMetadata(
        @Nullable FileTime lastModifiedTime,
        @Nullable FileTime lastAccessTime,
        @Nullable FileTime creationTime,
        int windowsAttributes,
        @Nullable SevenZipCompression compression
) {
    /// Creates metadata containing only the requested Windows attributes.
    static SevenZipEntryWriteMetadata withWindowsAttributes(int windowsAttributes) {
        return new SevenZipEntryWriteMetadata(null, null, null, windowsAttributes, null);
    }

    /// Applies every present metadata property to an archive entry.
    void applyTo(SevenZArchiveEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (lastModifiedTime != null) {
            entry.setLastModifiedTime(lastModifiedTime);
        }
        if (lastAccessTime != null) {
            entry.setAccessTime(lastAccessTime);
        }
        if (creationTime != null) {
            entry.setCreationTime(creationTime);
        }
        if (windowsAttributes != SevenZipArkivoEntryAttributes.UNKNOWN_WINDOWS_ATTRIBUTES) {
            entry.setHasWindowsAttributes(true);
            entry.setWindowsAttributes(windowsAttributes);
        }
        if (compression != null) {
            SevenZipCompressionSupport.applyTo(entry, compression);
        }
    }
}
