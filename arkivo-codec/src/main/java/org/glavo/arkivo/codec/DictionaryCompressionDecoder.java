// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;

/// Decodes a format that can request and accept a dictionary after decoding has started.
@NotNullByDefault
public interface DictionaryCompressionDecoder extends CompressionDecoder {
    /// Returns the format-specific identifier of the requested dictionary.
    ///
    /// This method is valid after `CodecOutcome.NEEDS_DICTIONARY`. Formats without an available identifier return
    /// `CompressionDictionary.UNKNOWN_ID`.
    long requiredDictionaryId();

    /// Supplies the dictionary requested by the most recent decode operation.
    void provideDictionary(CompressionDictionary dictionary) throws IOException;
}
