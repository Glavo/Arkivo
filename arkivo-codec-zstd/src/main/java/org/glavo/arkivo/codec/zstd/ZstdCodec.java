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
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.glavo.arkivo.codec.spi.CodecChannelAdapters;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.zstd.internal.ZstdDecoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdDictionarySupport;
import org.glavo.arkivo.codec.zstd.internal.ZstdEncoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdEncoderParameters;
import org.glavo.arkivo.codec.zstd.internal.ZstdFrameHeader;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/// Provides transport-independent Zstandard engines and channel adapters.
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

    /// The maximum hash-table logarithm supported by the pure Java match finder.
    private static final int MAXIMUM_HASH_LOG = 18;

    /// The minimum supported Zstandard chain-table logarithm.
    private static final int MINIMUM_CHAIN_LOG = 6;

    /// The maximum chain-table logarithm supported by the pure Java match finder.
    private static final int MAXIMUM_CHAIN_LOG = 17;

    /// The minimum supported Zstandard search-depth logarithm.
    private static final int MINIMUM_SEARCH_LOG = 1;

    /// The maximum search-depth logarithm supported by the pure Java match finder.
    private static final int MAXIMUM_SEARCH_LOG = 10;

    /// The minimum supported long-distance hash-table logarithm.
    private static final int MINIMUM_LDM_HASH_LOG = 6;

    /// The minimum explicit long-distance sampling-rate logarithm.
    private static final int MINIMUM_LDM_HASH_RATE_LOG = 1;

    /// The maximum long-distance hash-table logarithm supported by the pure Java matcher.
    private static final int MAXIMUM_LDM_HASH_LOG = 20;

    /// The minimum supported long-distance match length.
    private static final int MINIMUM_LDM_MATCH_LENGTH = 4;

    /// The maximum supported long-distance match length.
    private static final int MAXIMUM_LDM_MATCH_LENGTH = 4_096;

    /// The minimum supported long-distance bucket-size logarithm.
    private static final int MINIMUM_LDM_BUCKET_SIZE_LOG = 1;

    /// The maximum supported long-distance bucket-size logarithm.
    private static final int MAXIMUM_LDM_BUCKET_SIZE_LOG = 8;

    /// The maximum supported long-distance sampling-rate logarithm.
    private static final int MAXIMUM_LDM_HASH_RATE_LOG =
            MAXIMUM_WINDOW_LOG - MINIMUM_LDM_HASH_LOG;

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

    /// Selects the target size in bytes of jobs submitted to compression workers.
    ///
    /// Zero selects a parameter-derived default. Nonzero values are normalized to the supported
    /// range from 512 KiB through 1 GiB and have no effect when worker count is zero.
    public static final CodecOption<Long> JOB_SIZE = CodecOption.of("zstd.jobSize", Long.class);

    /// Selects the worker overlap logarithm from zero through nine.
    ///
    /// Zero selects a strategy-derived default, one disables overlap, and nine reloads a full
    /// searchable window. Intermediate values halve the retained prefix at each lower rank.
    public static final CodecOption<Long> OVERLAP_LOG = CodecOption.of("zstd.overlapLog", Long.class);

    /// Selects whether the frame header records the pledged content size.
    public static final CodecOption<Boolean> CONTENT_SIZE = CodecOption.of("zstd.contentSize", Boolean.class);

    /// Selects whether the frame header records a trained dictionary identifier.
    public static final CodecOption<Boolean> DICTIONARY_ID = CodecOption.of("zstd.dictionaryId", Boolean.class);

    /// Enables or disables Zstandard long-distance matching.
    public static final CodecOption<Boolean> LONG_DISTANCE_MATCHING =
            CodecOption.of("zstd.longDistanceMatching", Boolean.class);

    /// Overrides the long-distance hash-table logarithm; zero selects `windowLog - 7`.
    public static final CodecOption<Long> LDM_HASH_LOG =
            CodecOption.of("zstd.ldmHashLog", Long.class);

    /// Overrides the minimum long-distance match length; zero selects 64 bytes.
    public static final CodecOption<Long> LDM_MIN_MATCH =
            CodecOption.of("zstd.ldmMinMatch", Long.class);

    /// Overrides the long-distance collision-bucket logarithm; zero selects three.
    public static final CodecOption<Long> LDM_BUCKET_SIZE_LOG =
            CodecOption.of("zstd.ldmBucketSizeLog", Long.class);

    /// Overrides the long-distance sampling-rate logarithm; zero selects `windowLog - ldmHashLog`.
    public static final CodecOption<Long> LDM_HASH_RATE_LOG =
            CodecOption.of("zstd.ldmHashRateLog", Long.class);

    /// Selects standard or explicitly requested magicless Zstandard framing.
    public static final CodecOption<ZstdFrameFormat> FRAME_FORMAT =
            CodecOption.of("zstd.frameFormat", ZstdFrameFormat.class);

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
                    CompressionFeature.BUFFER_COMPRESSION,
                    CompressionFeature.BUFFER_DECOMPRESSION,
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
                    LONG_DISTANCE_MATCHING,
                    LDM_HASH_LOG,
                    LDM_MIN_MATCH,
                    LDM_BUCKET_SIZE_LOG,
                    LDM_HASH_RATE_LOG,
                    FRAME_FORMAT
            ),
            Set.of(
                    StandardCodecOptions.DICTIONARY,
                    StandardCodecOptions.CHECKSUM,
                    StandardCodecOptions.MAX_OUTPUT_SIZE,
                    StandardCodecOptions.MAX_WINDOW_SIZE,
                    FRAME_FORMAT
            )
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

    /// Returns the minimum supported long-distance hash-table logarithm.
    public long minimumLongDistanceHashLog() {
        return MINIMUM_LDM_HASH_LOG;
    }

    /// Returns the maximum supported long-distance hash-table logarithm.
    public long maximumLongDistanceHashLog() {
        return MAXIMUM_LDM_HASH_LOG;
    }

    /// Returns the minimum supported long-distance match length.
    public long minimumLongDistanceMatchLength() {
        return MINIMUM_LDM_MATCH_LENGTH;
    }

    /// Returns the maximum supported long-distance match length.
    public long maximumLongDistanceMatchLength() {
        return MAXIMUM_LDM_MATCH_LENGTH;
    }

    /// Returns the minimum supported long-distance collision-bucket logarithm.
    public long minimumLongDistanceBucketSizeLog() {
        return MINIMUM_LDM_BUCKET_SIZE_LOG;
    }

    /// Returns the maximum supported long-distance collision-bucket logarithm.
    public long maximumLongDistanceBucketSizeLog() {
        return MAXIMUM_LDM_BUCKET_SIZE_LOG;
    }

    /// Returns the minimum explicit long-distance sampling-rate logarithm.
    public long minimumLongDistanceHashRateLog() {
        return MINIMUM_LDM_HASH_RATE_LOG;
    }

    /// Returns the maximum supported long-distance sampling-rate logarithm.
    public long maximumLongDistanceHashRateLog() {
        return MAXIMUM_LDM_HASH_RATE_LOG;
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
        Objects.requireNonNull(prefix, "prefix");
        return ZstdFrameHeader.hasFrameMagic(prefix);
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
        return frameInfo(source, ZstdFrameFormat.STANDARD);
    }

    /// Parses one frame header in the selected physical format without changing the source buffer.
    public ZstdFrameInfo frameInfo(ByteBuffer source, ZstdFrameFormat format) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        return ZstdFrameHeader.parse(source, format == ZstdFrameFormat.MAGICLESS);
    }

    /// Returns the complete compressed size of one frame without changing the source buffer.
    ///
    /// This scans block framing and verifies that the complete frame is available without decompressing its payload.
    public long frameCompressedSize(ByteBuffer source) throws IOException {
        return frameCompressedSize(source, ZstdFrameFormat.STANDARD);
    }

    /// Returns the complete compressed size of one frame in the selected physical format.
    ///
    /// This scans block framing and verifies that the complete frame is available without decompressing its payload.
    public long frameCompressedSize(ByteBuffer source, ZstdFrameFormat format) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        return ZstdFrameHeader.frameCompressedSize(source, format == ZstdFrameFormat.MAGICLESS);
    }

    /// Returns the embedded identifier of a formatted Zstandard dictionary without changing the source buffer.
    ///
    /// Raw-content dictionaries and formatted dictionaries without an identifier return `CompressionDictionary.UNKNOWN_ID`.
    public long dictionaryId(ByteBuffer dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return ZstdDictionarySupport.dictionaryId(dictionary);
    }

    /// Creates a configured transport-independent Zstandard encoder.
    @Override
    public CompressionEncoder newEncoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Zstandard compression");
        long pledgedSourceSize = StandardCodecOptionSupport.pledgedSourceSize(options);
        return new ZstdEncoder(
                createEncoderParameters(options, pledgedSourceSize),
                frameFormat(options) == ZstdFrameFormat.MAGICLESS
        );
    }

    /// Creates a configured transport-independent Zstandard decoder.
    @Override
    public CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Zstandard decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        return StandardCodecOptionSupport.limitOutput(
                new ZstdDecoder(
                        decoderDictionary(options),
                        maximumWindowSize,
                        frameFormat(options) == ZstdFrameFormat.MAGICLESS,
                        checksum != ChecksumMode.DISABLED
                ),
                maximumOutputSize
        );
    }

    /// Opens a configured concatenated-frame Zstandard decoder over the source channel.
    @Override
    public DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "Zstandard decompression");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.openDecoder(
                source,
                ownership,
                true,
                () -> new ZstdDecoder(
                        decoderDictionary(options),
                        maximumWindowSize,
                        frameFormat(options) == ZstdFrameFormat.MAGICLESS,
                        checksum != ChecksumMode.DISABLED
                )
        );
        return StandardCodecOptionSupport.limitOutput(decoder, maximumOutputSize);
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
        @Nullable Long ldmHashLog = options.get(LDM_HASH_LOG);
        @Nullable Long ldmMinMatch = options.get(LDM_MIN_MATCH);
        @Nullable Long ldmBucketSizeLog = options.get(LDM_BUCKET_SIZE_LOG);
        @Nullable Long ldmHashRateLog = options.get(LDM_HASH_RATE_LOG);
        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        @Nullable WorkerCount workers = options.get(StandardCodecOptions.WORKER_COUNT);
        int selectedJobSize = nonNegativeInt(
                jobSize != null ? jobSize : 0L,
                "Zstandard job size"
        );
        int selectedOverlapLog = boundedParameter(
                overlapLog != null ? overlapLog : 0L,
                0, 9, "Zstandard overlap log"
        );

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
                strategy != null ? strategy.ordinal() + 1 : 0,
                checksum == ChecksumMode.ENABLED,
                contentSize == null || contentSize,
                dictionaryId == null || dictionaryId,
                longDistanceMatching != null && longDistanceMatching,
                boundedParameter(
                        ldmHashLog != null ? ldmHashLog : 0L,
                        MINIMUM_LDM_HASH_LOG,
                        MAXIMUM_LDM_HASH_LOG,
                        "Zstandard long-distance hash log"
                ),
                boundedParameter(
                        ldmMinMatch != null ? ldmMinMatch : 0L,
                        MINIMUM_LDM_MATCH_LENGTH,
                        MAXIMUM_LDM_MATCH_LENGTH,
                        "Zstandard long-distance minimum match"
                ),
                boundedParameter(
                        ldmBucketSizeLog != null ? ldmBucketSizeLog : 0L,
                        MINIMUM_LDM_BUCKET_SIZE_LOG,
                        MAXIMUM_LDM_BUCKET_SIZE_LOG,
                        "Zstandard long-distance bucket-size log"
                ),
                boundedParameter(
                        ldmHashRateLog != null ? ldmHashRateLog : 0L,
                        MINIMUM_LDM_HASH_RATE_LOG,
                        MAXIMUM_LDM_HASH_RATE_LOG,
                        "Zstandard long-distance hash-rate log"
                ),
                workers != null ? workers.value() : 0,
                selectedJobSize,
                selectedOverlapLog,
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

    /// Returns the selected physical frame format, defaulting to standard framing.
    private static ZstdFrameFormat frameFormat(CodecOptions options) {
        @Nullable ZstdFrameFormat format = options.get(FRAME_FORMAT);
        return format != null ? format : ZstdFrameFormat.STANDARD;
    }

    /// Compresses all remaining source bytes into the target buffer.
    @Override
    public void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        CompressionCodec.super.compress(source, target);
    }

    /// Decompresses all remaining source bytes into the target buffer.
    @Override
    public void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        CompressionCodec.super.decompress(source, target);
    }
}
