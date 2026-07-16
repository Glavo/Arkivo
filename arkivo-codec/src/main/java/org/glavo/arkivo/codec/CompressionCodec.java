// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.ByteBufferCodecSupport;
import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.spi.CodecChannelAdapters;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Describes an immutable compression-format configuration and creates transport-independent engines.
///
/// Implementations must be safe for concurrent use. Stateful encoding and decoding progress belongs exclusively to
/// engines and channel contexts created by a codec.
@NotNullByDefault
public interface CompressionCodec {
    /// The sentinel returned when a size cannot be calculated or is not known.
    long UNKNOWN_SIZE = -1L;

    /// Returns the discoverable compression format configured by this codec.
    CompressionFormat format();

    /// Returns the maximum compressed size for an input of sourceSize bytes, or UNKNOWN_SIZE when unknown.
    default long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        return UNKNOWN_SIZE;
    }

    /// Creates a fresh transport-independent encoder using this immutable configuration.
    CompressionEncoder newEncoder() throws IOException;

    /// Creates a fresh transport-independent decoder using operation-scoped safety limits.
    CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException;

    /// Creates a fresh decoder without output or history-window limits.
    default CompressionDecoder newDecoder() throws IOException {
        return newDecoder(DecompressionLimits.UNLIMITED);
    }

    /// Opens an encoder context over a target channel.
    default CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.openEncoder(target, ownership, this::newEncoder);
    }

    /// Opens an encoder and retains ownership of the target channel.
    default CompressingWritableByteChannel openEncoder(WritableByteChannel target) throws IOException {
        return openEncoder(target, ChannelOwnership.RETAIN);
    }

    /// Opens a decoder context over a source channel with operation-scoped safety limits.
    default DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            DecompressionLimits limits,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");

        // Enforce output across the complete channel session rather than resetting the limit at each frame.
        DecompressionLimits engineLimits =
                limits.withMaximumOutputSize(DecompressionLimits.UNLIMITED_SIZE);
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.openDecoder(
                source,
                ownership,
                () -> newDecoder(engineLimits)
        );
        return CompressionDecoderSupport.limitChannelOutput(decoder, limits.maximumOutputSize());
    }

    /// Opens a decoder with limits and retains ownership of the source channel.
    default DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return openDecoder(source, limits, ChannelOwnership.RETAIN);
    }

    /// Opens a decoder without safety limits and with explicit source ownership.
    default DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return openDecoder(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Opens a decoder without safety limits and retains ownership of the source channel.
    default DecompressingReadableByteChannel openDecoder(ReadableByteChannel source) throws IOException {
        return openDecoder(source, DecompressionLimits.UNLIMITED, ChannelOwnership.RETAIN);
    }

    /// Opens a compatibility channel that closes its target when compression finishes.
    default WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return openEncoder(target, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its target when compression finishes.
    default OutputStream compressTo(OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        return StreamChannelAdapters.outputStream(
                openEncoder(StreamChannelAdapters.writableChannel(target), ChannelOwnership.CLOSE)
        );
    }

    /// Opens a compatibility channel that closes its source with the decoder.
    default ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return openDecoder(source, DecompressionLimits.UNLIMITED, ChannelOwnership.CLOSE);
    }

    /// Opens a limited compatibility channel that closes its source with the decoder.
    default ReadableByteChannel decompressFrom(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return openDecoder(source, limits, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its source with the decoder.
    default InputStream decompressFrom(InputStream source) throws IOException {
        return decompressFrom(source, DecompressionLimits.UNLIMITED);
    }

    /// Opens a limited compatibility stream that closes its source with the decoder.
    default InputStream decompressFrom(InputStream source, DecompressionLimits limits) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        StreamChannelAdapters.readableChannel(source),
                        limits,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Compresses all bytes between channels without closing either channel.
    default CodecTransferResult compress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return CodecTransferSupport.compress(this, source, target);
    }

    /// Decompresses all bytes between channels without limits or channel ownership transfer.
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses all bytes with operation-scoped limits without closing either channel.
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target,
            DecompressionLimits limits
    ) throws IOException {
        return CodecTransferSupport.decompress(this, source, target, limits);
    }

    /// Compresses all remaining source bytes into a newly allocated heap buffer.
    ///
    /// The source is advanced by the number of bytes consumed. The returned buffer has position zero and its limit
    /// equals the compressed size.
    default ByteBuffer compress(ByteBuffer source) throws IOException {
        return ByteBufferCodecSupport.compressAllocating(this, source);
    }

    /// Decompresses all remaining source bytes into a newly allocated bounded heap buffer.
    default ByteBuffer decompress(ByteBuffer source, long maximumOutputSize) throws IOException {
        return decompress(source, DecompressionLimits.ofMaximumOutputSize(maximumOutputSize));
    }

    /// Decompresses all remaining source bytes using operation-scoped limits.
    ///
    /// Allocating decompression requires a finite maximumOutputSize that fits in a Java ByteBuffer.
    default ByteBuffer decompress(ByteBuffer source, DecompressionLimits limits) throws IOException {
        return ByteBufferCodecSupport.decompressAllocating(this, source, limits);
    }

    /// Decompresses one frame into a newly allocated bounded heap buffer.
    default ByteBuffer decompressFrame(ByteBuffer source, long maximumOutputSize) throws IOException {
        return decompressFrame(source, DecompressionLimits.ofMaximumOutputSize(maximumOutputSize));
    }

    /// Decompresses one frame using operation-scoped limits.
    ///
    /// The source position stops after the first complete frame, preserving following frames or trailing bytes.
    default ByteBuffer decompressFrame(ByteBuffer source, DecompressionLimits limits) throws IOException {
        return ByteBufferCodecSupport.decompressFrameAllocating(this, source, limits);
    }

    /// Compresses all remaining bytes from source into target.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    default void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target);
    }

    /// Decompresses all remaining bytes from source into target without a history-window limit.
    default void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        decompress(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses all remaining bytes from source into target using operation-scoped limits.
    default void decompress(
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target, limits);
    }

    /// Decompresses one frame into target without a history-window limit.
    default void decompressFrame(ByteBuffer source, ByteBuffer target) throws IOException {
        decompressFrame(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses one frame into target using operation-scoped limits.
    ///
    /// The source position stops after the first complete frame. The target must hold the complete decoded frame.
    default void decompressFrame(
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        ByteBufferCodecSupport.decompressFrame(this, source, target, limits);
    }

    /// Describes an immutable codec configuration with a selectable compression level.
    @NotNullByDefault
    interface LevelConfigurable extends CompressionCodec {
        /// Returns the configured compression level.
        long compressionLevel();

        /// Returns the minimum supported compression level.
        long minimumCompressionLevel();

        /// Returns the maximum supported compression level.
        long maximumCompressionLevel();

        /// Returns the format implementation's default compression level.
        long defaultCompressionLevel();

        /// Returns an immutable codec configured with the requested compression level.
        LevelConfigurable withCompressionLevel(long compressionLevel);
    }

    /// Describes an immutable codec configuration with a selectable generic compression strategy.
    @NotNullByDefault
    interface StrategyConfigurable extends CompressionCodec {
        /// Returns the configured compression strategy.
        CompressionStrategy compressionStrategy();

        /// Returns an immutable codec configured with the requested compression strategy.
        StrategyConfigurable withCompressionStrategy(CompressionStrategy compressionStrategy);
    }

    /// Describes an immutable codec configuration with an optional shared compression dictionary.
    @NotNullByDefault
    interface DictionaryConfigurable extends CompressionCodec {
        /// Returns the configured dictionary, or `null` when dictionary-free operation is selected.
        @Nullable CompressionDictionary dictionary();

        /// Returns an immutable codec configured with the requested dictionary.
        DictionaryConfigurable withDictionary(CompressionDictionary dictionary);

        /// Returns an immutable codec configured without a dictionary.
        DictionaryConfigurable withoutDictionary();
    }

    /// Creates encoders that can receive an exact uncompressed source size as operation-scoped metadata.
    @NotNullByDefault
    interface PledgedSourceSizeEncoderFactory extends CompressionCodec {
        /// Creates an encoder for a source with the requested exact byte count.
        ///
        /// `pledgedSourceSize` may be `CompressionCodec.UNKNOWN_SIZE` when the size is not known before encoding
        /// starts.
        CompressionEncoder newEncoder(long pledgedSourceSize) throws IOException;

        /// Opens an encoder context with exact uncompressed source-size metadata.
        default CompressingWritableByteChannel openEncoder(
                WritableByteChannel target,
                long pledgedSourceSize,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.openEncoder(
                    target,
                    ownership,
                    () -> newEncoder(pledgedSourceSize)
            );
        }

        /// Opens an encoder with exact source-size metadata and retains target ownership.
        default CompressingWritableByteChannel openEncoder(
                WritableByteChannel target,
                long pledgedSourceSize
        ) throws IOException {
            return openEncoder(target, pledgedSourceSize, ChannelOwnership.RETAIN);
        }

        /// Creates an encoder without a known source size.
        @Override
        default CompressionEncoder newEncoder() throws IOException {
            return newEncoder(UNKNOWN_SIZE);
        }
    }
}
