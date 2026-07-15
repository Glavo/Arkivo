// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// Connects optional compression codecs to ZIP entry streams without depending on codec implementations.
@NotNullByDefault
final class ZipCompressionCodecs {
    /// The optional raw LZMA codec's stable dictionary-size option.
    private static final CodecOption<Long> LZMA_DICTIONARY_SIZE =
            CodecOption.of("lzma.dictionarySize", Long.class);

    /// The optional raw LZMA codec's stable literal-context option.
    private static final CodecOption<Long> LZMA_LITERAL_CONTEXT_BITS =
            CodecOption.of("lzma.literalContextBits", Long.class);

    /// The optional raw LZMA codec's stable literal-position option.
    private static final CodecOption<Long> LZMA_LITERAL_POSITION_BITS =
            CodecOption.of("lzma.literalPositionBits", Long.class);

    /// The optional raw LZMA codec's stable position-state option.
    private static final CodecOption<Long> LZMA_POSITION_BITS =
            CodecOption.of("lzma.positionBits", Long.class);

    /// The optional raw LZMA codec's stable end-marker option.
    private static final CodecOption<Boolean> LZMA_END_MARKER =
            CodecOption.of("lzma.endMarker", Boolean.class);

    /// The optional raw LZMA codec's stable exact-output-size option.
    private static final CodecOption<Long> LZMA_DECODED_SIZE =
            CodecOption.of("lzma.decodedSize", Long.class);

    /// Creates no instances.
    private ZipCompressionCodecs() {
    }

    /// Opens a named encoder that retains the entry's compressed-data target.
    static CompressionEncoder openEncoder(String codecName, OutputStream target) throws IOException {
        return CompressionCodecs.openEncoder(
                codecName,
                StreamChannelAdapters.writableChannel(target),
                ChannelOwnership.RETAIN
        );
    }

    /// Opens a named decoder that owns the compressed entry stream.
    static CompressionDecoder openDecoder(String codecName, InputStream source) throws IOException {
        return CompressionCodecs.openDecoder(
                codecName,
                StreamChannelAdapters.readableChannel(source),
                ChannelOwnership.CLOSE
        );
    }

    /// Opens an input-stream view over an owning named decoder.
    static InputStream openInputStream(String codecName, InputStream source) throws IOException {
        return StreamChannelAdapters.inputStream(openDecoder(codecName, source));
    }

    /// Opens a raw LZMA encoder that retains the compressed entry target.
    static OutputStream openRawLZMAEncoder(
            long dictionarySize,
            boolean endMarker,
            OutputStream target
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(LZMA_DICTIONARY_SIZE, dictionarySize)
                .set(LZMA_END_MARKER, endMarker)
                .build();
        CompressionEncoder encoder = CompressionCodecs.openEncoder(
                "lzma-raw",
                StreamChannelAdapters.writableChannel(target),
                options,
                ChannelOwnership.RETAIN
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens an input-stream view over a raw LZMA decoder with externally stored ZIP properties.
    static InputStream openRawLZMADecoder(
            InputStream source,
            int property,
            long dictionarySize,
            long decodedSize
    ) throws IOException {
        return StreamChannelAdapters.inputStream(openRawLZMADecoderContext(
                source,
                property,
                dictionarySize,
                decodedSize
        ));
    }

    /// Opens a raw LZMA decoder context with externally stored ZIP properties.
    static CompressionDecoder openRawLZMADecoderContext(
            InputStream source,
            int property,
            long dictionarySize,
            long decodedSize
    ) throws IOException {
        int remainder = property;
        long literalContextBits = remainder % 9;
        remainder /= 9;
        long literalPositionBits = remainder % 5;
        long positionBits = remainder / 5;
        CodecOptions.Builder options = CodecOptions.builder()
                .set(LZMA_DICTIONARY_SIZE, dictionarySize)
                .set(LZMA_LITERAL_CONTEXT_BITS, literalContextBits)
                .set(LZMA_LITERAL_POSITION_BITS, literalPositionBits)
                .set(LZMA_POSITION_BITS, positionBits);
        if (decodedSize >= 0L) {
            options.set(LZMA_DECODED_SIZE, decodedSize)
                    .set(StandardCodecOptions.MAX_OUTPUT_SIZE, decodedSize);
        }
        return CompressionCodecs.openDecoder(
                "lzma-raw",
                StreamChannelAdapters.readableChannel(source),
                options.build(),
                ChannelOwnership.CLOSE
        );
    }
}