// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ChannelOwnership;
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
}
