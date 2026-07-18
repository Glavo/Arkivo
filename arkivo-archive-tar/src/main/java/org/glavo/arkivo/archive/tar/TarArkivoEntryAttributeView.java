// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;

/// Provides TAR-specific attributes for an archive entry.
@NotNullByDefault
public interface TarArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "tar";
    }

    /// Reads the TAR-specific entry attributes.
    @Override
    TarArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the numeric user identifier stored by the TAR header.
    void setUserId(long userId) throws IOException;

    /// Sets the numeric group identifier stored by the TAR header.
    void setGroupId(long groupId) throws IOException;

    /// Sets the POSIX mode bits stored by the TAR header.
    void setMode(int mode) throws IOException;

    /// Sets the user name stored by the TAR header, or clears it when `null`.
    void setUserName(@Nullable String userName) throws IOException;

    /// Sets the group name stored by the TAR header, or clears it when `null`.
    void setGroupName(@Nullable String groupName) throws IOException;

    /// Sets the last access time explicitly recorded by the archive, or clears it when `null`.
    void setRecordedLastAccessTime(@Nullable FileTime lastAccessTime) throws IOException;

    /// Sets the inode status change time explicitly recorded by the archive, or clears it when `null`.
    void setRecordedStatusChangeTime(@Nullable FileTime statusChangeTime) throws IOException;

    /// Sets the creation time explicitly recorded by the archive, or clears it when `null`.
    void setRecordedCreationTime(@Nullable FileTime creationTime) throws IOException;
}
