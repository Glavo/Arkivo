// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.DictionaryRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Requests the LZ4 dictionary identified by a standard frame descriptor.
///
/// @param dictionaryId unsigned 32-bit identifier stored in the frame
@NotNullByDefault
public record LZ4DictionaryRequest(long dictionaryId) implements DictionaryRequest<LZ4Dictionary> {
    /// Validates the unsigned identifier range.
    public LZ4DictionaryRequest {
        if (dictionaryId < 0L || dictionaryId > 0xffff_ffffL) {
            throw new IllegalArgumentException("dictionaryId must be an unsigned 32-bit value");
        }
    }

    /// Returns whether the supplied dictionary carries the requested identifier.
    @Override
    public boolean matches(LZ4Dictionary dictionary) {
        return Objects.requireNonNull(dictionary, "dictionary").dictionaryId() == dictionaryId;
    }
}
