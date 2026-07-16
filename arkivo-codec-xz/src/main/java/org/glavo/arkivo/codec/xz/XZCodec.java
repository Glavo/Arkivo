// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionFormat;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.FlushableFramedCompressionEncoder;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.glavo.arkivo.codec.xz.internal.XZCompressionFormat;
import org.glavo.arkivo.codec.xz.internal.XzChannelEncoder;
import org.glavo.arkivo.codec.xz.internal.XzDecoder;
import org.glavo.arkivo.codec.xz.internal.XzEncoder;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Provides an immutable XZ configuration and pure Java transport-independent engines.
@NotNullByDefault
public final class XZCodec implements CompressionCodec {
    /// The stable XZ compression format name.
    public static final String NAME = "xz";

    /// The default XZ LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = XzChannelEncoder.DEFAULT_DICTIONARY_SIZE;

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
    }

    /// Returns a new builder initialized to XZ defaults.
    public static Builder builder() {
        return new Builder();
    }

    /// Returns a mutable builder initialized from this immutable configuration.
    public Builder toBuilder() {
        return new Builder(this);
    }

    /// Returns the canonical XZ format.
    @Override
    public CompressionFormat format() {
        return XZCompressionFormat.instance();
    }

    /// Returns the configured LZMA2 model properties.
    public LZMAProperties properties() {
        return properties;
    }

    /// Returns the configured block integrity check.
    public XZCheckType checkType() {
        return checkType;
    }

    /// Returns the configured ordered preprocessing filter chain.
    public XZFilterChain filterChain() {
        return filterChain;
    }

    /// Returns the maximum uncompressed bytes per XZ block, or zero for one unbounded block.
    public long blockSize() {
        return blockSize;
    }

    /// Returns whether decoders verify block integrity checks.
    public boolean verifyChecksums() {
        return verifyChecksums;
    }

    /// Returns an immutable XZ codec with the requested LZMA2 properties.
    public XZCodec withProperties(LZMAProperties properties) {
        Objects.requireNonNull(properties, "properties");
        return properties.equals(this.properties)
                ? this
                : toBuilder().properties(properties).build();
    }

    /// Returns an immutable XZ codec with the requested dictionary size.
    public XZCodec withDictionarySize(int dictionarySize) {
        return withProperties(properties.withDictionarySize(dictionarySize));
    }

    /// Returns an immutable XZ codec with the requested block integrity check.
    public XZCodec withCheckType(XZCheckType checkType) {
        Objects.requireNonNull(checkType, "checkType");
        return checkType == this.checkType
                ? this
                : toBuilder().checkType(checkType).build();
    }

    /// Returns an immutable XZ codec with the requested preprocessing filters.
    public XZCodec withFilterChain(XZFilterChain filterChain) {
        Objects.requireNonNull(filterChain, "filterChain");
        return filterChain.equals(this.filterChain)
                ? this
                : toBuilder().filterChain(filterChain).build();
    }

    /// Returns an immutable XZ codec with the requested block size.
    public XZCodec withBlockSize(long blockSize) {
        return blockSize == this.blockSize
                ? this
                : toBuilder().blockSize(blockSize).build();
    }

    /// Returns an immutable XZ codec with the requested checksum-verification behavior.
    public XZCodec withVerifyChecksums(boolean verifyChecksums) {
        return verifyChecksums == this.verifyChecksums
                ? this
                : toBuilder().verifyChecksums(verifyChecksums).build();
    }


    /// Creates a flushable transport-independent XZ stream encoder.
    @Override
    public FlushableFramedCompressionEncoder newEncoder() throws IOException {
        return new XzEncoder(properties, checkType.flag(), filterChain, blockSize);
    }

    /// Creates a transport-independent XZ stream decoder with operation-scoped limits.
    @Override
    public CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(limits, "limits");
        return CompressionDecoderSupport.limitEngineOutput(
                new XzDecoder(limits.maximumWindowSize(), verifyChecksums),
                limits.maximumOutputSize()
        );
    }

    /// Builds immutable XZ codec configurations.
    ///
    /// Builders are mutable and not safe for concurrent use.
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
        }

        /// Selects all LZMA2 model properties.
        public Builder properties(LZMAProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
            return this;
        }

        /// Selects the LZMA2 dictionary size.
        public Builder dictionarySize(int dictionarySize) {
            properties = properties.withDictionarySize(dictionarySize);
            return this;
        }

        /// Selects the LZMA literal-context bit count.
        public Builder literalContextBits(int literalContextBits) {
            properties = properties.withLiteralContextBits(literalContextBits);
            return this;
        }

        /// Selects the LZMA literal-position bit count.
        public Builder literalPositionBits(int literalPositionBits) {
            properties = properties.withLiteralPositionBits(literalPositionBits);
            return this;
        }

        /// Selects the LZMA match-position bit count.
        public Builder positionBits(int positionBits) {
            properties = properties.withPositionBits(positionBits);
            return this;
        }

        /// Selects the exact XZ block integrity check.
        public Builder checkType(XZCheckType checkType) {
            this.checkType = Objects.requireNonNull(checkType, "checkType");
            return this;
        }

        /// Selects the ordered size-preserving preprocessing filter chain.
        public Builder filterChain(XZFilterChain filterChain) {
            this.filterChain = Objects.requireNonNull(filterChain, "filterChain");
            return this;
        }

        /// Selects the maximum uncompressed bytes per block; zero keeps one unbounded block.
        public Builder blockSize(long blockSize) {
            if (blockSize < 0L) {
                throw new IllegalArgumentException("XZ blockSize must not be negative");
            }
            this.blockSize = blockSize;
            return this;
        }

        /// Selects whether decoders verify XZ block integrity checks.
        public Builder verifyChecksums(boolean verifyChecksums) {
            this.verifyChecksums = verifyChecksums;
            return this;
        }

        /// Builds an immutable XZ codec configuration.
        public XZCodec build() {
            return new XZCodec(this);
        }
    }
}
