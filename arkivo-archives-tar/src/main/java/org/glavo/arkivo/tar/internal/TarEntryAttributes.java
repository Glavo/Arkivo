// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.tar.internal;

import org.glavo.arkivo.tar.TarArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;

/// Stores parsed metadata for one TAR entry.
@NotNullByDefault
final class TarEntryAttributes implements TarArkivoEntryAttributes, PosixFileAttributes {
    /// The TAR type flag for regular files.
    static final byte REGULAR_TYPE = '0';

    /// The TAR type flag for regular files in older archives.
    static final byte OLD_REGULAR_TYPE = 0;

    /// The TAR type flag for hard links.
    static final byte HARD_LINK_TYPE = TarArkivoEntryAttributes.HARD_LINK_TYPE;

    /// The TAR type flag for symbolic links.
    static final byte SYMBOLIC_LINK_TYPE = '2';

    /// The TAR type flag for directories.
    static final byte DIRECTORY_TYPE = '5';

    /// The GNU TAR type flag for a long path metadata entry.
    static final byte GNU_LONG_PATH_TYPE = 'L';

    /// The GNU TAR type flag for a long symbolic link metadata entry.
    static final byte GNU_LONG_LINK_TYPE = 'K';

    /// The POSIX PAX type flag for per-entry extended metadata.
    static final byte PAX_EXTENDED_HEADER_TYPE = 'x';

    /// The POSIX PAX type flag for global extended metadata.
    static final byte PAX_GLOBAL_EXTENDED_HEADER_TYPE = 'g';

    /// The decoded entry path.
    private final String path;

    /// The raw type flag.
    private final byte typeFlag;

    /// The POSIX mode bits.
    private final int mode;

    /// The numeric user identifier.
    private final long userId;

    /// The numeric group identifier.
    private final long groupId;

    /// The stored user name.
    private final @Nullable String userName;

    /// The stored group name.
    private final @Nullable String groupName;

    /// The link target stored by the TAR header.
    private final @Nullable String linkName;

    /// The entry size.
    private final long size;

    /// The last modified time.
    private final FileTime lastModifiedTime;

    /// The last access time.
    private final FileTime lastAccessTime;

    /// The creation time.
    private final FileTime creationTime;

    /// Creates parsed TAR entry attributes.
    TarEntryAttributes(
            String path,
            byte typeFlag,
            int mode,
            long userId,
            long groupId,
            @Nullable String userName,
            @Nullable String groupName,
            @Nullable String linkName,
            long size,
            FileTime lastModifiedTime,
            FileTime lastAccessTime,
            FileTime creationTime
    ) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.typeFlag = typeFlag;
        this.mode = mode;
        this.userId = userId;
        this.groupId = groupId;
        this.userName = userName;
        this.groupName = groupName;
        this.linkName = linkName;
        this.size = size;
        this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        this.lastAccessTime = Objects.requireNonNull(lastAccessTime, "lastAccessTime");
        this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
    }

    /// Returns the decoded TAR entry path.
    @Override
    public String path() {
        return path;
    }

    /// Returns the raw TAR type flag byte.
    @Override
    public byte typeFlag() {
        return typeFlag;
    }

    /// Returns the POSIX mode bits.
    @Override
    public int mode() {
        return mode;
    }

    /// Returns the numeric user identifier.
    @Override
    public long userId() {
        return userId;
    }

    /// Returns the numeric group identifier.
    @Override
    public long groupId() {
        return groupId;
    }

    /// Returns the stored user name.
    @Override
    public @Nullable String userName() {
        return userName;
    }

    /// Returns the stored group name.
    @Override
    public @Nullable String groupName() {
        return groupName;
    }

    /// Returns the link target stored by the TAR header.
    @Override
    public @Nullable String linkName() {
        return linkName;
    }

    /// Returns the last modified time.
    @Override
    public FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    /// Returns the last access time.
    @Override
    public FileTime lastAccessTime() {
        return lastAccessTime;
    }

    /// Returns the creation time.
    @Override
    public FileTime creationTime() {
        return creationTime;
    }

    /// Returns whether this entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return hasDataBody() || isHardLink();
    }

    /// Returns whether this entry is a hard link.
    @Override
    public boolean isHardLink() {
        return typeFlag == HARD_LINK_TYPE;
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return typeFlag == DIRECTORY_TYPE;
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return typeFlag == SYMBOLIC_LINK_TYPE;
    }

    /// Returns whether this entry has another TAR type.
    @Override
    public boolean isOther() {
        return !isRegularFile() && !isDirectory() && !isSymbolicLink();
    }

    /// Returns the regular file or hard link size, or zero for non-file entries.
    @Override
    public long size() {
        return isRegularFile() ? size : 0L;
    }

    /// Returns an implementation-specific file key.
    @Override
    public Object fileKey() {
        return path;
    }

    /// Returns the owner principal represented by the stored TAR user name.
    @Override
    public UserPrincipal owner() {
        return TarPosixSupport.owner(userName);
    }

    /// Returns the group principal represented by the stored TAR group name.
    @Override
    public GroupPrincipal group() {
        return TarPosixSupport.group(groupName);
    }

    /// Returns the POSIX permissions encoded by the stored TAR mode bits.
    @Override
    public @Unmodifiable Set<PosixFilePermission> permissions() {
        return TarPosixSupport.permissions(mode);
    }

    /// Returns the raw TAR body size.
    long bodySize() {
        return size;
    }

    /// Returns whether this entry stores file data in its own TAR body.
    boolean hasDataBody() {
        return typeFlag == REGULAR_TYPE || typeFlag == OLD_REGULAR_TYPE;
    }
}
