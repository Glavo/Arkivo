// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies how a partition was located in a decoded DMG disk image.
@NotNullByDefault
public enum DMGPartitionScheme {
    /// The whole decoded image is exposed as one unpartitioned volume.
    RAW,

    /// The partition was described by an Apple Partition Map.
    APPLE_PARTITION_MAP,

    /// The partition was described by a GUID Partition Table.
    GUID_PARTITION_TABLE
}
