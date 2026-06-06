// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.glavo.arkivo.sevenzip.SevenZipArkivoEntryAttributes;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;

/// Provides basic attributes for a parsed 7z entry.
@NotNullByDefault
final class SevenZipEntryAttributes implements SevenZipArkivoEntryAttributes {
    /// The epoch file time used until timestamp metadata parsing is implemented.
    private static final FileTime EPOCH = FileTime.fromMillis(0);

    /// The parsed entry metadata.
    private final SevenZipEntryMetadata metadata;

    /// Creates entry attributes.
    SevenZipEntryAttributes(SevenZipEntryMetadata metadata) {
        this.metadata = metadata;
    }

    /// Returns the decoded entry path stored in the 7z header.
    @Override
    public String path() {
        return metadata.path();
    }

    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    @Override
    public int windowsAttributes() {
        return metadata.windowsAttributes();
    }

    /// Returns the last modified time.
    @Override
    public FileTime lastModifiedTime() {
        FileTime time = metadata.lastModifiedTime();
        return time != null ? time : EPOCH;
    }

    /// Returns the last access time.
    @Override
    public FileTime lastAccessTime() {
        FileTime time = metadata.lastAccessTime();
        return time != null ? time : EPOCH;
    }

    /// Returns the creation time.
    @Override
    public FileTime creationTime() {
        FileTime time = metadata.creationTime();
        return time != null ? time : EPOCH;
    }

    /// Returns whether this entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return !metadata.directory();
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return metadata.directory();
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

    /// Returns the entry size.
    @Override
    public long size() {
        return metadata.size();
    }

    /// Returns no file key.
    @Override
    public @Nullable Object fileKey() {
        return null;
    }
}
