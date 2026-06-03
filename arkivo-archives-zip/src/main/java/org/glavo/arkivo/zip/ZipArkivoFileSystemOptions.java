// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemOptionsImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Configures opening a ZIP archive as a file system.
@NotNullByDefault
public sealed interface ZipArkivoFileSystemOptions permits ZipArkivoFileSystemOptionsImpl {
    /// The default ZIP file system options.
    ZipArkivoFileSystemOptions DEFAULTS = new ZipArkivoFileSystemOptionsImpl(
            null,
            false,
            ZipEncryption.none(),
            null
    );

    /// Returns the default ZIP file system options.
    static ZipArkivoFileSystemOptions defaults() {
        return DEFAULTS;
    }

    /// Creates a builder for ZIP file system options.
    static Builder builder() {
        return new Builder();
    }

    /// Returns the provider used to decrypt encrypted ZIP entries.
    @Nullable ArkivoPasswordProvider passwordProvider();

    /// Returns whether the file system should reject mutating operations.
    boolean readOnly();

    /// Returns the encryption method used for new entries that do not override encryption.
    ZipEncryption defaultEncryption();

    /// Returns the maximum size of each output volume, or `null` for single-file output.
    @Nullable Long splitSize();

    /// Builds `ZipArkivoFileSystemOptions` instances.
    @NotNullByDefault
    final class Builder {
        /// The provider used to decrypt encrypted ZIP entries.
        private @Nullable ArkivoPasswordProvider passwordProvider;

        /// Whether the file system should reject mutating operations.
        private boolean readOnly;

        /// The encryption method used for new entries that do not override encryption.
        private ZipEncryption defaultEncryption = ZipEncryption.none();

        /// The maximum size of each output volume.
        private @Nullable Long splitSize;

        /// Creates a ZIP file system options builder.
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

        /// Sets whether the file system should reject mutating operations.
        public Builder readOnly(boolean readOnly) {
            this.readOnly = readOnly;
            return this;
        }

        /// Sets the encryption method used for new entries that do not override encryption.
        public Builder defaultEncryption(ZipEncryption defaultEncryption) {
            this.defaultEncryption = defaultEncryption;
            return this;
        }

        /// Sets the maximum size of each output volume.
        public Builder splitSize(@Nullable Long splitSize) {
            this.splitSize = splitSize;
            return this;
        }

        /// Builds the ZIP file system options.
        public ZipArkivoFileSystemOptions build() {
            return new ZipArkivoFileSystemOptionsImpl(passwordProvider, readOnly, defaultEncryption, splitSize);
        }
    }
}
