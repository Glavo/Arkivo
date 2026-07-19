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

/// Stores immutable typed options for archive open, create, update, and streaming operations.
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

    /// Returns an empty mutable builder.
    ///
    /// @return a new empty builder
    public static Builder builder() {
        return new Builder();
    }

    /// Copies a raw NIO file-system environment into immutable archive options.
    ///
    /// Entries mapped to null are treated as absent, matching option lookup semantics.
    ///
    /// @param environment the raw NIO environment to validate and copy
    /// @return immutable options containing all non-null entries, or {@link #EMPTY} when none remain
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

    /// Converts format-independent read options for legacy internal adapters.
    ///
    /// @param options the public read options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromReadOptions(ArchiveReadOptions options) {
        Objects.requireNonNull(options, "options");
        Builder builder = builder()
                .set(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety())
                .set(ArchiveEnvironmentOptions.READ_LIMITS, options.limits());
        if (options.editStorageFactory() != null) {
            builder.set(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        return builder.build();
    }

    /// Converts format-independent creation options for legacy internal adapters.
    ///
    /// @param options the public creation options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromCreateOptions(ArchiveCreateOptions options) {
        Objects.requireNonNull(options, "options");
        Builder builder = builder().set(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety());
        if (options.editStorageFactory() != null) {
            builder.set(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        return builder.build();
    }

    /// Converts format-independent update options for legacy internal adapters.
    ///
    /// @param options the public update options to convert
    /// @return equivalent immutable internal options
    public static ArchiveOptions fromUpdateOptions(ArchiveUpdateOptions options) {
        Objects.requireNonNull(options, "options");
        Builder builder = builder()
                .set(ArchiveEnvironmentOptions.THREAD_SAFETY, options.threadSafety())
                .set(ArchiveEnvironmentOptions.READ_LIMITS, options.limits());
        if (options.editStorageFactory() != null) {
            builder.set(ArchiveEnvironmentOptions.EDIT_STORAGE_FACTORY, options.editStorageFactory());
        }
        if (options.commitTarget() != null) {
            builder.set(ArchiveEnvironmentOptions.COMMIT_TARGET, options.commitTarget());
        }
        return builder.build();
    }

    /// Creates an option set containing one typed value.
    ///
    /// @param <T> the option value type
    /// @param option the typed option descriptor
    /// @param value the value to validate and store
    /// @return immutable options containing the single value
    public static <T> ArchiveOptions of(ArchiveOption<T> option, T value) {
        return EMPTY.with(option, value);
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
        Object value = values.get(option.key());
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
        Object previous = values.get(option.key());
        if (checkedValue.equals(previous)) {
            return this;
        }
        LinkedHashMap<String, Object> updated = new LinkedHashMap<>(values);
        updated.put(option.key(), checkedValue);
        return new ArchiveOptions(updated);
    }

    /// Returns a copy containing a string converted by the requested option.
    ///
    /// @param option the descriptor used to convert and key the string
    /// @param value the non-null string to convert
    /// @return this instance when unchanged, otherwise an immutable updated copy
    public ArchiveOptions withString(ArchiveOption<?> option, String value) {
        Objects.requireNonNull(option, "option");
        Object converted = option.convert(Objects.requireNonNull(value, "value"));
        Object previous = values.get(option.key());
        if (converted.equals(previous)) {
            return this;
        }
        LinkedHashMap<String, Object> updated = new LinkedHashMap<>(values);
        updated.put(option.key(), converted);
        return new ArchiveOptions(updated);
    }

    /// Returns a copy without the requested option.
    ///
    /// @param option the descriptor whose stable key is removed
    /// @return this instance when absent, otherwise an immutable updated copy
    public ArchiveOptions without(ArchiveOption<?> option) {
        Objects.requireNonNull(option, "option");
        if (!values.containsKey(option.key())) {
            return this;
        }
        LinkedHashMap<String, Object> updated = new LinkedHashMap<>(values);
        updated.remove(option.key());
        return updated.isEmpty() ? EMPTY : new ArchiveOptions(updated);
    }

    /// Returns whether this option set is empty.
    ///
    /// @return {@code true} if no option values are stored
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /// Returns the number of configured options.
    ///
    /// @return the number of distinct stable option keys
    public int size() {
        return values.size();
    }

    /// Returns the immutable raw environment used for NIO provider interoperability.
    ///
    /// @return the immutable stable-key-to-raw-value mapping
    public @Unmodifiable Map<String, Object> toEnvironment() {
        return values;
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

    /// Builds immutable archive option sets through typed option descriptors.
    @NotNullByDefault
    public static final class Builder {
        /// Mutable option values accumulated by this builder.
        private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

        /// Creates an empty builder.
        private Builder() {
        }

        /// Sets one typed option value.
        ///
        /// @param <T> the option value type
        /// @param option the descriptor used to validate and key the value
        /// @param value the non-null value to store
        /// @return this builder
        public <T> Builder set(ArchiveOption<T> option, T value) {
            Objects.requireNonNull(option, "option");
            values.put(option.key(), option.convert(Objects.requireNonNull(value, "value")));
            return this;
        }

        /// Sets one string value after converting it through the requested option.
        ///
        /// @param option the descriptor used to convert and key the string
        /// @param value the non-null string to convert
        /// @return this builder
        public Builder setString(ArchiveOption<?> option, String value) {
            Objects.requireNonNull(option, "option");
            values.put(option.key(), option.convert(Objects.requireNonNull(value, "value")));
            return this;
        }

        /// Removes one configured option.
        ///
        /// @param option the descriptor whose stable key is removed
        /// @return this builder
        public Builder remove(ArchiveOption<?> option) {
            values.remove(Objects.requireNonNull(option, "option").key());
            return this;
        }

        /// Removes every configured option.
        ///
        /// @return this builder
        public Builder clear() {
            values.clear();
            return this;
        }

        /// Returns an immutable snapshot of the configured options.
        ///
        /// @return immutable options containing the current builder values, or {@link #EMPTY} if none are set
        public ArchiveOptions build() {
            return values.isEmpty() ? EMPTY : new ArchiveOptions(values);
        }
    }
}
