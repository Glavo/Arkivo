// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionLevelCodec;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.DictionaryCompressionCodec;
import org.glavo.arkivo.codec.DictionaryCompressionDecoder;
import org.glavo.arkivo.codec.FlushableFramedCompressionEncoder;
import org.glavo.arkivo.codec.PledgedSourceSizeCodec;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
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
import java.util.List;
import java.util.Objects;

/// Provides an immutable Zstandard configuration and transport-independent frame engines.
@NotNullByDefault
public final class ZstdCodec
        implements CompressionLevelCodec, DictionaryCompressionCodec, PledgedSourceSizeCodec {
    /// The stable Zstandard codec name.
    public static final String NAME = "zstd";

    /// The minimum compression level accepted by Zstandard 1.x.
    public static final int MINIMUM_COMPRESSION_LEVEL = -131_072;

    /// The maximum standard Zstandard compression level.
    public static final int MAXIMUM_COMPRESSION_LEVEL = 22;

    /// The default standard Zstandard compression level.
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;

    /// The minimum supported Zstandard window logarithm.
    public static final int MINIMUM_WINDOW_LOG = 10;

    /// The maximum supported Zstandard window logarithm.
    public static final int MAXIMUM_WINDOW_LOG = 31;

    /// The minimum supported Zstandard hash-table logarithm.
    public static final int MINIMUM_HASH_LOG = 6;

    /// The maximum supported Zstandard hash-table logarithm.
    public static final int MAXIMUM_HASH_LOG = 18;

    /// The minimum supported Zstandard chain-table logarithm.
    public static final int MINIMUM_CHAIN_LOG = 6;

    /// The maximum supported Zstandard chain-table logarithm.
    public static final int MAXIMUM_CHAIN_LOG = 17;

    /// The minimum supported Zstandard search-depth logarithm.
    public static final int MINIMUM_SEARCH_LOG = 1;

    /// The maximum supported Zstandard search-depth logarithm.
    public static final int MAXIMUM_SEARCH_LOG = 10;

    /// The minimum supported match length.
    public static final int MINIMUM_MATCH_LENGTH = 3;

    /// The maximum supported match length.
    public static final int MAXIMUM_MATCH_LENGTH = 7;

    /// The minimum supported long-distance hash-table logarithm.
    public static final int MINIMUM_LONG_DISTANCE_HASH_LOG = 6;

    /// The maximum supported long-distance hash-table logarithm.
    public static final int MAXIMUM_LONG_DISTANCE_HASH_LOG = 20;

    /// The minimum supported long-distance match length.
    public static final int MINIMUM_LONG_DISTANCE_MATCH_LENGTH = 4;

    /// The maximum supported long-distance match length.
    public static final int MAXIMUM_LONG_DISTANCE_MATCH_LENGTH = 4_096;

    /// The minimum supported long-distance bucket-size logarithm.
    public static final int MINIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG = 1;

    /// The maximum supported long-distance bucket-size logarithm.
    public static final int MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG = 8;

    /// The minimum explicit long-distance sampling-rate logarithm.
    public static final int MINIMUM_LONG_DISTANCE_HASH_RATE_LOG = 1;

    /// The maximum supported long-distance sampling-rate logarithm.
    public static final int MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG =
            MAXIMUM_WINDOW_LOG - MINIMUM_LONG_DISTANCE_HASH_LOG;

    /// The maximum supported parallel compression worker count.
    public static final int MAXIMUM_WORKER_COUNT = 256;

    /// The default immutable Zstandard codec configuration.
    public static final ZstdCodec DEFAULT = builder().build();

    /// The configured compression level.
    private final int compressionLevel;

    /// The configured dictionary, or null.
    private final @Nullable CompressionDictionary dictionary;

    /// Whether encoders emit frame checksums.
    private final boolean frameChecksum;

    /// Whether decoders verify frame checksums.
    private final boolean verifyChecksums;

    /// The number of parallel compression workers.
    private final int workerCount;

    /// The requested window logarithm, or zero.
    private final int windowLog;

    /// The requested hash-table logarithm, or zero.
    private final int hashLog;

    /// The requested chain-table logarithm, or zero.
    private final int chainLog;

    /// The requested search-depth logarithm, or zero.
    private final int searchLog;

    /// The requested minimum match length, or zero.
    private final int minimumMatch;

    /// The requested target match length, or zero.
    private final int targetLength;

    /// The requested strategy, or null.
    private final @Nullable ZstdStrategy strategy;

    /// The requested parallel job size, or zero.
    private final int jobSize;

    /// The requested worker overlap logarithm, or zero.
    private final int overlapLog;

    /// Whether known pledged source sizes are emitted.
    private final boolean contentSize;

    /// Whether trained dictionary identifiers are emitted.
    private final boolean dictionaryId;

    /// Whether long-distance matching is enabled.
    private final boolean longDistanceMatching;

    /// The requested long-distance hash logarithm, or zero.
    private final int longDistanceHashLog;

    /// The requested long-distance minimum match length, or zero.
    private final int longDistanceMinimumMatch;

    /// The requested long-distance bucket-size logarithm, or zero.
    private final int longDistanceBucketSizeLog;

    /// The requested long-distance sampling-rate logarithm, or zero.
    private final int longDistanceHashRateLog;

    /// The configured physical frame format.
    private final ZstdFrameFormat frameFormat;

    /// Creates the default Zstandard codec configuration.
    public ZstdCodec() {
        this(new Builder());
    }

    /// Creates an immutable configuration from a builder snapshot.
    private ZstdCodec(Builder builder) {
        compressionLevel = builder.compressionLevel;
        dictionary = builder.dictionary;
        frameChecksum = builder.frameChecksum;
        verifyChecksums = builder.verifyChecksums;
        workerCount = builder.workerCount;
        windowLog = builder.windowLog;
        hashLog = builder.hashLog;
        chainLog = builder.chainLog;
        searchLog = builder.searchLog;
        minimumMatch = builder.minimumMatch;
        targetLength = builder.targetLength;
        strategy = builder.strategy;
        jobSize = builder.jobSize;
        overlapLog = builder.overlapLog;
        contentSize = builder.contentSize;
        dictionaryId = builder.dictionaryId;
        longDistanceMatching = builder.longDistanceMatching;
        longDistanceHashLog = builder.longDistanceHashLog;
        longDistanceMinimumMatch = builder.longDistanceMinimumMatch;
        longDistanceBucketSizeLog = builder.longDistanceBucketSizeLog;
        longDistanceHashRateLog = builder.longDistanceHashRateLog;
        frameFormat = builder.frameFormat;
    }

    /// Returns a builder initialized to Zstandard defaults.
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a mutable builder initialized from this immutable configuration.
    public Builder toBuilder() {
        return new Builder(this);
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

    /// Returns the configured compression level.
    @Override
    public long compressionLevel() {
        return compressionLevel;
    }

    /// Returns the minimum supported compression level.
    @Override
    public long minimumCompressionLevel() {
        return MINIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the maximum supported compression level.
    @Override
    public long maximumCompressionLevel() {
        return MAXIMUM_COMPRESSION_LEVEL;
    }

    /// Returns the default compression level.
    @Override
    public long defaultCompressionLevel() {
        return DEFAULT_COMPRESSION_LEVEL;
    }

    /// Returns an immutable codec with the requested compression level.
    @Override
    public ZstdCodec withCompressionLevel(long compressionLevel) {
        requireCompressionLevel(compressionLevel);
        return compressionLevel == this.compressionLevel
                ? this
                : toBuilder().compressionLevel(compressionLevel).build();
    }

    /// Returns the configured dictionary, or null.
    @Override
    public @Nullable CompressionDictionary dictionary() {
        return dictionary;
    }

    /// Returns an immutable codec with the requested dictionary.
    @Override
    public ZstdCodec withDictionary(CompressionDictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return dictionary == this.dictionary
                ? this
                : toBuilder().dictionary(dictionary).build();
    }

    /// Returns an immutable codec with a copy of the raw dictionary bytes.
    public ZstdCodec withDictionary(byte[] dictionary) {
        return withDictionary(CompressionDictionary.of(dictionary));
    }

    /// Returns an immutable codec without a dictionary.
    @Override
    public ZstdCodec withoutDictionary() {
        return dictionary == null ? this : toBuilder().withoutDictionary().build();
    }

    /// Returns whether encoders emit frame checksums.
    public boolean frameChecksum() {
        return frameChecksum;
    }

    /// Returns whether decoders verify frame checksums.
    public boolean verifyChecksums() {
        return verifyChecksums;
    }

    /// Returns the configured parallel compression worker count.
    public int workerCount() {
        return workerCount;
    }

    /// Returns the configured window logarithm, or zero for automatic selection.
    public int windowLog() {
        return windowLog;
    }

    /// Returns the configured hash-table logarithm, or zero for automatic selection.
    public int hashLog() {
        return hashLog;
    }

    /// Returns the configured chain-table logarithm, or zero for automatic selection.
    public int chainLog() {
        return chainLog;
    }

    /// Returns the configured search-depth logarithm, or zero for automatic selection.
    public int searchLog() {
        return searchLog;
    }

    /// Returns the configured minimum match length, or zero for automatic selection.
    public int minimumMatch() {
        return minimumMatch;
    }

    /// Returns the configured target match length, or zero for no explicit target.
    public int targetLength() {
        return targetLength;
    }

    /// Returns the configured match-finding strategy, or null for level-derived selection.
    public @Nullable ZstdStrategy strategy() {
        return strategy;
    }

    /// Returns the configured parallel job size, or zero for automatic selection.
    public int jobSize() {
        return jobSize;
    }

    /// Returns the configured worker overlap logarithm, or zero for automatic selection.
    public int overlapLog() {
        return overlapLog;
    }

    /// Returns whether known pledged source sizes are emitted.
    public boolean contentSize() {
        return contentSize;
    }

    /// Returns whether trained dictionary identifiers are emitted.
    public boolean dictionaryId() {
        return dictionaryId;
    }

    /// Returns whether long-distance matching is enabled.
    public boolean longDistanceMatching() {
        return longDistanceMatching;
    }

    /// Returns the configured long-distance hash logarithm, or zero for automatic selection.
    public int longDistanceHashLog() {
        return longDistanceHashLog;
    }

    /// Returns the configured long-distance minimum match length, or zero for automatic selection.
    public int longDistanceMinimumMatch() {
        return longDistanceMinimumMatch;
    }

    /// Returns the configured long-distance bucket-size logarithm, or zero for automatic selection.
    public int longDistanceBucketSizeLog() {
        return longDistanceBucketSizeLog;
    }

    /// Returns the configured long-distance sampling-rate logarithm, or zero for automatic selection.
    public int longDistanceHashRateLog() {
        return longDistanceHashRateLog;
    }

    /// Returns the configured physical frame format.
    public ZstdFrameFormat frameFormat() {
        return frameFormat;
    }

    /// Returns an immutable codec with the requested physical frame format.
    public ZstdCodec withFrameFormat(ZstdFrameFormat frameFormat) {
        Objects.requireNonNull(frameFormat, "frameFormat");
        return frameFormat == this.frameFormat
                ? this
                : toBuilder().frameFormat(frameFormat).build();
    }

    /// Returns an immutable codec with the requested checksum emission behavior.
    public ZstdCodec withFrameChecksum(boolean frameChecksum) {
        return frameChecksum == this.frameChecksum
                ? this
                : toBuilder().frameChecksum(frameChecksum).build();
    }

    /// Returns an immutable codec with the requested checksum verification behavior.
    public ZstdCodec withVerifyChecksums(boolean verifyChecksums) {
        return verifyChecksums == this.verifyChecksums
                ? this
                : toBuilder().verifyChecksums(verifyChecksums).build();
    }

    /// Returns the number of leading bytes used to identify configured streams.
    @Override
    public int probeSize() {
        return frameFormat == ZstdFrameFormat.STANDARD ? 4 : 0;
    }

    /// Returns whether the prefix starts with a standard Zstandard frame signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return frameFormat == ZstdFrameFormat.STANDARD
                && ZstdFrameHeader.hasFrameMagic(prefix);
    }

    /// Returns the maximum compressed size for an input of sourceSize bytes.
    @Override
    public long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
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

    /// Parses one frame header using this codec's physical format.
    public ZstdFrameInfo frameInfo(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.parse(source, frameFormat == ZstdFrameFormat.MAGICLESS);
    }

    /// Parses one frame header in the selected physical format.
    public ZstdFrameInfo frameInfo(
            ByteBuffer source,
            ZstdFrameFormat frameFormat
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(frameFormat, "frameFormat");
        return ZstdFrameHeader.parse(source, frameFormat == ZstdFrameFormat.MAGICLESS);
    }

    /// Returns one complete frame's compressed size using this codec's physical format.
    public long frameCompressedSize(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        return ZstdFrameHeader.frameCompressedSize(
                source,
                frameFormat == ZstdFrameFormat.MAGICLESS
        );
    }

    /// Returns one complete frame's compressed size in the selected physical format.
    public long frameCompressedSize(
            ByteBuffer source,
            ZstdFrameFormat frameFormat
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(frameFormat, "frameFormat");
        return ZstdFrameHeader.frameCompressedSize(
                source,
                frameFormat == ZstdFrameFormat.MAGICLESS
        );
    }

    /// Returns the embedded identifier of a formatted dictionary.
    public long dictionaryId(ByteBuffer dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return ZstdDictionarySupport.dictionaryId(dictionary);
    }

    /// Creates a framed encoder without a known source size.
    @Override
    public FlushableFramedCompressionEncoder newEncoder() throws IOException {
        return newEncoder(UNKNOWN_SIZE);
    }

    /// Creates a framed encoder with optional exact source-size metadata.
    @Override
    public FlushableFramedCompressionEncoder newEncoder(long pledgedSourceSize) throws IOException {
        requirePledgedSourceSize(pledgedSourceSize);
        return new ZstdEncoder(
                createEncoderParameters(pledgedSourceSize),
                frameFormat == ZstdFrameFormat.MAGICLESS
        );
    }

    /// Creates an unrestricted dictionary-aware framed decoder.
    @Override
    public DictionaryCompressionDecoder newDecoder() throws IOException {
        return newDecoder(DecompressionLimits.UNLIMITED);
    }

    /// Creates a framed decoder with operation-scoped safety limits.
    @Override
    public DictionaryCompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return (DictionaryCompressionDecoder) CompressionDecoderSupport.limitEngineOutput(
                new ZstdDecoder(
                        dictionary,
                        limits.maximumWindowSize(),
                        frameFormat == ZstdFrameFormat.MAGICLESS,
                        verifyChecksums
                ),
                limits.maximumOutputSize()
        );
    }

    /// Resolves this immutable configuration into encoder parameters.
    private ZstdEncoderParameters createEncoderParameters(long pledgedSourceSize) throws IOException {
        return new ZstdEncoderParameters(
                compressionLevel,
                windowLog,
                hashLog,
                chainLog,
                searchLog,
                minimumMatch,
                targetLength,
                strategy != null ? strategy.ordinal() + 1 : 0,
                frameChecksum,
                contentSize,
                dictionaryId,
                longDistanceMatching,
                longDistanceHashLog,
                longDistanceMinimumMatch,
                longDistanceBucketSizeLog,
                longDistanceHashRateLog,
                workerCount,
                jobSize,
                overlapLog,
                pledgedSourceSize,
                dictionary
        );
    }

    /// Validates a supported compression level.
    private static void requireCompressionLevel(long compressionLevel) {
        if (compressionLevel < MINIMUM_COMPRESSION_LEVEL
                || compressionLevel > MAXIMUM_COMPRESSION_LEVEL) {
            throw new IllegalArgumentException(
                    "Zstandard compressionLevel must be between "
                            + MINIMUM_COMPRESSION_LEVEL + " and " + MAXIMUM_COMPRESSION_LEVEL
            );
        }
    }

    /// Validates a known or unknown pledged source size.
    private static void requirePledgedSourceSize(long pledgedSourceSize) {
        if (pledgedSourceSize < UNKNOWN_SIZE) {
            throw new IllegalArgumentException(
                    "pledgedSourceSize must be non-negative or UNKNOWN_SIZE"
            );
        }
    }

    /// Validates zero for automatic selection or a bounded value.
    private static int automaticOrBounded(
            long value,
            int minimum,
            int maximum,
            String name
    ) {
        if (value != 0L && (value < minimum || value > maximum)) {
            throw new IllegalArgumentException(
                    name + " must be zero or between " + minimum + " and " + maximum
            );
        }
        return Math.toIntExact(value);
    }

    /// Validates a non-negative integer-valued parameter.
    private static int nonNegativeInt(long value, String name) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    name + " must be between zero and " + Integer.MAX_VALUE
            );
        }
        return (int) value;
    }

    /// Builds immutable Zstandard codec configurations.
    ///
    /// Builders are mutable and not safe for concurrent use.
    @NotNullByDefault
    public static final class Builder {
        /// The selected compression level.
        private int compressionLevel = DEFAULT_COMPRESSION_LEVEL;

        /// The selected dictionary, or null.
        private @Nullable CompressionDictionary dictionary;

        /// Whether encoders emit frame checksums.
        private boolean frameChecksum;

        /// Whether decoders verify frame checksums.
        private boolean verifyChecksums = true;

        /// The selected worker count.
        private int workerCount;

        /// The selected window logarithm, or zero.
        private int windowLog;

        /// The selected hash logarithm, or zero.
        private int hashLog;

        /// The selected chain logarithm, or zero.
        private int chainLog;

        /// The selected search logarithm, or zero.
        private int searchLog;

        /// The selected minimum match length, or zero.
        private int minimumMatch;

        /// The selected target length, or zero.
        private int targetLength;

        /// The selected strategy, or null.
        private @Nullable ZstdStrategy strategy;

        /// The selected worker job size, or zero.
        private int jobSize;

        /// The selected worker overlap logarithm, or zero.
        private int overlapLog;

        /// Whether known source sizes are emitted.
        private boolean contentSize = true;

        /// Whether dictionary identifiers are emitted.
        private boolean dictionaryId = true;

        /// Whether long-distance matching is enabled.
        private boolean longDistanceMatching;

        /// The selected long-distance hash logarithm, or zero.
        private int longDistanceHashLog;

        /// The selected long-distance minimum match length, or zero.
        private int longDistanceMinimumMatch;

        /// The selected long-distance bucket-size logarithm, or zero.
        private int longDistanceBucketSizeLog;

        /// The selected long-distance sampling logarithm, or zero.
        private int longDistanceHashRateLog;

        /// The selected physical frame format.
        private ZstdFrameFormat frameFormat = ZstdFrameFormat.STANDARD;

        /// Creates a builder initialized to defaults.
        private Builder() {
        }

        /// Creates a builder initialized from an immutable codec.
        private Builder(ZstdCodec codec) {
            compressionLevel = codec.compressionLevel;
            dictionary = codec.dictionary;
            frameChecksum = codec.frameChecksum;
            verifyChecksums = codec.verifyChecksums;
            workerCount = codec.workerCount;
            windowLog = codec.windowLog;
            hashLog = codec.hashLog;
            chainLog = codec.chainLog;
            searchLog = codec.searchLog;
            minimumMatch = codec.minimumMatch;
            targetLength = codec.targetLength;
            strategy = codec.strategy;
            jobSize = codec.jobSize;
            overlapLog = codec.overlapLog;
            contentSize = codec.contentSize;
            dictionaryId = codec.dictionaryId;
            longDistanceMatching = codec.longDistanceMatching;
            longDistanceHashLog = codec.longDistanceHashLog;
            longDistanceMinimumMatch = codec.longDistanceMinimumMatch;
            longDistanceBucketSizeLog = codec.longDistanceBucketSizeLog;
            longDistanceHashRateLog = codec.longDistanceHashRateLog;
            frameFormat = codec.frameFormat;
        }

        /// Selects the compression level.
        public Builder compressionLevel(long compressionLevel) {
            requireCompressionLevel(compressionLevel);
            this.compressionLevel = Math.toIntExact(compressionLevel);
            return this;
        }

        /// Selects an immutable dictionary.
        public Builder dictionary(CompressionDictionary dictionary) {
            this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
            return this;
        }

        /// Selects a copy of raw dictionary bytes.
        public Builder dictionary(byte[] dictionary) {
            return dictionary(CompressionDictionary.of(dictionary));
        }

        /// Clears the selected dictionary.
        public Builder withoutDictionary() {
            dictionary = null;
            return this;
        }

        /// Selects whether encoders emit frame checksums.
        public Builder frameChecksum(boolean frameChecksum) {
            this.frameChecksum = frameChecksum;
            return this;
        }

        /// Selects whether decoders verify frame checksums.
        public Builder verifyChecksums(boolean verifyChecksums) {
            this.verifyChecksums = verifyChecksums;
            return this;
        }

        /// Selects the number of parallel compression workers.
        public Builder workerCount(int workerCount) {
            if (workerCount < 0 || workerCount > MAXIMUM_WORKER_COUNT) {
                throw new IllegalArgumentException(
                        "Zstandard workerCount must be between zero and "
                                + MAXIMUM_WORKER_COUNT
                );
            }
            this.workerCount = workerCount;
            return this;
        }

        /// Selects the window logarithm, or zero for automatic selection.
        public Builder windowLog(long windowLog) {
            this.windowLog = automaticOrBounded(
                    windowLog,
                    MINIMUM_WINDOW_LOG,
                    MAXIMUM_WINDOW_LOG,
                    "Zstandard windowLog"
            );
            return this;
        }

        /// Selects the hash-table logarithm, or zero for automatic selection.
        public Builder hashLog(long hashLog) {
            this.hashLog = automaticOrBounded(
                    hashLog,
                    MINIMUM_HASH_LOG,
                    MAXIMUM_HASH_LOG,
                    "Zstandard hashLog"
            );
            return this;
        }

        /// Selects the chain-table logarithm, or zero for automatic selection.
        public Builder chainLog(long chainLog) {
            this.chainLog = automaticOrBounded(
                    chainLog,
                    MINIMUM_CHAIN_LOG,
                    MAXIMUM_CHAIN_LOG,
                    "Zstandard chainLog"
            );
            return this;
        }

        /// Selects the search-depth logarithm, or zero for automatic selection.
        public Builder searchLog(long searchLog) {
            this.searchLog = automaticOrBounded(
                    searchLog,
                    MINIMUM_SEARCH_LOG,
                    MAXIMUM_SEARCH_LOG,
                    "Zstandard searchLog"
            );
            return this;
        }

        /// Selects the minimum match length, or zero for automatic selection.
        public Builder minimumMatch(long minimumMatch) {
            this.minimumMatch = automaticOrBounded(
                    minimumMatch,
                    MINIMUM_MATCH_LENGTH,
                    MAXIMUM_MATCH_LENGTH,
                    "Zstandard minimumMatch"
            );
            return this;
        }

        /// Selects the target match length, or zero for no explicit target.
        public Builder targetLength(long targetLength) {
            this.targetLength = nonNegativeInt(targetLength, "Zstandard targetLength");
            return this;
        }

        /// Selects an explicit match-finding strategy.
        public Builder strategy(ZstdStrategy strategy) {
            this.strategy = Objects.requireNonNull(strategy, "strategy");
            return this;
        }

        /// Restores level-derived strategy selection.
        public Builder automaticStrategy() {
            strategy = null;
            return this;
        }

        /// Selects the target worker job size, or zero for automatic selection.
        public Builder jobSize(long jobSize) {
            this.jobSize = nonNegativeInt(jobSize, "Zstandard jobSize");
            return this;
        }

        /// Selects the worker overlap logarithm from zero through nine.
        public Builder overlapLog(int overlapLog) {
            if (overlapLog < 0 || overlapLog > 9) {
                throw new IllegalArgumentException(
                        "Zstandard overlapLog must be between zero and nine"
                );
            }
            this.overlapLog = overlapLog;
            return this;
        }

        /// Selects whether known pledged sizes are written into frame headers.
        public Builder contentSize(boolean contentSize) {
            this.contentSize = contentSize;
            return this;
        }

        /// Selects whether trained dictionary identifiers are written into frame headers.
        public Builder dictionaryId(boolean dictionaryId) {
            this.dictionaryId = dictionaryId;
            return this;
        }

        /// Selects whether long-distance matching is enabled.
        public Builder longDistanceMatching(boolean longDistanceMatching) {
            this.longDistanceMatching = longDistanceMatching;
            return this;
        }

        /// Selects the long-distance hash logarithm, or zero for automatic selection.
        public Builder longDistanceHashLog(long value) {
            longDistanceHashLog = automaticOrBounded(
                    value,
                    MINIMUM_LONG_DISTANCE_HASH_LOG,
                    MAXIMUM_LONG_DISTANCE_HASH_LOG,
                    "Zstandard longDistanceHashLog"
            );
            return this;
        }

        /// Selects the long-distance minimum match length, or zero for automatic selection.
        public Builder longDistanceMinimumMatch(long value) {
            longDistanceMinimumMatch = automaticOrBounded(
                    value,
                    MINIMUM_LONG_DISTANCE_MATCH_LENGTH,
                    MAXIMUM_LONG_DISTANCE_MATCH_LENGTH,
                    "Zstandard longDistanceMinimumMatch"
            );
            return this;
        }

        /// Selects the long-distance bucket-size logarithm, or zero for automatic selection.
        public Builder longDistanceBucketSizeLog(long value) {
            longDistanceBucketSizeLog = automaticOrBounded(
                    value,
                    MINIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG,
                    MAXIMUM_LONG_DISTANCE_BUCKET_SIZE_LOG,
                    "Zstandard longDistanceBucketSizeLog"
            );
            return this;
        }

        /// Selects the long-distance sampling-rate logarithm, or zero for automatic selection.
        public Builder longDistanceHashRateLog(long value) {
            longDistanceHashRateLog = automaticOrBounded(
                    value,
                    MINIMUM_LONG_DISTANCE_HASH_RATE_LOG,
                    MAXIMUM_LONG_DISTANCE_HASH_RATE_LOG,
                    "Zstandard longDistanceHashRateLog"
            );
            return this;
        }

        /// Selects standard or explicitly magicless physical framing.
        public Builder frameFormat(ZstdFrameFormat frameFormat) {
            this.frameFormat = Objects.requireNonNull(frameFormat, "frameFormat");
            return this;
        }

        /// Builds an immutable Zstandard codec configuration.
        public ZstdCodec build() {
            return new ZstdCodec(this);
        }
    }
}
