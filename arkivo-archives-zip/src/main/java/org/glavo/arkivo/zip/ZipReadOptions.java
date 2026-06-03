// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures reading an existing ZIP archive.
///
/// @param passwordProvider the provider used to decrypt encrypted ZIP items
@NotNullByDefault
public record ZipReadOptions(
        @Nullable ArkivoPasswordProvider passwordProvider
) {
    /// The default ZIP read options.
    private static final ZipReadOptions DEFAULTS = new ZipReadOptions(null);

    /// Returns the default ZIP read options.
    public static ZipReadOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP read options.
    public static Builder builder() {
        return new Builder();
    }

    /// Builds `ZipReadOptions` instances.
    @NotNullByDefault
    public static final class Builder {
        /// The provider used to decrypt encrypted ZIP items.
        private @Nullable ArkivoPasswordProvider passwordProvider;

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

        /// Builds the ZIP read options.
        public ZipReadOptions build() {
            return new ZipReadOptions(passwordProvider);
        }
    }
}
