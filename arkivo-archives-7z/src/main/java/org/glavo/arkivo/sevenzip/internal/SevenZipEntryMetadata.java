// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Stores parsed metadata for one 7z entry.
@NotNullByDefault
public final class SevenZipEntryMetadata {
    /// The data offset value used when an entry has no stored body.
    public static final long NO_DATA_OFFSET = -1L;

    /// The CRC-32 value used when an entry does not declare an expected digest.
    public static final long UNKNOWN_CRC32 = -1L;

    /// The decoded entry path.
    private final String path;

    /// Whether this entry is a directory.
    private final boolean directory;

    /// The uncompressed entry size.
    private final long size;

    /// The absolute archive data offset, or `NO_DATA_OFFSET` when this entry has no stored body.
    private final long dataOffset;

    /// The decoded stream offset inside the folder output.
    private final long decodedOffset;

    /// The packed entry size.
    private final long packedSize;

    /// The expected packed stream CRC-32, or `UNKNOWN_CRC32` when not present or not entry-addressable.
    private final long packedCrc32;

    /// The expected uncompressed entry CRC-32, or `UNKNOWN_CRC32` when not present.
    private final long crc32;

    /// The 7z folder method used to store this entry.
    private final SevenZipFolderMethod method;

    /// The creation time, or `null` when not present.
    private final @Nullable FileTime creationTime;

    /// The last access time, or `null` when not present.
    private final @Nullable FileTime lastAccessTime;

    /// The last modified time, or `null` when not present.
    private final @Nullable FileTime lastModifiedTime;

    /// The Windows file attributes, or `-1` when not present.
    private final int windowsAttributes;

    /// Creates parsed 7z entry metadata.
    public SevenZipEntryMetadata(
            String path,
            boolean directory,
            long size,
            long dataOffset,
            long decodedOffset,
            long packedSize,
            byte[] methodId,
            byte[] coderProperties,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) {
        this(
                path,
                directory,
                size,
                dataOffset,
                decodedOffset,
                packedSize,
                UNKNOWN_CRC32,
                UNKNOWN_CRC32,
                SevenZipFolderMethod.single(methodId, coderProperties, size),
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
    }

    /// Creates parsed 7z entry metadata.
    SevenZipEntryMetadata(
            String path,
            boolean directory,
            long size,
            long dataOffset,
            long decodedOffset,
            long packedSize,
            SevenZipFolderMethod method,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) {
        this(
                path,
                directory,
                size,
                dataOffset,
                decodedOffset,
                packedSize,
                UNKNOWN_CRC32,
                UNKNOWN_CRC32,
                method,
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
    }

    /// Creates parsed 7z entry metadata.
    SevenZipEntryMetadata(
            String path,
            boolean directory,
            long size,
            long dataOffset,
            long decodedOffset,
            long packedSize,
            long crc32,
            SevenZipFolderMethod method,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) {
        this(
                path,
                directory,
                size,
                dataOffset,
                decodedOffset,
                packedSize,
                UNKNOWN_CRC32,
                crc32,
                method,
                creationTime,
                lastAccessTime,
                lastModifiedTime,
                windowsAttributes
        );
    }

    /// Creates parsed 7z entry metadata.
    SevenZipEntryMetadata(
            String path,
            boolean directory,
            long size,
            long dataOffset,
            long decodedOffset,
            long packedSize,
            long packedCrc32,
            long crc32,
            SevenZipFolderMethod method,
            @Nullable FileTime creationTime,
            @Nullable FileTime lastAccessTime,
            @Nullable FileTime lastModifiedTime,
            int windowsAttributes
    ) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (dataOffset < NO_DATA_OFFSET) {
            throw new IllegalArgumentException("dataOffset must be non-negative or NO_DATA_OFFSET");
        }
        if (decodedOffset < 0) {
            throw new IllegalArgumentException("decodedOffset must be non-negative");
        }
        if (packedSize < 0) {
            throw new IllegalArgumentException("packedSize must be non-negative");
        }
        if (packedCrc32 < UNKNOWN_CRC32 || packedCrc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("packedCrc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
        }
        if (crc32 < UNKNOWN_CRC32 || crc32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("crc32 must be an unsigned 32-bit value or UNKNOWN_CRC32");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.directory = directory;
        this.size = size;
        this.dataOffset = dataOffset;
        this.decodedOffset = decodedOffset;
        this.packedSize = packedSize;
        this.packedCrc32 = packedCrc32;
        this.crc32 = crc32;
        this.method = Objects.requireNonNull(method, "method");
        this.creationTime = creationTime;
        this.lastAccessTime = lastAccessTime;
        this.lastModifiedTime = lastModifiedTime;
        this.windowsAttributes = windowsAttributes;
    }

    /// Returns the decoded entry path.
    public String path() {
        return path;
    }

    /// Returns whether this entry is a directory.
    public boolean directory() {
        return directory;
    }

    /// Returns the uncompressed entry size.
    public long size() {
        return size;
    }

    /// Returns the absolute archive data offset, or `NO_DATA_OFFSET` when this entry has no stored body.
    public long dataOffset() {
        return dataOffset;
    }

    /// Returns the decoded stream offset inside the folder output.
    public long decodedOffset() {
        return decodedOffset;
    }

    /// Returns the packed entry size.
    public long packedSize() {
        return packedSize;
    }

    /// Returns the expected packed stream CRC-32, or `UNKNOWN_CRC32` when not present or not entry-addressable.
    public long packedCrc32() {
        return packedCrc32;
    }

    /// Returns the expected uncompressed entry CRC-32, or `UNKNOWN_CRC32` when not present.
    public long crc32() {
        return crc32;
    }

    /// Returns a copy of the 7z method ID used to store this entry.
    public byte[] methodId() {
        return method.firstMethodId();
    }

    /// Returns whether this entry is stored with the given 7z method ID.
    public boolean hasMethod(byte[] expectedMethodId) {
        return method.containsMethod(expectedMethodId);
    }

    /// Returns a copy of the 7z coder properties used to store this entry.
    public byte[] coderProperties() {
        return method.firstProperties();
    }

    /// Returns the 7z folder method used to store this entry.
    SevenZipFolderMethod method() {
        return method;
    }

    /// Returns the creation time, or `null` when not present.
    public @Nullable FileTime creationTime() {
        return creationTime;
    }

    /// Returns the last access time, or `null` when not present.
    public @Nullable FileTime lastAccessTime() {
        return lastAccessTime;
    }

    /// Returns the last modified time, or `null` when not present.
    public @Nullable FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    /// Returns the Windows file attributes, or `-1` when not present.
    public int windowsAttributes() {
        return windowsAttributes;
    }
}
