// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Exposes immutable metadata for one archive entry.
@NotNullByDefault
public interface ArkivoEntryInfo {
    /// Returns the raw encoded entry path bytes stored by the archive format.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded entry path text.
    String path();

    /// Returns the entry type.
    ArkivoItemType type();

    /// Returns the uncompressed size when the archive stores it.
    @Nullable Long uncompressedSize();

    /// Returns the last modified time when the archive stores it.
    @Nullable FileTime modifiedTime();

    /// Returns the metadata extension container.
    ArkivoMetadata metadata();
}
