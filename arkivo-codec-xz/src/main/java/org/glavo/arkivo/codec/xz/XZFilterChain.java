// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Stores the ordered size-preserving filters applied before XZ's terminal LZMA2 filter.
///
/// @param filters zero through three preprocessing filters in encoding order
@NotNullByDefault
public record XZFilterChain(@Unmodifiable List<XZFilter> filters) {
    /// The empty preprocessing chain.
    public static final XZFilterChain EMPTY = new XZFilterChain(List.of());

    /// Copies and validates the ordered preprocessing filters.
    public XZFilterChain {
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        if (filters.size() > 3) {
            throw new IllegalArgumentException("XZ supports at most three filters before LZMA2");
        }
    }

    /// Creates a filter chain from filters in encoding order.
    public static XZFilterChain of(XZFilter... filters) {
        Objects.requireNonNull(filters, "filters");
        return filters.length == 0
                ? EMPTY
                : new XZFilterChain(Arrays.asList(filters.clone()));
    }

    /// Returns whether this chain contains no preprocessing filters.
    public boolean isEmpty() {
        return filters.isEmpty();
    }
}
