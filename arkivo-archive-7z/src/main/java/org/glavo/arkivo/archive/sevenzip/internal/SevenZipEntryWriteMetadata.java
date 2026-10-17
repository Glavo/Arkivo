// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipCompression;
import org.glavo.arkivo.archive.sevenzip.SevenZipFilterChain;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Objects;

/// Stores optional metadata applied when a forward-only or rewritten 7z entry is opened.
///
/// @param lastModifiedTime the last modification time, or null when absent
/// @param lastAccessTime the last access time, or null when absent
/// @param creationTime the creation time, or null when absent
/// @param windowsAttributes the Windows attributes, or UNKNOWN_WINDOWS_ATTRIBUTES when absent
/// @param compression the entry-specific compression, or null to use the writer default
/// @param filters the entry-specific filter chain, empty to disable filters, or null to use the writer default
@NotNullByDefault
record SevenZipEntryWriteMetadata(
        @Nullable FileTime lastModifiedTime,
        @Nullable FileTime lastAccessTime,
        @Nullable FileTime creationTime,
        int windowsAttributes,
        @Nullable SevenZipCompression compression,
        @Nullable SevenZipFilterChain filters
) {
    /// Seconds between the Windows epoch and the Unix epoch.
    private static final long WINDOWS_EPOCH_OFFSET = 11_644_473_600L;

    /// Number of 100-nanosecond FILETIME ticks in one second.
    private static final long WINDOWS_TICKS_PER_SECOND = 10_000_000L;

    /// Validates entry write metadata.
    SevenZipEntryWriteMetadata {
        validateTimes(lastModifiedTime, lastAccessTime, creationTime);
    }

    /// Checks all supplied times before callers apply any of them to an entry.
    ///
    /// @throws IllegalArgumentException if a non-null time cannot be stored as an unsigned FILETIME
    static void validateTimes(@Nullable FileTime modified, @Nullable FileTime accessed, @Nullable FileTime created) {
        if (modified != null) windowsTicks(modified);
        if (accessed != null) windowsTicks(accessed);
        if (created != null) windowsTicks(created);
    }

    /// Converts a time to unsigned FILETIME ticks, truncating fractional ticks toward the past.
    ///
    /// @param time the time to encode
    /// @return the unsigned 64-bit FILETIME bit pattern; zero denotes the Windows epoch
    /// @throws IllegalArgumentException if the time is outside the unsigned FILETIME range
    static long windowsTicks(FileTime time) {
        Instant instant = time.toInstant();
        long seconds = instant.getEpochSecond() + WINDOWS_EPOCH_OFFSET;
        long fraction = instant.getNano() / 100;
        long maximumSeconds = Long.divideUnsigned(-1L, WINDOWS_TICKS_PER_SECOND);
        if (seconds < 0 || seconds > maximumSeconds
                || (seconds == maximumSeconds
                && fraction > Long.remainderUnsigned(-1L, WINDOWS_TICKS_PER_SECOND))) {
            throw new IllegalArgumentException("Time is outside the unsigned 7z FILETIME range");
        }
        // Overflow into the sign bit is intentional; FILETIME uses all 64 bits as an unsigned value.
        return seconds * WINDOWS_TICKS_PER_SECOND + fraction;
    }

    /// Returns the entry-specific compression or the writer default.
    SevenZipCompression resolvedCompression(SevenZipCompression defaultCompression) {
        Objects.requireNonNull(defaultCompression, "defaultCompression");
        return compression != null ? compression : defaultCompression;
    }

    /// Returns the entry-specific filter decision or the writer default.
    SevenZipFilterChain resolvedFilters(SevenZipFilterChain defaultFilters) {
        Objects.requireNonNull(defaultFilters, "defaultFilters");
        return filters != null ? filters : defaultFilters;
    }
}
