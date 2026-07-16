// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.rar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;


/// Exposes metadata parsed from one RAR archive file header.
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
    String path();

    /// Returns the RAR host operating system value.
    int hostOs();

    /// Returns the raw operating-system-specific file attributes.
    long fileAttributes();

    /// Returns the RAR compression method number.
    int compressionMethod();

    /// Returns the packed data size stored in this archive block.
    long packedSize();

    /// Returns the unpacked data size, or `UNKNOWN_NUMERIC_VALUE` when the archive marks it as unknown.
    long unpackedSize();

    /// Returns the unsigned data CRC32 value, or `UNKNOWN_NUMERIC_VALUE` when absent.
    long dataCrc32();

    /// Returns the 32-byte BLAKE2sp checksum representation stored in the file hash extra record, or `null` when absent.
    /// For an encrypted file using tweaked checksums, these bytes are the HMAC-SHA256 representation of the raw BLAKE2sp
    /// digest rather than the raw digest itself.
    byte @Nullable @Unmodifiable [] blake2spHash();

    /// Returns whether this entry data is encrypted.
    boolean isEncrypted();

    /// Returns whether this entry data continues from a previous volume.
    boolean continuesFromPreviousVolume();

    /// Returns whether this entry data continues in the next volume.
    boolean continuesInNextVolume();

    /// Returns the symbolic link target, or `null` when this entry is not a symbolic link.
    @Nullable String linkName();

    /// Returns the RAR redirection type, or `NO_REDIRECTION_TYPE` when absent.
    int redirectionType();

    /// Returns the RAR redirection flags.
    long redirectionFlags();

    /// Returns the RAR redirection target, or `null` when absent.
    @Nullable String redirectionTarget();

    /// Returns whether the RAR redirection target is a directory.
    boolean redirectionTargetDirectory();

    /// Returns the Unix owner user name, or `null` when absent.
    @Nullable String userName();

    /// Returns the Unix owner group name, or `null` when absent.
    @Nullable String groupName();

    /// Returns the numeric Unix owner user identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    long userId();

    /// Returns the numeric Unix owner group identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    long groupId();
}
