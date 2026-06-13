// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.rar.internal;

import org.glavo.arkivo.rar.RarArkivoEntryAttributes;
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

/// Stores parsed metadata for one RAR archive entry.
@NotNullByDefault
final class RarEntryAttributes implements RarArkivoEntryAttributes, PosixFileAttributes {
    /// The decoded entry path.
    private final String path;

    /// Whether this entry is a directory.
    private final boolean directory;

    /// Whether this entry is a symbolic link.
    private final boolean symbolicLink;

    /// Whether this entry has another non-regular file type.
    private final boolean other;

    /// The symbolic link target, or `null` when absent.
    private final @Nullable String linkName;

    /// The RAR redirection type.
    private final int redirectionType;

    /// The RAR redirection flags.
    private final long redirectionFlags;

    /// The RAR redirection target, or `null` when absent.
    private final @Nullable String redirectionTarget;

    /// The Unix owner user name, or `null` when absent.
    private final @Nullable String userName;

    /// The Unix owner group name, or `null` when absent.
    private final @Nullable String groupName;

    /// The numeric Unix owner user identifier.
    private final long userId;

    /// The numeric Unix owner group identifier.
    private final long groupId;

    /// The RAR host operating system value.
    private final int hostOs;

    /// The raw operating-system-specific file attributes.
    private final long fileAttributes;

    /// The RAR compression method number.
    private final int compressionMethod;

    /// The packed data size stored in this archive block.
    private final long packedSize;

    /// The unpacked data size.
    private final long unpackedSize;

    /// The unsigned data CRC32 value.
    private final long dataCrc32;

    /// The BLAKE2sp hash, or `null` when absent.
    private final byte @Nullable @Unmodifiable [] blake2spHash;

    /// Whether this entry data is encrypted.
    private final boolean encrypted;

    /// Whether this entry continues from a previous volume.
    private final boolean continuesFromPreviousVolume;

    /// Whether this entry continues in the next volume.
    private final boolean continuesInNextVolume;

    /// The last modified time.
    private final FileTime lastModifiedTime;

    /// The creation time.
    private final FileTime creationTime;

    /// The last access time.
    private final FileTime lastAccessTime;

    /// Creates parsed RAR entry attributes.
    RarEntryAttributes(
            String path,
            boolean directory,
            boolean symbolicLink,
            boolean other,
            @Nullable String linkName,
            int redirectionType,
            long redirectionFlags,
            @Nullable String redirectionTarget,
            @Nullable String userName,
            @Nullable String groupName,
            long userId,
            long groupId,
            int hostOs,
            long fileAttributes,
            int compressionMethod,
            long packedSize,
            long unpackedSize,
            long dataCrc32,
            byte @Nullable @Unmodifiable [] blake2spHash,
            boolean encrypted,
            boolean continuesFromPreviousVolume,
            boolean continuesInNextVolume,
            FileTime lastModifiedTime,
            FileTime creationTime,
            FileTime lastAccessTime
    ) {
        if (packedSize < 0) {
            throw new IllegalArgumentException("packedSize must not be negative");
        }
        if (unpackedSize < UNKNOWN_NUMERIC_VALUE) {
            throw new IllegalArgumentException("unpackedSize is out of range");
        }
        if (dataCrc32 < UNKNOWN_NUMERIC_VALUE || dataCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("dataCrc32 is out of range");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.directory = directory;
        this.symbolicLink = symbolicLink;
        this.other = other;
        this.linkName = linkName;
        this.redirectionType = redirectionType;
        this.redirectionFlags = redirectionFlags;
        this.redirectionTarget = redirectionTarget;
        this.userName = userName;
        this.groupName = groupName;
        this.userId = userId;
        this.groupId = groupId;
        this.hostOs = hostOs;
        this.fileAttributes = fileAttributes;
        this.compressionMethod = compressionMethod;
        this.packedSize = packedSize;
        this.unpackedSize = unpackedSize;
        this.dataCrc32 = dataCrc32;
        this.blake2spHash = blake2spHash != null ? blake2spHash.clone() : null;
        this.encrypted = encrypted;
        this.continuesFromPreviousVolume = continuesFromPreviousVolume;
        this.continuesInNextVolume = continuesInNextVolume;
        this.lastModifiedTime = Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        this.creationTime = Objects.requireNonNull(creationTime, "creationTime");
        this.lastAccessTime = Objects.requireNonNull(lastAccessTime, "lastAccessTime");
    }

    /// Returns the decoded RAR entry path.
    @Override
    public String path() {
        return path;
    }

    /// Returns the RAR host operating system value.
    @Override
    public int hostOs() {
        return hostOs;
    }

    /// Returns the raw operating-system-specific file attributes.
    @Override
    public long fileAttributes() {
        return fileAttributes;
    }

    /// Returns the RAR compression method number.
    @Override
    public int compressionMethod() {
        return compressionMethod;
    }

    /// Returns the packed data size stored in this archive block.
    @Override
    public long packedSize() {
        return packedSize;
    }

    /// Returns the unpacked data size, or `UNKNOWN_NUMERIC_VALUE` when unknown.
    @Override
    public long unpackedSize() {
        return unpackedSize;
    }

    /// Returns the unsigned data CRC32 value, or `UNKNOWN_NUMERIC_VALUE` when absent.
    @Override
    public long dataCrc32() {
        return dataCrc32;
    }

    /// Returns the BLAKE2sp hash, or `null` when absent.
    @Override
    public byte @Nullable @Unmodifiable [] blake2spHash() {
        byte @Nullable @Unmodifiable [] hash = blake2spHash;
        return hash != null ? hash.clone() : null;
    }

    /// Returns whether this entry data is encrypted.
    @Override
    public boolean isEncrypted() {
        return encrypted;
    }

    /// Returns whether this entry data continues from a previous volume.
    @Override
    public boolean continuesFromPreviousVolume() {
        return continuesFromPreviousVolume;
    }

    /// Returns whether this entry data continues in the next volume.
    @Override
    public boolean continuesInNextVolume() {
        return continuesInNextVolume;
    }

    /// Returns the symbolic link target, or `null` when absent.
    @Override
    public @Nullable String linkName() {
        return linkName;
    }

    /// Returns the RAR redirection type, or `NO_REDIRECTION_TYPE` when absent.
    @Override
    public int redirectionType() {
        return redirectionType;
    }

    /// Returns the RAR redirection flags.
    @Override
    public long redirectionFlags() {
        return redirectionFlags;
    }

    /// Returns the RAR redirection target, or `null` when absent.
    @Override
    public @Nullable String redirectionTarget() {
        return redirectionTarget;
    }

    /// Returns whether the RAR redirection target is a directory.
    @Override
    public boolean redirectionTargetDirectory() {
        return (redirectionFlags & REDIRECTION_FLAG_TARGET_DIRECTORY) != 0;
    }

    /// Returns the Unix owner user name, or `null` when absent.
    @Override
    public @Nullable String userName() {
        return userName;
    }

    /// Returns the Unix owner group name, or `null` when absent.
    @Override
    public @Nullable String groupName() {
        return groupName;
    }

    /// Returns the numeric Unix owner user identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    @Override
    public long userId() {
        return userId;
    }

    /// Returns the numeric Unix owner group identifier, or `UNKNOWN_NUMERIC_VALUE` when absent.
    @Override
    public long groupId() {
        return groupId;
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
        return !directory && !symbolicLink && !other;
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return directory;
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return symbolicLink;
    }

    /// Returns whether this entry has another file type.
    @Override
    public boolean isOther() {
        return other;
    }

    /// Returns the unpacked data size when known.
    @Override
    public long size() {
        return unpackedSize;
    }

    /// Returns an implementation-specific file key.
    @Override
    public Object fileKey() {
        return path;
    }

    /// Returns the owner principal represented by the stored RAR Unix owner metadata.
    @Override
    public UserPrincipal owner() {
        return RarPosixSupport.owner(userName, userId);
    }

    /// Returns the group principal represented by the stored RAR Unix owner metadata.
    @Override
    public GroupPrincipal group() {
        return RarPosixSupport.group(groupName, groupId);
    }

    /// Returns the POSIX permissions encoded by the stored Unix file attributes.
    @Override
    public @Unmodifiable Set<PosixFilePermission> permissions() {
        return RarPosixSupport.permissions(hostOs, fileAttributes);
    }
}
