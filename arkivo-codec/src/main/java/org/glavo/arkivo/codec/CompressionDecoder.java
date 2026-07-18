// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;

/// Incrementally decodes one compression stream between caller-owned byte buffers.
///
/// Implementations are stateful and not safe for concurrent use. They must not retain a reference to a source or target
/// buffer after an operation returns. Successful operations advance the source position by compressed bytes consumed and
/// the target position by decoded bytes produced without changing either limit or byte order. Positions remain the
/// authoritative progress record if an operation throws after partial progress. Every target must be writable, and each
/// operation requires different source and target instances. These are caller preconditions: implementations are not
/// required to reject a read-only target or the same source and target instance before making progress.
///
/// A completed encoding leaves trailing bytes unconsumed when its boundary is detectable from the compressed data.
/// [#decode(ByteBuffer, ByteBuffer)] permits later input; [#finish(ByteBuffer, ByteBuffer)] asserts that its source
/// contains the final available bytes and therefore reports truncation as an `IOException`. Once an operation returns
/// [CodecOutcome#FINISHED], call [#reset()] before decoding another independent or concatenated frame.
///
/// A buffer-driven decoding loop can switch to `finish` only after a blocking source reports physical end-of-input:
///
/// ```java
/// try (CompressionDecoder decoder = codec.newDecoder(limits)) {
///     ByteBuffer input = ByteBuffer.allocate(8192);
///     ByteBuffer output = ByteBuffer.allocate(8192);
///     input.limit(0);
///     boolean endOfInput = false;
///     while (true) {
///         CodecOutcome outcome = endOfInput
///                 ? decoder.finish(input, output)
///                 : decoder.decode(input, output);
///         if (outcome == CodecOutcome.NEEDS_OUTPUT || outcome == CodecOutcome.FINISHED) {
///             output.flip();
///             while (output.hasRemaining()) {
///                 sink.write(output);
///             }
///             output.compact();
///             if (outcome == CodecOutcome.FINISHED) {
///                 break;
///             }
///             continue;
///         }
///         if (outcome == CodecOutcome.NEEDS_INPUT) {
///             input.compact();
///             endOfInput = compressedSource.read(input) < 0;
///             input.flip();
///             continue;
///         }
///         throw new IOException("Dictionary handling is required");
///     }
/// }
/// ```
///
/// Dictionary-aware callers replace the final branch with [DictionaryAware#dictionaryRequest()] and
/// [DictionaryAware#provideDictionary(CompressionDictionary)]. A `FINISHED` result may leave trailing compressed bytes
/// between the input position and limit.
@NotNullByDefault
public interface CompressionDecoder extends AutoCloseable {
    /// Decodes compressed source bytes while allowing additional source bytes in a later operation.
    ///
    /// `NEEDS_INPUT` means the source was exhausted before the encoding completed. `NEEDS_OUTPUT` means the target was
    /// filled first. `NEEDS_DICTIONARY` is returned only by a [CompressionDecoder.DictionaryAware] decoder, and
    /// `FINISHED` marks the detected end of the current encoding.
    ///
    /// @param source the compressed input buffer
    /// @param target the distinct writable decoded-output buffer
    /// @return the actionable reason this operation returned
    /// @throws IOException if the compressed data is invalid or decoding fails
    CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Decodes after the caller has supplied all remaining compressed source bytes.
    ///
    /// Exhausting the source before the encoding completes is an `IOException`; this method does not return
    /// `NEEDS_INPUT`. A `NEEDS_OUTPUT` result can be resumed with more target space and the source in its resulting state.
    ///
    /// @param source the final compressed input buffer
    /// @param target the distinct writable decoded-output buffer
    /// @return the actionable reason this operation returned
    /// @throws IOException if the encoding is truncated, invalid, or cannot be decoded
    CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException;

    /// Abandons the current encoding and restores the decoder's original immutable configuration.
    ///
    /// Buffered algorithm state and any outstanding dictionary request are discarded.
    void reset();

    /// Releases decoder resources without consuming additional input.
    ///
    /// The decoder cannot be reused after this method returns.
    @Override
    void close();

    /// Decodes a format whose independently terminated frames may be concatenated and decoded after reset.
    ///
    /// When `FINISHED` is returned, the source position identifies the first byte after the completed frame. If more
    /// source remains, [CompressionDecoder#reset()] prepares the engine for the following frame.
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
        /// This method is valid only after `CodecOutcome.NEEDS_DICTIONARY` and before reset or successful dictionary
        /// provision.
        ///
        /// @return the outstanding format-specific dictionary request
        R dictionaryRequest();

        /// Supplies a dictionary in response to the most recent request.
        ///
        /// After this method succeeds, resume decoding with the source and target positions left by the operation that
        /// requested the dictionary.
        ///
        /// @param dictionary the format-specific dictionary satisfying the outstanding request
        /// @throws IOException if the dictionary cannot be installed
        /// @throws IllegalArgumentException if {@code dictionary} does not satisfy the request
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
