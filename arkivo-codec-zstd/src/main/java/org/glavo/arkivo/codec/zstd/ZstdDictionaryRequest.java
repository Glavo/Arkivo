// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.DictionaryRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Requests the formatted Zstandard dictionary with a particular dictionary identifier.
///
/// Matching compares only the unsigned identifier stored in the frame header. It does not compare dictionary bytes, so
/// callers are responsible for maintaining an unambiguous identifier-to-content mapping.
///
/// @param dictionaryId nonzero unsigned 32-bit identifier from the Zstandard frame header
@NotNullByDefault
public record ZstdDictionaryRequest(long dictionaryId) implements DictionaryRequest<ZstdDictionary> {
    /// Validates the nonzero unsigned 32-bit identifier.
    ///
    /// @throws IllegalArgumentException if {@code dictionaryId} is zero or outside the unsigned 32-bit range
    public ZstdDictionaryRequest {
        if (dictionaryId <= ZstdDictionary.NO_DICTIONARY_ID || dictionaryId > 0xffff_ffffL) {
            throw new IllegalArgumentException("dictionaryId must be a nonzero unsigned 32-bit value");
        }
    }

    /// Returns whether the supplied dictionary satisfies this request.
    ///
    /// @param dictionary the dictionary whose embedded identifier is compared
    /// @return {@code true} if the dictionary has the requested identifier
    /// @throws NullPointerException if {@code dictionary} is {@code null}
    @Override
    public boolean matches(ZstdDictionary dictionary) {
        return Objects.requireNonNull(dictionary, "dictionary").dictionaryId() == dictionaryId;
    }
}
