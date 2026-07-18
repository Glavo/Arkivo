// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;


/// Represents a stable snapshot of metadata parsed from one RAR archive file header.
///
/// Snapshot values remain valid after the file-system path changes state and after a streaming reader advances. Array
/// values are defensively copied. Optional numeric and object properties use the documented sentinels or null values.
@NotNullByDefault
public interface RarArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// The sentinel for optional numeric values that are absent or unknown.
    long UNKNOWN_NUMERIC_VALUE = -1L;

    /// The RAR host operating system value for Windows entries.
    int HOST_OS_WINDOWS = 0;

    /// The RAR host operating system value for Unix entries.
    int HOST_OS_UNIX = 1;

    /// The sentinel returned when an entry has no file system redirection record.
    int NO_REDIRECTION_TYPE = -1;

    /// The RAR redirection type for Unix symbolic links.
    int REDIRECTION_TYPE_UNIX_SYMLINK = 1;

    /// The RAR redirection type for Windows symbolic links.
    int REDIRECTION_TYPE_WINDOWS_SYMLINK = 2;

    /// The RAR redirection type for Windows junctions.
    int REDIRECTION_TYPE_WINDOWS_JUNCTION = 3;

    /// The RAR redirection type for hard links.
    int REDIRECTION_TYPE_HARD_LINK = 4;

    /// The RAR redirection type for file copies.
    int REDIRECTION_TYPE_FILE_COPY = 5;

    /// The RAR redirection flag indicating that the redirection target is a directory.
    long REDIRECTION_FLAG_TARGET_DIRECTORY = 0x0001L;

    /// Returns the decoded RAR entry path.
    ///
    /// @return the normalized archive path recorded for this entry
    String path();

    /// Returns the RAR host operating system value.
    ///
    /// @return the raw host operating system identifier
    int hostOs();

    /// Returns the raw operating-system-specific file attributes.
    ///
    /// @return the unsigned file attribute bit field
    long fileAttributes();

    /// Returns the RAR compression method number.
    ///
    /// @return the raw compression method number from the file header
    int compressionMethod();

    /// Returns the packed data size stored in this archive block.
    ///
    /// @return the number of compressed bytes stored for this block
    long packedSize();

    /// Returns the unpacked data size, or `UNKNOWN_NUMERIC_VALUE` when the archive marks it as unknown.
    ///
    /// @return the logical entry size, or `UNKNOWN_NUMERIC_VALUE`
    long unpackedSize();

    /// Returns the unsigned data CRC32 value, or `UNKNOWN_NUMERIC_VALUE` when absent.
    ///
    /// @return the CRC32 value in the range 0 through `0xffffffffL`, or `UNKNOWN_NUMERIC_VALUE`
    long dataCrc32();

    /// Returns the 32-byte BLAKE2sp checksum representation stored in the file hash extra record, or `null` when absent.
    /// For an encrypted file using tweaked checksums, these bytes are the HMAC-SHA256 representation of the raw BLAKE2sp
    /// digest rather than the raw digest itself.
    ///
    /// @return a defensive copy of the stored hash representation, or `null`
    byte @Nullable @Unmodifiable [] blake2spHash();

    /// Returns whether this entry data is encrypted.
    ///
    /// @return `true` if opening the entry body requires decryption
    boolean isEncrypted();

    /// Returns whether this entry data continues from a previous volume.
    ///
    /// @return `true` if the first bytes of this entry occur in an earlier volume
    boolean continuesFromPreviousVolume();

    /// Returns whether this entry data continues in the next volume.
    ///
    /// @return `true` if the entry body continues in a later volume
    boolean continuesInNextVolume();

    /// Returns the symbolic link target, or `null` when this entry is not a symbolic link.
    ///
    /// @return the decoded symbolic-link target, or `null`
    @Nullable String linkName();

    /// Returns the RAR redirection type, or `NO_REDIRECTION_TYPE` when absent.
    ///
    /// @return the raw redirection type, or `NO_REDIRECTION_TYPE`
    int redirectionType();

    /// Returns the RAR redirection flags.
    ///
    /// @return the raw redirection flag bit field, or zero when no redirection record is present
    long redirectionFlags();

    /// Returns the RAR redirection target, or `null` when absent.
    ///
    /// @return the decoded redirection target, or `null`
    @Nullable String redirectionTarget();

    /// Returns whether the RAR redirection target is a directory.
    ///
    /// @return `true` if the redirection record identifies a directory target
    boolean redirectionTargetDirectory();

    /// Returns the Unix owner user name, or `null` when absent.
    ///
    /// @return the decoded owner user name, or `null`
    @Nullable String userName();

    /// Returns the Unix owner group name, or `null` when absent.
    ///
    /// @return the decoded owner group name, or `null`
    @Nullable String groupName();

    /// Returns the numeric Unix owner user identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    ///
    /// @return the unsigned owner user identifier, or `UNKNOWN_NUMERIC_VALUE`
    long userId();

    /// Returns the numeric Unix owner group identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    ///
    /// @return the unsigned owner group identifier, or `UNKNOWN_NUMERIC_VALUE`
    long groupId();
}
