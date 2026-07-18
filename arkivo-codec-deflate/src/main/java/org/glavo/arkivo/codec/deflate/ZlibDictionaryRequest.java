// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.DictionaryRequest;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Requests the zlib preset dictionary with a particular Adler-32 identifier.
///
/// Matching compares only the unsigned Adler-32 identifier carried by the zlib header. Because Adler-32 is not a
/// collision-resistant content identity, callers remain responsible for associating identifiers with the intended
/// dictionary bytes.
///
/// @param adler32 unsigned 32-bit Adler-32 identifier from the zlib stream header
@NotNullByDefault
public record ZlibDictionaryRequest(long adler32) implements DictionaryRequest<ZlibDictionary> {
    /// Validates the unsigned 32-bit identifier.
    public ZlibDictionaryRequest {
        if (adler32 < 0L || adler32 > 0xffff_ffffL) {
            throw new IllegalArgumentException("adler32 must be an unsigned 32-bit value");
        }
    }

    /// Returns whether the supplied dictionary satisfies this request.
    @Override
    public boolean matches(ZlibDictionary dictionary) {
        return Objects.requireNonNull(dictionary, "dictionary").adler32() == adler32;
    }
}
