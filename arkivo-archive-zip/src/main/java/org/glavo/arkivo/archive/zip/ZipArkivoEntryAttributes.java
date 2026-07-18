// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.PosixFileAttributes;

/// Represents a stable snapshot of ZIP-specific and synthesized POSIX attributes for an archive entry path.
///
/// Snapshot values remain valid after a streaming reader advances, after a pending writer entry is reconfigured, and
/// after later file-system mutations. Raw byte arrays are defensively copied. Metadata unavailable from a streaming
/// local header or absent from the archive uses the documented sentinels or null values.
@NotNullByDefault
public interface ZipArkivoEntryAttributes extends PosixFileAttributes, ArchiveEntryAttributes {
    /// The numeric value returned when a ZIP entry size is not known.
    long UNKNOWN_SIZE = -1L;

    /// The numeric value returned when a ZIP entry CRC-32 value is not known.
    long UNKNOWN_CRC32 = -1L;

    /// The numeric value returned when a ZIP entry has no Unix user or group identifier.
    long UNKNOWN_UNIX_ID = -1L;

    /// Returns a copy of the raw encoded ZIP entry path bytes.
    ///
    /// @return a newly allocated array containing the encoded path bytes
    byte @Unmodifiable [] rawPath();

    /// Returns the decoded ZIP entry path text with archive separators normalized to `/`.
    String path();

    /// Returns the decoded ZIP entry comment text, or `null` when no comment is present.
    ///
    /// @return the decoded comment, or `null` when absent
    @Nullable String comment();

    /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
    ///
    /// @return the compressed size, or [#UNKNOWN_SIZE] when unknown
    long compressedSize();

    /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
    ///
    /// @return the unsigned 32-bit CRC-32 value, or [#UNKNOWN_CRC32] when unknown
    long crc32();

    /// Returns the general purpose bit flags stored for the ZIP entry.
    ///
    /// @return the unsigned 16-bit general purpose bit flags
    int generalPurposeFlags();

    /// Returns the ZIP version made by field.
    ///
    /// @return the unsigned 16-bit version made by field
    int versionMadeBy();

    /// Returns the ZIP version needed to extract field.
    ///
    /// @return the unsigned 16-bit version needed to extract field
    int versionNeededToExtract();

    /// Returns the ZIP internal file attributes.
    ///
    /// @return the unsigned 16-bit internal file attributes
    int internalAttributes();

    /// Returns the ZIP external file attributes.
    ///
    /// @return the unsigned 32-bit external file attributes
    long externalAttributes();

    /// Returns the numeric Unix owner user identifier, or `UNKNOWN_UNIX_ID` when absent.
    ///
    /// @return the unsigned Unix user identifier, or [#UNKNOWN_UNIX_ID] when absent
    long userId();

    /// Returns the numeric Unix owner group identifier, or `UNKNOWN_UNIX_ID` when absent.
    ///
    /// @return the unsigned Unix group identifier, or [#UNKNOWN_UNIX_ID] when absent
    long groupId();

    /// Returns the numeric ZIP compression method identifier after resolving WinZip AES metadata.
    ///
    /// @return the unsigned 16-bit compression method identifier
    int compressionMethodId();

    /// Returns the recognized ZIP compression method, or `null` when the method identifier is unknown.
    ///
    /// @return the recognized compression method, or `null` when it is unknown
    @Nullable ZipMethod compressionMethod();

    /// Returns [ZipEncryption#NONE] for an unencrypted entry, a recognized encryption method otherwise, or `null`
    /// when encrypted metadata is unrecognized or malformed.
    ///
    /// @return the recognized encryption method, [ZipEncryption#NONE] for an unencrypted entry, or `null` when the
    /// metadata does not identify a supported method
    @Nullable ZipEncryption encryption();

    /// Returns a copy of the raw local file header extra data bytes.
    ///
    /// @return a newly allocated array containing the local-header extra data
    byte @Unmodifiable [] localExtraData();

    /// Returns a copy of the raw central directory extra data bytes.
    ///
    /// @return a newly allocated array containing the central-directory extra data
    byte @Unmodifiable [] centralDirectoryExtraData();

    /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
    ///
    /// @return a newly allocated array containing the raw comment, or `null` when absent
    byte @Nullable @Unmodifiable [] rawComment();
}
