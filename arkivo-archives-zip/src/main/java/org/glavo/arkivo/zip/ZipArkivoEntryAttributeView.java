// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;

/// Provides ZIP-specific attributes for an archive entry path.
@NotNullByDefault
public interface ZipArkivoEntryAttributeView extends PosixFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "zip";
    }

    /// Reads the ZIP-specific entry attributes.
    @Override
    ZipArkivoEntryAttributes readAttributes() throws IOException;

    /// Returns the synthesized ZIP entry owner.
    @Override
    default UserPrincipal getOwner() throws IOException {
        return readAttributes().owner();
    }

    /// Always rejects setting ZIP entry owners because ZIP owner metadata is synthesized.
    @Override
    default void setOwner(UserPrincipal owner) {
        Objects.requireNonNull(owner, "owner");
        throw new UnsupportedOperationException("ZIP entry owners are synthesized and cannot be set through the ZIP view");
    }

    /// Always rejects setting ZIP entry groups because ZIP group metadata is synthesized.
    @Override
    default void setGroup(GroupPrincipal group) {
        Objects.requireNonNull(group, "group");
        throw new UnsupportedOperationException("ZIP entry groups are synthesized and cannot be set through the ZIP view");
    }

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

    /// Sets raw local file header extra data bytes containing complete ZIP extra field records.
    void setLocalExtraData(byte[] extraData) throws IOException;

    /// Sets raw central directory extra data bytes containing complete ZIP extra field records.
    void setCentralDirectoryExtraData(byte[] extraData) throws IOException;

    /// Sets the raw ZIP entry comment bytes.
    void setRawComment(byte @Nullable [] rawComment) throws IOException;
}
