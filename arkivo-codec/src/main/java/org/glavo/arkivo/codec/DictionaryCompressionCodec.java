// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Describes an immutable codec configuration with an optional shared compression dictionary.
@NotNullByDefault
public interface DictionaryCompressionCodec extends CompressionCodec {
    /// Returns the configured dictionary, or `null` when dictionary-free operation is selected.
    @Nullable CompressionDictionary dictionary();

    /// Returns an immutable codec configured with the requested dictionary.
    DictionaryCompressionCodec withDictionary(CompressionDictionary dictionary);

    /// Returns an immutable codec configured without a dictionary.
    DictionaryCompressionCodec withoutDictionary();
}
