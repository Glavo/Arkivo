// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.BasicFileAttributeView;

/// Provides ZIP-specific attributes for an archive entry path.
@NotNullByDefault
public interface ZipArkivoEntryAttributeView extends BasicFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "zip";
    }

    /// Reads the ZIP-specific entry attributes.
    @Override
    ZipArkivoEntryAttributes readAttributes() throws IOException;

    /// Sets the ZIP compression method requested for the entry.
    void setMethod(ZipMethod method) throws IOException;

    /// Sets the ZIP encryption method requested for the entry.
    void setEncryption(ZipEncryption encryption) throws IOException;

    /// Sets the expected uncompressed size and CRC-32 value for entries that require them before writing.
    void setUncompressedSizeAndCrc32(long uncompressedSize, long crc32) throws IOException;

    /// Sets the ZIP internal file attributes.
    void setInternalAttributes(int internalAttributes) throws IOException;

    /// Sets the ZIP external file attributes.
    void setExternalAttributes(long externalAttributes) throws IOException;

    /// Sets the raw local file header extra data bytes.
    void setLocalExtraData(byte[] extraData) throws IOException;

    /// Sets the raw central directory extra data bytes.
    void setCentralDirectoryExtraData(byte[] extraData) throws IOException;

    /// Sets the decoded ZIP entry comment.
    void setComment(@Nullable String comment) throws IOException;

    /// Sets the raw ZIP entry comment bytes.
    void setRawComment(byte @Nullable [] rawComment) throws IOException;
}
