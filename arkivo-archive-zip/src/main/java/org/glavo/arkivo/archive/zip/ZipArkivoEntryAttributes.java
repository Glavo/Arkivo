// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFileAttributes;

/// Exposes ZIP-specific and synthesized POSIX file attributes for an archive entry path.
@NotNullByDefault
public interface ZipArkivoEntryAttributes extends PosixFileAttributes, ArchiveEntryAttributes {
    /// The numeric value returned when a ZIP entry size is not known.
    long UNKNOWN_SIZE = -1L;

    /// The numeric value returned when a ZIP entry CRC-32 value is not known.
    long UNKNOWN_CRC32 = -1L;

    /// The numeric value returned when a ZIP entry has no Unix user or group identifier.
    long UNKNOWN_UNIX_ID = -1L;

    /// Returns a copy of the raw encoded ZIP entry path bytes.
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded ZIP entry path text with archive separators normalized to `/`.
    String path();

    /// Returns the decoded ZIP entry comment text, or `null` when no comment is present.
    @Nullable String comment();

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

    /// Returns the numeric Unix owner user identifier, or `UNKNOWN_UNIX_ID` when absent.
    long userId();

    /// Returns the numeric Unix owner group identifier, or `UNKNOWN_UNIX_ID` when absent.
    long groupId();

    /// Returns the numeric ZIP compression method identifier after resolving WinZip AES metadata.
    int compressionMethodId();

    /// Returns the recognized ZIP compression method, or `null` when the method identifier is unknown.
    @Nullable ZipMethod compressionMethod();

    /// Returns [ZipEncryption#NONE] for an unencrypted entry, a recognized encryption method otherwise, or `null`
    /// when encrypted metadata is unrecognized or malformed.
    @Nullable ZipEncryption encryption();

    /// Returns a copy of the raw local file header extra data bytes.
    byte @Unmodifiable [] localExtraData();

    /// Returns a copy of the raw central directory extra data bytes.
    byte @Unmodifiable [] centralDirectoryExtraData();

    /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
    byte @Nullable @Unmodifiable [] rawComment();
}
