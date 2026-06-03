// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.glavo.arkivo.ArkivoItemType;
import org.glavo.arkivo.ArkivoMetadata;
import org.glavo.arkivo.BasicArkivoEntryOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.file.attribute.FileTime;

/// Stores general-purpose archive entry options.
@NotNullByDefault
public final class BasicArkivoEntryOptionsImpl implements BasicArkivoEntryOptions {
    /// The raw encoded entry path bytes.
    private final byte @Unmodifiable [] rawPath;

    /// The decoded entry path text.
    private final String path;

    /// The requested entry type.
    private final ArkivoItemType type;

    /// The expected uncompressed size.
    private final @Nullable Long uncompressedSize;

    /// The requested last modified time.
    private final @Nullable FileTime modifiedTime;

    /// Additional metadata requested for the entry.
    private final ArkivoMetadata metadata;

    /// Creates general-purpose archive entry options.
    public BasicArkivoEntryOptionsImpl(
            byte @Unmodifiable [] rawPath,
            String path,
            ArkivoItemType type,
            @Nullable Long uncompressedSize,
            @Nullable FileTime modifiedTime,
            ArkivoMetadata metadata
    ) {
        this.rawPath = rawPath.clone();
        this.path = path;
        this.type = type;
        this.uncompressedSize = uncompressedSize;
        this.modifiedTime = modifiedTime;
        this.metadata = metadata;
    }

    /// Returns the raw encoded entry path bytes.
    @Override
    public byte @Unmodifiable [] rawPath() {
        return rawPath.clone();
    }

    /// Returns the decoded entry path text.
    @Override
    public String path() {
        return path;
    }

    /// Returns the requested entry type.
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

    /// Returns additional metadata requested for the entry.
    @Override
    public ArkivoMetadata metadata() {
        return metadata;
    }
}
