// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipEditOptions;
import org.glavo.arkivo.zip.ZipEncryption;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores ZIP edit options.
@NotNullByDefault
public final class ZipEditOptionsImpl implements ZipEditOptions {
    /// The provider used to decrypt existing encrypted entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for newly written entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates ZIP edit options.
    public ZipEditOptionsImpl(
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize
    ) {
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = defaultEncryption;
        this.splitSize = splitSize;
    }

    /// Returns the provider used to decrypt existing encrypted entries.
    @Override
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the encryption method used for newly written entries that do not override encryption.
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
