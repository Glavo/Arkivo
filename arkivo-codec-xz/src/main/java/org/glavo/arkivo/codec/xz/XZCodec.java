// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.glavo.arkivo.codec.xz.internal.XZChannelEncoder;
import org.glavo.arkivo.codec.xz.internal.XZDecoder;
import org.glavo.arkivo.codec.xz.internal.XZEncoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable XZ configuration and pure Java transport-independent engines.
///
/// One encoded frame is one complete XZ Stream. The configured block size controls uncompressed bytes per Block inside
/// that Stream; zero selects one Block that grows until frame finalization. A nonterminal flush finishes the active
/// Block, while frame finalization writes the Index and Stream Footer.
///
/// Codec values are safe for concurrent use. Builders and created engines are mutable and not safe for concurrent use.
/// Decoders obtain LZMA2 and preprocessing-filter requirements from Block Headers, apply operation-scoped limits, and
/// verify Block checks only when checksum verification is enabled.
@NotNullByDefault
public final class XZCodec implements CompressionCodec.FlushableFramed<XZCodec> {
    /// The default XZ LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = XZChannelEncoder.DEFAULT_DICTIONARY_SIZE;

    /// The default immutable XZ codec configuration.
    public static final XZCodec DEFAULT = builder().build();


    /// The configured LZMA2 model properties.
    private final LZMAProperties properties;

    /// The configured block integrity check.
    private final XZCheckType checkType;

    /// The configured ordered preprocessing filter chain.
    private final XZFilterChain filterChain;

    /// The maximum uncompressed bytes per XZ block, or zero for one unbounded block.
    private final long blockSize;

    /// Whether decoders verify block integrity checks.
    private final boolean verifyChecksums;

    /// The configured decoded-output limit.
    private final long maximumOutputSize;

    /// The configured decoder history-window limit.
    private final long maximumWindowSize;

    /// The configured decoder working-memory limit.
    private final long maximumMemorySize;

    /// Creates the default XZ codec configuration.
    public XZCodec() {
        this(new Builder());
    }

    /// Creates an immutable XZ codec from a validated builder snapshot.
    private XZCodec(Builder builder) {
        this.properties = Objects.requireNonNull(builder.properties, "properties");
        this.checkType = Objects.requireNonNull(builder.checkType, "checkType");
        this.filterChain = Objects.requireNonNull(builder.filterChain, "filterChain");
        this.blockSize = builder.blockSize;
        this.verifyChecksums = builder.verifyChecksums;
        this.maximumOutputSize = builder.maximumOutputSize;
        this.maximumWindowSize = builder.maximumWindowSize;
        this.maximumMemorySize = builder.maximumMemorySize;
    }

    /// Returns a new builder initialized to XZ defaults.
    ///
    /// @return a new mutable builder
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a mutable builder initialized from this immutable configuration.
    ///
    /// @return a new builder whose values initially equal this codec's values
    public Builder toBuilder() {
        return new Builder(this);
    }

    /// Returns the canonical XZ format.
    @Override
    public XZFormat format() {
        return XZFormat.instance();
    }

    /// Returns the configured decoded-output limit.
    @Override
    public long maximumOutputSize() {
        return maximumOutputSize;
    }

    /// Returns the configured decoder history-window limit.
    @Override
    public long maximumWindowSize() {
        return maximumWindowSize;
    }

    /// Returns the configured decoder working-memory limit.
    @Override
    public long maximumMemorySize() {
        return maximumMemorySize;
    }

    /// Returns an immutable codec with the requested decoded-output limit.
    @Override
    public XZCodec withMaximumOutputSize(long maximumOutputSize) {
        return maximumOutputSize == this.maximumOutputSize
                ? this
                : toBuilder().maximumOutputSize(maximumOutputSize).build();
    }

    /// Returns an immutable codec with the requested decoder history-window limit.
    @Override
    public XZCodec withMaximumWindowSize(long maximumWindowSize) {
        return maximumWindowSize == this.maximumWindowSize
                ? this
                : toBuilder().maximumWindowSize(maximumWindowSize).build();
    }

    /// Returns an immutable codec with the requested decoder working-memory limit.
    @Override
    public XZCodec withMaximumMemorySize(long maximumMemorySize) {
        return maximumMemorySize == this.maximumMemorySize
                ? this
                : toBuilder().maximumMemorySize(maximumMemorySize).build();
    }

    /// Returns the configured LZMA2 model properties.
    ///
    /// @return the immutable properties used by newly created encoders
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns the configured block integrity check.
    ///
    /// @return the check written after each encoded Block
    public XZCheckType checkType() {
        return checkType;
    }

    /// Returns the configured ordered preprocessing filter chain.
    ///
    /// @return the immutable preprocessing chain in encoding order
    public XZFilterChain filterChain() {
        return filterChain;
    }

    /// Returns the maximum uncompressed bytes per XZ block, or zero for one unbounded block.
    ///
    /// @return the nonnegative Block size limit, in bytes
    public long blockSize() {
        return blockSize;
    }

    /// Returns whether decoders verify block integrity checks.
    ///
    /// @return {@code true} if newly created decoders calculate and compare supported Block checks
    public boolean verifiesChecksums() {
        return verifyChecksums;
    }

    /// Returns an immutable XZ codec with the requested LZMA2 properties.
    ///
    /// @param properties the replacement LZMA2 model properties
    /// @return this codec if the properties are unchanged; otherwise, a new codec with the requested properties
    /// @throws NullPointerException if {@code properties} is {@code null}
    public XZCodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties)
                ? this
                : toBuilder().properties(properties).build();
    }

    /// Returns an immutable XZ codec with the requested dictionary size.
    ///
    /// @param dictionarySize the replacement LZMA2 dictionary size, in bytes
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
    public XZCodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Returns an immutable XZ codec with the requested block integrity check.
    ///
    /// @param checkType the check to write after each encoded Block
    /// @return this codec if the check is unchanged; otherwise, a new codec with the requested check
    /// @throws NullPointerException if {@code checkType} is {@code null}
    public XZCodec withCheckType(XZCheckType checkType) {
        Objects.requireNonNull(checkType, "checkType");
        return checkType == this.checkType
                ? this
                : toBuilder().checkType(checkType).build();
    }

    /// Returns an immutable XZ codec with the requested preprocessing filters.
    ///
    /// @param filterChain the replacement preprocessing chain in encoding order
    /// @return this codec if the chain is unchanged; otherwise, a new codec with the requested chain
    /// @throws NullPointerException if {@code filterChain} is {@code null}
    public XZCodec withFilterChain(XZFilterChain filterChain) {
        Objects.requireNonNull(filterChain, "filterChain");
        return filterChain.equals(this.filterChain)
                ? this
                : toBuilder().filterChain(filterChain).build();
    }

    /// Returns an immutable XZ codec with the requested block size.
    ///
    /// @param blockSize the maximum uncompressed bytes per Block, or zero for one unbounded Block
    /// @return this codec if the size is unchanged; otherwise, a new codec with the requested size
    /// @throws IllegalArgumentException if {@code blockSize} is negative
    public XZCodec withBlockSize(long blockSize) {
        return blockSize == this.blockSize
                ? this
                : toBuilder().blockSize(blockSize).build();
    }

    /// Returns an immutable XZ codec with the requested checksum-verification behavior.
    ///
    /// @param verifyChecksums whether decoders calculate and compare supported Block checks
    /// @return this codec if the setting is unchanged; otherwise, a new codec with the requested setting
    public XZCodec withVerifyChecksums(boolean verifyChecksums) {
        return verifyChecksums == this.verifyChecksums
                ? this
                : toBuilder().verifyChecksums(verifyChecksums).build();
    }


    /// Creates a flushable transport-independent XZ stream encoder.
    @Override
    public CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        return new XZEncoder(properties, checkType.flag(), filterChain, blockSize);
    }

    /// Creates a transport-independent XZ stream decoder using this codec's configured limits.
    @Override
    public CompressionDecoder.Framed newDecoder() throws IOException {
        return CompressionDecoderSupport.limitEngineOutput(
                new XZDecoder(
                        CompressionDecoderSupport.effectiveMaximumWindowSize(
                                maximumWindowSize,
                                maximumMemorySize
                        ),
                        verifyChecksums
                ),
                maximumOutputSize
        );
    }

    /// Builds immutable XZ codec configurations.
    ///
    /// A builder may be reused after [#build()]; each build captures the values selected at that time. Builders are
    /// mutable and not safe for concurrent use.
    @NotNullByDefault
    public static final class Builder {
        /// The selected LZMA2 model properties.
        private LZMAProperties properties = LZMAProperties.defaults(DEFAULT_DICTIONARY_SIZE);

        /// The selected block integrity check.
        private XZCheckType checkType = XZCheckType.CRC64;

        /// The selected ordered preprocessing filter chain.
        private XZFilterChain filterChain = XZFilterChain.EMPTY;

        /// The selected maximum uncompressed bytes per block.
        private long blockSize;

        /// Whether decoders verify block integrity checks.
        private boolean verifyChecksums = true;

        /// The selected decoded-output limit.
        private long maximumOutputSize = UNLIMITED_SIZE;

        /// The selected decoder history-window limit.
        private long maximumWindowSize = UNLIMITED_SIZE;

        /// The selected decoder working-memory limit.
        private long maximumMemorySize = UNLIMITED_SIZE;

        /// Creates a builder initialized to XZ defaults.
        private Builder() {
        }

        /// Creates a builder initialized from an immutable codec.
        private Builder(XZCodec codec) {
            properties = codec.properties;
            checkType = codec.checkType;
            filterChain = codec.filterChain;
            blockSize = codec.blockSize;
            verifyChecksums = codec.verifyChecksums;
            maximumOutputSize = codec.maximumOutputSize;
            maximumWindowSize = codec.maximumWindowSize;
            maximumMemorySize = codec.maximumMemorySize;
        }

        /// Selects all LZMA2 model properties.
        ///
        /// @param properties the model properties used by newly created encoders
        /// @return this builder
        /// @throws NullPointerException if {@code properties} is {@code null}
        public Builder properties(LZMAProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
            return this;
        }

        /// Selects the LZMA2 dictionary size.
        ///
        /// @param dictionarySize the dictionary size, in bytes
        /// @return this builder
        /// @throws IllegalArgumentException if {@code dictionarySize} is outside the supported range
        public Builder dictionarySize(int dictionarySize) {
            properties = properties.withDictionarySize(dictionarySize);
            return this;
        }

        /// Selects the LZMA literal-context bit count.
        ///
        /// @param literalContextBits the bit count, from {@code 0} through {@code 8}
        /// @return this builder
        /// @throws IllegalArgumentException if the count is invalid with the selected literal-position count
        public Builder literalContextBits(int literalContextBits) {
            properties = properties.withLiteralContextBits(literalContextBits);
            return this;
        }

        /// Selects the LZMA literal-position bit count.
        ///
        /// @param literalPositionBits the bit count, from {@code 0} through {@code 4}
        /// @return this builder
        /// @throws IllegalArgumentException if the count is invalid with the selected literal-context count
        public Builder literalPositionBits(int literalPositionBits) {
            properties = properties.withLiteralPositionBits(literalPositionBits);
            return this;
        }

        /// Selects the LZMA match-position bit count.
        ///
        /// @param positionBits the bit count, from {@code 0} through {@code 4}
        /// @return this builder
        /// @throws IllegalArgumentException if {@code positionBits} is outside the supported range
        public Builder positionBits(int positionBits) {
            properties = properties.withPositionBits(positionBits);
            return this;
        }

        /// Selects the exact XZ block integrity check.
        ///
        /// @param checkType the check to write after each encoded Block
        /// @return this builder
        /// @throws NullPointerException if {@code checkType} is {@code null}
        public Builder checkType(XZCheckType checkType) {
            this.checkType = Objects.requireNonNull(checkType, "checkType");
            return this;
        }

        /// Selects the ordered size-preserving preprocessing filter chain.
        ///
        /// @param filterChain the preprocessing filters in encoding order
        /// @return this builder
        /// @throws NullPointerException if {@code filterChain} is {@code null}
        public Builder filterChain(XZFilterChain filterChain) {
            this.filterChain = Objects.requireNonNull(filterChain, "filterChain");
            return this;
        }

        /// Selects the maximum uncompressed bytes per block; zero keeps one unbounded block.
        ///
        /// @param blockSize the nonnegative Block size limit, in bytes
        /// @return this builder
        /// @throws IllegalArgumentException if {@code blockSize} is negative
        public Builder blockSize(long blockSize) {
            if (blockSize < 0L) {
                throw new IllegalArgumentException("XZ blockSize must not be negative");
            }
            this.blockSize = blockSize;
            return this;
        }

        /// Selects whether decoders verify XZ block integrity checks.
        ///
        /// @param verifyChecksums whether decoders calculate and compare supported Block checks
        /// @return this builder
        public Builder verifyChecksums(boolean verifyChecksums) {
            this.verifyChecksums = verifyChecksums;
            return this;
        }

        /// Selects the maximum decoded-output size.
        ///
        /// @param maximumOutputSize the nonnegative decoded-output limit, or [CompressionCodec#UNLIMITED_SIZE]
        /// @return this builder
        /// @throws IllegalArgumentException if `maximumOutputSize` is less than [CompressionCodec#UNLIMITED_SIZE]
        public Builder maximumOutputSize(long maximumOutputSize) {
            CompressionDecoderSupport.validateLimit(maximumOutputSize, "maximumOutputSize");
            this.maximumOutputSize = maximumOutputSize;
            return this;
        }

        /// Selects the maximum decoder history-window size.
        ///
        /// @param maximumWindowSize the nonnegative history-window limit, or [CompressionCodec#UNLIMITED_SIZE]
        /// @return this builder
        /// @throws IllegalArgumentException if `maximumWindowSize` is less than [CompressionCodec#UNLIMITED_SIZE]
        public Builder maximumWindowSize(long maximumWindowSize) {
            CompressionDecoderSupport.validateLimit(maximumWindowSize, "maximumWindowSize");
            this.maximumWindowSize = maximumWindowSize;
            return this;
        }

        /// Selects the maximum decoder working-memory size.
        ///
        /// @param maximumMemorySize the nonnegative decoder-memory limit, or [CompressionCodec#UNLIMITED_SIZE]
        /// @return this builder
        /// @throws IllegalArgumentException if `maximumMemorySize` is less than [CompressionCodec#UNLIMITED_SIZE]
        public Builder maximumMemorySize(long maximumMemorySize) {
            CompressionDecoderSupport.validateLimit(maximumMemorySize, "maximumMemorySize");
            this.maximumMemorySize = maximumMemorySize;
            return this;
        }

        /// Builds an immutable XZ codec configuration.
        ///
        /// @return an immutable snapshot of this builder's current values
        public XZCodec build() {
            return new XZCodec(this);
        }
    }
}
