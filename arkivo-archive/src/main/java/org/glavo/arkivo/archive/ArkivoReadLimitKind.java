// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies the configured archive-reading limit that rejected archive metadata or entry data.
@NotNullByDefault
public enum ArkivoReadLimitKind {
    /// The maximum number of logical archive entries.
    ENTRY_COUNT,

    /// The maximum logical size of one archive entry.
    ENTRY_SIZE,

    /// The maximum sum of logical archive entry sizes.
    TOTAL_ENTRY_SIZE,

    /// The maximum cumulative number of archive metadata bytes processed while reading.
    METADATA_SIZE,

    /// The maximum decoded byte size of an outer-compressed archive stream.
    DECODED_ARCHIVE_SIZE,

    /// The maximum number of nested outer compression layers decoded before archive recognition.
    OUTER_COMPRESSION_LAYERS
}
