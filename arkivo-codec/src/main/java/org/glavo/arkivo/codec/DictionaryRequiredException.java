// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Indicates that decoding cannot continue without a format-specific dictionary.
@NotNullByDefault
public final class DictionaryRequiredException extends IOException {
    /// The serialization version for this exception type.
    private static final long serialVersionUID = 0L;

    /// The format-specific dictionary request.
    private final DictionaryRequest<?> request;

    /// Creates an exception for the requested dictionary.
    public DictionaryRequiredException(DictionaryRequest<?> request) {
        super("Compression decoder requires dictionary: " + Objects.requireNonNull(request, "request"));
        this.request = request;
    }

    /// Returns the format-specific dictionary request.
    public DictionaryRequest<?> request() {
        return request;
    }
}
