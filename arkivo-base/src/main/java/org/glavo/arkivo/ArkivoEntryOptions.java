// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Describes metadata and policies requested when writing an archive entry.
@NotNullByDefault
public interface ArkivoEntryOptions {
    /// Returns the raw encoded entry path bytes to write.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded entry path text.
    String path();

    /// Returns the requested entry type.
    ArkivoItemType type();

    /// Returns the expected uncompressed size.
    @Nullable Long uncompressedSize();

    /// Returns the requested last modified time.
    @Nullable FileTime modifiedTime();

    /// Returns additional metadata requested for the entry.
    ArkivoMetadata metadata();
}
