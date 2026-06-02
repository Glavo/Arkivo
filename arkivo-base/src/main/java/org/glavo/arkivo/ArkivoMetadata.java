// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/// Exposes typed metadata extensions beyond the common archive fields.
@NotNullByDefault
public interface ArkivoMetadata {
    /// Returns an empty metadata container.
    static ArkivoMetadata empty() {
        return EmptyMetadata.INSTANCE;
    }

    /// Returns the value associated with the given key.
    @Nullable <T> T get(ArkivoMetadataKey<T> key);

    /// Returns the available metadata keys.
    @Unmodifiable Set<ArkivoMetadataKey<?>> keys();

    /// Provides the singleton empty metadata container.
    @NotNullByDefault
    final class EmptyMetadata implements ArkivoMetadata {
        /// The singleton instance.
        private static final EmptyMetadata INSTANCE = new EmptyMetadata();

        /// Creates an empty metadata container.
        private EmptyMetadata() {
        }

        /// Returns no value because the container is empty.
        @Override
        public @Nullable <T> T get(ArkivoMetadataKey<T> key) {
            return null;
        }

        /// Returns an empty key set.
        @Override
        public @Unmodifiable Set<ArkivoMetadataKey<?>> keys() {
            return Set.of();
        }
    }
}
