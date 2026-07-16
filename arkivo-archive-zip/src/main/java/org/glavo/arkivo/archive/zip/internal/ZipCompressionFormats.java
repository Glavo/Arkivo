// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// Connects optional immutable compression codecs to ZIP entry streams.
@NotNullByDefault
final class ZipCompressionFormats {
    /// The stable optional raw LZMA codec name.
    private static final String RAW_LZMA_NAME = "lzma-raw";

    /// Creates no instances.
    private ZipCompressionFormats() {
    }

    /// Opens a named default encoder that retains the entry's compressed-data target.
    static CompressingWritableByteChannel openEncoder(
            String codecName,
            OutputStream target
    ) throws IOException {
        return CompressionFormats.openEncoder(
                codecName,
                StreamChannelAdapters.writableChannel(target),
                ChannelOwnership.RETAIN
        );
    }

    /// Opens a named default decoder that owns the compressed entry stream.
    static DecompressingReadableByteChannel openDecoder(
            String codecName,
            InputStream source
    ) throws IOException {
        return CompressionFormats.openDecoder(
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
        CompressionCodec codec = requireCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        RawLZMACodec configured = rawCodec
                .withDictionarySize(Math.toIntExact(dictionarySize))
                .withEndMarker(endMarker);
        CompressingWritableByteChannel encoder = configured.openEncoder(
                StreamChannelAdapters.writableChannel(target),
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
    static DecompressingReadableByteChannel openRawLZMADecoderContext(
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
        RawLZMACodec configured = rawCodec.withProperties(properties);
        DecompressionLimits limits = DecompressionLimits.UNLIMITED;
        if (decodedSize >= 0L) {
            configured = configured.withDecodedSize(decodedSize);
            limits = DecompressionLimits.ofMaximumOutputSize(decodedSize);
        }
        return configured.openDecoder(
                StreamChannelAdapters.readableChannel(source),
                limits,
                ChannelOwnership.CLOSE
        );
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
