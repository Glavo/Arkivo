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

    /// The converter used when the environment map contains a value that is not already accepted directly.
    private final @Nullable Function<Object, T> converter;

    /// Creates a typed option that accepts only values of the given type.
    private ArkivoFileSystemOption(String key, Class<T> type, @Nullable Function<Object, T> converter) {
        this.key = Objects.requireNonNull(key, "key");
        this.type = Objects.requireNonNull(type, "type");
        this.converter = converter;
    }

    /// Returns a typed option that accepts only values of the given type.
    public static <T> ArkivoFileSystemOption<T> of(String key, Class<T> type) {
        return new ArkivoFileSystemOption<>(key, type, null);
    }

    /// Returns a typed option that accepts values of the given type and values converted by the given converter.
    public static <T> ArkivoFileSystemOption<T> of(
            String key,
            Class<T> type,
            Function<Object, T> converter
    ) {
        return new ArkivoFileSystemOption<>(key, type, Objects.requireNonNull(converter, "converter"));
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

    /// Puts a string option value into an environment map after validating that this option can convert it.
    public void putString(Map<String, Object> environment, String value) {
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(value, "value");
        convert(value);
        environment.put(key, value);
    }

    /// Converts a raw environment value into this option's typed value.
    public T convert(Object value) {
        Objects.requireNonNull(value, "value");
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        if (value instanceof Number numberValue) {
            T convertedValue = convertNumber(numberValue);
            if (convertedValue != null) {
                return convertedValue;
            }
        }
        if (converter != null) {
            return Objects.requireNonNull(converter.apply(value), "converter result");
        }
        throw new IllegalArgumentException(expectedValueMessage());
    }

    /// Converts a numeric environment value into this option's typed numeric value.
    private @Nullable T convertNumber(Number value) {
        if (type == Byte.class && isIntegralNumber(value)) {
            long longValue = value.longValue();
            if (longValue >= Byte.MIN_VALUE && longValue <= Byte.MAX_VALUE) {
                return type.cast((byte) longValue);
            }
        } else if (type == Short.class && isIntegralNumber(value)) {
            long longValue = value.longValue();
            if (longValue >= Short.MIN_VALUE && longValue <= Short.MAX_VALUE) {
                return type.cast((short) longValue);
            }
        } else if (type == Integer.class && isIntegralNumber(value)) {
            long longValue = value.longValue();
            if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                return type.cast((int) longValue);
            }
        } else if (type == Long.class && isIntegralNumber(value)) {
            return type.cast(value.longValue());
        } else if (type == Float.class) {
            return type.cast(value.floatValue());
        } else if (type == Double.class) {
            return type.cast(value.doubleValue());
        }
        return null;
    }

    /// Returns whether a number is an integral wrapper value.
    private static boolean isIntegralNumber(Number value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    /// Returns a message that describes the accepted value kinds for a rejected value.
    private String expectedValueMessage() {
        if (converter != null) {
            return "Expected " + type.getSimpleName() + " or convertible value for key: " + key;
        }
        return "Expected " + type.getSimpleName() + " for key: " + key;
    }

    /// Returns the environment map key used by this option.
    @Override
    public String toString() {
        return key;
    }
}
