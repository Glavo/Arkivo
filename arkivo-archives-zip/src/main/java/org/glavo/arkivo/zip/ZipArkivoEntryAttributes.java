// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFileAttributes;

/// Exposes ZIP-specific and synthesized POSIX file attributes for an archive entry path.
@NotNullByDefault
public interface ZipArkivoEntryAttributes extends PosixFileAttributes {
    /// The numeric value returned when a ZIP entry size is not known.
    long UNKNOWN_SIZE = -1L;

    /// The numeric value returned when a ZIP entry CRC-32 value is not known.
    long UNKNOWN_CRC32 = -1L;

    /// Returns the raw encoded ZIP entry path bytes.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded ZIP entry path text.
    String path();

    /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
    long compressedSize();

    /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
    long crc32();

    /// Returns the general purpose bit flags stored for the ZIP entry.
    int generalPurposeFlags();

    /// Returns the ZIP version made by field.
    int versionMadeBy();

    /// Returns the ZIP version needed to extract field.
    int versionNeededToExtract();

    /// Returns the ZIP internal file attributes.
    int internalAttributes();

    /// Returns the ZIP external file attributes.
    long externalAttributes();

    /// Returns the ZIP compression method.
    ZipMethod method();

    /// Returns the ZIP encryption method.
    ZipEncryption encryption();

    /// Returns the raw local file header extra data bytes.
    byte @Unmodifiable [] localExtraData();

    /// Returns the raw central directory extra data bytes.
    byte @Unmodifiable [] centralDirectoryExtraData();

    /// Returns the raw ZIP entry comment bytes, or `null` when no comment is present.
    byte @Nullable @Unmodifiable [] rawComment();

    /// Returns the decoded ZIP entry comment, or `null` when no comment is present.
    @Nullable String comment();
}
