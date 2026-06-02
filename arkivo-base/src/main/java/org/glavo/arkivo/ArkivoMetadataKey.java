package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;

/// Identifies a typed metadata extension value.
///
/// @param name the stable metadata key name
/// @param type the Java type used by values stored under this key
@NotNullByDefault
public record ArkivoMetadataKey<T>(
        String name,
        Class<T> type
) {
}
