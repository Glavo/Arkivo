// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.glavo.arkivo.archive.ArchiveReadLimits;
import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.CompressionFormats;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lzma.LZMA2Codec;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.codec.ppmd.PPMdCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Objects;

/// Resolves optional compression formats and adapts their immutable codecs to 7z coder streams.
@NotNullByDefault
final class SevenZipCompressionFormats {
    /// The stable optional raw LZMA format name.
    private static final String RAW_LZMA_NAME = "lzma-raw";

    /// The stable optional raw LZMA2 format name.
    private static final String LZMA2_NAME = "lzma2";

    /// The stable optional PPMd format name.
    private static final String PPMD_NAME = "ppmd";

    /// Creates no instances.
    private SevenZipCompressionFormats() {
    }

    /// Creates a compression-level-configured output stream that owns the downstream coder stream.
    static OutputStream newOutputStream(
            String formatName,
            int compressionLevel,
            OutputStream target
    ) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(formatName);
        if (!(codec instanceof CompressionCodec.LevelConfigurable<?> configurableCodec)) {
            throw new UnsupportedOperationException(
                    "Compression codec does not support compression levels: " + codec.format().name()
            );
        }
        return newOwningOutputStream(
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
        CompressionCodec<?> codec = requireDefaultCodec(PPMD_NAME);
        if (!(codec instanceof PPMdCodec ppmdCodec)) {
            throw incompatibleCodec(PPMD_NAME);
        }
        return newOwningOutputStream(
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
        CompressionCodec<?> codec = requireDefaultCodec(RAW_LZMA_NAME);
        if (!(codec instanceof RawLZMACodec rawCodec)) {
            throw incompatibleCodec(RAW_LZMA_NAME);
        }
        return newOwningOutputStream(
                rawCodec
                        .withDictionarySize(Math.toIntExact(dictionarySize))
                        .withEndMarker(endMarker),
                target
        );
    }

    /// Opens a raw LZMA2 encoder that owns the downstream coder stream.
    static OutputStream openLZMA2Encoder(long dictionarySize, OutputStream target) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(LZMA2_NAME);
        if (!(codec instanceof LZMA2Codec lzma2Codec)) {
            throw incompatibleCodec(LZMA2_NAME);
        }
        return newOwningOutputStream(
                lzma2Codec.withDictionarySize(Math.toIntExact(dictionarySize)),
                target
        );
    }

    /// Creates a size-limited input stream that owns the packed coder stream.
    static InputStream newInputStream(
            String formatName,
            InputStream source,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        return newOwningInputStream(
                withDecodingLimits(requireDefaultCodec(formatName), maximumOutputSize, readLimits),
                source
        );
    }

    /// Opens an exactly sized PPMd7 decoder through the optional compression format.
    static InputStream openPpmdDecoder(
            InputStream source,
            int maximumOrder,
            long memorySize,
            long decodedSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(PPMD_NAME);
        if (!(codec instanceof PPMdCodec ppmdCodec)) {
            throw incompatibleCodec(PPMD_NAME);
        }
        PPMdCodec configured = ppmdCodec
                .withMaximumOrder(maximumOrder)
                .withMemorySize(memorySize)
                .withDecodedSize(decodedSize);
        return newOwningInputStream(withDecodingLimits(configured, decodedSize, readLimits), source);
    }

    /// Opens an exactly sized raw LZMA decoder with packed coder properties.
    static InputStream openRawLZMADecoder(
            InputStream source,
            int property,
            long dictionarySize,
            long decodedSize,
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
        RawLZMACodec configured = rawCodec
                .withProperties(properties)
                .withDecodedSize(decodedSize);
        return newOwningInputStream(withDecodingLimits(configured, decodedSize, readLimits), source);
    }

    /// Opens a size-limited raw LZMA2 decoder.
    static InputStream openLZMA2Decoder(
            InputStream source,
            long dictionarySize,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) throws IOException {
        CompressionCodec<?> codec = requireDefaultCodec(LZMA2_NAME);
        if (!(codec instanceof LZMA2Codec lzma2Codec)) {
            throw incompatibleCodec(LZMA2_NAME);
        }
        return newOwningInputStream(
                withDecodingLimits(
                        lzma2Codec.withDictionarySize(Math.toIntExact(dictionarySize)),
                        maximumOutputSize,
                        readLimits
                ),
                source
        );
    }

    /// Returns a codec constrained by its existing limits, one coder output bound, and archive read policy.
    static CompressionCodec<?> withDecodingLimits(
            CompressionCodec<?> codec,
            long maximumOutputSize,
            ArchiveReadLimits readLimits
    ) {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(readLimits, "readLimits");
        if (maximumOutputSize < CompressionCodec.UNLIMITED_SIZE) {
            throw new IllegalArgumentException("maximumOutputSize must be non-negative or UNLIMITED_SIZE");
        }
        return codec
                .withMaximumOutputSize(moreRestrictive(codec.maximumOutputSize(), maximumOutputSize))
                .withMaximumWindowSize(moreRestrictive(
                        codec.maximumWindowSize(),
                        readLimits.maximumCompressionWindowSize()
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

    /// Creates an owning output-stream view over a configured codec.
    private static OutputStream newOwningOutputStream(
            CompressionCodec<?> codec,
            OutputStream target
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(target, "target");
        return codec.newOutputStream(target, EncodingOptions.DEFAULT, ResourceOwnership.OWNED);
    }

    /// Creates an owning input-stream view over a configured codec.
    private static InputStream newOwningInputStream(
            CompressionCodec<?> codec,
            InputStream source
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        return codec.newInputStream(source, ResourceOwnership.OWNED);
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
