// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilter;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores optional metadata applied when a forward-only or rewritten 7z entry is opened.
///
/// @param lastModifiedTime the last modification time, or `null` when absent
/// @param lastAccessTime the last access time, or `null` when absent
/// @param creationTime the creation time, or `null` when absent
/// @param windowsAttributes the Windows attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when absent
/// @param compression the entry-specific compression, or `null` to use the writer default
/// @param filterConfigured whether the entry overrides the writer's default filter
/// @param filter the entry-specific filter, or `null` to disable filtering when `filterConfigured` is true
@NotNullByDefault
record SevenZipEntryWriteMetadata(
        @Nullable FileTime lastModifiedTime,
        @Nullable FileTime lastAccessTime,
        @Nullable FileTime creationTime,
        int windowsAttributes,
        @Nullable SevenZipCompression compression,
        boolean filterConfigured,
        @Nullable SevenZipFilter filter
) {
    /// Creates metadata containing only the requested Windows attributes.
    static SevenZipEntryWriteMetadata withWindowsAttributes(int windowsAttributes) {
        return new SevenZipEntryWriteMetadata(null, null, null, windowsAttributes, null, false, null);
    }

    /// Returns the entry-specific compression or the writer default.
    SevenZipCompression resolvedCompression(SevenZipCompression defaultCompression) {
        Objects.requireNonNull(defaultCompression, "defaultCompression");
        return compression != null ? compression : defaultCompression;
    }

    /// Returns the entry-specific filter decision or the writer default.
    @Nullable SevenZipFilter resolvedFilter(@Nullable SevenZipFilter defaultFilter) {
        return filterConfigured ? filter : defaultFilter;
    }
}
