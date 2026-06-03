// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/// Describes a typed file system environment option.
///
/// @param <T> the typed value accepted by this option
@NotNullByDefault
public final class ArkivoFileSystemOption<T> {
    /// The environment map key used by this option.
    private final String key;

    /// The typed value class accepted by this option.
    private final Class<T> type;

    /// The parser used when the environment map contains a string value.
    private final @Nullable Function<String, ? extends T> stringParser;

    /// Creates a typed option that accepts only values of the given type.
    private ArkivoFileSystemOption(String key, Class<T> type, @Nullable Function<String, ? extends T> stringParser) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.stringParser = stringParser;
    }

    /// Returns a typed option that accepts only values of the given type.
    public static <T> ArkivoFileSystemOption<T> of(String key, Class<T> type) {
        return new ArkivoFileSystemOption<>(key, type, null);
    }

    /// Returns a typed option that accepts values of the given type and string values parsed by the given parser.
    public static <T> ArkivoFileSystemOption<T> of(
            String key,
            Class<T> type,
            Function<String, ? extends T> stringParser
    ) {
        return new ArkivoFileSystemOption<>(key, type, Objects.requireNonNull(stringParser, "stringParser"));
    }

    /// Returns the environment map key used by this option.
    public String key() {
        return key;
    }

    /// Returns the typed value class accepted by this option.
    public Class<T> type() {
        return type;
    }

    /// Returns whether the environment map contains this option.
    public boolean isPresent(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        return environment.containsKey(key);
    }

    /// Reads this option from an environment map, or returns `null` when the key is absent or mapped to `null`.
    public @Nullable T read(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        Object value = environment.get(key);
        if (value == null) {
            return null;
        }
        return convert(value);
    }

    /// Reads this option from an environment map, or returns the default value when the key is absent or mapped to `null`.
    public T readOrDefault(Map<String, ?> environment, T defaultValue) {
        T value = read(environment);
        return value != null ? value : defaultValue;
    }

    /// Puts a typed option value into an environment map.
    public void put(Map<String, Object> environment, T value) {
        Objects.requireNonNull(environment, "environment");
        environment.put(key, Objects.requireNonNull(value, "value"));
    }

    /// Puts a string option value into an environment map after validating that this option accepts string values.
    public void putString(Map<String, Object> environment, String value) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(value, "value");
        if (type == String.class && stringParser == null) {
            environment.put(key, value);
            return;
        }
        if (stringParser == null) {
            throw new IllegalArgumentException("Option does not accept string values: " + key);
        }
        Objects.requireNonNull(stringParser.apply(value), "stringParser result");
        environment.put(key, value);
    }

    /// Converts a raw environment value into this option's typed value.
    public T convert(Object value) {
        Objects.requireNonNull(value, "value");
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof String stringValue && stringParser != null) {
            return Objects.requireNonNull(stringParser.apply(stringValue), "stringParser result");
        }
        throw new IllegalArgumentException(expectedValueMessage());
    }

    /// Returns a message that describes the accepted value kinds for a rejected value.
    private String expectedValueMessage() {
        if (stringParser != null || type == String.class) {
            return "Expected " + type.getSimpleName() + " or String for key: " + key;
        }
        return "Expected " + type.getSimpleName() + " for key: " + key;
    }

    /// Returns the environment map key used by this option.
    @Override
    public String toString() {
        return key;
    }
}
