// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Describes metadata requested when writing an archive item.
@NotNullByDefault
public interface ArkivoInfoSpec {
    /// Returns the raw encoded item path bytes to write.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded item path text.
    String path();

    /// Returns the item type.
    ArkivoItemType type();

    /// Returns the expected uncompressed size.
    @Nullable Long uncompressedSize();

    /// Returns the requested last modified time.
    @Nullable FileTime modifiedTime();

    /// Returns additional metadata requested for the item.
    ArkivoMetadata metadata();
}
