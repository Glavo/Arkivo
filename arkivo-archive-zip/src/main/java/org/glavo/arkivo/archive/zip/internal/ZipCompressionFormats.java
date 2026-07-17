// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.ResourceOwnership;
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
import java.util.Objects;

/// Resolves compression formats and adapts their immutable codecs to ZIP entry streams.
@NotNullByDefault
final class ZipCompressionFormats {
    /// The stable optional raw LZMA format name.
    private static final String RAW_LZMA_NAME = "lzma-raw";

    /// Creates no instances.
    private ZipCompressionFormats() {
    }

    /// Creates a default compressing channel for a named format and retains the entry's compressed-data target.
    static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            OutputStream target
    ) throws IOException {
        return CompressionFormats.newWritableByteChannel(
                formatName,
                StreamChannelAdapters.writableChannel(target),
                ResourceOwnership.BORROWED
        );
    }

    /// Creates a default decompressing channel for a named format and owns the compressed entry stream.
    static DecompressingReadableByteChannel newReadableByteChannel(
            String formatName,
            InputStream source,
            long decodedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        return CompressionFormats.newReadableByteChannel(
                formatName,
                StreamChannelAdapters.readableChannel(source),
                decompressionLimits(decodedSize, readLimits),
                ResourceOwnership.OWNED
        );
    }

    /// Creates a limited input-stream view over the owning default decoder for a named format.
    static InputStream newInputStream(
            String formatName,
            InputStream source,
            long decodedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        return StreamChannelAdapters.inputStream(newReadableByteChannel(
                formatName,
                source,
                decodedSize,
                readLimits
        ));
    }

    /// Opens a raw LZMA encoder that retains the compressed entry target.
    static OutputStream openRawLZMAEncoder(
            long dictionarySize,
            boolean endMarker,
            OutputStream target
    ) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        RawLZMACodec configured = rawCodec
                .withDictionarySize(Math.toIntExact(dictionarySize))
                .withEndMarker(endMarker);
        CompressingWritableByteChannel encoder = configured.newWritableByteChannel(
                StreamChannelAdapters.writableChannel(target),
                ResourceOwnership.BORROWED
        );
        return StreamChannelAdapters.outputStream(encoder);
    }

    /// Opens an input-stream view over a raw LZMA decoder with externally stored ZIP properties.
    static InputStream openRawLZMADecoder(
            InputStream source,
            int property,
            long dictionarySize,
            long expectedDecodedSize,
            long entryDecodedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        return StreamChannelAdapters.inputStream(openRawLZMADecoderContext(
                source,
                property,
                dictionarySize,
                expectedDecodedSize,
                entryDecodedSize,
                readLimits
        ));
    }

    /// Opens a raw LZMA decoder context with externally stored ZIP properties.
    static DecompressingReadableByteChannel openRawLZMADecoderContext(
            InputStream source,
            int property,
            long dictionarySize,
            long expectedDecodedSize,
            long entryDecodedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        LZMAProperties properties = LZMAProperties.decode(
                property,
                Math.toIntExact(dictionarySize)
        );
        RawLZMACodec configured = rawCodec.withProperties(properties);
        if (expectedDecodedSize >= 0L) {
            configured = configured.withDecodedSize(expectedDecodedSize);
        }
        return configured.newReadableByteChannel(
                StreamChannelAdapters.readableChannel(source),
                decompressionLimits(entryDecodedSize, readLimits),
                ResourceOwnership.OWNED
        );
    }

    /// Maps archive read limits to one codec decoder operation.
    static DecompressionLimits decompressionLimits(long decodedSize, ArchiveReadLimits readLimits) {
        Objects.requireNonNull(readLimits, "readLimits");
        if (decodedSize < DecompressionLimits.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("decodedSize must be non-negative or UNLIMITED_SIZE");
        }
        return new DecompressionLimits(
                decodedSize,
                readLimits.effectiveCompressionWindowSize(),
                readLimits.maximumDecoderMemorySize()
        );
    }

    /// Returns the default codec for an installed optional format or reports the stable missing-format diagnostic.
    private static CompressionCodec<?> requireDefaultCodec(String formatName) throws IOException {
        @Nullable CompressionFormat format = CompressionFormats.find(formatName);
        if (format == null) {
            throw new IOException("Unknown compression format: " + formatName);
        }
        return format.defaultCodec();
    }

    /// Creates a failure for an installed format with an unexpected implementation type.
    private static IOException incompatibleCodec(String formatName) {
        return new IOException("Incompatible default codec for compression format: " + formatName);
    }
}
