// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.Objects;

/// Configures one 7z output compression method and its method-specific parameter.
///
/// `parameter` is zero for `COPY`, dictionary size in bytes for `LZMA` and `LZMA2`, BZIP2 block size from 1 through 9,
/// DEFLATE/Deflate64 level from 0 through 9, PPMd maximum order from 2 through 64, or a Zstandard level from -131072
/// through 22. `secondaryParameter` is the PPMd model-memory size in bytes and zero for every other method.
///
/// @param method the output compression method
/// @param parameter the validated method-specific parameter
/// @param secondaryParameter the validated secondary PPMd parameter, or zero
@NotNullByDefault
public record SevenZipCompression(SevenZipCompressionMethod method, int parameter, int secondaryParameter) {
    /// The minimum LZMA and LZMA2 dictionary size.
    public static final int MIN_DICTIONARY_SIZE = 4 * 1024;

    /// The maximum LZMA and LZMA2 dictionary size supported by the encoder.
    public static final int MAX_DICTIONARY_SIZE = 768 * 1024 * 1024;

    /// The default LZMA and LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The minimum BZIP2 block size.
    public static final int MIN_BZIP2_BLOCK_SIZE = 1;

    /// The maximum and default BZIP2 block size.
    public static final int MAX_BZIP2_BLOCK_SIZE = 9;

    /// The minimum DEFLATE compression level.
    public static final int MIN_DEFLATE_LEVEL = 0;

    /// The maximum and default DEFLATE compression level.
    public static final int MAX_DEFLATE_LEVEL = 9;

    /// The minimum Deflate64 compression level.
    public static final int MIN_DEFLATE64_LEVEL = 0;

    /// The maximum and default Deflate64 compression level.
    public static final int MAX_DEFLATE64_LEVEL = 9;

    /// The minimum PPMd maximum context order.
    public static final int MIN_PPMD_MAXIMUM_ORDER = 2;

    /// The maximum PPMd maximum context order.
    public static final int MAX_PPMD_MAXIMUM_ORDER = 64;

    /// The default PPMd maximum context order.
    public static final int DEFAULT_PPMD_MAXIMUM_ORDER = 6;

    /// The minimum PPMd model-memory size.
    public static final int MIN_PPMD_MEMORY_SIZE = 2 * 1024;

    /// The maximum PPMd model-memory size.
    public static final int MAX_PPMD_MEMORY_SIZE = 256 * 1024 * 1024;

    /// The default PPMd model-memory size.
    public static final int DEFAULT_PPMD_MEMORY_SIZE = 16 * 1024 * 1024;

    /// The minimum Zstandard compression level.
    public static final int MIN_ZSTANDARD_LEVEL = -131_072;

    /// The maximum Zstandard compression level.
    public static final int MAX_ZSTANDARD_LEVEL = 22;

    /// The default Zstandard compression level.
    public static final int DEFAULT_ZSTANDARD_LEVEL = 3;

    /// Validates a 7z compression configuration.
    public SevenZipCompression {
        Objects.requireNonNull(method, "method");
        if (method != SevenZipCompressionMethod.PPMD && secondaryParameter != 0) {
            throw new IllegalArgumentException("Secondary compression parameter must be zero for " + method);
        }
        switch (method) {
            case COPY -> {
                if (parameter != 0) {
                    throw new IllegalArgumentException("COPY compression parameter must be zero");
                }
            }
            case LZMA, LZMA2 -> {
                if (parameter < MIN_DICTIONARY_SIZE || parameter > MAX_DICTIONARY_SIZE) {
                    throw new IllegalArgumentException(
                            "LZMA dictionary size must be between "
                                    + MIN_DICTIONARY_SIZE
                                    + " and "
                                    + MAX_DICTIONARY_SIZE
                    );
                }
            }
            case BZIP2 -> {
                if (parameter < MIN_BZIP2_BLOCK_SIZE || parameter > MAX_BZIP2_BLOCK_SIZE) {
                    throw new IllegalArgumentException("BZIP2 block size must be between 1 and 9");
                }
            }
            case DEFLATE -> {
                if (parameter < MIN_DEFLATE_LEVEL || parameter > MAX_DEFLATE_LEVEL) {
                    throw new IllegalArgumentException("DEFLATE level must be between 0 and 9");
                }
            }
            case DEFLATE64 -> {
                if (parameter < MIN_DEFLATE64_LEVEL || parameter > MAX_DEFLATE64_LEVEL) {
                    throw new IllegalArgumentException("Deflate64 level must be between 0 and 9");
                }
            }
            case PPMD -> {
                if (parameter < MIN_PPMD_MAXIMUM_ORDER || parameter > MAX_PPMD_MAXIMUM_ORDER) {
                    throw new IllegalArgumentException("PPMd maximum order must be between 2 and 64");
                }
                if (secondaryParameter < MIN_PPMD_MEMORY_SIZE
                        || secondaryParameter > MAX_PPMD_MEMORY_SIZE) {
                    throw new IllegalArgumentException(
                            "PPMd memory size must be between 2 KiB and 256 MiB"
                    );
                }
            }
            case ZSTANDARD -> {
                if (parameter < MIN_ZSTANDARD_LEVEL || parameter > MAX_ZSTANDARD_LEVEL) {
                    throw new IllegalArgumentException(
                            "Zstandard level must be between "
                                    + MIN_ZSTANDARD_LEVEL
                                    + " and "
                                    + MAX_ZSTANDARD_LEVEL
                    );
                }
            }
        }
    }

    /// Creates a single-parameter compression configuration.
    public SevenZipCompression(SevenZipCompressionMethod method, int parameter) {
        this(method, parameter, 0);
    }

    /// Returns a default configuration for the given method.
    public static SevenZipCompression of(SevenZipCompressionMethod method) {
        Objects.requireNonNull(method, "method");
        return switch (method) {
            case COPY -> copy();
            case LZMA -> lzma();
            case LZMA2 -> lzma2();
            case BZIP2 -> bzip2();
            case DEFLATE -> deflate();
            case DEFLATE64 -> deflate64();
            case PPMD -> ppmd();
            case ZSTANDARD -> zstandard();
        };
    }

    /// Returns uncompressed COPY output.
    public static SevenZipCompression copy() {
        return new SevenZipCompression(SevenZipCompressionMethod.COPY, 0);
    }

    /// Returns LZMA output with the default dictionary size.
    public static SevenZipCompression lzma() {
        return lzma(DEFAULT_DICTIONARY_SIZE);
    }

    /// Returns LZMA output with the requested dictionary size.
    public static SevenZipCompression lzma(int dictionarySize) {
        return new SevenZipCompression(SevenZipCompressionMethod.LZMA, dictionarySize);
    }

    /// Returns LZMA2 output with the default dictionary size.
    public static SevenZipCompression lzma2() {
        return lzma2(DEFAULT_DICTIONARY_SIZE);
    }

    /// Returns LZMA2 output with the requested dictionary size.
    public static SevenZipCompression lzma2(int dictionarySize) {
        return new SevenZipCompression(SevenZipCompressionMethod.LZMA2, dictionarySize);
    }

    /// Returns BZIP2 output with the default maximum block size.
    public static SevenZipCompression bzip2() {
        return bzip2(MAX_BZIP2_BLOCK_SIZE);
    }

    /// Returns BZIP2 output with the requested block size from 1 through 9.
    public static SevenZipCompression bzip2(int blockSize) {
        return new SevenZipCompression(SevenZipCompressionMethod.BZIP2, blockSize);
    }

    /// Returns DEFLATE output with the default maximum compression level.
    public static SevenZipCompression deflate() {
        return deflate(MAX_DEFLATE_LEVEL);
    }

    /// Returns DEFLATE output with the requested compression level from 0 through 9.
    public static SevenZipCompression deflate(int level) {
        return new SevenZipCompression(SevenZipCompressionMethod.DEFLATE, level);
    }

    /// Returns Deflate64 output with the default maximum compression level.
    public static SevenZipCompression deflate64() {
        return deflate64(MAX_DEFLATE64_LEVEL);
    }

    /// Returns Deflate64 output with the requested compression level from 0 through 9.
    public static SevenZipCompression deflate64(int level) {
        return new SevenZipCompression(SevenZipCompressionMethod.DEFLATE64, level);
    }

    /// Returns PPMd7 output with its default model parameters.
    public static SevenZipCompression ppmd() {
        return ppmd(DEFAULT_PPMD_MAXIMUM_ORDER, DEFAULT_PPMD_MEMORY_SIZE);
    }

    /// Returns PPMd7 output with the requested maximum order and model-memory size.
    public static SevenZipCompression ppmd(int maximumOrder, int memorySize) {
        return new SevenZipCompression(SevenZipCompressionMethod.PPMD, maximumOrder, memorySize);
    }

    /// Returns Zstandard output with its default compression level.
    public static SevenZipCompression zstandard() {
        return zstandard(DEFAULT_ZSTANDARD_LEVEL);
    }

    /// Returns Zstandard output with the requested compression level.
    public static SevenZipCompression zstandard(int level) {
        return new SevenZipCompression(SevenZipCompressionMethod.ZSTANDARD, level);
    }

    /// Returns the stable method name and parameter.
    @Override
    public String toString() {
        if (method == SevenZipCompressionMethod.PPMD) {
            return method.optionName() + "(" + parameter + "," + secondaryParameter + ")";
        }
        return method.optionName() + "(" + parameter + ")";
    }
}
