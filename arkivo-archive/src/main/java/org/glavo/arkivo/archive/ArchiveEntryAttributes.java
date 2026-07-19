// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.BasicFileAttributes;

/// Exposes format-independent metadata shared by every archive entry.
@NotNullByDefault
public interface ArchiveEntryAttributes extends BasicFileAttributes {
    /// Returns the effective decoded path recorded for this archive entry.
    ///
    /// The value describes archive metadata, not the normalized [java.nio.file.Path] used by an indexed file system.
    /// Redundant components, repeated separators, a directory's trailing separator, and other format-significant text
    /// may therefore be retained. A format may canonicalize its native separator characters to `/`, and a synthetic
    /// file-system entry has a generated path because it has no physical header.
    ///
    /// @return the effective decoded archive-entry path
    String path();
}
