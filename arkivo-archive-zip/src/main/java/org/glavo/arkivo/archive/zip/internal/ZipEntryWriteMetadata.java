// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;
import java.util.Objects;

/// Describes the metadata serialized for one ZIP entry.
///
/// @param method                    the ZIP compression method identifier
/// @param encryption                the ZIP encryption method
/// @param lastModifiedTime          the optional last-modified timestamp
/// @param versionMadeBy             the ZIP version-made-by field
/// @param internalAttributes        the ZIP internal file attributes
/// @param externalAttributes        the ZIP external file attributes
/// @param expectedUncompressedSize  the expected uncompressed size, or the ZIP unknown-size sentinel
/// @param expectedCrc32             the expected CRC-32 value, or the ZIP unknown-CRC sentinel
/// @param localExtraData            the raw local-header extra data
/// @param centralDirectoryExtraData the raw central-directory extra data
/// @param rawComment                the optional raw entry comment
@NotNullByDefault
record ZipEntryWriteMetadata(
        int method,
        ZipEncryption encryption,
        @Nullable FileTime lastModifiedTime,
        int versionMadeBy,
        int internalAttributes,
        long externalAttributes,
        long expectedUncompressedSize,
        long expectedCrc32,
        byte @Unmodifiable [] localExtraData,
        byte @Unmodifiable [] centralDirectoryExtraData,
        byte @Nullable @Unmodifiable [] rawComment
) {
    /// Creates an immutable metadata snapshot.
    ZipEntryWriteMetadata {
        Objects.requireNonNull(encryption, "encryption");
        localExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
        centralDirectoryExtraData = Objects.requireNonNull(
                centralDirectoryExtraData,
                "centralDirectoryExtraData"
        ).clone();
        rawComment = rawComment != null ? rawComment.clone() : null;
    }

    /// Returns a copy of the raw local-header extra data.
    @Override
    public byte @Unmodifiable [] localExtraData() {
        return localExtraData.clone();
    }

    /// Returns a copy of the raw central-directory extra data.
    @Override
    public byte @Unmodifiable [] centralDirectoryExtraData() {
        return centralDirectoryExtraData.clone();
    }

    /// Returns a copy of the raw entry comment, or `null` when no comment was configured.
    @Override
    public byte @Nullable @Unmodifiable [] rawComment() {
        return rawComment != null ? rawComment.clone() : null;
    }
}
