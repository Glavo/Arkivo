// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Describes a format-specific dictionary requested by a decoder.
///
/// Implementations carry the complete identifier and dictionary-kind information exposed by their format.
///
/// @param <D> the format-specific dictionary type that can satisfy this request
@FunctionalInterface
@NotNullByDefault
public interface DictionaryRequest<D extends CompressionDictionary> {
    /// Returns whether the supplied dictionary satisfies this request.
    boolean matches(D dictionary);
}
