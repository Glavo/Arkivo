// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.sevenzip.SevenZipArkivoEntryAttributes;
import org.glavo.arkivo.archive.sevenzip.SevenZipCoderGraph;
import org.glavo.arkivo.archive.sevenzip.SevenZipPackedStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;
import java.util.Set;

/// Provides basic attributes for a parsed 7z entry.
@NotNullByDefault
final class SevenZipEntryAttributes implements SevenZipArkivoEntryAttributes, PosixFileAttributes {
    /// The Windows readonly attribute bit.
    private static final int WINDOWS_ATTRIBUTE_READ_ONLY = 0x01;

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

    /// Returns the complete coder graph, or null when the entry has no packed stream.
    @Override
    public @Nullable SevenZipCoderGraph coderGraph() {
        return metadata.coderGraph();
    }

    /// Returns whether this entry shares its folder with another file-addressable substream.
    @Override
    public boolean solid() {
        return metadata.solid();
    }

    /// Returns this entry's index among file-addressable folder substreams.
    @Override
    public int substreamIndex() {
        return metadata.substreamIndex();
    }

    /// Returns the number of file-addressable substreams in this entry's folder.
    @Override
    public int substreamCount() {
        return metadata.substreamCount();
    }
    /// Returns the absolute logical archive offset of the first packed stream, or `NO_DATA_OFFSET`.
    @Override
    public long dataOffset() {
        return metadata.dataOffset();
    }

    /// Returns the decoded byte offset of this entry within the folder output.
    @Override
    public long decodedOffset() {
        return metadata.decodedOffset();
    }

    /// Returns the total byte size of the packed ranges used to read this entry.
    @Override
    public long packedSize() {
        return metadata.packedSize();
    }

    /// Returns the packed stream CRC-32 when the folder has one packed stream, or `UNKNOWN_CRC32`.
    @Override
    public long packedCrc32() {
        return metadata.packedCrc32();
    }

    /// Returns the immutable physical packed ranges used to read this entry.
    @Override
    public @Unmodifiable List<SevenZipPackedStream> packedStreams() {
        return metadata.packedStreams();
    }

    /// Returns the decoded entry CRC-32, or `UNKNOWN_CRC32` when absent.
    @Override
    public long crc32() {
        return metadata.crc32();
    }
    /// Returns the Windows file attributes, or `UNKNOWN_WINDOWS_ATTRIBUTES` when not present.
    @Override
    public int windowsAttributes() {
        return metadata.windowsAttributes();
    }

    /// Returns the stored Unix mode bits, or `UNKNOWN_UNIX_MODE` when not present.
    @Override
    public int unixMode() {
        return SevenZipPosixSupport.unixMode(metadata.windowsAttributes());
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
        return !metadata.directory()
                && !SevenZipPosixSupport.isSymbolicLink(metadata.windowsAttributes())
                && !SevenZipPosixSupport.isOther(metadata.windowsAttributes());
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return metadata.directory();
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return !metadata.directory() && SevenZipPosixSupport.isSymbolicLink(metadata.windowsAttributes());
    }

    /// Returns whether this entry has another special type.
    @Override
    public boolean isOther() {
        return !metadata.directory() && SevenZipPosixSupport.isOther(metadata.windowsAttributes());
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

    /// Returns the synthesized owner principal for this entry.
    @Override
    public UserPrincipal owner() {
        return SevenZipPosixSupport.owner();
    }

    /// Returns the synthesized group principal for this entry.
    @Override
    public GroupPrincipal group() {
        return SevenZipPosixSupport.group();
    }

    /// Returns synthesized POSIX permissions for this entry.
    @Override
    public @Unmodifiable Set<PosixFilePermission> permissions() {
        return SevenZipPosixSupport.permissions(
                metadata.directory(),
                readOnlyWindowsAttribute(),
                metadata.windowsAttributes()
        );
    }

    /// Returns whether this entry is marked read-only by stored Windows attributes.
    private boolean readOnlyWindowsAttribute() {
        int windowsAttributes = metadata.windowsAttributes();
        return windowsAttributes != UNKNOWN_WINDOWS_ATTRIBUTES
                && (windowsAttributes & WINDOWS_ATTRIBUTE_READ_ONLY) != 0;
    }
}
