// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.dmg.internal;

import org.glavo.arkivo.archive.internal.NamedGroupPrincipal;
import org.glavo.arkivo.archive.internal.NamedUserPrincipal;
import org.glavo.arkivo.archive.internal.PosixModes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Stores one immutable HFS Plus catalog entry and its indexed child paths.
@NotNullByDefault
final class HFSPlusNode implements PosixFileAttributes {
    /// The HFS Plus catalog node identifier.
    private final long id;

    /// The parent catalog node identifier.
    private final long parentId;

    /// The on-disk catalog name mapped to an Arkivo path element.
    private final String name;

    /// The normalized archive-local path, assigned after hierarchy resolution.
    private String path = "";

    /// Whether this node is a directory.
    private final boolean directory;

    /// Whether this node is a symbolic link.
    private final boolean symbolicLink;

    /// Whether this node has a non-regular special file type.
    private final boolean other;

    /// The file's data fork, or `null` for a directory.
    private final @Nullable HFSPlusFork fork;

    /// The immutable POSIX permission set.
    private final @Unmodifiable Set<PosixFilePermission> permissions;

    /// The archive-local numeric owner principal.
    private final UserPrincipal owner;

    /// The archive-local numeric group principal.
    private final GroupPrincipal group;

    /// The recorded content modification time.
    private final FileTime modificationTime;

    /// The recorded access time.
    private final FileTime accessTime;

    /// The recorded creation time.
    private final FileTime creationTime;

    /// Child paths keyed by exact path element, populated during hierarchy resolution.
    private final LinkedHashMap<String, String> children = new LinkedHashMap<>();

    /// Creates one parsed catalog node.
    HFSPlusNode(
            long id,
            long parentId,
            String name,
            boolean directory,
            boolean symbolicLink,
            boolean other,
            @Nullable HFSPlusFork fork,
            int mode,
            long ownerId,
            long groupId,
            FileTime modificationTime,
            FileTime accessTime,
            FileTime creationTime
    ) {
        this.id = id;
        this.parentId = parentId;
        this.name = Objects.requireNonNull(name, "name");
        this.directory = directory;
        this.symbolicLink = symbolicLink;
        this.other = other;
        this.fork = fork;
        this.permissions = PosixModes.permissions(mode);
        this.owner = new NamedUserPrincipal(Long.toUnsignedString(ownerId));
        this.group = new NamedGroupPrincipal(Long.toUnsignedString(groupId));
        this.modificationTime = Objects.requireNonNull(modificationTime, "modificationTime");
        this.accessTime = Objects.requireNonNull(accessTime, "accessTime");
        this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
    }

    /// Returns the catalog node identifier.
    long id() {
        return id;
    }

    /// Returns the parent catalog node identifier.
    long parentId() {
        return parentId;
    }

    /// Returns the mapped catalog name.
    String name() {
        return name;
    }

    /// Returns the normalized archive path.
    String path() {
        return path;
    }

    /// Assigns the normalized archive path once during hierarchy resolution.
    void setPath(String value) {
        path = Objects.requireNonNull(value, "value");
    }

    /// Returns the data fork, or `null` for a directory.
    @Nullable HFSPlusFork fork() {
        return fork;
    }

    /// Returns the mutable child index used only during volume construction.
    Map<String, String> mutableChildren() {
        return children;
    }

    /// Returns child paths in catalog traversal order.
    @Unmodifiable List<String> childPaths() {
        return List.copyOf(children.values());
    }

    /// Returns the recorded content modification time.
    @Override
    public FileTime lastModifiedTime() {
        return modificationTime;
    }

    /// Returns the recorded access time.
    @Override
    public FileTime lastAccessTime() {
        return accessTime;
    }

    /// Returns the recorded creation time.
    @Override
    public FileTime creationTime() {
        return creationTime;
    }

    /// Returns whether the entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return !directory && !symbolicLink && !other;
    }

    /// Returns whether the entry is a directory.
    @Override
    public boolean isDirectory() {
        return directory;
    }

    /// Returns whether the entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return symbolicLink;
    }

    /// Returns whether the entry has another special file type.
    @Override
    public boolean isOther() {
        return other;
    }

    /// Returns the logical data-fork size, or zero for a directory.
    @Override
    public long size() {
        return fork != null ? fork.logicalSize() : 0L;
    }

    /// Returns the stable catalog node identifier as this snapshot's file key.
    @Override
    public Object fileKey() {
        return id;
    }

    /// Returns the archive-local numeric owner principal.
    @Override
    public UserPrincipal owner() {
        return owner;
    }

    /// Returns the archive-local numeric group principal.
    @Override
    public GroupPrincipal group() {
        return group;
    }

    /// Returns the immutable POSIX permission set.
    @Override
    public @Unmodifiable Set<PosixFilePermission> permissions() {
        return permissions;
    }
}
