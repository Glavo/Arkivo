// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip.internal;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.ZipEncryption;
import org.glavo.arkivo.zip.ZipWriteOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Stores ZIP write options.
@NotNullByDefault
public final class ZipWriteOptionsImpl implements ZipWriteOptions {
    /// The provider used when encrypted ZIP entries need a password.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates ZIP write options.
    public ZipWriteOptionsImpl(
            @Nullable ArkivoPasswordProvider passwordProvider,
            ZipEncryption defaultEncryption,
            @Nullable Long splitSize
    ) {
        this.passwordProvider = passwordProvider;
        this.defaultEncryption = defaultEncryption;
        this.splitSize = splitSize;
    }

    /// Returns the provider used when encrypted ZIP entries need a password.
    @Override
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }

    /// Returns the encryption method used for entries that do not override encryption.
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
