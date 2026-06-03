// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEntryInfo;
import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Exposes immutable metadata for one ZIP entry.
///
/// @param zipName the ZIP entry name
/// @param type the ZIP entry type
/// @param uncompressedSize the uncompressed size stored in the ZIP metadata
/// @param compressedSize the compressed size stored in the ZIP metadata
/// @param modifiedTime the last modified time stored in the ZIP metadata
/// @param crc32 the CRC-32 value stored in the ZIP metadata
/// @param method the ZIP compression method
/// @param encryption the ZIP encryption method
/// @param metadata additional ZIP metadata
@NotNullByDefault
public record ZipArkivoEntryInfo(
        ZipName zipName,
        ArkivoItemType type,
        @Nullable Long uncompressedSize,
        @Nullable Long compressedSize,
        @Nullable FileTime modifiedTime,
        @Nullable Long crc32,
        ZipMethod method,
        ZipEncryption encryption,
        ArkivoMetadata metadata
) implements ArkivoEntryInfo {
    /// Returns the raw encoded ZIP entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return zipName.rawPath();
    }

    /// Returns the decoded ZIP entry path text.
    @Override
    public String path() {
        return zipName.path();
    }
}
