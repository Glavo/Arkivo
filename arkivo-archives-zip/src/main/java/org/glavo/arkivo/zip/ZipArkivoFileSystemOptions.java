// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.zip;

import org.glavo.arkivo.ArkivoPasswordProvider;
import org.glavo.arkivo.zip.internal.ZipArkivoFileSystemOptionsImpl;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;
import java.util.Objects;

/// Configures opening a ZIP archive as a file system.
@NotNullByDefault
public sealed interface ZipArkivoFileSystemOptions permits ZipArkivoFileSystemOptionsImpl {
    /// The environment key used to pass ZIP file system options to a JDK file system provider.
    String ENVIRONMENT_KEY = "arkivo.zip.options";

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

    /// Returns a provider environment map containing the given ZIP file system options.
    static @Unmodifiable Map<String, ZipArkivoFileSystemOptions> environment(ZipArkivoFileSystemOptions options) {
        return Map.of(ENVIRONMENT_KEY, Objects.requireNonNull(options, "options"));
    }

    /// Reads ZIP file system options from a provider environment map.
    static ZipArkivoFileSystemOptions fromEnvironment(Map<String, ?> environment) {
        Object value = environment.get(ENVIRONMENT_KEY);
        if (value == null) {
            return defaults();
        }
        if (value instanceof ZipArkivoFileSystemOptions options) {
            return options;
        }
        throw new IllegalArgumentException("Expected ZipArkivoFileSystemOptions for environment key: " + ENVIRONMENT_KEY);
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
            this.defaultEncryption = Objects.requireNonNull(defaultEncryption, "defaultEncryption");
            return this;
        }

        /// Sets the maximum size of each output volume.
        public Builder splitSize(@Nullable Long splitSize) {
            if (splitSize != null && splitSize <= 0) {
                throw new IllegalArgumentException("splitSize must be positive");
            }
            this.splitSize = splitSize;
            return this;
        }

        /// Builds the ZIP file system options.
        public ZipArkivoFileSystemOptions build() {
            return new ZipArkivoFileSystemOptionsImpl(passwordProvider, readOnly, defaultEncryption, splitSize);
        }
    }
}
