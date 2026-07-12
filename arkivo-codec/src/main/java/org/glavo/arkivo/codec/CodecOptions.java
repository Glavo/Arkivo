// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/// Stores immutable type-safe options for one codec operation.
@NotNullByDefault
public final class CodecOptions {
    /// The empty option set.
    public static final CodecOptions EMPTY = new CodecOptions(Map.of());

    /// Option values keyed by their stable typed identifiers.
    private final @Unmodifiable Map<CodecOption<?>, Object> values;

    /// Creates an immutable option set from validated values.
    private CodecOptions(Map<CodecOption<?>, Object> values) {
        this.values = Map.copyOf(values);
    }

    /// Returns a mutable builder for an option set.
    public static Builder builder() {
        return new Builder();
    }

    /// Returns whether no options are configured.
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /// Returns whether the given option is configured.
    public boolean contains(CodecOption<?> option) {
        return values.containsKey(Objects.requireNonNull(option, "option"));
    }

    /// Returns the configured value, or `null` when the option is absent.
    public <T> @Nullable T get(CodecOption<T> option) {
        Objects.requireNonNull(option, "option");
        @Nullable Object value = values.get(option);
        return value != null ? option.cast(value) : null;
    }

    /// Returns an immutable view of all configured values.
    public @Unmodifiable Map<CodecOption<?>, Object> asMap() {
        return values;
    }

    /// Rejects options not advertised for the selected operation.
    public void requireSupported(@Unmodifiable Set<CodecOption<?>> supported, String operation) {
        Objects.requireNonNull(supported, "supported");
        Objects.requireNonNull(operation, "operation");
        for (CodecOption<?> option : values.keySet()) {
            if (!supported.contains(option)) {
                throw new UnsupportedOperationException(
                        "Unsupported " + operation + " option: " + option.name()
                );
            }
        }
    }

    /// Builds immutable codec option sets.
    @NotNullByDefault
    public static final class Builder {
        /// Mutable values accumulated by the builder.
        private final Map<CodecOption<?>, Object> values = new LinkedHashMap<>();

        /// Creates an empty builder.
        private Builder() {
        }

        /// Configures one typed option value.
        public <T> Builder set(CodecOption<T> option, T value) {
            Objects.requireNonNull(option, "option");
            values.put(option, option.cast(Objects.requireNonNull(value, "value")));
            return this;
        }

        /// Removes one configured option.
        public Builder remove(CodecOption<?> option) {
            values.remove(Objects.requireNonNull(option, "option"));
            return this;
        }

        /// Builds an immutable option set.
        public CodecOptions build() {
            return values.isEmpty() ? EMPTY : new CodecOptions(values);
        }
    }
}
