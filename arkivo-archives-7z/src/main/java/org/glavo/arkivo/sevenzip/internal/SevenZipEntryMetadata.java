// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Stores parsed metadata for one 7z entry.
@NotNullByDefault
public final class SevenZipEntryMetadata {
    /// The decoded entry path.
    private final String path;

    /// Whether this entry is a directory.
    private final boolean directory;

    /// The uncompressed entry size.
    private final long size;

    /// Creates parsed 7z entry metadata.
    public SevenZipEntryMetadata(String path, boolean directory, long size) {
        this.path = Objects.requireNonNull(path, "path");
        this.directory = directory;
        this.size = size;
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
}
