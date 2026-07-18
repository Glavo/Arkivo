// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.cpio;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Reads and configures CPIO-specific metadata for one pending streaming entry.
///
/// [#readAttributes()] returns a stable snapshot. Mutators are valid only while the entry is pending and reject calls
/// after its body has been opened or the entry has otherwise been committed. Numeric fields must be non-negative and
/// must fit the selected dialect when the header is committed.
@NotNullByDefault
public interface CPIOArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "cpio";
    }

    /// Reads a snapshot of the pending CPIO entry metadata.
    @Override
    CPIOArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the non-negative inode number.
    ///
    /// @param inode the replacement inode number
    /// @throws IllegalArgumentException if `inode` is negative
    /// @throws IOException if the entry is no longer pending
    void setInode(long inode) throws IOException;

    /// Sets the non-negative numeric user identifier.
    ///
    /// @param userId the replacement user identifier
    /// @throws IllegalArgumentException if `userId` is negative
    /// @throws IOException if the entry is no longer pending
    void setUserId(long userId) throws IOException;

    /// Sets the non-negative numeric group identifier.
    ///
    /// @param groupId the replacement group identifier
    /// @throws IllegalArgumentException if `groupId` is negative
    /// @throws IOException if the entry is no longer pending
    void setGroupId(long groupId) throws IOException;

    /// Sets the positive link count.
    ///
    /// @param linkCount the replacement link count
    /// @throws IllegalArgumentException if `linkCount` is not positive
    /// @throws IOException if the entry is no longer pending
    void setLinkCount(long linkCount) throws IOException;

    /// Sets non-negative POSIX mode bits whose file type matches the entry begin operation.
    ///
    /// @param mode the replacement mode, including file-type and permission bits
    /// @throws IllegalArgumentException if `mode` is negative or describes an incompatible entry type
    /// @throws IOException if the entry is no longer pending
    void setMode(int mode) throws IOException;

    /// Sets the legacy device field used by old CPIO dialects.
    ///
    /// @param device the non-negative legacy device value
    /// @throws IllegalArgumentException if `device` is negative
    /// @throws IOException if the entry is no longer pending
    void setDevice(long device) throws IOException;

    /// Sets the legacy remote-device field used by old CPIO dialects.
    ///
    /// @param remoteDevice the non-negative legacy remote-device value
    /// @throws IllegalArgumentException if `remoteDevice` is negative
    /// @throws IOException if the entry is no longer pending
    void setRemoteDevice(long remoteDevice) throws IOException;

    /// Sets the device major and minor numbers used by new ASCII dialects.
    ///
    /// @param major the non-negative device major number
    /// @param minor the non-negative device minor number
    /// @throws IllegalArgumentException if either number is negative
    /// @throws IOException if the entry is no longer pending
    void setDeviceNumbers(long major, long minor) throws IOException;

    /// Sets the remote-device major and minor numbers used by new ASCII dialects.
    ///
    /// @param major the non-negative remote-device major number
    /// @param minor the non-negative remote-device minor number
    /// @throws IllegalArgumentException if either number is negative
    /// @throws IOException if the entry is no longer pending
    void setRemoteDeviceNumbers(long major, long minor) throws IOException;

    /// Sets the non-negative expected entry data size before its body is opened.
    ///
    /// The size must fit the selected dialect and must equal the fixed body size of a directory or symbolic link.
    ///
    /// @param size the expected body size, in bytes
    /// @throws IllegalArgumentException if `size` is negative or exceeds the selected dialect's range
    /// @throws IOException if a fixed body has a different size or the entry is no longer pending
    void setSize(long size) throws IOException;
}
