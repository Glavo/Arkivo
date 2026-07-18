// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;

/// Reads and configures ZIP-specific attributes for an archive entry path.
///
/// [#readAttributes()] returns a stable snapshot. All mutators fail for read-only file systems. A pending streaming
/// entry can be configured until its body is opened or it is committed. An update file system can change timestamps,
/// permissions, internal and external attributes, and the raw comment; changing compression, encryption, declared
/// size or CRC, or local or central extra data on an existing entry is unsupported because it requires rewriting that
/// entry's local record.
@NotNullByDefault
public interface ZipArkivoEntryAttributeView extends PosixFileAttributeView {
    /// Returns the attribute view name.
    @Override
    default String name() {
        return "zip";
    }

    /// Reads a stable snapshot of the ZIP-specific entry attributes.
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

    /// Sets a recognized ZIP compression method and verifies that the entry type and runtime encoder support it.
    ///
    /// Directories and symbolic links support only [ZipMethod#STORED].
    ///
    /// @param method the requested compression method
    /// @throws NullPointerException if `method` is `null`
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only, the entry cannot be recompressed, or the
    /// requested method is unavailable for the entry type or runtime
    /// @throws IOException if an I/O error occurs while updating the entry
    void setMethod(ZipMethod method) throws IOException;

    /// Sets the ZIP encryption method requested for a pending streaming entry.
    ///
    /// A non-[ZipEncryption#NONE] method also requires the writer's password provider when the entry is committed.
    ///
    /// @param encryption the requested encryption method
    /// @throws NullPointerException if `encryption` is `null`
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only or the existing entry cannot be re-encrypted
    /// @throws IOException if an I/O error occurs while updating the entry
    void setEncryption(ZipEncryption encryption) throws IOException;

    /// Sets a non-negative expected uncompressed size and an unsigned 32-bit CRC-32 value for a pending entry.
    ///
    /// @param uncompressedSize the expected uncompressed byte count
    /// @param crc32 the expected unsigned 32-bit CRC-32 value
    /// @throws IllegalArgumentException if `uncompressedSize` is negative or `crc32` is outside the unsigned 32-bit
    /// range
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only or the existing entry metadata is derived from
    /// an already encoded body
    /// @throws IOException if an I/O error occurs while updating the entry
    void setUncompressedSizeAndCrc32(long uncompressedSize, long crc32) throws IOException;

    /// Sets the ZIP internal file attributes.
    ///
    /// @param internalAttributes the unsigned 16-bit internal file attributes
    /// @throws IllegalArgumentException if `internalAttributes` is outside the unsigned 16-bit range
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only
    /// @throws IOException if an I/O error occurs while updating the entry
    void setInternalAttributes(int internalAttributes) throws IOException;

    /// Sets the ZIP external file attributes as an unsigned 32-bit value.
    ///
    /// @param externalAttributes the unsigned 32-bit external file attributes
    /// @throws IllegalArgumentException if `externalAttributes` is outside the unsigned 32-bit range
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only
    /// @throws IOException if an I/O error occurs while updating the entry
    void setExternalAttributes(long externalAttributes) throws IOException;

    /// Sets a defensive copy of raw local-header extra data containing complete ZIP extra field records.
    ///
    /// @param extraData the complete encoded local-header extra field records
    /// @throws NullPointerException if `extraData` is `null`
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only or the existing local record cannot be changed
    /// @throws IOException if `extraData` is malformed or an I/O error occurs while updating the entry
    void setLocalExtraData(byte[] extraData) throws IOException;

    /// Sets a defensive copy of raw central-directory extra data containing complete ZIP extra field records.
    ///
    /// @param extraData the complete encoded central-directory extra field records
    /// @throws NullPointerException if `extraData` is `null`
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only or the existing central record cannot be changed
    /// @throws IOException if `extraData` is malformed or an I/O error occurs while updating the entry
    void setCentralDirectoryExtraData(byte[] extraData) throws IOException;

    /// Sets a defensive copy of the raw ZIP entry comment bytes, or clears the comment when `null`.
    ///
    /// @param rawComment the raw comment bytes, or `null` to clear the comment
    /// @throws IllegalArgumentException if the comment is too long for an unsigned 16-bit ZIP length field
    /// @throws IllegalStateException if the streaming entry has already been committed
    /// @throws UnsupportedOperationException if the view is read-only
    /// @throws IOException if an I/O error occurs while updating the entry
    void setRawComment(byte @Nullable [] rawComment) throws IOException;
}
