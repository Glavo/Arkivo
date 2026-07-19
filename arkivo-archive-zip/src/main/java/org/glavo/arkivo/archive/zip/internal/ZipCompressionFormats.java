// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.zip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.archive.internal.StreamChannelAdapters;
import org.glavo.arkivo.archive.zip.ZipMethod;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.EncodingOptions;
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

    /// Requires the encoder implementation used by the given ZIP compression method to be installed.
    static void requireEncoderAvailable(ZipMethod method) throws IOException {
        Objects.requireNonNull(method, "method");
        @Nullable String formatName = switch (method) {
            case STORED -> null;
            case DEFLATED -> "deflate";
            case DEFLATE64 -> "deflate64";
            case BZIP2 -> "bzip2";
            case LZMA -> RAW_LZMA_NAME;
            case DEPRECATED_ZSTANDARD, ZSTANDARD -> "zstd";
            case XZ -> "xz";
        };
        if (formatName == null) {
            return;
        }

        CompressionCodec<?> codec = requireDefaultCodec(formatName);
        if (method == ZipMethod.LZMA && !(codec instanceof RawLZMACodec)) {
            throw incompatibleCodec(formatName);
        }
    }

    /// Creates a default compressing channel for a named format and retains the entry's compressed-data target.
    static CompressingWritableByteChannel newWritableByteChannel(
            String formatName,
            OutputStream target
    ) throws IOException {
        return CompressionFormats.newWritableByteChannel(
                formatName,
                StreamChannelAdapters.writableChannel(target),
                EncodingOptions.DEFAULT,
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
        CompressionCodec<?> configured = withDecodingLimits(
                requireDefaultCodec(formatName),
                decodedSize,
                readLimits
        );
        return configured.newReadableByteChannel(
                StreamChannelAdapters.readableChannel(source),
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
                EncodingOptions.DEFAULT,
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
        return withDecodingLimits(configured, entryDecodedSize, readLimits).newReadableByteChannel(
                StreamChannelAdapters.readableChannel(source),
                ResourceOwnership.OWNED
        );
    }

    /// Returns a codec constrained by its existing limits, the entry size, and the archive read policy.
    static CompressionCodec<?> withDecodingLimits(
            CompressionCodec<?> codec,
            long decodedSize,
            ArchiveReadLimits readLimits
    ) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(readLimits, "readLimits");
        if (decodedSize < CompressionCodec.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("decodedSize must be non-negative or UNLIMITED_SIZE");
        }
        return codec
                .withMaximumOutputSize(moreRestrictive(codec.maximumOutputSize(), decodedSize))
                .withMaximumWindowSize(moreRestrictive(
                        codec.maximumWindowSize(),
                        readLimits.effectiveCompressionWindowSize()
                ))
                .withMaximumMemorySize(moreRestrictive(
                        codec.maximumMemorySize(),
                        readLimits.maximumDecoderMemorySize()
                ));
    }

    /// Returns the smaller finite limit, or the available finite limit when the other is unrestricted.
    private static long moreRestrictive(long first, long second) {
        if (first < 0L) {
            return second;
        }
        if (second < 0L) {
            return first;
        }
        return Math.min(first, second);
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
