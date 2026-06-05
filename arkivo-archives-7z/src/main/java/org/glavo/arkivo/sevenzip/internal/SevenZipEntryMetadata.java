// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Stores parsed metadata for one 7z entry.
@NotNullByDefault
public final class SevenZipEntryMetadata {
    /// The data offset value used when an entry has no stored body.
    public static final long NO_DATA_OFFSET = -1L;

    /// The decoded entry path.
    private final String path;

    /// Whether this entry is a directory.
    private final boolean directory;

    /// The uncompressed entry size.
    private final long size;

    /// The absolute archive data offset, or `NO_DATA_OFFSET` when this entry has no stored body.
    private final long dataOffset;

    /// Creates parsed 7z entry metadata.
    public SevenZipEntryMetadata(String path, boolean directory, long size, long dataOffset) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (dataOffset < NO_DATA_OFFSET) {
            throw new IllegalArgumentException("dataOffset must be non-negative or NO_DATA_OFFSET");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.directory = directory;
        this.size = size;
        this.dataOffset = dataOffset;
    }

    /// Returns the decoded entry path.
    public String path() {
        return path;
    }

    /// Returns whether this entry is a directory.
    public boolean directory() {
        return directory;
    }

    /// Returns the uncompressed entry size.
    public long size() {
        return size;
    }

    /// Returns the absolute archive data offset, or `NO_DATA_OFFSET` when this entry has no stored body.
    public long dataOffset() {
        return dataOffset;
    }
}
