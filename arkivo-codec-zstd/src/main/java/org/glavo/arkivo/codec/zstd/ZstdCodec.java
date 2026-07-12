// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.ChecksumMode;
import org.glavo.arkivo.codec.CodecOptions;
import org.glavo.arkivo.codec.CompressionCapabilities;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionFeature;
import org.glavo.arkivo.codec.StandardCodecOptions;
import org.glavo.arkivo.codec.WorkerCount;
import org.glavo.arkivo.codec.zstd.internal.ZstdChannelDecoder;
import org.glavo.arkivo.codec.zstd.internal.ZstdChannelEncoder;
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
                    StandardCodecOptions.WORKER_COUNT,
                    StandardCodecOptions.PLEDGED_SOURCE_SIZE
            ),
            Set.of(StandardCodecOptions.DICTIONARY)
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
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return new ZstdChannelEncoder(target, ownership, createEncoderContext(options));
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
        return new ZstdChannelDecoder(source, ownership, createDecoderContext(options));
    }

    /// Creates and configures one native compression context.
    private ZstdCompressCtx createEncoderContext(CodecOptions options) {
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

            @Nullable Long pledgedSourceSize = options.get(StandardCodecOptions.PLEDGED_SOURCE_SIZE);
            if (pledgedSourceSize != null) {
                if (pledgedSourceSize < 0) {
                    throw new IllegalArgumentException("Zstandard pledged source size must not be negative");
                }
                context.setPledgedSrcSize(pledgedSourceSize);
            }
            return context;
        } catch (RuntimeException | Error exception) {
            context.close();
            throw exception;
        }
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
