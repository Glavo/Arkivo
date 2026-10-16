// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/// Stores environment values for archive operations and converts them on lookup.
///
/// The map cannot be modified after construction. Values obtained from a raw environment are not copied or converted
/// until requested with an [ArchiveOption]. Mutable values must therefore remain unchanged while these options are used.
@NotNullByDefault
public final class ArchiveOptions {
    /// The reusable empty option set.
    public static final ArchiveOptions EMPTY = new ArchiveOptions(Map.of());

    /// Option values indexed by their stable environment keys.
    private final @Unmodifiable Map<String, Object> values;

    /// Creates an immutable option set from validated values.
    private ArchiveOptions(Map<String, Object> values) {
        this.values = Map.copyOf(values);
    }

    /// Copies the entries of an NIO file-system environment.
    ///
    /// Entries mapped to `null` are omitted. The map is copied, but its values are not; conversion and type checking
    /// occur when an option is read. Unknown keys are retained.
    ///
    /// @param environment the environment whose entries are copied
    /// @return options containing all non-null entries, or [#EMPTY] when none remain
    /// @throws IllegalArgumentException if an environment key is blank
    public static ArchiveOptions fromEnvironment(Map<String, ?> environment) {
        Objects.requireNonNull(environment, "environment");
        if (environment.isEmpty()) {
            return EMPTY;
        }
        LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        environment.forEach((key, value) -> {
            String checkedKey = Objects.requireNonNull(key, "environment key");
            if (checkedKey.isBlank()) {
                throw new IllegalArgumentException("Archive option keys must not be blank");
            }
            if (value != null) {
                values.put(checkedKey, value);
            }
        });
        return values.isEmpty() ? EMPTY : new ArchiveOptions(values);
    }

    /// Returns whether the requested option is present.
    ///
    /// @param option the option descriptor to query by stable key
    /// @return {@code true} if a raw value is stored for the option
    public boolean contains(ArchiveOption<?> option) {
        Objects.requireNonNull(option, "option");
        return values.containsKey(option.key());
    }

    /// Returns the requested typed option value, or null when it is absent.
    ///
    /// @param <T> the option value type
    /// @param option the descriptor used to locate, convert, and validate the raw value
    /// @return the converted value, or {@code null} when absent
    public <T> @Nullable T get(ArchiveOption<T> option) {
        Objects.requireNonNull(option, "option");
        @Nullable Object value = values.get(option.key());
        return value == null ? null : option.convert(value);
    }

    /// Returns the requested typed option value, or the supplied default when it is absent.
    ///
    /// @param <T> the option value type
    /// @param option the descriptor used to locate, convert, and validate the raw value
    /// @param defaultValue the non-null value returned when the option is absent
    /// @return the configured value or {@code defaultValue}
    public <T> T getOrDefault(ArchiveOption<T> option, T defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        @Nullable T value = get(option);
        return value != null ? value : defaultValue;
    }

    /// Returns whether this option set is empty.
    ///
    /// @return {@code true} if no option values are stored
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /// Returns whether another option set contains the same raw values.
    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || other instanceof ArchiveOptions options && values.equals(options.values);
    }

    /// Returns the hash code of the configured raw values.
    @Override
    public int hashCode() {
        return values.hashCode();
    }

    /// Returns a diagnostic string containing the configured raw values.
    @Override
    public String toString() {
        return values.toString();
    }

}
