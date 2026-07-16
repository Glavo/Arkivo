// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.CompressionLevelCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Connects optional immutable compression codecs to 7z coder streams.
@NotNullByDefault
final class SevenZipCompressionFormats {
    /// The stable optional raw LZMA codec name.
    private static final String RAW_LZMA_NAME = "lzma-raw";

    /// The stable optional raw LZMA2 codec name.
    private static final String LZMA2_NAME = "lzma2";

    /// The stable optional PPMd codec name.
    private static final String PPMD_NAME = "ppmd";
    /// Creates no instances.
    private SevenZipCompressionFormats() {
    }

    /// Opens a compression-level-configured encoder that owns the downstream coder stream.
    static OutputStream openEncoder(
            String codecName,
            int compressionLevel,
            OutputStream target
    ) throws IOException {
        CompressionCodec codec = requireCodec(codecName);
        if (!(codec instanceof CompressionLevelCodec configurableCodec)) {
            throw new UnsupportedOperationException(
                    "Compression codec does not support compression levels: " + codec.format().name()
            );
        }
        return openOwningEncoder(
                configurableCodec.withCompressionLevel(compressionLevel),
                target
        );
    }

    /// Opens a configured PPMd7 encoder that owns the downstream coder stream.
    static OutputStream openPpmdEncoder(
            int maximumOrder,
            long memorySize,
            OutputStream target
    ) throws IOException {
        CompressionCodec codec = requireCodec(PPMD_NAME);
        if (!(codec instanceof PPMdCodec ppmdCodec)) {
            throw incompatibleCodec(PPMD_NAME);
        }
        return openOwningEncoder(
                ppmdCodec.withMaximumOrder(maximumOrder).withMemorySize(memorySize),
                target
        );
    }

    /// Opens a raw LZMA encoder that owns the downstream coder stream.
    static OutputStream openRawLZMAEncoder(
            long dictionarySize,
            boolean endMarker,
            OutputStream target
    ) throws IOException {
        CompressionCodec codec = requireCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        return openOwningEncoder(
                rawCodec
                        .withDictionarySize(Math.toIntExact(dictionarySize))
                        .withEndMarker(endMarker),
                target
        );
    }

    /// Opens a raw LZMA2 encoder that owns the downstream coder stream.
    static OutputStream openLZMA2Encoder(long dictionarySize, OutputStream target) throws IOException {
        CompressionCodec codec = requireCodec(LZMA2_NAME);
        if (!(codec instanceof LZMA2Codec lzma2Codec)) {
            throw incompatibleCodec(LZMA2_NAME);
        }
        return openOwningEncoder(
                lzma2Codec.withDictionarySize(Math.toIntExact(dictionarySize)),
                target
        );
    }

    /// Opens a size-limited decoder that owns the packed coder stream.
    static InputStream openDecoder(
            String codecName,
            InputStream source,
            long maximumOutputSize
    ) throws IOException {
        DecompressingReadableByteChannel decoder = CompressionFormats.openDecoder(
                codecName,
                StreamChannelAdapters.readableChannel(source),
                DecompressionLimits.ofMaximumOutputSize(maximumOutputSize),
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }

    /// Opens an exactly sized PPMd7 decoder through the optional compression format.
    static InputStream openPpmdDecoder(
            InputStream source,
            int maximumOrder,
            long memorySize,
            long decodedSize
    ) throws IOException {
        CompressionCodec codec = requireCodec(PPMD_NAME);
        if (!(codec instanceof PPMdCodec ppmdCodec)) {
            throw incompatibleCodec(PPMD_NAME);
        }
        PPMdCodec configured = ppmdCodec
                .withMaximumOrder(maximumOrder)
                .withMemorySize(memorySize)
                .withDecodedSize(decodedSize);
        return openOwningDecoder(
                configured,
                source,
                DecompressionLimits.ofMaximumOutputSize(decodedSize)
        );
    }

    /// Opens an exactly sized raw LZMA decoder with packed coder properties.
    static InputStream openRawLZMADecoder(
            InputStream source,
            int property,
            long dictionarySize,
            long decodedSize
    ) throws IOException {
        CompressionCodec codec = requireCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        LZMAProperties properties = LZMAProperties.decode(
                property,
                Math.toIntExact(dictionarySize)
        );
        RawLZMACodec configured = rawCodec
                .withProperties(properties)
                .withDecodedSize(decodedSize);
        return openOwningDecoder(
                configured,
                source,
                DecompressionLimits.ofMaximumOutputSize(decodedSize)
        );
    }

    /// Opens a size-limited raw LZMA2 decoder.
    static InputStream openLZMA2Decoder(
            InputStream source,
            long dictionarySize,
            long maximumOutputSize
    ) throws IOException {
        CompressionCodec codec = requireCodec(LZMA2_NAME);
        if (!(codec instanceof LZMA2Codec lzma2Codec)) {
            throw incompatibleCodec(LZMA2_NAME);
        }
        return openOwningDecoder(
                lzma2Codec.withDictionarySize(Math.toIntExact(dictionarySize)),
                source,
                DecompressionLimits.ofMaximumOutputSize(maximumOutputSize)
        );
    }

    /// Opens an owning output-stream view over a configured codec.
    private static OutputStream openOwningEncoder(
            CompressionCodec codec,
            OutputStream target
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(target, "target");
        WritableByteChannel targetChannel = StreamChannelAdapters.writableChannel(target);
        CompressingWritableByteChannel encoder =
                codec.openEncoder(targetChannel, ChannelOwnership.CLOSE);
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens an owning input-stream view over a configured codec.
    private static InputStream openOwningDecoder(
            CompressionCodec codec,
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        DecompressingReadableByteChannel decoder = codec.openDecoder(
                StreamChannelAdapters.readableChannel(source),
                limits,
                ChannelOwnership.CLOSE
        );
        return StreamChannelAdapters.inputStream(decoder);
    }

    /// Returns an installed optional codec or reports the stable missing-codec diagnostic.
    private static CompressionCodec requireCodec(String codecName) throws IOException {
        @Nullable CompressionFormat format = CompressionFormats.find(codecName);
        if (format == null) {
            throw new IOException("Unknown compression format: " + codecName);
        }
        return format.defaultCodec();
    }

    /// Creates a failure for an installed format with an unexpected implementation type.
    private static IOException incompatibleCodec(String codecName) {
        return new IOException("Incompatible default codec for compression format: " + codecName);
    }
}
