// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Exposes immutable metadata for one archive item.
@NotNullByDefault
public interface ArkivoInfo {
    /// Returns the raw encoded item path bytes stored by the archive format.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded item path text.
    String path();

    /// Returns the item type.
    ArkivoItemType type();

    /// Returns the uncompressed size when the archive stores it.
    @Nullable Long uncompressedSize();

    /// Returns the last modified time when the archive stores it.
    @Nullable FileTime modifiedTime();

    /// Returns the metadata extension container.
    ArkivoMetadata metadata();
}
