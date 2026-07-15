// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. A completed encoding leaves trailing bytes unconsumed when its boundary is
/// detectable from the compressed data.
@NotNullByDefault
public interface CompressionDecoder extends AutoCloseable {
    /// Decodes compressed source bytes while allowing additional source bytes in a later operation.
    default CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decode(source, target, false);
    }

    /// Decodes compressed source bytes until input, output space, a dictionary, or the encoding boundary stops progress.
    ///
    /// When ndOfInput is true, exhausting the source before the encoding completes is an error. Buffer positions
    /// are the
    /// authoritative record of bytes consumed and produced.
    ///
    /// @return the actionable reason this operation returned
    CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException;

    /// Returns the format-specific identifier of the requested dictionary.
    ///
    /// This method is valid after `CodecOutcome.NEEDS_DICTIONARY`. Formats without an available identifier return
    /// `CompressionDictionary.UNKNOWN_ID`.
    default long requiredDictionaryId() {
        return CompressionDictionary.UNKNOWN_ID;
    }

    /// Supplies the dictionary requested by the most recent decode operation.
    ///
    /// The default implementation rejects decoders that do not support late dictionary binding.
    default void provideDictionary(CompressionDictionary dictionary) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        throw new UnsupportedOperationException("Late dictionary binding is not supported");
    }

    /// Abandons the current encoding and restores the decoder's original immutable configuration.
    void reset();

    /// Releases decoder resources without consuming additional input.
    @Override
    void close();
}
