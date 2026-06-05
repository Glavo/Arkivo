// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.Objects;

/// Stores parsed metadata for one 7z entry.
@NotNullByDefault
public final class SevenZipEntryMetadata {
    /// The data offset value used when an entry has no stored body.
    public static final long NO_DATA_OFFSET = -1L;

    /// The decoded entry path.
    private final String path;

    /// Whether this entry is a directory.
    private final boolean directory;

    /// The uncompressed entry size.
    private final long size;

    /// The absolute archive data offset, or `NO_DATA_OFFSET` when this entry has no stored body.
    private final long dataOffset;

    /// The packed entry size.
    private final long packedSize;

    /// The 7z method ID used to store this entry.
    private final byte @Unmodifiable [] methodId;

    /// The 7z coder properties used to store this entry.
    private final byte @Unmodifiable [] coderProperties;

    /// Creates parsed 7z entry metadata.
    public SevenZipEntryMetadata(
            String path,
            boolean directory,
            long size,
            long dataOffset,
            long packedSize,
            byte[] methodId,
            byte[] coderProperties
    ) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (dataOffset < NO_DATA_OFFSET) {
            throw new IllegalArgumentException("dataOffset must be non-negative or NO_DATA_OFFSET");
        }
        if (packedSize < 0) {
            throw new IllegalArgumentException("packedSize must be non-negative");
        }
        this.path = Objects.requireNonNull(path, "path");
        this.directory = directory;
        this.size = size;
        this.dataOffset = dataOffset;
        this.packedSize = packedSize;
        this.methodId = Objects.requireNonNull(methodId, "methodId").clone();
        this.coderProperties = Objects.requireNonNull(coderProperties, "coderProperties").clone();
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

    /// Returns the packed entry size.
    public long packedSize() {
        return packedSize;
    }

    /// Returns the 7z method ID used to store this entry.
    public byte @Unmodifiable [] methodId() {
        return methodId.clone();
    }

    /// Returns whether this entry is stored with the given 7z method ID.
    public boolean hasMethod(byte[] expectedMethodId) {
        return Arrays.equals(methodId, expectedMethodId);
    }

    /// Returns the 7z coder properties used to store this entry.
    public byte @Unmodifiable [] coderProperties() {
        return coderProperties.clone();
    }
}
