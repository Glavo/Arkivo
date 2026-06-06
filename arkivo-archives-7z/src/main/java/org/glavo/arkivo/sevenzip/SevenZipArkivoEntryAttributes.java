// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.BasicFileAttributes;

/// Exposes parsed 7z entry attributes.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributes extends BasicFileAttributes {
    /// The Windows attributes value used when the archive entry has no Windows attributes property.
    int UNKNOWN_WINDOWS_ATTRIBUTES = -1;

    /// Returns the decoded entry path stored in the 7z header.
    String path();

    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    int windowsAttributes();
}
