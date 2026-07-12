// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.jetbrains.annotations.NotNullByDefault;

/// Defines standard type-safe options that codecs may advertise and implement.
@NotNullByDefault
public final class StandardCodecOptions {
    /// Selects an algorithm-specific compression level.
    public static final CodecOption<CompressionLevel> COMPRESSION_LEVEL =
            CodecOption.of("compression.level", CompressionLevel.class);

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

    /// Limits the total number of uncompressed bytes produced by decompression.
    public static final CodecOption<Long> MAX_OUTPUT_SIZE =
            CodecOption.of("decompression.maxOutputSize", Long.class);

    /// Limits the decoding window size in bytes.
    public static final CodecOption<Long> MAX_WINDOW_SIZE =
            CodecOption.of("decompression.maxWindowSize", Long.class);

    /// Creates no instances.
    private StandardCodecOptions() {
    }
}
