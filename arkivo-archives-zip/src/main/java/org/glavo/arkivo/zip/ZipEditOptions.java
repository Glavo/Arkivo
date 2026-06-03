// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.internal.ZipEditOptionsImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures editing an existing ZIP archive.
@NotNullByDefault
public sealed interface ZipEditOptions permits ZipEditOptionsImpl {
    /// The default ZIP edit options.
    ZipEditOptions DEFAULTS = new ZipEditOptionsImpl(null, ZipEncryption.none(), null);

    /// Returns the default ZIP edit options.
    static ZipEditOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP edit options.
    static Builder builder() {
        return new Builder();
    }

    /// Returns the provider used to decrypt existing encrypted entries.
    @Nullable ArkivoPasswordProvider passwordProvider();

    /// Returns the encryption method used for newly written entries that do not override encryption.
    ZipEncryption defaultEncryption();

    /// Returns the maximum size of each output volume, or `null` for single-file output.
    @Nullable Long splitSize();

    /// Builds `ZipEditOptions` instances.
    @NotNullByDefault
    final class Builder {
        /// The provider used to decrypt existing encrypted entries.
        private @Nullable ArkivoPasswordProvider passwordProvider;

        /// The encryption method used for newly written entries that do not override encryption.
        private ZipEncryption defaultEncryption = ZipEncryption.none();

        /// The maximum size of each output volume.
        private @Nullable Long splitSize;

        /// Creates a ZIP edit options builder.
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

        /// Sets the encryption method used for newly written entries that do not override encryption.
        public Builder defaultEncryption(ZipEncryption defaultEncryption) {
            this.defaultEncryption = defaultEncryption;
            return this;
        }

        /// Sets the maximum size of each output volume.
        public Builder splitSize(@Nullable Long splitSize) {
            this.splitSize = splitSize;
            return this;
        }

        /// Builds the ZIP edit options.
        public ZipEditOptions build() {
            return new ZipEditOptionsImpl(passwordProvider, defaultEncryption, splitSize);
        }
    }
}
