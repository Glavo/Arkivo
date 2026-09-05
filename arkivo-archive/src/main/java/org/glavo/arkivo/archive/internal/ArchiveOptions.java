// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.internal;

import org.glavo.arkivo.archive.ArchiveCreateOptions;
import org.glavo.arkivo.archive.ArchiveReadOptions;
import org.glavo.arkivo.archive.ArchiveUpdateOptions;

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

    /// Converts format-independent read options to the internal option map.
    ///
    /// @param options the public read options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromReadOptions(ArchiveReadOptions options) {
        Objects.requireNonNull(options, "options");
        ArchiveOptions result = EMPTY
                .with(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety())
                .with(ArchiveEnvironmentOptions.READ_LIMITS, options.limits());
        if (options.editStorageFactory() != null) {
            result = result.with(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        return result;
    }

    /// Converts format-independent creation options to the internal option map.
    ///
    /// @param options the public creation options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromCreateOptions(ArchiveCreateOptions options) {
        Objects.requireNonNull(options, "options");
        ArchiveOptions result = EMPTY.with(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety());
        if (options.editStorageFactory() != null) {
            result = result.with(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        return result;
    }

    /// Converts format-independent update options to the internal option map.
    ///
    /// @param options the public update options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromUpdateOptions(ArchiveUpdateOptions options) {
        Objects.requireNonNull(options, "options");
        ArchiveOptions result = EMPTY
                .with(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety())
                .with(ArchiveEnvironmentOptions.READ_LIMITS, options.limits());
        if (options.editStorageFactory() != null) {
            result = result.with(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        if (options.commitTarget() != null) {
            result = result.with(ArchiveEnvironmentOptions.COMMIT_TARGET, options.commitTarget());
        }
        return result;
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

    /// Returns a copy containing the requested typed option value.
    ///
    /// @param <T> the option value type
    /// @param option the descriptor used to validate and key the value
    /// @param value the non-null value to store
    /// @return this instance when unchanged, otherwise an immutable updated copy
    public <T> ArchiveOptions with(ArchiveOption<T> option, T value) {
        Objects.requireNonNull(option, "option");
        T checkedValue = option.convert(Objects.requireNonNull(value, "value"));
        @Nullable Object previous = values.get(option.key());
        if (checkedValue.equals(previous)) {
            return this;
        }
        LinkedHashMap<String, Object> updated = new LinkedHashMap<>(values);
        updated.put(option.key(), checkedValue);
        return new ArchiveOptions(updated);
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
