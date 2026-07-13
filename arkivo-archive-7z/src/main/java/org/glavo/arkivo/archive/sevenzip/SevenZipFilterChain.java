// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Configures an immutable sequence of 7z preprocessing filters in application order.
///
/// Entry bytes pass through the first filter, then each following filter, and finally the configured compression
/// method. An empty chain disables preprocessing.
///
/// @param filters the filters in application order
@NotNullByDefault
public record SevenZipFilterChain(@Unmodifiable List<SevenZipFilter> filters) {
    /// The empty filter chain.
    public static final SevenZipFilterChain EMPTY = new SevenZipFilterChain(List.of());

    /// Creates an immutable filter chain.
    public SevenZipFilterChain {
        filters = List.copyOf(Objects.requireNonNull(filters, "filters"));
    }

    /// Creates a filter chain from filters in application order.
    public static SevenZipFilterChain of(SevenZipFilter... filters) {
        Objects.requireNonNull(filters, "filters");
        return filters.length == 0
                ? EMPTY
                : new SevenZipFilterChain(Arrays.asList(filters.clone()));
    }

    /// Returns whether this chain contains no preprocessing filters.
    public boolean isEmpty() {
        return filters.isEmpty();
    }
}
