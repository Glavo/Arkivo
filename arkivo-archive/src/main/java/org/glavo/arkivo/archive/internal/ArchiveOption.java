// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;
import java.util.function.Function;

/// Describes one typed archive option.
///
/// @param <T> the value type accepted by this option
@NotNullByDefault
public final class ArchiveOption<T> {
    /// The namespace that owns this option.
    private final String namespace;

    /// The local option name inside the namespace.
    private final String name;

    /// The stable NIO environment key used by this option.
    private final String key;

    /// The typed value class accepted by this option.
    private final Class<T> type;

    /// The converter used to validate and normalize raw option values.
    private final Function<Object, T> converter;

    /// Creates a typed option descriptor.
    private ArchiveOption(String namespace, String name, Class<T> type, Function<Object, T> converter) {
        this.namespace = validateNamespace(namespace);
        this.name = validateName(name);
        this.key = this.namespace + "." + this.name;
        this.type = Objects.requireNonNull(type, "type");
        this.converter = Objects.requireNonNull(converter, "converter");
    }

    /// Returns an option that accepts only values of the given type.
    public static <T> ArchiveOption<T> of(String namespace, String name, Class<T> type) {
        String key = key(namespace, name);
        return new ArchiveOption<>(namespace, name, type, value -> {
            if (type.isInstance(value)) {
                return type.cast(value);
            }
            throw new IllegalArgumentException("Expected " + type.getSimpleName() + " for key: " + key);
        });
    }

    /// Returns an option that normalizes values through the given converter.
    public static <T> ArchiveOption<T> of(
            String namespace,
            String name,
            Class<T> type,
            Function<Object, T> converter
    ) {
        return new ArchiveOption<>(namespace, name, type, converter);
    }

    /// Returns the namespace that owns this option.
    public String namespace() {
        return namespace;
    }

    /// Returns the local option name inside the namespace.
    public String name() {
        return name;
    }

    /// Returns the stable NIO environment key used by this option.
    public String key() {
        return key;
    }

    /// Returns the typed value class accepted by this option.
    public Class<T> type() {
        return type;
    }

    /// Converts and validates one raw value.
    T convert(Object value) {
        Object converted = Objects.requireNonNull(
                converter.apply(Objects.requireNonNull(value, "value")),
                "converter result"
        );
        if (!type.isInstance(converted)) {
            throw new IllegalStateException("Converter for " + key + " returned " + converted.getClass().getName());
        }
        return type.cast(converted);
    }

    /// Returns the stable NIO environment key used by this option.
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
        if (namespace.isBlank() || namespace.startsWith(".") || namespace.endsWith(".")
                || namespace.contains("..") || namespace.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("namespace must contain non-empty dot-separated segments");
        }
        return namespace;
    }

    /// Returns a validated local option name.
    private static String validateName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.indexOf('.') >= 0 || name.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("name must be a non-empty local option name");
        }
        return name;
    }
}
