// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures writing a new ZIP archive.
///
/// @param passwordProvider the provider used when encrypted ZIP items need a password
/// @param defaultEncryption the encryption method used for items that do not override encryption
/// @param splitSize the maximum size of each output volume, or `null` for single-file output
@NotNullByDefault
public record ZipWriteOptions(
        @Nullable ArkivoPasswordProvider passwordProvider,
        ZipEncryption defaultEncryption,
        @Nullable Long splitSize
) {
    /// The default ZIP write options.
    private static final ZipWriteOptions DEFAULTS = new ZipWriteOptions(null, ZipEncryption.none(), null);

    /// Returns the default ZIP write options.
    public static ZipWriteOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP write options.
    public static Builder builder() {
        return new Builder();
    }

    /// Builds `ZipWriteOptions` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The provider used when encrypted ZIP items need a password.
        private @Nullable ArkivoPasswordProvider passwordProvider;

        /// The encryption method used for items that do not override encryption.
        private ZipEncryption defaultEncryption = ZipEncryption.none();

        /// The maximum size of each output volume.
        private @Nullable Long splitSize;

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

        /// Sets the encryption method used for items that do not override encryption.
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
            return new ZipWriteOptions(passwordProvider, defaultEncryption, splitSize);
        }
    }
}
