// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.glavo.arkivo.zip.ZipArkivoEntryOptions;
import org.glavo.arkivo.zip.ZipEncryption;
import org.glavo.arkivo.zip.ZipMethod;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Stores metadata and policies requested when writing one ZIP entry.
@NotNullByDefault
public final class ZipArkivoEntryOptionsImpl implements ZipArkivoEntryOptions {
    /// The raw encoded ZIP entry path bytes.
    private final byte @Unmodifiable [] rawPath;

    /// The decoded ZIP entry path text.
    private final String path;

    /// The requested ZIP entry type.
    private final ArkivoItemType type;

    /// The expected uncompressed size.
    private final @Nullable Long uncompressedSize;

    /// The requested last modified time.
    private final @Nullable FileTime modifiedTime;

    /// The requested ZIP compression method.
    private final ZipMethod method;

    /// The requested ZIP encryption method.
    private final @Nullable ZipEncryption encryption;

    /// Additional ZIP metadata.
    private final ArkivoMetadata metadata;

    /// Creates ZIP entry options.
    public ZipArkivoEntryOptionsImpl(
            byte @Unmodifiable [] rawPath,
            String path,
            ArkivoItemType type,
            @Nullable Long uncompressedSize,
            @Nullable FileTime modifiedTime,
            ZipMethod method,
            @Nullable ZipEncryption encryption,
            ArkivoMetadata metadata
    ) {
        this.rawPath = rawPath.clone();
        this.path = path;
        this.type = type;
        this.uncompressedSize = uncompressedSize;
        this.modifiedTime = modifiedTime;
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

    /// Returns the requested ZIP entry type.
    @Override
    public ArkivoItemType type() {
        return type;
    }

    /// Returns the expected uncompressed size.
    @Override
    public @Nullable Long uncompressedSize() {
        return uncompressedSize;
    }

    /// Returns the requested last modified time.
    @Override
    public @Nullable FileTime modifiedTime() {
        return modifiedTime;
    }

    /// Returns the requested ZIP compression method.
    @Override
    public ZipMethod method() {
        return method;
    }

    /// Returns the requested ZIP encryption method.
    @Override
    public @Nullable ZipEncryption encryption() {
        return encryption;
    }

    /// Returns additional ZIP metadata.
    @Override
    public ArkivoMetadata metadata() {
        return metadata;
    }
}
