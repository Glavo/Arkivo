// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/// Provides basic attributes for the synthetic 7z root directory.
@NotNullByDefault
final class SevenZipRootAttributes implements BasicFileAttributes {
    /// The shared root attributes instance.
    static final SevenZipRootAttributes INSTANCE = new SevenZipRootAttributes();

    /// The epoch file time used when the archive has not supplied root metadata.
    private static final FileTime EPOCH = FileTime.fromMillis(0);

    /// Creates root attributes.
    private SevenZipRootAttributes() {
    }

    /// Returns the last modified time.
    @Override
    public FileTime lastModifiedTime() {
        return EPOCH;
    }

    /// Returns the last access time.
    @Override
    public FileTime lastAccessTime() {
        return EPOCH;
    }

    /// Returns the creation time.
    @Override
    public FileTime creationTime() {
        return EPOCH;
    }

    /// Returns whether this entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return false;
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return true;
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    /// Returns whether this entry has another special type.
    @Override
    public boolean isOther() {
        return false;
    }

    /// Returns the root directory size.
    @Override
    public long size() {
        return 0;
    }

    /// Returns no file key.
    @Override
    public @Nullable Object fileKey() {
        return null;
    }
}
