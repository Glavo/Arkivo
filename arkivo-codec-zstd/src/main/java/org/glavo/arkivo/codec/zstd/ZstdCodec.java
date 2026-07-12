// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.CompressionLevel;
import org.glavo.arkivo.codec.CompressionLevelRange;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.glavo.arkivo.codec.spi.StreamCodecAdapters;
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
    public static final int DEFAULT_COMPRESSION_LEVEL = -1;

    /// The supported Zstandard operations and options.
    private static final CompressionCapabilities CAPABILITIES = new CompressionCapabilities(
            Set.of(
                    CompressionFeature.COMPRESSION,
                    CompressionFeature.DECOMPRESSION,
                    CompressionFeature.ONE_SHOT_COMPRESSION,
                    CompressionFeature.ONE_SHOT_DECOMPRESSION,
                    CompressionFeature.FLUSH,
                    CompressionFeature.DICTIONARY,
                    CompressionFeature.DIRECT_BYTE_BUFFER
            ),
            Set.of(
                    StandardCodecOptions.COMPRESSION_LEVEL,
                    StandardCodecOptions.DICTIONARY,
                    StandardCodecOptions.CHECKSUM,
                    StandardCodecOptions.WORKER_COUNT
            ),
            Set.of(StandardCodecOptions.DICTIONARY),
            new CompressionLevelRange(
                    Zstd.minCompressionLevel(),
                    Zstd.maxCompressionLevel(),
                    Zstd.defaultCompressionLevel()
            )
    );

    /// The requested Zstandard compression level.
    private final int compressionLevel;

    /// The dictionary bytes, or `null` when no dictionary is configured.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// Creates a Zstandard codec.
    public ZstdCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, null);
    }

    /// Creates a configured Zstandard codec.
    private ZstdCodec(int compressionLevel, byte @Nullable [] dictionary) {
        if (compressionLevel != DEFAULT_COMPRESSION_LEVEL
                && (compressionLevel < Zstd.minCompressionLevel()
                || compressionLevel > Zstd.maxCompressionLevel())) {
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
    public int compressionLevel() {
        return compressionLevel;
    }

    /// Returns a copy of the dictionary bytes, or `null` when no dictionary is configured.
    public byte @Nullable [] dictionary() {
        return dictionary != null ? dictionary.clone() : null;
    }

    /// Returns a codec configured with the given compression level.
    public ZstdCodec withCompressionLevel(int compressionLevel) {
        return new ZstdCodec(compressionLevel, dictionary);
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

    /// Opens a configured Zstandard encoder over the target channel.
    @Override
    public CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        options.requireSupported(CAPABILITIES.compressionOptions(), "Zstandard compression");
        return StreamCodecAdapters.openEncoder(
                target,
                ownership,
                output -> configureEncoder(new ZstdOutputStream(output), options)
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
        return StreamCodecAdapters.openDecoder(
                source,
                ownership,
                input -> configureDecoder(new ZstdInputStream(input), options)
        );
    }

    /// Applies configured-instance defaults and operation-specific encoder options.
    private ZstdOutputStream configureEncoder(
            ZstdOutputStream output,
            CodecOptions options
    ) throws IOException {
        @Nullable CompressionLevel requestedLevel = options.get(StandardCodecOptions.COMPRESSION_LEVEL);
        if (requestedLevel != null) {
            if (!Objects.requireNonNull(CAPABILITIES.compressionLevels()).contains(requestedLevel.value())) {
                throw new IllegalArgumentException("Zstandard compression level is out of range");
            }
            output.setLevel(requestedLevel.value());
        } else if (compressionLevel != DEFAULT_COMPRESSION_LEVEL) {
            output.setLevel(compressionLevel);
        }

        @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
        if (requestedDictionary != null) {
            output.setDict(requestedDictionary.bytes());
        } else if (dictionary != null) {
            output.setDict(dictionary);
        }

        @Nullable ChecksumMode checksum = options.get(StandardCodecOptions.CHECKSUM);
        if (checksum == ChecksumMode.ENABLED) {
            output.setChecksum(true);
        } else if (checksum == ChecksumMode.DISABLED) {
            output.setChecksum(false);
        }

        @Nullable WorkerCount workers = options.get(StandardCodecOptions.WORKER_COUNT);
        if (workers != null) {
            output.setWorkers(workers.value());
        }
        return output;
    }

    /// Applies configured-instance defaults and operation-specific decoder options.
    private ZstdInputStream configureDecoder(
            ZstdInputStream input,
            CodecOptions options
    ) throws IOException {
        @Nullable CompressionDictionary requestedDictionary = options.get(StandardCodecOptions.DICTIONARY);
        if (requestedDictionary != null) {
            input.setDict(requestedDictionary.bytes());
        } else if (dictionary != null) {
            input.setDict(dictionary);
        }
        return input;
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
                ? compressionLevel
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
