// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;


/// Exposes metadata from one TAR archive entry header.
///
/// Instances read from an indexed file system or streaming reader are stable snapshots. A pending streaming-writer
/// view exposes a read-only projection of the entry being configured and can reflect later attribute-view mutations
/// until that entry is committed. Explicitly recorded timestamps distinguish archive metadata from basic-attribute
/// fallback values.
@NotNullByDefault
public interface TarArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// The TAR type flag for hard link entries.
    byte HARD_LINK_TYPE = '1';

    /// Returns the decoded TAR entry path.
    String path();

    /// Returns the raw TAR type flag byte.
    ///
    /// @return the type flag stored in the effective entry header
    byte typeFlag();

    /// Returns the POSIX mode bits stored by the TAR header.
    ///
    /// @return the non-negative raw mode value, including file-type and permission bits when present
    int mode();

    /// Returns the numeric user identifier stored by the TAR header.
    ///
    /// @return the non-negative numeric user identifier
    long userId();

    /// Returns the numeric group identifier stored by the TAR header.
    ///
    /// @return the non-negative numeric group identifier
    long groupId();

    /// Returns the user name stored by the TAR header, or `null` when absent.
    ///
    /// @return the decoded user name, or `null`
    @Nullable String userName();

    /// Returns the group name stored by the TAR header, or `null` when absent.
    ///
    /// @return the decoded group name, or `null`
    @Nullable String groupName();

    /// Returns whether this entry is a TAR hard link entry.
    ///
    /// @return `true` when `typeFlag()` is `HARD_LINK_TYPE`
    default boolean isHardLink() {
        return typeFlag() == HARD_LINK_TYPE;
    }

    /// Returns the link target stored by the TAR header, or `null` when absent.
    ///
    /// @return the decoded symbolic-link or hard-link target text, or `null`
    @Nullable String linkName();

    /// Returns the last access time explicitly recorded by the archive, or `null` when absent.
    ///
    /// [#lastAccessTime()] returns the last modification time as a non-null fallback when this value is absent.
    ///
    /// @return the explicitly recorded last-access time, or `null`
    @Nullable FileTime recordedLastAccessTime();

    /// Returns the inode status change time explicitly recorded by the archive, or `null` when absent.
    ///
    /// This is the TAR `ctime` value and is distinct from a file creation time.
    ///
    /// @return the explicitly recorded inode status-change time, or `null`
    @Nullable FileTime recordedStatusChangeTime();

    /// Returns the file creation time explicitly recorded by the archive, or `null` when absent.
    ///
    /// [#creationTime()] returns the last modification time as a non-null fallback when this value is absent.
    ///
    /// @return the explicitly recorded creation time, or `null`
    @Nullable FileTime recordedCreationTime();
}
