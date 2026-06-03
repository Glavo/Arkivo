// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures reading an existing ZIP archive.
@NotNullByDefault
public sealed interface ZipReadOptions permits ZipReadOptionsImpl {
    /// The default ZIP read options.
    ZipReadOptions DEFAULTS = new ZipReadOptionsImpl(null);

    /// Returns the default ZIP read options.
    static ZipReadOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP read options.
    static Builder builder() {
        return new Builder();
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    @Nullable ArkivoPasswordProvider passwordProvider();

    /// Builds `ZipReadOptions` instances.
    @NotNullByDefault
    final class Builder {
        /// The provider used to decrypt encrypted ZIP entries.
        private @Nullable ArkivoPasswordProvider passwordProvider;

        /// Creates a ZIP read options builder.
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

        /// Builds the ZIP read options.
        public ZipReadOptions build() {
            return new ZipReadOptionsImpl(passwordProvider);
        }
    }
}

/// Stores ZIP read options.
@NotNullByDefault
final class ZipReadOptionsImpl implements ZipReadOptions {
    /// The provider used to decrypt encrypted ZIP entries.
    private final @Nullable ArkivoPasswordProvider passwordProvider;

    /// Creates ZIP read options.
    ZipReadOptionsImpl(@Nullable ArkivoPasswordProvider passwordProvider) {
        this.passwordProvider = passwordProvider;
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    @Override
    public @Nullable ArkivoPasswordProvider passwordProvider() {
        return passwordProvider;
    }
}
