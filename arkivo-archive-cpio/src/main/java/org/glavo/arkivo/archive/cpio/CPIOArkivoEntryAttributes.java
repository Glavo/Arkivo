// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Represents a stable snapshot of metadata stored by one CPIO entry header.
///
/// A snapshot read from a pending streaming-writer entry remains valid after later configuration changes and after
/// the entry is committed. Fields not represented by the selected dialect use the documented sentinels.
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
    ///
    /// @return the dialect that defines the entry header layout
    CPIODialect dialect();

    /// Returns the old binary word byte order, or `null` for an ASCII dialect.
    ///
    /// @return the old-binary byte order, or `null` when the dialect is ASCII
    @Nullable CPIOBinaryByteOrder binaryByteOrder();

    /// Returns the inode number.
    ///
    /// @return the stored non-negative inode number
    long inode();

    /// Returns the numeric user identifier.
    ///
    /// @return the stored non-negative user identifier
    long userId();

    /// Returns the numeric group identifier.
    ///
    /// @return the stored non-negative group identifier
    long groupId();

    /// Returns the link count.
    ///
    /// @return the positive stored link count
    long linkCount();

    /// Returns the POSIX mode and file-type bits.
    ///
    /// @return the non-negative stored mode
    int mode();

    /// Returns the legacy device field, or `NOT_STORED` for a new ASCII entry.
    ///
    /// @return the legacy device value or `NOT_STORED`
    long device();

    /// Returns the legacy remote-device field, or `NOT_STORED` for a new ASCII entry.
    ///
    /// @return the legacy remote-device value or `NOT_STORED`
    long remoteDevice();

    /// Returns the device major number, or `NOT_STORED` for an old entry.
    ///
    /// @return the device major number or `NOT_STORED`
    long deviceMajor();

    /// Returns the device minor number, or `NOT_STORED` for an old entry.
    ///
    /// @return the device minor number or `NOT_STORED`
    long deviceMinor();

    /// Returns the remote-device major number, or `NOT_STORED` for an old entry.
    ///
    /// @return the remote-device major number or `NOT_STORED`
    long remoteDeviceMajor();

    /// Returns the remote-device minor number, or `NOT_STORED` for an old entry.
    ///
    /// @return the remote-device minor number or `NOT_STORED`
    long remoteDeviceMinor();

    /// Returns the unsigned data checksum, `UNKNOWN_CHECKSUM` before a pending CRC body is committed, or
    /// `NOT_STORED` when the dialect has no checksum field.
    ///
    /// @return the unsigned checksum or the applicable sentinel
    long checksum();
}
