// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

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

/// Connects optional compression codecs to 7z coder streams without depending on codec implementations.
@NotNullByDefault
final class SevenZipCompressionCodecs {
    /// The optional LZMA codecs' stable dictionary-size option.
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

    /// The optional PPMd codec's stable maximum-order option.
    private static final CodecOption<Long> PPMD_MAXIMUM_ORDER =
            CodecOption.of("ppmd.maximumOrder", Long.class);

    /// The optional PPMd codec's stable model-memory option.
    private static final CodecOption<Long> PPMD_MEMORY_SIZE =
            CodecOption.of("ppmd.memorySize", Long.class);

    /// The optional PPMd codec's stable exact-output-size option.
    private static final CodecOption<Long> PPMD_DECODED_SIZE =
            CodecOption.of("ppmd.decodedSize", Long.class);

    /// Creates no instances.
    private SevenZipCompressionCodecs() {
    }

    /// Opens a configured encoder that owns the downstream coder stream.
    static OutputStream openEncoder(
            String codecName,
            int compressionLevel,
            OutputStream target
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.COMPRESSION_LEVEL, (long) compressionLevel)
                .build();
        CompressionEncoder encoder = CompressionCodecs.openEncoder(
                codecName,
                StreamChannelAdapters.writableChannel(target),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens a configured PPMd7 encoder that owns the downstream coder stream.
    static OutputStream openPpmdEncoder(
            int maximumOrder,
            long memorySize,
            OutputStream target
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(PPMD_MAXIMUM_ORDER, (long) maximumOrder)
                .set(PPMD_MEMORY_SIZE, memorySize)
                .build();
        CompressionEncoder encoder = CompressionCodecs.openEncoder(
                "ppmd",
                StreamChannelAdapters.writableChannel(target),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens a raw LZMA encoder that owns the downstream coder stream.
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
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens a raw LZMA2 encoder that owns the downstream coder stream.
    static OutputStream openLZMA2Encoder(long dictionarySize, OutputStream target) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(LZMA_DICTIONARY_SIZE, dictionarySize)
                .build();
        CompressionEncoder encoder = CompressionCodecs.openEncoder(
                "lzma2",
                StreamChannelAdapters.writableChannel(target),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens a size-limited decoder that owns the packed coder stream.
    static InputStream openDecoder(
            String codecName,
            InputStream source,
            long maximumOutputSize
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, maximumOutputSize)
                .build();
        CompressionDecoder decoder = CompressionCodecs.openDecoder(
                codecName,
                StreamChannelAdapters.readableChannel(source),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }

    /// Opens an exactly sized PPMd7 decoder through the optional codec provider.
    static InputStream openPpmdDecoder(
            InputStream source,
            int maximumOrder,
            long memorySize,
            long decodedSize
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(PPMD_MAXIMUM_ORDER, (long) maximumOrder)
                .set(PPMD_MEMORY_SIZE, memorySize)
                .set(PPMD_DECODED_SIZE, decodedSize)
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, decodedSize)
                .build();
        CompressionDecoder decoder = CompressionCodecs.openDecoder(
                "ppmd",
                StreamChannelAdapters.readableChannel(source),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }

    /// Opens an exactly sized raw LZMA decoder with packed coder properties.
    static InputStream openRawLZMADecoder(
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
        CodecOptions options = CodecOptions.builder()
                .set(LZMA_DICTIONARY_SIZE, dictionarySize)
                .set(LZMA_LITERAL_CONTEXT_BITS, literalContextBits)
                .set(LZMA_LITERAL_POSITION_BITS, literalPositionBits)
                .set(LZMA_POSITION_BITS, positionBits)
                .set(LZMA_DECODED_SIZE, decodedSize)
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, decodedSize)
                .build();
        CompressionDecoder decoder = CompressionCodecs.openDecoder(
                "lzma-raw",
                StreamChannelAdapters.readableChannel(source),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }

    /// Opens a size-limited raw LZMA2 decoder.
    static InputStream openLZMA2Decoder(
            InputStream source,
            long dictionarySize,
            long maximumOutputSize
    ) throws IOException {
        CodecOptions options = CodecOptions.builder()
                .set(LZMA_DICTIONARY_SIZE, dictionarySize)
                .set(StandardCodecOptions.MAX_OUTPUT_SIZE, maximumOutputSize)
                .build();
        CompressionDecoder decoder = CompressionCodecs.openDecoder(
                "lzma2",
                StreamChannelAdapters.readableChannel(source),
                options,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }
}
