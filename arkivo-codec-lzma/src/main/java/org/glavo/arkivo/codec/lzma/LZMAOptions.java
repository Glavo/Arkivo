// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma;

import org.glavo.arkivo.codec.CodecOption;
import org.jetbrains.annotations.NotNullByDefault;

/// Defines LZMA model options shared by LZMA-alone and LZMA2-based codecs.
@NotNullByDefault
public final class LZMAOptions {
    /// Selects the non-negative LZMA dictionary size in bytes.
    public static final CodecOption<Long> DICTIONARY_SIZE =
            CodecOption.of("lzma.dictionarySize", Long.class);

    /// Selects zero through four previous-byte high bits used by literal contexts.
    ///
    /// The default is three, and the sum with `LITERAL_POSITION_BITS` must not exceed four.
    public static final CodecOption<Long> LITERAL_CONTEXT_BITS =
            CodecOption.of("lzma.literalContextBits", Long.class);

    /// Selects zero through four output-position low bits used by literal contexts.
    ///
    /// The default is zero, and the sum with `LITERAL_CONTEXT_BITS` must not exceed four.
    public static final CodecOption<Long> LITERAL_POSITION_BITS =
            CodecOption.of("lzma.literalPositionBits", Long.class);

    /// Selects zero through four output-position low bits used by match contexts.
    ///
    /// The default is two.
    public static final CodecOption<Long> POSITION_BITS =
            CodecOption.of("lzma.positionBits", Long.class);

    /// Selects whether a raw LZMA encoder writes an end marker.
    ///
    /// The default is `true`. Containers that carry an exact decoded size may disable the marker.
    public static final CodecOption<Boolean> END_MARKER =
            CodecOption.of("lzma.endMarker", Boolean.class);

    /// Supplies the exact decoded size of a raw LZMA stream.
    ///
    /// When absent, a raw decoder requires an end marker to terminate the stream.
    public static final CodecOption<Long> DECODED_SIZE =
            CodecOption.of("lzma.decodedSize", Long.class);

    /// Creates no instances.
    private LZMAOptions() {
    }
}
