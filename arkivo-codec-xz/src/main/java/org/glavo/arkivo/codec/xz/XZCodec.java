// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOption;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.lzma.LZMAOptions;
import org.glavo.arkivo.codec.lzma.internal.LzmaProperties;
import org.glavo.arkivo.codec.xz.internal.XzChannelDecoder;
import org.glavo.arkivo.codec.xz.internal.XzChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Set;

/// Provides XZ compression and decompression channels.
@NotNullByDefault
public final class XZCodec implements CompressionCodec {
    /// The stable XZ codec name.
    public static final String NAME = "xz";

    /// Selects the XZ LZMA2 dictionary size in bytes.
    public static final CodecOption<Long> DICTIONARY_SIZE =
            CodecOption.of("xz.dictionarySize", Long.class);

    /// Selects the exact XZ block integrity-check algorithm.
    public static final CodecOption<XZCheckType> CHECK_TYPE =
            CodecOption.of("xz.checkType", XZCheckType.class);

    /// Selects the ordered size-preserving filters applied before LZMA2.
    public static final CodecOption<XZFilterChain> FILTER_CHAIN =
            CodecOption.of("xz.filterChain", XZFilterChain.class);

    /// Selects the maximum uncompressed bytes per XZ Block; zero keeps one unbounded Block.
    public static final CodecOption<Long> BLOCK_SIZE =
            CodecOption.of("xz.blockSize", Long.class);

    /// The supported XZ operations.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.DIRECT_BYTE_BUFFER,
            CompressionFeature.MULTI_FRAME,
            CompressionFeature.CONCATENATED_FRAMES
    ), Set.of(
            DICTIONARY_SIZE,
            LZMAOptions.LITERAL_CONTEXT_BITS,
            LZMAOptions.LITERAL_POSITION_BITS,
            LZMAOptions.POSITION_BITS,
            StandardCodecOptions.CHECKSUM,
            CHECK_TYPE,
            FILTER_CHAIN,
            BLOCK_SIZE
    ), Set.of(StandardCodecOptions.MAX_OUTPUT_SIZE, StandardCodecOptions.MAX_WINDOW_SIZE));

    /// The XZ stream header magic bytes.
    private static final byte @Unmodifiable [] HEADER_MAGIC = {
            (byte) 0xfd, 0x37, 0x7a, 0x58, 0x5a, 0x00
    };

    /// Creates an XZ codec.
    public XZCodec() {
    }

    /// Returns the stable XZ codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns the supported XZ operations and options.
    @Override
    public CompressionCapabilities capabilities() {
        return CAPABILITIES;
    }

    /// Returns the number of leading bytes used to identify XZ streams.
    @Override
    public int probeSize() {
        return HEADER_MAGIC.length;
    }

    /// Returns whether the given prefix starts with the XZ stream signature.
    @Override
    public boolean matches(ByteBuffer prefix) {
        int position = prefix.position();
        if (prefix.remaining() < HEADER_MAGIC.length) {
            return false;
        }

        for (int i = 0; i < HEADER_MAGIC.length; i++) {
            if (prefix.get(position + i) != HEADER_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /// Opens a configured XZ encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "XZ compression");
        LzmaProperties properties = LzmaProperties.fromOptions(
                options,
                DICTIONARY_SIZE,
                XzChannelEncoder.DEFAULT_DICTIONARY_SIZE
        );

        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        @Nullable XZCheckType requestedCheck = options.get(CHECK_TYPE);
        if (checksum != null && checksum != ChecksumMode.DEFAULT && requestedCheck != null) {
            throw new IllegalArgumentException("XZ CHECKSUM and CHECK_TYPE options cannot both select a check");
        }
        XZCheckType checkType = requestedCheck != null
                ? requestedCheck
                : checksum == ChecksumMode.DISABLED ? XZCheckType.NONE : XZCheckType.CRC64;
        @Nullable XZFilterChain requestedFilters = options.get(FILTER_CHAIN);
        XZFilterChain filterChain = requestedFilters != null ? requestedFilters : XZFilterChain.EMPTY;
        @Nullable Long requestedBlockSize = options.get(BLOCK_SIZE);
        long blockSize = requestedBlockSize != null ? requestedBlockSize : 0L;
        if (blockSize < 0L) {
            throw new IllegalArgumentException("XZ Block size must not be negative: " + blockSize);
        }
        return new XzChannelEncoder(
                target,
                ownership,
                properties,
                checkType.flag(),
                filterChain,
                blockSize
        );
    }

    /// Opens a configured XZ decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "XZ decompression");
        long maximumWindowSize = StandardCodecOptionSupport.maximumWindowSize(options);
        long maximumOutputSize = StandardCodecOptionSupport.maximumOutputSize(options);
        return StandardCodecOptionSupport.limitOutput(
                new XzChannelDecoder(source, ownership, true, maximumWindowSize),
                maximumOutputSize
        );
    }
}
