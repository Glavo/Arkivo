// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;

/// Reads and configures TAR-specific attributes for an archive entry.
///
/// Mutators are supported by update-enabled indexed file systems and by the current pending streaming-writer entry.
/// They fail for read-only file systems and after a pending entry has been committed. Indexed reads are snapshots;
/// attributes read from a pending streaming entry can reflect subsequent mutations until commit.
@NotNullByDefault
public interface TarArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "tar";
    }

    /// Reads the TAR-specific entry attributes for the current view state.
    @Override
    TarArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the non-negative numeric user identifier stored by the TAR header.
    ///
    /// @param userId the non-negative replacement user identifier
    /// @throws IllegalArgumentException if `userId` is negative
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setUserId(long userId) throws IOException;

    /// Sets the non-negative numeric group identifier stored by the TAR header.
    ///
    /// @param groupId the non-negative replacement group identifier
    /// @throws IllegalArgumentException if `groupId` is negative
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setGroupId(long groupId) throws IOException;

    /// Sets the non-negative POSIX mode bits stored by the TAR header.
    ///
    /// @param mode the non-negative raw TAR mode value
    /// @throws IllegalArgumentException if `mode` is negative
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setMode(int mode) throws IOException;

    /// Sets the user name stored by the TAR header, or clears it when `null`.
    ///
    /// @param userName the replacement decoded user name, or `null` to omit it
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setUserName(@Nullable String userName) throws IOException;

    /// Sets the group name stored by the TAR header, or clears it when `null`.
    ///
    /// @param groupName the replacement decoded group name, or `null` to omit it
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setGroupName(@Nullable String groupName) throws IOException;

    /// Sets the last access time explicitly recorded by the archive, or clears it when `null`.
    ///
    /// @param lastAccessTime the replacement recorded last-access time, or `null` to omit it
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setRecordedLastAccessTime(@Nullable FileTime lastAccessTime) throws IOException;

    /// Sets the inode status change time explicitly recorded by the archive, or clears it when `null`.
    ///
    /// @param statusChangeTime the replacement recorded inode status-change time, or `null` to omit it
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setRecordedStatusChangeTime(@Nullable FileTime statusChangeTime) throws IOException;

    /// Sets the creation time explicitly recorded by the archive, or clears it when `null`.
    ///
    /// @param creationTime the replacement recorded creation time, or `null` to omit it
    /// @throws IOException if the entry is unavailable or its pending metadata cannot be updated
    void setRecordedCreationTime(@Nullable FileTime creationTime) throws IOException;
}
