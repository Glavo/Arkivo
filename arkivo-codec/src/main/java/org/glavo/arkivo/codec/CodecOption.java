// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Identifies a type-safe codec option shared by descriptors and operation settings.
///
/// @param <T> the option value type
@NotNullByDefault
public final class CodecOption<T> {
    /// The stable option name.
    private final String name;

    /// The runtime option value type.
    private final Class<T> type;

    /// Creates a codec option.
    private CodecOption(String name, Class<T> type) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.name = name;
        this.type = Objects.requireNonNull(type, "type");
    }

    /// Creates a codec option with the given stable name and value type.
    public static <T> CodecOption<T> of(String name, Class<T> type) {
        return new CodecOption<>(Objects.requireNonNull(name, "name"), type);
    }

    /// Returns the stable option name.
    public String name() {
        return name;
    }

    /// Returns the runtime option value type.
    public Class<T> type() {
        return type;
    }

    /// Verifies and casts an option value.
    T cast(Object value) {
        return type.cast(value);
    }

    /// Returns whether the other object identifies the same named and typed option.
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other
                || other instanceof CodecOption<?> option
                && name.equals(option.name)
                && type.equals(option.type);
    }

    /// Returns the hash code of the option name and type.
    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    /// Returns the stable option name.
    @Override
    public String toString() {
        return name;
    }
}
