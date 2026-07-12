// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines standard type-safe options that codecs may advertise and implement.
@NotNullByDefault
public final class StandardCodecOptions {
    /// Selects an algorithm-specific compression level.
    public static final CodecOption<Long> COMPRESSION_LEVEL =
            CodecOption.of("compression.level", Long.class);

    /// Supplies raw dictionary content for compression or decompression.
    public static final CodecOption<CompressionDictionary> DICTIONARY =
            CodecOption.of("compression.dictionary", CompressionDictionary.class);

    /// Declares the exact uncompressed source size before compression starts.
    public static final CodecOption<Long> PLEDGED_SOURCE_SIZE =
            CodecOption.of("compression.pledgedSourceSize", Long.class);

    /// Selects checksum generation or verification behavior.
    public static final CodecOption<ChecksumMode> CHECKSUM =
            CodecOption.of("compression.checksum", ChecksumMode.class);

    /// Selects the number of compression worker threads.
    public static final CodecOption<WorkerCount> WORKER_COUNT =
            CodecOption.of("compression.workerCount", WorkerCount.class);

    /// Limits decompression to a non-negative number of caller-visible bytes.
    ///
    /// A decoder throws DecompressionLimitException after confirming that additional output exists.
    public static final CodecOption<Long> MAX_OUTPUT_SIZE =
            CodecOption.of("decompression.maxOutputSize", Long.class);

    /// Limits a format's declared or fixed decoding window to a non-negative number of bytes.
    ///
    /// Only codecs with an LZ-style history window advertise this option.
    public static final CodecOption<Long> MAX_WINDOW_SIZE =
            CodecOption.of("decompression.maxWindowSize", Long.class);

    /// Creates no instances.
    private StandardCodecOptions() {
    }
}
