// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.zip.ZipArkivoEntryAttributes;
import org.glavo.arkivo.zip.ZipEncryption;
import org.glavo.arkivo.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.Objects;
import java.util.Set;

/// Implements ZIP entry attributes exposed while streaming local file headers.
@NotNullByDefault
final class StreamingZipEntryAttributes implements ZipArkivoEntryAttributes {
    /// The zero file time used when local header timestamps are not exposed yet.
    private static final FileTime ZERO_TIME = FileTime.fromMillis(0);

    /// The decoded ZIP entry path text.
    private final String path;

    /// The raw encoded ZIP entry path bytes.
    private final byte[] rawPath;

    /// The ZIP general purpose bit flags.
    private final int generalPurposeFlags;

    /// The ZIP compression method identifier.
    private final int method;

    /// The ZIP version needed to extract field.
    private final int versionNeededToExtract;

    /// The CRC-32 value, or `UNKNOWN_CRC32` when it is not known from the local header.
    private final long crc32;

    /// The compressed size, or `UNKNOWN_SIZE` when it is not known from the local header.
    private final long compressedSize;

    /// The uncompressed size, or `UNKNOWN_SIZE` when it is not known from the local header.
    private final long uncompressedSize;

    /// The raw local file header extra data bytes.
    private final byte[] localExtraData;

    /// Whether this entry represents a directory.
    private final boolean directory;

    /// Creates streaming ZIP entry attributes.
    StreamingZipEntryAttributes(
            String path,
            byte[] rawPath,
            int generalPurposeFlags,
            int method,
            int versionNeededToExtract,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            byte[] localExtraData,
            boolean directory
    ) {
        this.path = Objects.requireNonNull(path, "path");
        this.rawPath = Objects.requireNonNull(rawPath, "rawPath").clone();
        this.generalPurposeFlags = generalPurposeFlags;
        this.method = method;
        this.versionNeededToExtract = versionNeededToExtract;
        this.crc32 = crc32;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.localExtraData = Objects.requireNonNull(localExtraData, "localExtraData").clone();
        this.directory = directory;
    }

    /// Returns a copy of the raw encoded ZIP entry path bytes.
    @Override
    public byte[] rawPath() {
        return rawPath.clone();
    }

    /// Returns the decoded ZIP entry path text.
    @Override
    public String path() {
        return path;
    }

    /// Returns the compressed size stored in the ZIP metadata, or `UNKNOWN_SIZE` when it is not known.
    @Override
    public long compressedSize() {
        return directory ? UNKNOWN_SIZE : compressedSize;
    }

    /// Returns the CRC-32 value stored in the ZIP metadata, or `UNKNOWN_CRC32` when it is not known.
    @Override
    public long crc32() {
        return directory ? UNKNOWN_CRC32 : crc32;
    }

    /// Returns the general purpose bit flags stored for the ZIP entry.
    @Override
    public int generalPurposeFlags() {
        return generalPurposeFlags;
    }

    /// Returns the ZIP version made by field.
    @Override
    public int versionMadeBy() {
        return 0;
    }

    /// Returns the ZIP version needed to extract field.
    @Override
    public int versionNeededToExtract() {
        return versionNeededToExtract;
    }

    /// Returns the ZIP internal file attributes.
    @Override
    public int internalAttributes() {
        return 0;
    }

    /// Returns the ZIP external file attributes.
    @Override
    public long externalAttributes() {
        return 0;
    }

    /// Returns the ZIP compression method.
    @Override
    public ZipMethod method() {
        return ZipMethod.of(ZipAesExtraField.compressionMethod(generalPurposeFlags, method, localExtraData));
    }

    /// Returns the ZIP encryption method.
    @Override
    public ZipEncryption encryption() {
        return ZipAesExtraField.encryption(generalPurposeFlags, method, localExtraData);
    }

    /// Returns a copy of the raw local file header extra data bytes.
    @Override
    public byte[] localExtraData() {
        return localExtraData.clone();
    }

    /// Returns a copy of the raw central directory extra data bytes.
    @Override
    public byte[] centralDirectoryExtraData() {
        return new byte[0];
    }

    /// Returns a copy of the raw ZIP entry comment bytes, or `null` when no comment is present.
    @Override
    public byte @Nullable [] rawComment() {
        return null;
    }

    /// Returns the last modified time.
    @Override
    public FileTime lastModifiedTime() {
        return ZERO_TIME;
    }

    /// Returns the last access time.
    @Override
    public FileTime lastAccessTime() {
        return ZERO_TIME;
    }

    /// Returns the creation time.
    @Override
    public FileTime creationTime() {
        return ZERO_TIME;
    }

    /// Returns whether this entry is a regular file.
    @Override
    public boolean isRegularFile() {
        return !directory;
    }

    /// Returns whether this entry is a directory.
    @Override
    public boolean isDirectory() {
        return directory;
    }

    /// Returns whether this entry is a symbolic link.
    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    /// Returns whether this entry is another file type.
    @Override
    public boolean isOther() {
        return false;
    }

    /// Returns the uncompressed entry size.
    @Override
    public long size() {
        return directory ? 0 : uncompressedSize;
    }

    /// Returns no stable file key.
    @Override
    public @Nullable Object fileKey() {
        return null;
    }

    /// Returns the synthesized owner.
    @Override
    public UserPrincipal owner() {
        return ZipPosixSupport.DEFAULT_OWNER;
    }

    /// Returns the synthesized group.
    @Override
    public GroupPrincipal group() {
        return ZipPosixSupport.DEFAULT_GROUP;
    }

    /// Returns synthesized POSIX permissions.
    @Override
    public @Unmodifiable Set<PosixFilePermission> permissions() {
        return ZipPosixSupport.defaultPermissions(directory);
    }
}
