// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipArkivoFileSystemOptions;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Stores ZIP file system options.
@NotNullByDefault
public final class ZipArkivoFileSystemOptionsImpl implements ZipArkivoFileSystemOptions {
    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// Whether the file system should reject mutating operations.
    private final boolean readOnly;

    /// Whether the file system should create a new ZIP archive.
    private final boolean create;

    /// Whether the file system should use append-only streaming write semantics.
    private final boolean streamingWrite;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates ZIP file system options.
    public ZipArkivoFileSystemOptionsImpl(
            @Nullable ArkivoPasswordProvider passwordProvider,
            boolean readOnly,
            boolean create,
            boolean streamingWrite,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize
    ) {
        if (splitSize != null && splitSize <= 0) {
            throw new IllegalArgumentException("splitSize must be positive");
        }
        if (readOnly && create) {
            throw new IllegalArgumentException("readOnly and create cannot both be true");
        }
        if (readOnly && streamingWrite) {
            throw new IllegalArgumentException("readOnly and streamingWrite cannot both be true");
        }
        if (streamingWrite && !create) {
            throw new IllegalArgumentException("streamingWrite requires create");
        }
        this.passwordProvider = passwordProvider;
        this.readOnly = readOnly;
        this.create = create;
        this.streamingWrite = streamingWrite;
        this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
        this.splitSize = splitSize;
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    @Override
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns whether the file system should reject mutating operations.
    @Override
    public boolean readOnly() {
        return readOnly;
    }

    /// Returns whether the file system should create a new ZIP archive.
    @Override
    public boolean create() {
        return create;
    }

    /// Returns whether the file system should use append-only streaming write semantics.
    @Override
    public boolean streamingWrite() {
        return streamingWrite;
    }

    /// Returns the encryption method used for new entries that do not override encryption.
    @Override
    public ZipEncryption defaultEncryption() {
        return defaultEncryption;
    }

    /// Returns the maximum size of each output volume.
    @Override
    public @Nullable Long splitSize() {
        return splitSize;
    }
}
