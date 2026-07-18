// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio.internal;

import org.glavo.arkivo.archive.cpio.CPIOArkivoEntryAttributes;
import org.glavo.arkivo.archive.cpio.CPIOBinaryByteOrder;
import org.glavo.arkivo.archive.cpio.CPIODialect;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores one immutable CPIO entry metadata snapshot.
///
/// @param path the normalized archive-local path
/// @param dialect the entry header dialect
/// @param binaryByteOrder the old binary word byte order, or `null` for ASCII headers
/// @param inode the inode number
/// @param userId the numeric user identifier
/// @param groupId the numeric group identifier
/// @param linkCount the link count
/// @param mode the POSIX mode and file-type bits
/// @param device the legacy device field or `NOT_STORED`
/// @param remoteDevice the legacy remote-device field or `NOT_STORED`
/// @param deviceMajor the device major number or `NOT_STORED`
/// @param deviceMinor the device minor number or `NOT_STORED`
/// @param remoteDeviceMajor the remote-device major number or `NOT_STORED`
/// @param remoteDeviceMinor the remote-device minor number or `NOT_STORED`
/// @param checksum the stored checksum, `UNKNOWN_CHECKSUM`, or `NOT_STORED`
/// @param lastModifiedTime the entry modification time
/// @param size the entry data size or `UNKNOWN_SIZE`
@NotNullByDefault
record CPIOEntryAttributes(
        String path,
        CPIODialect dialect,
        @Nullable CPIOBinaryByteOrder binaryByteOrder,
        long inode,
        long userId,
        long groupId,
        long linkCount,
        int mode,
        long device,
        long remoteDevice,
        long deviceMajor,
        long deviceMinor,
        long remoteDeviceMajor,
        long remoteDeviceMinor,
        long checksum,
        FileTime lastModifiedTime,
        long size
) implements CPIOArkivoEntryAttributes {
    /// Validates one immutable metadata snapshot.
    CPIOEntryAttributes {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        if (path.isEmpty() || inode < 0L || userId < 0L || groupId < 0L || linkCount < 0L
                || mode < 0 || size < CPIOArkivoEntryAttributes.UNKNOWN_SIZE
                || checksum < CPIOArkivoEntryAttributes.UNKNOWN_CHECKSUM) {
            throw new IllegalArgumentException("Invalid CPIO entry metadata");
        }
    }

    /// Returns whether this entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return CPIOPosixSupport.isRegularFile(mode);
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return CPIOPosixSupport.isDirectory(mode);
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return CPIOPosixSupport.isSymbolicLink(mode);
    }

    /// Returns whether this entry has another file type.
    @Override
    public boolean isOther() {
        return CPIOPosixSupport.isOther(mode);
    }

    /// Returns the entry modification time as its access time.
    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime;
    }

    /// Returns the entry modification time as its creation time.
    @Override
    public FileTime creationTime() {
        return lastModifiedTime;
    }

    /// Returns no stable file key for a streaming CPIO entry.
    @Override
    public @Nullable Object fileKey() {
        return null;
    }
}
