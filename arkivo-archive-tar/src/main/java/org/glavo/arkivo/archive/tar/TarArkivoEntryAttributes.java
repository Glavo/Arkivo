// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.tar;

import org.glavo.arkivo.archive.ArchiveEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;


/// Exposes metadata parsed from one TAR archive entry header.
@NotNullByDefault
public interface TarArkivoEntryAttributes extends ArchiveEntryAttributes {
    /// The TAR type flag for hard link entries.
    byte HARD_LINK_TYPE = '1';

    /// Returns the decoded TAR entry path.
    String path();

    /// Returns the raw TAR type flag byte.
    byte typeFlag();

    /// Returns the POSIX mode bits stored by the TAR header.
    int mode();

    /// Returns the numeric user identifier stored by the TAR header.
    long userId();

    /// Returns the numeric group identifier stored by the TAR header.
    long groupId();

    /// Returns the user name stored by the TAR header, or `null` when absent.
    @Nullable String userName();

    /// Returns the group name stored by the TAR header, or `null` when absent.
    @Nullable String groupName();

    /// Returns whether this entry is a TAR hard link entry.
    default boolean isHardLink() {
        return typeFlag() == HARD_LINK_TYPE;
    }

    /// Returns the link target stored by the TAR header, or `null` when absent.
    @Nullable String linkName();

    /// Returns the last access time explicitly recorded by the archive, or `null` when absent.
    ///
    /// [#lastAccessTime()] returns the last modification time as a non-null fallback when this value is absent.
    @Nullable FileTime recordedLastAccessTime();

    /// Returns the inode status change time explicitly recorded by the archive, or `null` when absent.
    ///
    /// This is the TAR `ctime` value and is distinct from a file creation time.
    @Nullable FileTime recordedStatusChangeTime();

    /// Returns the file creation time explicitly recorded by the archive, or `null` when absent.
    ///
    /// [#creationTime()] returns the last modification time as a non-null fallback when this value is absent.
    @Nullable FileTime recordedCreationTime();
}
