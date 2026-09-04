// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Defines format-specific options for opening DMG disk images as archive file systems.
///
/// Partition indexes refer to the immutable list returned by [DMGImage#partitions()], after unused partition-table
/// slots have been omitted. The common limits are enforced cumulatively across the UDIF resource fork, partition map,
/// HFS Plus metadata, and indexed entries during one open operation.
///
/// @param common the common archive limits, lifecycle, and metadata options
/// @param partitionIndex the zero-based present-partition index, or [#AUTOMATIC_PARTITION_INDEX]
@NotNullByDefault
public record DMGArchiveOptions(ArchiveReadOptions common, int partitionIndex) {
    /// Selects the first partition containing a supported file system.
    public static final int AUTOMATIC_PARTITION_INDEX = -1;

    /// The default read configuration with automatic partition selection.
    public static final DMGArchiveOptions DEFAULT = new DMGArchiveOptions(
            ArchiveReadOptions.DEFAULT,
            AUTOMATIC_PARTITION_INDEX
    );

    /// Validates one immutable read configuration.
    public DMGArchiveOptions {
        Objects.requireNonNull(common, "common");
        if (partitionIndex < AUTOMATIC_PARTITION_INDEX) {
            throw new IllegalArgumentException(
                    "partitionIndex must be non-negative or AUTOMATIC_PARTITION_INDEX"
            );
        }
    }

    /// Returns a copy with the requested common archive options.
    ///
    /// @param value the replacement common options
    /// @return this value when unchanged, otherwise a new read configuration
    /// @throws NullPointerException if `value` is `null`
    public DMGArchiveOptions withCommon(ArchiveReadOptions value) {
        Objects.requireNonNull(value, "value");
        return value.equals(common) ? this : new DMGArchiveOptions(value, partitionIndex);
    }

    /// Returns a copy selecting one partition or automatic selection.
    ///
    /// @param value the zero-based present-partition index, or [#AUTOMATIC_PARTITION_INDEX]
    /// @return this value when unchanged, otherwise a new read configuration
    /// @throws IllegalArgumentException if `value` is less than [#AUTOMATIC_PARTITION_INDEX]
    public DMGArchiveOptions withPartitionIndex(int value) {
        return value == partitionIndex ? this : new DMGArchiveOptions(common, value);
    }
}
