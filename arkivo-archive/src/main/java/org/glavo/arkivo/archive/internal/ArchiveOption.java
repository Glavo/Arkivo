// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.function.Function;

/// Associates an NIO environment key with its conversion rule.
///
/// @param <T> the value type accepted by this option
@NotNullByDefault
public final class ArchiveOption<T> {
    /// The stable NIO environment key used by this option.
    private final String key;

    /// The converter used to validate and normalize raw option values.
    private final Function<Object, T> converter;

    /// Creates a typed option descriptor.
    private ArchiveOption(String key, Function<Object, T> converter) {
        this.key = validateKey(key);
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    /// Returns an option that accepts only values of the given type.
    ///
    /// @param <T> the option value type
    /// @param key the dot-separated environment key, without empty segments or whitespace
    /// @param type the runtime value class
    /// @return an option that accepts instances of `type`
    /// @throws IllegalArgumentException if `key` is invalid
    public static <T> ArchiveOption<T> of(String key, Class<T> type) {
        Objects.requireNonNull(type, "type");
        return new ArchiveOption<>(key, value -> {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " for key: " + key);
        });
    }

    /// Returns an option that normalizes values through the given converter.
    ///
    /// @param <T> the option value type
    /// @param key the dot-separated environment key, without empty segments or whitespace
    /// @param converter the raw-value validator and normalizer; must return a non-null value
    /// @return an option using `converter`
    /// @throws IllegalArgumentException if `key` is invalid
    public static <T> ArchiveOption<T> of(
            String key,
            Function<Object, T> converter
    ) {
        return new ArchiveOption<>(key, converter);
    }

    /// Returns the stable NIO environment key used by this option.
    ///
    /// @return the environment key
    public String key() {
        return key;
    }

    /// Converts and validates one raw value.
    T convert(Object value) {
        return Objects.requireNonNull(
                converter.apply(Objects.requireNonNull(value, "value")),
                "converter result"
        );
    }

    /// Returns the stable NIO environment key used by this option.
    @Override
    public String toString() {
        return key;
    }

    /// Rejects empty key segments and whitespace.
    private static String validateKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank() || key.startsWith(".") || key.endsWith(".")
                || key.contains("..") || key.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("key must contain non-empty dot-separated segments without whitespace");
        }
        return key;
    }
}
