// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipArkivoFileSystemOptions;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores ZIP file system options.
@NotNullByDefault
public final class ZipArkivoFileSystemOptionsImpl implements ZipArkivoFileSystemOptions {
    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// Whether the file system should reject mutating operations.
    private final boolean readOnly;

    /// The encryption method used for new entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates ZIP file system options.
    public ZipArkivoFileSystemOptionsImpl(
            @Nullable ArkivoPasswordProvider passwordProvider,
            boolean readOnly,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize
    ) {
        this.passwordProvider = passwordProvider;
        this.readOnly = readOnly;
        this.defaultEncryption = defaultEncryption;
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
