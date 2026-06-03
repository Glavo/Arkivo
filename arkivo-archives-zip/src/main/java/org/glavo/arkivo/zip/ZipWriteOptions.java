// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures writing a new ZIP archive.
@NotNullByDefault
public sealed interface ZipWriteOptions permits ZipWriteOptionsImpl {
    /// The default ZIP write options.
    ZipWriteOptions DEFAULTS = new ZipWriteOptionsImpl(null, ZipEncryption.none(), null);

    /// Returns the default ZIP write options.
    static ZipWriteOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP write options.
    static Builder builder() {
        return new Builder();
    }

    /// Returns the provider used when encrypted ZIP entries need a password.
    @Nullable ArkivoPasswordProvider passwordProvider();

    /// Returns the encryption method used for entries that do not override encryption.
    ZipEncryption defaultEncryption();

    /// Returns the maximum size of each output volume, or `null` for single-file output.
    @Nullable Long splitSize();

    /// Builds `ZipWriteOptions` instances.
    @NotNullByDefault
    final class Builder {
        /// The provider used when encrypted ZIP entries need a password.
        private @Nullable ArkivoPasswordProvider passwordProvider;

        /// The encryption method used for entries that do not override encryption.
        private ZipEncryption defaultEncryption = ZipEncryption.none();

        /// The maximum size of each output volume.
        private @Nullable Long splitSize;

        /// Creates a ZIP write options builder.
        public Builder() {
        }

        /// Sets the password provider.
        public Builder passwordProvider(@Nullable ArkivoPasswordProvider passwordProvider) {
            this.passwordProvider = passwordProvider;
            return this;
        }

        /// Sets a fixed password provider.
        public Builder password(char[] password) {
            this.passwordProvider = ArkivoPasswordProvider.fixed(password);
            return this;
        }

        /// Sets the encryption method used for entries that do not override encryption.
        public Builder defaultEncryption(ZipEncryption defaultEncryption) {
            this.defaultEncryption = defaultEncryption;
            return this;
        }

        /// Sets the maximum size of each output volume.
        public Builder splitSize(@Nullable Long splitSize) {
            this.splitSize = splitSize;
            return this;
        }

        /// Builds the ZIP write options.
        public ZipWriteOptions build() {
            return new ZipWriteOptionsImpl(passwordProvider, defaultEncryption, splitSize);
        }
    }
}

/// Stores ZIP write options.
@NotNullByDefault
final class ZipWriteOptionsImpl implements ZipWriteOptions {
    /// The provider used when encrypted ZIP entries need a password.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// The encryption method used for entries that do not override encryption.
    private final ZipEncryption defaultEncryption;

    /// The maximum size of each output volume.
    private final @Nullable Long splitSize;

    /// Creates ZIP write options.
    ZipWriteOptionsImpl(
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
