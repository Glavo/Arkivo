// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.BasicFileAttributes;

/// Exposes parsed 7z entry attributes.
@NotNullByDefault
public interface SevenZipArkivoEntryAttributes extends BasicFileAttributes {
    /// The Windows attributes value used when the archive entry has no Windows attributes property.
    int UNKNOWN_WINDOWS_ATTRIBUTES = -1;

    /// The Unix mode value used when the archive entry has no Unix mode metadata.
    int UNKNOWN_UNIX_MODE = -1;

    /// Returns the decoded entry path stored in the 7z header.
    String path();

    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    int windowsAttributes();

    /// Returns the Unix mode bits stored in the high 16 bits of the 7z attributes, or `UNKNOWN_UNIX_MODE`.
    int unixMode();
}
