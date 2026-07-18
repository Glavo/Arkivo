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
/// Construction copies the list and rejects more than three preprocessing filters, leaving room for the required
/// terminal LZMA2 filter in XZ's four-filter limit. The contained filter implementations are immutable, so chain values
/// are safe for concurrent use.
///
/// @param filters zero through three preprocessing filters in encoding order
@NotNullByDefault
public record XZFilterChain(@Unmodifiable List<XZFilter> filters) {
    /// The empty preprocessing chain.
    public static final XZFilterChain EMPTY = new XZFilterChain(List.of());

    /// Copies and validates the ordered preprocessing filters.
    ///
    /// @throws NullPointerException if the list or any contained filter is {@code null}
    /// @throws IllegalArgumentException if the list contains more than three filters
    public XZFilterChain {
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
        if (filters.size() > 3) {
            throw new IllegalArgumentException("XZ supports at most three filters before LZMA2");
        }
    }

    /// Creates a filter chain from filters in encoding order.
    ///
    /// @param filters zero through three filters in encoding order
    /// @return an immutable chain containing a defensive copy of the supplied filters
    /// @throws NullPointerException if the array or any contained filter is {@code null}
    /// @throws IllegalArgumentException if more than three filters are supplied
    public static XZFilterChain of(XZFilter... filters) {
        Objects.requireNonNull(filters, "filters");
        return filters.length == 0
                ? EMPTY
                : new XZFilterChain(Arrays.asList(filters.clone()));
    }

    /// Returns whether this chain contains no preprocessing filters.
    ///
    /// @return {@code true} if the chain contains no filters
    public boolean isEmpty() {
        return filters.isEmpty();
    }
}
