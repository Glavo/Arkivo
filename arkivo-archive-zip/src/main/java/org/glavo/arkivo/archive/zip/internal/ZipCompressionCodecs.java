// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionCodecs;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/// Connects optional compression codecs to ZIP entry streams without depending on codec implementations.
@NotNullByDefault
final class ZipCompressionCodecs {
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
}