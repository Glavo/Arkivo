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
///
/// @param <C> the concrete immutable codec type returned by configuration methods
@NotNullByDefault
public interface CompressionCodec<C extends CompressionCodec<C>> {
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

    /// Creates a compressing writable channel with explicit target ownership.
    default CompressingWritableByteChannel newWritableByteChannel(
            WritableByteChannel target,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.newWritableByteChannel(target, ownership, this::newEncoder);
    }

    /// Creates a compressing writable channel and retains ownership of the target channel.
    default CompressingWritableByteChannel newWritableByteChannel(WritableByteChannel target) throws IOException {
        return newWritableByteChannel(target, ChannelOwnership.RETAIN);
    }

    /// Creates a decompressing readable channel with operation-scoped safety limits and explicit source ownership.
    default DecompressingReadableByteChannel newReadableByteChannel(
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
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ownership,
                () -> newDecoder(engineLimits)
        );
        return CompressionDecoderSupport.limitChannelOutput(decoder, limits.maximumOutputSize());
    }

    /// Creates a limited decompressing readable channel and retains ownership of the source channel.
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return newReadableByteChannel(source, limits, ChannelOwnership.RETAIN);
    }

    /// Creates an unlimited decompressing readable channel with explicit source ownership.
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ChannelOwnership ownership
    ) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates an unlimited decompressing readable channel and retains ownership of the source channel.
    default DecompressingReadableByteChannel newReadableByteChannel(ReadableByteChannel source) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ChannelOwnership.RETAIN);
    }

    /// Creates a compressing output stream with explicit target ownership.
    default OutputStream newOutputStream(
            OutputStream target,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.outputStream(
                newWritableByteChannel(StreamChannelAdapters.writableChannel(target), ownership)
        );
    }

    /// Creates a compressing output stream and retains ownership of the target stream.
    default OutputStream newOutputStream(OutputStream target) throws IOException {
        return newOutputStream(target, ChannelOwnership.RETAIN);
    }

    /// Creates a decompressing input stream with operation-scoped limits and explicit source ownership.
    default InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(StreamChannelAdapters.readableChannel(source), limits, ownership)
        );
    }

    /// Creates a limited decompressing input stream and retains ownership of the source stream.
    default InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        return newInputStream(source, limits, ChannelOwnership.RETAIN);
    }

    /// Creates an unlimited decompressing input stream with explicit source ownership.
    default InputStream newInputStream(
            InputStream source,
            ChannelOwnership ownership
    ) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates an unlimited decompressing input stream and retains ownership of the source stream.
    default InputStream newInputStream(InputStream source) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ChannelOwnership.RETAIN);
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

    /// Describes a format whose encoder can flush pending output without ending the active encoding.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface Flushable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Creates a fresh flush-capable encoder.
        @Override
        CompressionEncoder.Flushable newEncoder() throws IOException;

        /// Creates a flush-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a flush-capable compressing channel and retains target ownership.
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ChannelOwnership.RETAIN);
        }
    }

    /// Describes a format composed of independently terminated, concatenable frames.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface Framed<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Creates a fresh frame-capable encoder.
        @Override
        CompressionEncoder.Framed newEncoder() throws IOException;

        /// Creates a fresh frame-capable decoder using operation-scoped safety limits.
        @Override
        CompressionDecoder.Framed newDecoder(DecompressionLimits limits) throws IOException;

        /// Creates a fresh frame-capable decoder without output or history-window limits.
        @Override
        default CompressionDecoder.Framed newDecoder() throws IOException {
            return newDecoder(DecompressionLimits.UNLIMITED);
        }

        /// Creates a frame-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFramedWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a frame-capable compressing channel and retains target ownership.
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ChannelOwnership.RETAIN);
        }

        /// Creates a frame-capable decompressing channel with limits and explicit source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                DecompressionLimits limits,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(limits, "limits");
            Objects.requireNonNull(ownership, "ownership");

            DecompressionLimits engineLimits =
                    limits.withMaximumOutputSize(DecompressionLimits.UNLIMITED_SIZE);
            DecompressingReadableByteChannel.Framed decoder = CodecChannelAdapters.newFramedReadableByteChannel(
                    source,
                    ownership,
                    () -> newDecoder(engineLimits)
            );
            return CompressionDecoderSupport.limitChannelOutput(decoder, limits.maximumOutputSize());
        }

        /// Creates a limited frame-capable decompressing channel and retains source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                DecompressionLimits limits
        ) throws IOException {
            return newReadableByteChannel(source, limits, ChannelOwnership.RETAIN);
        }

        /// Creates an unlimited frame-capable decompressing channel with explicit source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                ChannelOwnership ownership
        ) throws IOException {
            return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ownership);
        }

        /// Creates an unlimited frame-capable decompressing channel and retains source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source
        ) throws IOException {
            return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ChannelOwnership.RETAIN);
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
    }

    /// Describes a framed format whose encoder also supports nonterminal flushing.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface FlushableFramed<C extends CompressionCodec<C>> extends Flushable<C>, Framed<C> {
        /// Creates a fresh frame- and flush-capable encoder.
        @Override
        CompressionEncoder.FlushableFramed newEncoder() throws IOException;

        /// Creates a frame- and flush-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableFramedWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a frame- and flush-capable compressing channel and retains target ownership.
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ChannelOwnership.RETAIN);
        }
    }

    /// Describes an immutable codec configuration with a selectable compression level.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface LevelConfigurable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Returns the configured compression level.
        long compressionLevel();

        /// Returns the minimum supported compression level.
        long minimumCompressionLevel();

        /// Returns the maximum supported compression level.
        long maximumCompressionLevel();

        /// Returns the format implementation's default compression level.
        long defaultCompressionLevel();

        /// Returns an immutable codec configured with the requested compression level.
        C withCompressionLevel(long compressionLevel);
    }

    /// Describes an immutable codec configuration with a selectable generic compression strategy.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface StrategyConfigurable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Returns the configured compression strategy.
        CompressionStrategy compressionStrategy();

        /// Returns an immutable codec configured with the requested compression strategy.
        C withCompressionStrategy(CompressionStrategy compressionStrategy);
    }

    /// Describes an immutable codec configuration with an optional format-specific compression dictionary.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    /// @param <D> the format-specific dictionary type accepted by this codec
    @NotNullByDefault
    interface DictionaryConfigurable<
            C extends CompressionCodec<C>,
            D extends CompressionDictionary
    > extends CompressionCodec<C> {
        /// Returns the configured dictionary, or `null` when dictionary-free operation is selected.
        @Nullable D dictionary();

        /// Returns an immutable codec configured with the requested dictionary.
        C withDictionary(D dictionary);

        /// Returns an immutable codec configured without a dictionary.
        C withoutDictionary();
    }

    /// Creates encoders that can receive an exact uncompressed source size as operation-scoped metadata.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    /// @param <E> the encoder capability type created by this codec
    @NotNullByDefault
    interface PledgedSourceSizeEncoderFactory<
            C extends CompressionCodec<C>,
            E extends CompressionEncoder
    > extends CompressionCodec<C> {
        /// Creates an encoder for a source with the requested exact byte count.
        ///
        /// `pledgedSourceSize` may be `CompressionCodec.UNKNOWN_SIZE` when the size is not known before encoding
        /// starts.
        E newEncoder(long pledgedSourceSize) throws IOException;

        /// Creates a compressing channel with exact uncompressed source-size metadata and explicit target ownership.
        default CompressingWritableByteChannel newWritableByteChannel(
                WritableByteChannel target,
                long pledgedSourceSize,
                ChannelOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newWritableByteChannel(
                    target,
                    ownership,
                    () -> newEncoder(pledgedSourceSize)
            );
        }

        /// Creates a compressing channel with exact source-size metadata and retains target ownership.
        default CompressingWritableByteChannel newWritableByteChannel(
                WritableByteChannel target,
                long pledgedSourceSize
        ) throws IOException {
            return newWritableByteChannel(target, pledgedSourceSize, ChannelOwnership.RETAIN);
        }

        /// Creates an encoder without a known source size.
        @Override
        default E newEncoder() throws IOException {
            return newEncoder(UNKNOWN_SIZE);
        }
    }
}
