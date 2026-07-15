// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.all;

import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.lzma.LZMAOptions;
import org.glavo.arkivo.codec.ppmd.PPMdCodecOptions;
import org.jetbrains.annotations.NotNullByDefault;

/// Supplies format metadata required by raw codecs in cross-provider contract tests.
@NotNullByDefault
final class CodecContractOptions {
    /// The PPMd context order used by its default encoder.
    private static final long PPMD_MAXIMUM_ORDER = 6L;

    /// The PPMd model memory used by its default encoder.
    private static final long PPMD_MEMORY_SIZE = 16L << 20;

    /// The dictionary used by default raw LZMA and LZMA2 encoders.
    private static final long LZMA_DICTIONARY_SIZE = 1L << 20;

    /// Creates no instances.
    private CodecContractOptions() {
    }

    /// Returns decoder options matching a default encoding of the known uncompressed size.
    static CodecOptions decoderOptions(CompressionCodec codec, long decodedSize) {
        CodecOptions.Builder builder = CodecOptions.builder();
        addRequiredDecoderOptions(builder, codec, decodedSize);
        return builder.build();
    }

    /// Adds metadata required to decode a default encoding of the known uncompressed size.
    static void addRequiredDecoderOptions(
            CodecOptions.Builder builder,
            CompressionCodec codec,
            long decodedSize
    ) {
        if ("ppmd".equals(codec.name())) {
            builder.set(PPMdCodecOptions.MAXIMUM_ORDER, PPMD_MAXIMUM_ORDER)
                    .set(PPMdCodecOptions.MEMORY_SIZE, PPMD_MEMORY_SIZE)
                    .set(PPMdCodecOptions.DECODED_SIZE, decodedSize);
        } else if ("lzma-raw".equals(codec.name())) {
            builder.set(LZMAOptions.DICTIONARY_SIZE, LZMA_DICTIONARY_SIZE);
        } else if ("lzma2".equals(codec.name())) {
            builder.set(LZMAOptions.DICTIONARY_SIZE, LZMA_DICTIONARY_SIZE);
        }
    }

    /// Returns whether the raw codec requires external metadata for decompression.
    static boolean requiresDecoderOptions(CompressionCodec codec) {
        return "ppmd".equals(codec.name())
                || "lzma-raw".equals(codec.name())
                || "lzma2".equals(codec.name());
    }
}
