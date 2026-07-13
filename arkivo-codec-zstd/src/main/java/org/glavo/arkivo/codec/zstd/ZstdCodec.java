// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
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

    /// Selects the native Zstandard match-finding strategy.
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
        return Zstd.minCompressionLevel();
    }

    /// Returns the maximum Zstandard compression level.
    @Override
    public long maximumCompressionLevel() {
        return Zstd.maxCompressionLevel();
    }

    /// Returns the default Zstandard compression level.
    @Override
    public long defaultCompressionLevel() {
        return Zstd.defaultCompressionLevel();
    }

    /// Returns the minimum supported Zstandard window logarithm.
    public long minimumWindowLog() {
        return Zstd.windowLogMin();
    }

    /// Returns the maximum supported Zstandard window logarithm.
    public long maximumWindowLog() {
        return Zstd.windowLogMax();
    }

    /// Returns the minimum supported Zstandard hash-table logarithm.
    public long minimumHashLog() {
        return Zstd.hashLogMin();
    }

    /// Returns the maximum supported Zstandard hash-table logarithm.
    public long maximumHashLog() {
        return Zstd.hashLogMax();
    }

    /// Returns the minimum supported Zstandard chain-table logarithm.
    public long minimumChainLog() {
        return Zstd.chainLogMin();
    }

    /// Returns the maximum supported Zstandard chain-table logarithm.
    public long maximumChainLog() {
        return Zstd.chainLogMax();
    }

    /// Returns the minimum supported Zstandard search-depth logarithm.
    public long minimumSearchLog() {
        return Zstd.searchLogMin();
    }

    /// Returns the maximum supported Zstandard search-depth logarithm.
    public long maximumSearchLog() {
        return Zstd.searchLogMax();
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
        return Zstd.compressBound(sourceSize);
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
                createEncoderContext(options, pledgedSourceSize),
                pledgedSourceSize
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
                new ZstdChannelDecoder(source, ownership, createDecoderContext(options), maximumWindowSize),
                maximumOutputSize
        );
    }

    /// Creates and configures one native compression context.
    private ZstdCompressCtx createEncoderContext(CodecOptions options, long pledgedSourceSize) {
        ZstdCompressCtx context = new ZstdCompressCtx();
        try {
            @Nullable Long requestedLevel = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
            if (requestedLevel != null) {
                if (requestedLevel < minimumCompressionLevel() || requestedLevel > maximumCompressionLevel()) {
                    throw new IllegalArgumentException("Zstandard compression level is out of range");
                }
                context.setLevel(Math.toIntExact(requestedLevel));
            } else if (compressionLevel != DEFAULT_COMPRESSION_LEVEL) {
                context.setLevel(Math.toIntExact(compressionLevel));
            }

            configureAdvancedCompression(context, options);

            @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
            if (requestedDictionary != null) {
                context.loadDict(requestedDictionary.bytes());
            } else if (dictionary != null) {
                context.loadDict(dictionary);
            }

            @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
            if (checksum == ChecksumMode.ENABLED) {
                context.setChecksum(true);
            } else if (checksum == ChecksumMode.DISABLED) {
                context.setChecksum(false);
            }

            @Nullable WorkerCount workers = options.get(StandardCodecOptions.WORKER_COUNT);
            if (workers != null) {
                context.setWorkers(workers.value());
            }

            if (pledgedSourceSize >= 0L) {
                context.setPledgedSrcSize(pledgedSourceSize);
            }
            return context;
        } catch (RuntimeException | Error exception) {
            context.close();
            throw exception;
        }
    }

    /// Applies algorithm-specific compression parameters to a native context.
    private static void configureAdvancedCompression(ZstdCompressCtx context, CodecOptions options) {
        @Nullable Long windowLog = options.get(WINDOW_LOG);
        if (windowLog != null) {
            context.setWindowLog(boundedParameter(
                    windowLog,
                    Zstd.windowLogMin(),
                    Zstd.windowLogMax(),
                    "Zstandard window log"
            ));
        }

        @Nullable Long hashLog = options.get(HASH_LOG);
        if (hashLog != null) {
            context.setHashLog(boundedParameter(
                    hashLog,
                    Zstd.hashLogMin(),
                    Zstd.hashLogMax(),
                    "Zstandard hash log"
            ));
        }

        @Nullable Long chainLog = options.get(CHAIN_LOG);
        if (chainLog != null) {
            context.setChainLog(boundedParameter(
                    chainLog,
                    Zstd.chainLogMin(),
                    Zstd.chainLogMax(),
                    "Zstandard chain log"
            ));
        }

        @Nullable Long searchLog = options.get(SEARCH_LOG);
        if (searchLog != null) {
            context.setSearchLog(boundedParameter(
                    searchLog,
                    Zstd.searchLogMin(),
                    Zstd.searchLogMax(),
                    "Zstandard search log"
            ));
        }

        @Nullable Long minMatch = options.get(MIN_MATCH);
        if (minMatch != null) {
            context.setMinMatch(boundedParameter(
                    minMatch,
                    MIN_MATCH_LENGTH_MINIMUM,
                    MIN_MATCH_LENGTH_MAXIMUM,
                    "Zstandard minimum match length"
            ));
        }

        @Nullable Long targetLength = options.get(TARGET_LENGTH);
        if (targetLength != null) {
            context.setTargetLength(nonNegativeInt(targetLength, "Zstandard target length"));
        }

        @Nullable ZstdStrategy strategy = options.get(STRATEGY);
        if (strategy != null) {
            context.setStrategy(strategy.nativeValue());
        }

        @Nullable Long jobSize = options.get(JOB_SIZE);
        if (jobSize != null) {
            context.setJobSize(nonNegativeInt(jobSize, "Zstandard job size"));
        }

        @Nullable Long overlapLog = options.get(OVERLAP_LOG);
        if (overlapLog != null) {
            context.setOverlapLog(boundedParameter(overlapLog, 0, 9, "Zstandard overlap log"));
        }

        @Nullable Boolean contentSize = options.get(CONTENT_SIZE);
        if (contentSize != null) {
            context.setContentSize(contentSize);
        }

        @Nullable Boolean dictionaryId = options.get(DICTIONARY_ID);
        if (dictionaryId != null) {
            context.setDictID(dictionaryId);
        }

        @Nullable Boolean longDistanceMatching = options.get(LONG_DISTANCE_MATCHING);
        if (longDistanceMatching != null) {
            context.setEnableLongDistanceMatching(
                    longDistanceMatching ? Zstd.ParamSwitch.ENABLE : Zstd.ParamSwitch.DISABLE
            );
        }
    }

    /// Converts a parameter that accepts zero for automatic selection or a native bounded value.
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

    /// Creates and configures one native decompression context.
    private ZstdDecompressCtx createDecoderContext(CodecOptions options) {
        ZstdDecompressCtx context = new ZstdDecompressCtx();
        try {
            @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
            if (requestedDictionary != null) {
                context.loadDict(requestedDictionary.bytes());
            } else if (dictionary != null) {
                context.loadDict(dictionary);
            }
            return context;
        } catch (RuntimeException | Error exception) {
            context.close();
            throw exception;
        }
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
        int level = compressionLevel != DEFAULT_COMPRESSION_LEVEL
                ? Math.toIntExact(compressionLevel)
                : Zstd.defaultCompressionLevel();
        long result;
        if (dictionary != null) {
            result = Zstd.compressDirectByteBufferUsingDict(
                    target,
                    target.position(),
                    target.remaining(),
                    source,
                    source.position(),
                    source.remaining(),
                    dictionary,
                    level
            );
        } else {
            result = Zstd.compressDirectByteBuffer(
                    target,
                    target.position(),
                    target.remaining(),
                    source,
                    source.position(),
                    source.remaining(),
                    level
            );
        }
        if (Zstd.isError(result)) {
            throw new IOException("Zstandard ByteBuffer compression failed: " + Zstd.getErrorName(result));
        }

        source.position(source.limit());
        target.position(target.position() + (int) result);
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
        long result;
        if (dictionary != null) {
            result = Zstd.decompressDirectByteBufferUsingDict(
                    target,
                    target.position(),
                    target.remaining(),
                    source,
                    source.position(),
                    source.remaining(),
                    dictionary
            );
        } else {
            result = Zstd.decompressDirectByteBuffer(
                    target,
                    target.position(),
                    target.remaining(),
                    source,
                    source.position(),
                    source.remaining()
            );
        }
        if (Zstd.isError(result)) {
            throw new IOException("Zstandard ByteBuffer decompression failed: " + Zstd.getErrorName(result));
        }

        source.position(source.limit());
        target.position(target.position() + (int) result);
    }
}
