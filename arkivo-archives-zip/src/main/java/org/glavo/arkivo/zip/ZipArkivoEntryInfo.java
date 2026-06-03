// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoEntryInfo;
import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Exposes immutable metadata for one ZIP entry.
@NotNullByDefault
public sealed interface ZipArkivoEntryInfo extends ArkivoEntryInfo permits ZipArkivoEntryInfoImpl {
    /// Returns the compressed size stored in the ZIP metadata.
    @Nullable Long compressedSize();

    /// Returns the CRC-32 value stored in the ZIP metadata.
    @Nullable Long crc32();

    /// Returns the ZIP compression method.
    ZipMethod method();

    /// Returns the ZIP encryption method.
    ZipEncryption encryption();
}

/// Stores immutable metadata for one ZIP entry.
@NotNullByDefault
final class ZipArkivoEntryInfoImpl implements ZipArkivoEntryInfo {
    /// The raw encoded ZIP entry path bytes.
    private final byte @Unmodifiable [] rawPath;

    /// The decoded ZIP entry path text.
    private final String path;

    /// The ZIP entry type.
    private final ArkivoItemType type;

    /// The uncompressed size stored in the ZIP metadata.
    private final @Nullable Long uncompressedSize;

    /// The compressed size stored in the ZIP metadata.
    private final @Nullable Long compressedSize;

    /// The last modified time stored in the ZIP metadata.
    private final @Nullable FileTime modifiedTime;

    /// The CRC-32 value stored in the ZIP metadata.
    private final @Nullable Long crc32;

    /// The ZIP compression method.
    private final ZipMethod method;

    /// The ZIP encryption method.
    private final ZipEncryption encryption;

    /// Additional ZIP metadata.
    private final ArkivoMetadata metadata;

    /// Creates immutable metadata for one ZIP entry.
    ZipArkivoEntryInfoImpl(
            byte @Unmodifiable [] rawPath,
            String path,
            ArkivoItemType type,
            @Nullable Long uncompressedSize,
            @Nullable Long compressedSize,
            @Nullable FileTime modifiedTime,
            @Nullable Long crc32,
            ZipMethod method,
            ZipEncryption encryption,
            ArkivoMetadata metadata
    ) {
        this.rawPath = rawPath.clone();
        this.path = path;
        this.type = type;
        this.uncompressedSize = uncompressedSize;
        this.compressedSize = compressedSize;
        this.modifiedTime = modifiedTime;
        this.crc32 = crc32;
        this.method = method;
        this.encryption = encryption;
        this.metadata = metadata;
    }

    /// Returns the raw encoded ZIP entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Returns the decoded ZIP entry path text.
    @Override
    public String path() {
        return path;
    }

    /// Returns the ZIP entry type.
    @Override
    public ArkivoItemType type() {
        return type;
    }

    /// Returns the uncompressed size stored in the ZIP metadata.
    @Override
    public @Nullable Long uncompressedSize() {
        return uncompressedSize;
    }

    /// Returns the compressed size stored in the ZIP metadata.
    @Override
    public @Nullable Long compressedSize() {
        return compressedSize;
    }

    /// Returns the last modified time stored in the ZIP metadata.
    @Override
    public @Nullable FileTime modifiedTime() {
        return modifiedTime;
    }

    /// Returns the CRC-32 value stored in the ZIP metadata.
    @Override
    public @Nullable Long crc32() {
        return crc32;
    }

    /// Returns the ZIP compression method.
    @Override
    public ZipMethod method() {
        return method;
    }

    /// Returns the ZIP encryption method.
    @Override
    public ZipEncryption encryption() {
        return encryption;
    }

    /// Returns additional ZIP metadata.
    @Override
    public ArkivoMetadata metadata() {
        return metadata;
    }
}
