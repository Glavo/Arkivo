// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally decodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. A completed encoding leaves trailing bytes unconsumed when its boundary is
/// detectable from the compressed data.
@NotNullByDefault
public interface CompressionDecoder extends AutoCloseable {
    /// Decodes compressed source bytes while allowing additional source bytes in a later operation.
    ///
    /// Buffer positions are the authoritative record of bytes consumed and produced.
    ///
    /// @return the actionable reason this operation returned
    CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Decodes after the caller has supplied all remaining compressed source bytes.
    ///
    /// Exhausting the source before the encoding completes is an error. Buffer positions are the authoritative record
    /// of bytes consumed and produced.
    ///
    /// @return the actionable reason this operation returned
    CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Abandons the current encoding and restores the decoder's original immutable configuration.
    void reset();

    /// Releases decoder resources without consuming additional input.
    @Override
    void close();

    /// Decodes a format whose independently terminated frames may be concatenated and decoded after reset.
    @NotNullByDefault
    interface Framed extends CompressionDecoder {
    }

    /// Decodes a format that can request and accept a format-specific dictionary after decoding has started.
    ///
    /// @param <D> the format-specific dictionary type accepted by this decoder
    /// @param <R> the format-specific request type exposed by this decoder
    @NotNullByDefault
    interface DictionaryAware<
            D extends CompressionDictionary,
            R extends DictionaryRequest<D>
            > extends CompressionDecoder {
        /// Returns the dictionary request produced by the most recent decode operation.
        ///
        /// This method is valid only after `CodecOutcome.NEEDS_DICTIONARY`.
        R dictionaryRequest();

        /// Supplies a dictionary in response to the most recent request.
        void provideDictionary(D dictionary) throws IOException;
    }

    /// Decodes concatenated frames and supports late binding of format-specific dictionaries.
    ///
    /// @param <D> the format-specific dictionary type accepted by this decoder
    /// @param <R> the format-specific request type exposed by this decoder
    @NotNullByDefault
    interface FramedDictionaryAware<
            D extends CompressionDictionary,
            R extends DictionaryRequest<D>
            > extends Framed, DictionaryAware<D, R> {
    }
}
