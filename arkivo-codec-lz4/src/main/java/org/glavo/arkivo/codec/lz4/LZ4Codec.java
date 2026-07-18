// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.DecodingOptions;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lz4.internal.LZ4FrameDecoder;
import org.glavo.arkivo.codec.lz4.internal.LZ4FrameEncoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/// Provides an immutable standard LZ4 frame configuration and transport-independent engines.
///
/// Encoders always write standard frames. Decoders additionally accept legacy and skippable frames when this codec is
/// selected. The configured block size is a maximum decoded physical-block size, not a frame-size limit; block
/// independence controls whether later blocks may refer to earlier decoded content.
///
/// Codec values are safe for concurrent use. Builders and created engines are mutable and not safe for concurrent use.
/// A frame encoder flush ends the active physical block, while frame finalization writes the frame end mark and optional
/// content checksum.
@NotNullByDefault
public final class LZ4Codec
        implements CompressionCodec.DictionaryConfigurable<LZ4Codec, LZ4Dictionary>,
        CompressionCodec.FlushableFramed<LZ4Codec> {
    /// The default immutable LZ4 frame configuration.
    public static final LZ4Codec DEFAULT = builder().build();

    /// Configured maximum decoded block size.
    private final LZ4BlockSize blockSize;

    /// Whether each block starts with an empty history window.
    private final boolean independentBlocks;

    /// Whether encoders append a checksum to each physical data block.
    private final boolean blockChecksum;

    /// Whether encoders append a checksum for the complete decoded frame content.
    private final boolean contentChecksum;

    /// Whether decoders verify checksums present in input frames.
    private final boolean verifyChecksums;

    /// Configured external prefix dictionary, or null.
    private final @Nullable LZ4Dictionary dictionary;

    /// Creates the default LZ4 frame configuration.
    public LZ4Codec() {
        this(new Builder());
    }

    /// Creates an immutable configuration from a builder snapshot.
    private LZ4Codec(Builder builder) {
        blockSize = builder.blockSize;
        independentBlocks = builder.independentBlocks;
        blockChecksum = builder.blockChecksum;
        contentChecksum = builder.contentChecksum;
        verifyChecksums = builder.verifyChecksums;
        dictionary = builder.dictionary;
    }

    /// Returns a builder initialized to interoperable LZ4 frame defaults.
    ///
    /// @return a new mutable builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a mutable builder initialized from this immutable configuration.
    ///
    /// @return a new builder whose selected values equal this codec's configuration
    public Builder toBuilder() {
        return new Builder(this);
    }

    /// Returns the canonical standard LZ4 frame format.
    @Override
    public LZ4Format format() {
        return LZ4Format.instance();
    }

    /// Returns the configured maximum decoded block size.
    ///
    /// @return the maximum size represented in new frame descriptors
    public LZ4BlockSize blockSize() {
        return blockSize;
    }

    /// Returns whether blocks are independently decodable.
    ///
    /// @return `true` when each block starts with empty frame history
    public boolean usesIndependentBlocks() {
        return independentBlocks;
    }

    /// Returns whether encoders emit physical block checksums.
    ///
    /// @return `true` when each encoded block is followed by xxHash-32
    public boolean emitsBlockChecksums() {
        return blockChecksum;
    }

    /// Returns whether encoders emit a complete content checksum.
    ///
    /// @return `true` when each frame ends with decoded-content xxHash-32
    public boolean emitsContentChecksum() {
        return contentChecksum;
    }

    /// Returns whether decoders verify checksums present in input frames.
    ///
    /// @return `true` when present block and content checksums are validated
    public boolean verifiesChecksums() {
        return verifyChecksums;
    }

    /// Returns the configured external prefix dictionary, or null.
    @Override
    public @Nullable LZ4Dictionary dictionary() {
        return dictionary;
    }

    /// Returns an immutable codec with the requested external prefix dictionary.
    @Override
    public LZ4Codec withDictionary(LZ4Dictionary dictionary) {
        Objects.requireNonNull(dictionary, "dictionary");
        return dictionary == this.dictionary ? this : toBuilder().dictionary(dictionary).build();
    }

    /// Returns an immutable codec without an external prefix dictionary.
    @Override
    public LZ4Codec withoutDictionary() {
        return dictionary == null ? this : toBuilder().withoutDictionary().build();
    }

    /// Returns an immutable codec with the requested maximum decoded block size.
    ///
    /// @param blockSize the block-size code for new frames
    /// @return this codec when unchanged, otherwise a new immutable configuration
    public LZ4Codec withBlockSize(LZ4BlockSize blockSize) {
        Objects.requireNonNull(blockSize, "blockSize");
        return blockSize == this.blockSize ? this : toBuilder().blockSize(blockSize).build();
    }

    /// Returns an immutable codec with the requested block-independence policy.
    ///
    /// @param independentBlocks whether each physical block starts with empty frame history
    /// @return this codec when unchanged, otherwise a new immutable configuration
    public LZ4Codec withIndependentBlocks(boolean independentBlocks) {
        return independentBlocks == this.independentBlocks
                ? this
                : toBuilder().independentBlocks(independentBlocks).build();
    }

    /// Returns an immutable codec with the requested block-checksum emission policy.
    ///
    /// @param blockChecksum whether encoders append xxHash-32 to each physical block
    /// @return this codec when unchanged, otherwise a new immutable configuration
    public LZ4Codec withBlockChecksum(boolean blockChecksum) {
        return blockChecksum == this.blockChecksum
                ? this
                : toBuilder().blockChecksum(blockChecksum).build();
    }

    /// Returns an immutable codec with the requested content-checksum emission policy.
    ///
    /// @param contentChecksum whether encoders append decoded-content xxHash-32
    /// @return this codec when unchanged, otherwise a new immutable configuration
    public LZ4Codec withContentChecksum(boolean contentChecksum) {
        return contentChecksum == this.contentChecksum
                ? this
                : toBuilder().contentChecksum(contentChecksum).build();
    }

    /// Returns an immutable codec with the requested checksum-verification policy.
    ///
    /// @param verifyChecksums whether decoders validate checksums present in input
    /// @return this codec when unchanged, otherwise a new immutable configuration
    public LZ4Codec withVerifyChecksums(boolean verifyChecksums) {
        return verifyChecksums == this.verifyChecksums
                ? this
                : toBuilder().verifyChecksums(verifyChecksums).build();
    }

    /// Returns a safe upper bound for one encoded LZ4 frame.
    @Override
    public long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        long blockCount = sourceSize == 0L
                ? 0L
                : 1L + (sourceSize - 1L) / blockSize.byteSize();
        long perBlockOverhead = Integer.BYTES + (blockChecksum ? Integer.BYTES : 0L);
        long dictionaryIdSize = dictionary != null && dictionary.hasDictionaryId() ? Integer.BYTES : 0L;
        long frameOverhead = 7L + dictionaryIdSize + Integer.BYTES + (contentChecksum ? Integer.BYTES : 0L);
        try {
            return Math.addExact(
                    sourceSize,
                    Math.addExact(frameOverhead, Math.multiplyExact(blockCount, perBlockOverhead))
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /// Creates a transport-independent, flush-capable LZ4 frame encoder.
    @Override
    public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        return new LZ4FrameEncoder(
                blockSize,
                independentBlocks,
                blockChecksum,
                contentChecksum,
                dictionary
        );
    }

    /// Creates a transport-independent LZ4 frame decoder with operation-scoped limits.
    @Override
    public CompressionDecoder.FramedDictionaryAware<LZ4Dictionary, LZ4DictionaryRequest> newDecoder(
            DecodingOptions options
    ) {
        Objects.requireNonNull(options, "options");
        return CompressionDecoderSupport.limitEngineOutput(
                new LZ4FrameDecoder(
                        dictionary,
                        options.effectiveMaximumWindowSize(),
                        options.maximumMemorySize(),
                        verifyChecksums
                ),
                options.maximumOutputSize()
        );
    }

    /// Creates a dictionary-aware decoder without operation-scoped safety limits.
    @Override
    public CompressionDecoder.FramedDictionaryAware<LZ4Dictionary, LZ4DictionaryRequest> newDecoder() {
        return newDecoder(DecodingOptions.DEFAULT);
    }

    /// Builds immutable standard LZ4 frame configurations.
    ///
    /// A builder may be reused after [#build()]; each build captures the values selected at that time. Builders are
    /// mutable and not safe for concurrent use.
    @NotNullByDefault
    public static final class Builder {
        /// Selected maximum decoded block size.
        private LZ4BlockSize blockSize = LZ4BlockSize.MIB_4;

        /// Selected block-independence policy.
        private boolean independentBlocks = true;

        /// Selected block-checksum emission policy.
        private boolean blockChecksum;

        /// Selected content-checksum emission policy.
        private boolean contentChecksum = true;

        /// Selected checksum-verification policy.
        private boolean verifyChecksums = true;

        /// Selected external prefix dictionary, or null.
        private @Nullable LZ4Dictionary dictionary;

        /// Creates a builder initialized to standard defaults.
        public Builder() {
        }

        /// Creates a builder initialized from an immutable codec.
        private Builder(LZ4Codec codec) {
            blockSize = codec.blockSize;
            independentBlocks = codec.independentBlocks;
            blockChecksum = codec.blockChecksum;
            contentChecksum = codec.contentChecksum;
            verifyChecksums = codec.verifyChecksums;
            dictionary = codec.dictionary;
        }

        /// Selects the maximum decoded block size.
        ///
        /// @param blockSize the standard block-size code written to new frames
        /// @return this builder
        public Builder blockSize(LZ4BlockSize blockSize) {
            this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
            return this;
        }

        /// Selects whether every block starts with an empty history window.
        ///
        /// @param independentBlocks whether blocks are independently decodable
        /// @return this builder
        public Builder independentBlocks(boolean independentBlocks) {
            this.independentBlocks = independentBlocks;
            return this;
        }

        /// Selects whether encoders append a checksum to every physical block.
        ///
        /// @param blockChecksum whether to append block xxHash-32 values
        /// @return this builder
        public Builder blockChecksum(boolean blockChecksum) {
            this.blockChecksum = blockChecksum;
            return this;
        }

        /// Selects whether encoders append a checksum for the complete decoded content.
        ///
        /// @param contentChecksum whether to append a decoded-content xxHash-32
        /// @return this builder
        public Builder contentChecksum(boolean contentChecksum) {
            this.contentChecksum = contentChecksum;
            return this;
        }

        /// Selects whether decoders verify checksums present in input frames.
        ///
        /// @param verifyChecksums whether to validate present block and content checksums
        /// @return this builder
        public Builder verifyChecksums(boolean verifyChecksums) {
            this.verifyChecksums = verifyChecksums;
            return this;
        }

        /// Selects the external prefix dictionary used by encoders and decoders.
        ///
        /// @param dictionary the immutable effective prefix history
        /// @return this builder
        public Builder dictionary(LZ4Dictionary dictionary) {
            this.dictionary = Objects.requireNonNull(dictionary, "dictionary");
            return this;
        }

        /// Removes the external prefix dictionary from this builder.
        ///
        /// @return this builder
        public Builder withoutDictionary() {
            dictionary = null;
            return this;
        }

        /// Builds one immutable standard LZ4 frame configuration.
        ///
        /// @return a new codec snapshot of the selected values
        public LZ4Codec build() {
            return new LZ4Codec(this);
        }
    }
}
