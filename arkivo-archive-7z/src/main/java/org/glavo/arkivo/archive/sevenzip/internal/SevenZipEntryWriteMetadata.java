// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores optional metadata applied when a forward-only or rewritten 7z entry is opened.
///
/// @param lastModifiedTime the last modification time, or null when absent
/// @param lastAccessTime the last access time, or null when absent
/// @param creationTime the creation time, or null when absent
/// @param windowsAttributes the Windows attributes, or UNKNOWN_WINDOWS_ATTRIBUTES when absent
/// @param compression the entry-specific compression, or null to use the writer default
/// @param filtersConfigured whether the entry overrides the writer's default filter chain
/// @param filters the entry-specific filter chain, or null when filtersConfigured is false
@NotNullByDefault
record SevenZipEntryWriteMetadata(
        @Nullable FileTime lastModifiedTime,
        @Nullable FileTime lastAccessTime,
        @Nullable FileTime creationTime,
        int windowsAttributes,
        @Nullable SevenZipCompression compression,
        boolean filtersConfigured,
        @Nullable SevenZipFilterChain filters
) {
    /// Validates entry write metadata.
    SevenZipEntryWriteMetadata {
        if (filtersConfigured && filters == null) {
            throw new IllegalArgumentException("Configured entry filters must not be null");
        }
        if (!filtersConfigured && filters != null) {
            throw new IllegalArgumentException("Inherited entry filters must be null");
        }
    }

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
    SevenZipFilterChain resolvedFilters(SevenZipFilterChain defaultFilters) {
        Objects.requireNonNull(defaultFilters, "defaultFilters");
        return filtersConfigured ? Objects.requireNonNull(filters, "filters") : defaultFilters;
    }
}
