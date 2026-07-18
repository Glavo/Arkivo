// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.BasicFileAttributes;

/// Exposes format-independent metadata shared by every archive entry.
@NotNullByDefault
public interface ArchiveEntryAttributes extends BasicFileAttributes {
    /// Returns the normalized archive-local path text.
    ///
    /// @return the normalized path relative to the archive root
    String path();
}
