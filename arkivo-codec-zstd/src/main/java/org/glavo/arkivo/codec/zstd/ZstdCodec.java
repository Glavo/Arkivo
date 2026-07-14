// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.WorkerCount;
import org.glavo.arkivo.codec.zstd.internal.ZstdChannelDecoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdChannelEncoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdDictionarySupport;
import org.glavo.arkivo.codec.zstd.internal.ZstdEncoderParameters;
import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Provides Zstandard compression and decompression channels.
@NotNullByDefault
public final class ZstdCodec implements CompressionCodec {
    /// The stable Zstandard codec name.
    public static final String NAME = "zstd";

    /// The sentinel compression level that asks Zstandard to use its default level.
    public static final long DEFAULT_COMPRESSION_LEVEL = Long.MIN_VALUE;

    /// The minimum compression level accepted by Zstandard 1.x.
    private static final int MINIMUM_COMPRESSION_LEVEL = -131_072;

    /// The maximum standard Zstandard compression level.
    private static final int MAXIMUM_COMPRESSION_LEVEL = 22;

    /// The default standard Zstandard compression level.
    private static final int STANDARD_DEFAULT_COMPRESSION_LEVEL = 3;

    /// The minimum supported Zstandard window logarithm.
    private static final int MINIMUM_WINDOW_LOG = 10;

    /// The maximum supported Zstandard window logarithm on the Java runtime.
    private static final int MAXIMUM_WINDOW_LOG = 31;

    /// The minimum supported Zstandard hash-table logarithm.
    private static final int MINIMUM_HASH_LOG = 6;

    /// The maximum supported Zstandard hash-table logarithm.
    private static final int MAXIMUM_HASH_LOG = 30;

    /// The minimum supported Zstandard chain-table logarithm.
    private static final int MINIMUM_CHAIN_LOG = 6;

    /// The maximum supported Zstandard chain-table logarithm.
    private static final int MAXIMUM_CHAIN_LOG = 30;

    /// The minimum supported Zstandard search-depth logarithm.
    private static final int MINIMUM_SEARCH_LOG = 1;

    /// The maximum supported Zstandard search-depth logarithm.
    private static final int MAXIMUM_SEARCH_LOG = 30;

    /// The minimum match length accepted by the stable Zstandard compression API.
    private static final int MIN_MATCH_LENGTH_MINIMUM = 3;

    /// The maximum match length accepted by the stable Zstandard compression API.
    private static final int MIN_MATCH_LENGTH_MAXIMUM = 7;

    /// Overrides the Zstandard window logarithm; zero selects the level-derived default.
    public static final CodecOption<Long> WINDOW_LOG = CodecOption.of("zstd.windowLog", Long.class);

    /// Overrides the Zstandard hash-table logarithm; zero selects the level-derived default.
    public static final CodecOption<Long> HASH_LOG = CodecOption.of("zstd.hashLog", Long.class);

    /// Overrides the Zstandard chain-table logarithm; zero selects the level-derived default.
    public static final CodecOption<Long> CHAIN_LOG = CodecOption.of("zstd.chainLog", Long.class);

    /// Overrides the Zstandard search-depth logarithm; zero selects the level-derived default.
    public static final CodecOption<Long> SEARCH_LOG = CodecOption.of("zstd.searchLog", Long.class);

    /// Overrides the minimum match length; zero selects the strategy-derived default.
    public static final CodecOption<Long> MIN_MATCH = CodecOption.of("zstd.minMatch", Long.class);

    /// Overrides the strategy-specific target match length; zero selects the default.
    public static final CodecOption<Long> TARGET_LENGTH = CodecOption.of("zstd.targetLength", Long.class);

    /// Selects the Zstandard match-finding strategy.
    public static final CodecOption<ZstdStrategy> STRATEGY =
            CodecOption.of("zstd.strategy", ZstdStrategy.class);

    /// Selects the size in bytes of jobs submitted to compression workers; zero selects the default.
    public static final CodecOption<Long> JOB_SIZE = CodecOption.of("zstd.jobSize", Long.class);

    /// Selects the worker overlap logarithm from zero through nine.
    public static final CodecOption<Long> OVERLAP_LOG = CodecOption.of("zstd.overlapLog", Long.class);

    /// Selects whether the frame header records the pledged content size.
    public static final CodecOption<Boolean> CONTENT_SIZE = CodecOption.of("zstd.contentSize", Boolean.class);

    /// Selects whether the frame header records a trained dictionary identifier.
    public static final CodecOption<Boolean> DICTIONARY_ID = CodecOption.of("zstd.dictionaryId", Boolean.class);

    /// Enables or disables Zstandard long-distance matching.
    public static final CodecOption<Boolean> LONG_DISTANCE_MATCHING =
            CodecOption.of("zstd.longDistanceMatching", Boolean.class);

    /// The supported Zstandard operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DICTIONARY,
                    CompressionFeature.DIRECT_BYTE_BUFFER,
                    CompressionFeature.MULTI_FRAME,
                    CompressionFeature.CONCATENATED_FRAMES
            ),
            Set.of(
                    StandardCodecOptions.COMPRESSION_LEVEL,
                    StandardCodecOptions.DICTIONARY,
                    StandardCodecOptions.CHECKSUM,
                    StandardCodecOptions.WORKER_COUNT,
                    StandardCodecOptions.PLEDGED_SOURCE_SIZE,
                    WINDOW_LOG,
                    HASH_LOG,
                    CHAIN_LOG,
                    SEARCH_LOG,
                    MIN_MATCH,
                    TARGET_LENGTH,
                    STRATEGY,
                    JOB_SIZE,
                    OVERLAP_LOG,
                    CONTENT_SIZE,
                    DICTIONARY_ID,
                    LONG_DISTANCE_MATCHING
            ),
            Set.of(StandardCodecOptions.DICTIONARY, StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE)
    );

    /// The requested Zstandard compression level.
    private final long compressionLevel;

    /// The dictionary bytes, or `null` when no dictionary is configured.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// Creates a Zstandard codec.
    public ZstdCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, null);
    }

    /// Creates a configured Zstandard codec.
    private ZstdCodec(long compressionLevel, byte @Nullable [] dictionary) {
        if (compressionLevel != DEFAULT_COMPRESSION_LEVEL
                && (compressionLevel < minimumCompressionLevel()
                || compressionLevel > maximumCompressionLevel())) {
            throw new IllegalArgumentException("compressionLevel is out of range");
        }
        this.compressionLevel = compressionLevel;
        this.dictionary = dictionary != null ? dictionary.clone() : null;
    }

    /// Returns the stable Zstandard codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common Zstandard file extensions.
    @Override
    public @Unmodifiable List<String> fileExtensions() {
        return List.of("zst", "zstd");
    }

    /// Returns the supported Zstandard operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the requested compression level.
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns a copy of the dictionary bytes, or `null` when no dictionary is configured.
    public byte @Nullable [] dictionary() {
        return dictionary != null ? dictionary.clone() : null;
    }

    /// Returns a codec configured with the given compression level.
    public ZstdCodec withCompressionLevel(long compressionLevel) {
        return new ZstdCodec(compressionLevel, dictionary);
    }

    /// Returns the minimum Zstandard compression level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum Zstandard compression level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default Zstandard compression level.
    @Override
    public long defaultCompressionLevel() {
        return STANDARD_DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns the minimum supported Zstandard window logarithm.
    public long minimumWindowLog() {
        return MINIMUM_WINDOW_LOG;
    }

    /// Returns the maximum supported Zstandard window logarithm.
    public long maximumWindowLog() {
        return MAXIMUM_WINDOW_LOG;
    }

    /// Returns the minimum supported Zstandard hash-table logarithm.
    public long minimumHashLog() {
        return MINIMUM_HASH_LOG;
    }

    /// Returns the maximum supported Zstandard hash-table logarithm.
    public long maximumHashLog() {
        return MAXIMUM_HASH_LOG;
    }

    /// Returns the minimum supported Zstandard chain-table logarithm.
    public long minimumChainLog() {
        return MINIMUM_CHAIN_LOG;
    }

    /// Returns the maximum supported Zstandard chain-table logarithm.
    public long maximumChainLog() {
        return MAXIMUM_CHAIN_LOG;
    }

    /// Returns the minimum supported Zstandard search-depth logarithm.
    public long minimumSearchLog() {
        return MINIMUM_SEARCH_LOG;
    }

    /// Returns the maximum supported Zstandard search-depth logarithm.
    public long maximumSearchLog() {
        return MAXIMUM_SEARCH_LOG;
    }

    /// Returns the minimum supported Zstandard match length.
    public long minimumMatchLength() {
        return MIN_MATCH_LENGTH_MINIMUM;
    }

    /// Returns the maximum supported Zstandard match length.
    public long maximumMatchLength() {
        return MIN_MATCH_LENGTH_MAXIMUM;
    }

    /// Returns a codec configured with the given dictionary bytes.
    public ZstdCodec withDictionary(byte[] dictionary) {
        return new ZstdCodec(compressionLevel, Objects.requireNonNull(dictionary, "dictionary"));
    }

    /// Returns a codec configured without dictionary bytes.
    public ZstdCodec withoutDictionary() {
        return dictionary == null ? this : new ZstdCodec(compressionLevel, null);
    }

    /// Returns the number of leading bytes used to identify Zstandard streams.
    @Override
    public int probeSize() {
        return 4;
    }

    /// Returns whether the given prefix starts with the Zstandard frame signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        return prefix.remaining() >= 4
                && Byte.toUnsignedInt(prefix.get(position)) == 0x28
                && Byte.toUnsignedInt(prefix.get(position + 1)) == 0xb5
                && Byte.toUnsignedInt(prefix.get(position + 2)) == 0x2f
                && Byte.toUnsignedInt(prefix.get(position + 3)) == 0xfd;
    }

    /// Returns the maximum Zstandard compressed size for an input of `sourceSize` bytes.
    @Override
    public long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        long margin = sourceSize < 128L * 1024L
                ? (128L * 1024L - sourceSize) >>> 11
                : 0L;
        try {
            return Math.addExact(Math.addExact(sourceSize, sourceSize >>> 8), margin);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /// Parses one standard or skippable frame header without changing the source buffer.
    public ZstdFrameInfo frameInfo(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.parse(source);
    }

    /// Returns the complete compressed size of one frame without changing the source buffer.
    ///
    /// This scans block framing and verifies that the complete frame is available without decompressing its payload.
    public long frameCompressedSize(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.frameCompressedSize(source);
    }

    /// Returns the embedded identifier of a formatted Zstandard dictionary without changing the source buffer.
    ///
    /// Raw-content dictionaries and formatted dictionaries without an identifier return `CompressionDictionary.UNKNOWN_ID`.
    public long dictionaryId(ByteBuffer dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return ZstdDictionarySupport.dictionaryId(dictionary);
    }

    /// Opens a configured Zstandard encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Zstandard compression");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        long pledgedSourceSize = StandardCodecOptionSupport.pledgedSourceSize(options);
        return new ZstdChannelEncoder(
                target,
                ownership,
                createEncoderParameters(options, pledgedSourceSize)
        );
    }

    /// Opens a configured Zstandard decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Zstandard decompression");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new ZstdChannelDecoder(source, ownership, decoderDictionary(options), maximumWindowSize),
                maximumOutputSize
        );
    }

    /// Resolves operation options into immutable pure Java encoder parameters.
    private ZstdEncoderParameters createEncoderParameters(
            CodecOptions options,
            long pledgedSourceSize
    ) throws IOException {
        @Nullable Long requestedLevel = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        long selectedLevel = requestedLevel != null
                ? requestedLevel
                : compressionLevel != DEFAULT_COMPRESSION_LEVEL
                ? compressionLevel
                : STANDARD_DEFAULT_COMPRESSION_LEVEL;
        if (selectedLevel < minimumCompressionLevel() || selectedLevel > maximumCompressionLevel()) {
            throw new IllegalArgumentException("Zstandard compression level is out of range");
        }

        @Nullable Long windowLog = options.get(WINDOW_LOG);
        @Nullable Long hashLog = options.get(HASH_LOG);
        @Nullable Long chainLog = options.get(CHAIN_LOG);
        @Nullable Long searchLog = options.get(SEARCH_LOG);
        @Nullable Long minMatch = options.get(MIN_MATCH);
        @Nullable Long targetLength = options.get(TARGET_LENGTH);
        @Nullable ZstdStrategy strategy = options.get(STRATEGY);
        @Nullable Long jobSize = options.get(JOB_SIZE);
        @Nullable Long overlapLog = options.get(OVERLAP_LOG);
        @Nullable Boolean contentSize = options.get(CONTENT_SIZE);
        @Nullable Boolean dictionaryId = options.get(DICTIONARY_ID);
        @Nullable Boolean longDistanceMatching = options.get(LONG_DISTANCE_MATCHING);
        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        @Nullable WorkerCount workers = options.get(StandardCodecOptions.WORKER_COUNT);
        nonNegativeInt(jobSize != null ? jobSize : 0L, "Zstandard job size");
        boundedParameter(overlapLog != null ? overlapLog : 0L, 0, 9, "Zstandard overlap log");

        @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
        @Nullable CompressionDictionary selectedDictionary = requestedDictionary != null
                ? requestedDictionary
                : dictionary != null ? CompressionDictionary.of(dictionary) : null;
        return new ZstdEncoderParameters(
                Math.toIntExact(selectedLevel),
                boundedParameter(
                        windowLog != null ? windowLog : 0L,
                        MINIMUM_WINDOW_LOG,
                        MAXIMUM_WINDOW_LOG,
                        "Zstandard window log"
                ),
                boundedParameter(
                        hashLog != null ? hashLog : 0L,
                        MINIMUM_HASH_LOG,
                        MAXIMUM_HASH_LOG,
                        "Zstandard hash log"
                ),
                boundedParameter(
                        chainLog != null ? chainLog : 0L,
                        MINIMUM_CHAIN_LOG,
                        MAXIMUM_CHAIN_LOG,
                        "Zstandard chain log"
                ),
                boundedParameter(
                        searchLog != null ? searchLog : 0L,
                        MINIMUM_SEARCH_LOG,
                        MAXIMUM_SEARCH_LOG,
                        "Zstandard search log"
                ),
                boundedParameter(
                        minMatch != null ? minMatch : 0L,
                        MIN_MATCH_LENGTH_MINIMUM,
                        MIN_MATCH_LENGTH_MAXIMUM,
                        "Zstandard minimum match length"
                ),
                nonNegativeInt(
                        targetLength != null ? targetLength : 0L,
                        "Zstandard target length"
                ),
                strategy != null ? strategy.ordinal() + 1 : 1,
                checksum == ChecksumMode.ENABLED,
                contentSize == null || contentSize,
                dictionaryId == null || dictionaryId,
                longDistanceMatching != null && longDistanceMatching,
                workers != null ? workers.value() : 0,
                pledgedSourceSize,
                selectedDictionary
        );
    }

    /// Converts a parameter that accepts zero for automatic selection or a bounded value.
    private static int boundedParameter(long value, int minimum, int maximum, String name) {
        if (value != 0L && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    name + " must be zero or between " + minimum + " and " + maximum
            );
        }
        return Math.toIntExact(value);
    }

    /// Converts a non-negative integer-valued Zstandard parameter.
    private static int nonNegativeInt(long value, String name) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    name + " must be between zero and " + Integer.MAX_VALUE
            );
        }
        return (int) value;
    }

    /// Returns the configured decoder dictionary, or null when no dictionary is configured.
    private @Nullable CompressionDictionary decoderDictionary(CodecOptions options) {
        @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
        return requestedDictionary != null
                ? requestedDictionary
                : dictionary != null ? CompressionDictionary.of(dictionary) : null;
    }

    /// Compresses all remaining source bytes into the target buffer.
    @Override
    public void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer compression requires direct buffers");
        }
        if (target.isReadOnly()) {
            throw new IOException("Zstandard ByteBuffer compression target buffer must be writable");
        }
        CompressionCodec.super.compress(source, target);
    }

    /// Decompresses all remaining source bytes into the target buffer.
    @Override
    public void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer decompression requires direct buffers");
        }
        if (target.isReadOnly()) {
            throw new IOException("Zstandard ByteBuffer decompression target buffer must be writable");
        }
        CompressionCodec.super.decompress(source, target);
    }
}
