// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive;

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
    public static Builder builder() {
        return new Builder();
    }

    /// Copies a raw NIO file-system environment into immutable archive options.
    ///
    /// Entries mapped to null are treated as absent, matching option lookup semantics.
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

    /// Creates an option set containing one typed value.
    public static <T> ArchiveOptions of(ArchiveOption<T> option, T value) {
        return EMPTY.with(option, value);
    }

    /// Returns whether the requested option is present.
    public boolean contains(ArchiveOption<?> option) {
        Objects.requireNonNull(option, "option");
        return values.containsKey(option.key());
    }

    /// Returns the requested typed option value, or null when it is absent.
    public <T> @Nullable T get(ArchiveOption<T> option) {
        Objects.requireNonNull(option, "option");
        Object value = values.get(option.key());
        return value == null ? null : option.convert(value);
    }

    /// Returns the requested typed option value, or the supplied default when it is absent.
    public <T> T getOrDefault(ArchiveOption<T> option, T defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        @Nullable T value = get(option);
        return value != null ? value : defaultValue;
    }

    /// Returns a copy containing the requested typed option value.
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
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /// Returns the number of configured options.
    public int size() {
        return values.size();
    }

    /// Returns the immutable raw environment used for NIO provider interoperability.
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
        public <T> Builder set(ArchiveOption<T> option, T value) {
            Objects.requireNonNull(option, "option");
            values.put(option.key(), option.convert(Objects.requireNonNull(value, "value")));
            return this;
        }

        /// Sets one string value after converting it through the requested option.
        public Builder setString(ArchiveOption<?> option, String value) {
            Objects.requireNonNull(option, "option");
            values.put(option.key(), option.convert(Objects.requireNonNull(value, "value")));
            return this;
        }

        /// Removes one configured option.
        public Builder remove(ArchiveOption<?> option) {
            values.remove(Objects.requireNonNull(option, "option").key());
            return this;
        }

        /// Removes every configured option.
        public Builder clear() {
            values.clear();
            return this;
        }

        /// Returns an immutable snapshot of the configured options.
        public ArchiveOptions build() {
            return values.isEmpty() ? EMPTY : new ArchiveOptions(values);
        }
    }
}
