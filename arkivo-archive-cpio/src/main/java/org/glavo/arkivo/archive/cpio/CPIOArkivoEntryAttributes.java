// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Exposes metadata stored by one CPIO entry header.
@NotNullByDefault
public interface CPIOArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// The sentinel returned for numeric fields that the current CPIO dialect does not store.
    long NOT_STORED = -1L;

    /// The size returned by a pending regular-file entry whose body size has not been configured yet.
    long UNKNOWN_SIZE = -1L;

    /// The checksum returned by a pending CRC entry before its body has been committed.
    long UNKNOWN_CHECKSUM = -2L;

    /// Returns the body size, or `UNKNOWN_SIZE` for a pending regular file without a configured size.
    @Override
    long size();

    /// Returns the header dialect used by this entry.
    CPIODialect dialect();

    /// Returns the old binary word byte order, or `null` for an ASCII dialect.
    @Nullable CPIOBinaryByteOrder binaryByteOrder();

    /// Returns the inode number.
    long inode();

    /// Returns the numeric user identifier.
    long userId();

    /// Returns the numeric group identifier.
    long groupId();

    /// Returns the link count.
    long linkCount();

    /// Returns the POSIX mode and file-type bits.
    int mode();

    /// Returns the legacy device field, or `NOT_STORED` for a new ASCII entry.
    long device();

    /// Returns the legacy remote-device field, or `NOT_STORED` for a new ASCII entry.
    long remoteDevice();

    /// Returns the device major number, or `NOT_STORED` for an old entry.
    long deviceMajor();

    /// Returns the device minor number, or `NOT_STORED` for an old entry.
    long deviceMinor();

    /// Returns the remote-device major number, or `NOT_STORED` for an old entry.
    long remoteDeviceMajor();

    /// Returns the remote-device minor number, or `NOT_STORED` for an old entry.
    long remoteDeviceMinor();

    /// Returns the unsigned data checksum, `UNKNOWN_CHECKSUM` before a pending CRC body is committed, or
    /// `NOT_STORED` when the dialect has no checksum field.
    long checksum();
}
