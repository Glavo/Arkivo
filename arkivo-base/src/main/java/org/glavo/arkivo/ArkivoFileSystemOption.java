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
    /// The namespace that owns this option.
    private final String namespace;

    /// The local option name inside the namespace.
    private final String name;

    /// The environment map key used by this option.
    private final String key;

    /// The typed value class accepted by this option.
    private final Class<T> type;

    /// The converter used to validate and convert raw environment values.
    private final Function<Object, T> converter;

    /// Creates a typed option that accepts only values of the given type.
    private ArkivoFileSystemOption(String namespace, String name, Class<T> type, Function<Object, T> converter) {
        this.namespace = validateNamespace(namespace);
        this.name = validateName(name);
        this.key = this.namespace + "." + this.name;
        this.type = Objects.requireNonNull(type, "type");
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    /// Returns a typed option that accepts only values of the given type.
    public static <T> ArkivoFileSystemOption<T> of(String namespace, String name, Class<T> type) {
        String key = key(namespace, name);
        return new ArkivoFileSystemOption<>(namespace, name, type, value -> {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " for key: " + key);
        });
    }

    /// Returns a typed option that accepts values of the given type and values converted by the given converter.
    public static <T> ArkivoFileSystemOption<T> of(
            String namespace,
            String name,
            Class<T> type,
            Function<Object, T> converter
    ) {
        return new ArkivoFileSystemOption<>(namespace, name, type, converter);
    }

    /// Returns the namespace that owns this option.
    public String namespace() {
        return namespace;
    }

    /// Returns the local option name inside the namespace.
    public String name() {
        return name;
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
        return Objects.requireNonNull(converter.apply(Objects.requireNonNull(value, "value")), "converter result");
    }

    /// Returns the environment map key used by this option.
    @Override
    public String toString() {
        return key;
    }

    /// Returns the environment key for an option namespace and local name.
    private static String key(String namespace, String name) {
        return validateNamespace(namespace) + "." + validateName(name);
    }

    /// Returns a validated option namespace.
    private static String validateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        if (namespace.isEmpty() || namespace.startsWith(".") || namespace.endsWith(".") || namespace.contains("..")) {
            throw new IllegalArgumentException("namespace must contain non-empty dot-separated segments");
        }
        return namespace;
    }

    /// Returns a validated local option name.
    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isEmpty() || name.indexOf('.') >= 0) {
            throw new IllegalArgumentException("name must be a non-empty local option name");
        }
        return name;
    }
}
