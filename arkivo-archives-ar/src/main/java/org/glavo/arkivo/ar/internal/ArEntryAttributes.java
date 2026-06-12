// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.ar.internal;

import org.glavo.arkivo.ar.ArArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores parsed metadata for one AR archive member.
@NotNullByDefault
final class ArEntryAttributes implements ArArkivoEntryAttributes {
    /// The decoded member path.
    private final String path;

    /// The raw header identifier.
    private final String identifier;

    /// The numeric user identifier.
    private final long userId;

    /// The numeric group identifier.
    private final long groupId;

    /// The POSIX mode bits.
    private final int mode;

    /// The member data size.
    private final long size;

    /// The last modified time.
    private final FileTime lastModifiedTime;

    /// Creates parsed AR member attributes.
    ArEntryAttributes(
            String path,
            String identifier,
            long userId,
            long groupId,
            int mode,
            long size,
            FileTime lastModifiedTime
    ) {
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.identifier = Objects.requireNonNull(identifier, "identifier");
        this.userId = userId;
        this.groupId = groupId;
        this.mode = mode;
        this.size = size;
        this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
    }

    /// Returns the decoded archive member path.
    @Override
    public String path() {
        return path;
    }

    /// Returns the raw AR member identifier before long-name resolution.
    @Override
    public String identifier() {
        return identifier;
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

    /// Returns the POSIX mode bits.
    @Override
    public int mode() {
        return mode;
    }

    /// Returns the last modified time.
    @Override
    public FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    /// Returns the last access time.
    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime;
    }

    /// Returns the creation time.
    @Override
    public FileTime creationTime() {
        return lastModifiedTime;
    }

    /// Returns whether this member is a regular file.
    @Override
    public boolean isRegularFile() {
        return true;
    }

    /// Returns whether this member is a directory.
    @Override
    public boolean isDirectory() {
        return false;
    }

    /// Returns whether this member is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    /// Returns whether this member has another file type.
    @Override
    public boolean isOther() {
        return false;
    }

    /// Returns the member data size.
    @Override
    public long size() {
        return size;
    }

    /// Returns an implementation-specific file key.
    @Override
    public Object fileKey() {
        return path;
    }
}
