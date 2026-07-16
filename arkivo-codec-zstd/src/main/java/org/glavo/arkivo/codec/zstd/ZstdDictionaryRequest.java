// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.DictionaryRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Requests the formatted Zstandard dictionary with a particular dictionary identifier.
///
/// @param dictionaryId nonzero unsigned 32-bit identifier from the Zstandard frame header
@NotNullByDefault
public record ZstdDictionaryRequest(long dictionaryId) implements DictionaryRequest {
    /// Validates the nonzero unsigned 32-bit identifier.
    public ZstdDictionaryRequest {
        if (dictionaryId <= ZstdDictionary.NO_DICTIONARY_ID || dictionaryId > 0xffff_ffffL) {
            throw new IllegalArgumentException("dictionaryId must be a nonzero unsigned 32-bit value");
        }
    }

    /// Returns whether the supplied dictionary satisfies this request.
    public boolean matches(ZstdDictionary dictionary) {
        return Objects.requireNonNull(dictionary, "dictionary").dictionaryId() == dictionaryId;
    }
}
