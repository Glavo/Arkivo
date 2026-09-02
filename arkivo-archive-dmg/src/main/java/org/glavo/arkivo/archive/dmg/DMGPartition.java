// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Describes one addressable partition in the decoded disk image.
///
/// Offsets and sizes are measured in bytes from the beginning of the decoded image. The index is zero-based among
/// present partitions in on-disk order; unused partition-table entries are not counted.
///
/// @param index the zero-based present-partition index
/// @param offset the non-negative decoded-image byte offset
/// @param size the non-negative partition size in bytes
/// @param name the stored partition name, or {@code null} when absent
/// @param type the stored partition type name or GUID, or {@code null} when absent
/// @param scheme the partitioning scheme that described this partition
@NotNullByDefault
public record DMGPartition(
        int index,
        long offset,
        long size,
        @Nullable String name,
        @Nullable String type,
        DMGPartitionScheme scheme
) {
    /// Validates one immutable partition description.
    public DMGPartition {
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        if (offset < 0L) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (size < 0L) {
            throw new IllegalArgumentException("size must not be negative");
        }
        java.util.Objects.requireNonNull(scheme, "scheme");
    }
}
