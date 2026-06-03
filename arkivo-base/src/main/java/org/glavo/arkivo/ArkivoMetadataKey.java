// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Identifies a typed metadata extension value.
@NotNullByDefault
public final class ArkivoMetadataKey<T> {
    /// The stable metadata key name.
    private final String name;

    /// The Java type used by values stored under this key.
    private final Class<T> type;

    /// Creates a metadata key.
    private ArkivoMetadataKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /// Returns a metadata key with the given stable name and value type.
    public static <T> ArkivoMetadataKey<T> of(String name, Class<T> type) {
        return new ArkivoMetadataKey<>(name, type);
    }

    /// Returns the stable metadata key name.
    public String name() {
        return name;
    }

    /// Returns the Java type used by values stored under this key.
    public Class<T> type() {
        return type;
    }

    /// Compares this metadata key with another object.
    @Override
    public boolean equals(@Nullable Object object) {
        return object instanceof ArkivoMetadataKey<?> that && name.equals(that.name) && type.equals(that.type);
    }

    /// Returns the hash code for this metadata key.
    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    /// Returns the stable metadata key name.
    @Override
    public String toString() {
        return name;
    }
}
