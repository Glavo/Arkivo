package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies a typed metadata extension value.
@NotNullByDefault
public record ArkivoMetadataKey<T>(
        /// The stable metadata key name.
        String name,
        /// The Java type used by values stored under this key.
        Class<T> type
) {
}
