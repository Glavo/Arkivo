// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
import org.glavo.arkivo.codec.xz.internal.XzInputStream;
import org.glavo.arkivo.codec.xz.internal.XzOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
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

    /// The supported XZ operations.
    private static final CompressionCapabilities CAPABILITIES = CompressionCapabilities.of(Set.of(
            CompressionFeature.COMPRESSION,
            CompressionFeature.DECOMPRESSION,
            CompressionFeature.ONE_SHOT_COMPRESSION,
            CompressionFeature.ONE_SHOT_DECOMPRESSION,
            CompressionFeature.CONCATENATED_FRAMES
    ));

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
        return StreamCodecAdapters.openEncoder(target, ownership, XzOutputStream::new);
    }

    /// Opens a configured XZ decoder over the source channel.
    @Override
    public CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.decompressionOptions(), "XZ decompression");
        return StreamCodecAdapters.openDecoder(source, ownership, XzInputStream::new);
    }
}
