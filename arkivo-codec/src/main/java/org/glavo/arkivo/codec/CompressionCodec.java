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
/// engines and channel or stream contexts created by a codec; those operation objects are not safe for concurrent use.
///
/// Endpoint factories validate arguments before applying [ResourceOwnership]. With `BORROWED`, closing the returned
/// context releases codec state but leaves the endpoint open. With `OWNED`, the context closes the endpoint after codec
/// finalization or release, and setup failure closes it without hiding the primary failure.
///
/// Channel-to-channel convenience operations are blocking, reject zero-progress transports with `IOException`, and
/// leave both caller channels open. One-shot buffer operations never retain buffers or change their limits. They advance
/// positions to report partial as well as successful progress.
///
/// @param <C> the concrete immutable codec type represented by this configuration
@NotNullByDefault
public interface CompressionCodec<C extends CompressionCodec<C>> {
    /// The sentinel returned when a size cannot be calculated or is not known.
    long UNKNOWN_SIZE = -1L;

    /// Returns the discoverable compression format configured by this codec.
    ///
    /// @return the configured compression format
    CompressionFormat format();

    /// Returns the maximum compressed size for an input of `sourceSize` bytes, or [#UNKNOWN_SIZE] when unknown.
    ///
    /// @param sourceSize the nonnegative uncompressed input size
    /// @return the maximum compressed size, or [#UNKNOWN_SIZE] when this codec cannot calculate one
    /// @throws IllegalArgumentException when `sourceSize` is negative
    default long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0L) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        return UNKNOWN_SIZE;
    }

    /// Creates a fresh transport-independent encoder using this immutable configuration.
    ///
    /// Each successful call returns independent mutable state owned by the caller. Closing the engine does not affect
    /// this codec or engines created by other calls.
    ///
    /// @return a fresh encoder with independent mutable state
    /// @throws IOException if the encoder's resources cannot be initialized
    CompressionEncoder newEncoder() throws IOException;

    /// Creates a fresh transport-independent decoder using operation-scoped safety limits.
    ///
    /// The returned engine owns its algorithm resources but no caller buffer or transport. Each finite value requests a
    /// ceiling for decoded output, a history window, or decoder working memory. Codecs enforce the limits applicable to
    /// the structures and allocations they can account for and document any format-specific exclusions;
    /// `maximumMemorySize` is not a general JVM allocation budget. Applied limits cover this decoder session and are
    /// reset when the engine itself is reset.
    ///
    /// @param limits the output, window, and memory limits for the decoder session
    /// @return a fresh decoder with independent mutable state
    /// @throws IOException if the decoder's resources cannot be initialized
    CompressionDecoder newDecoder(DecompressionLimits limits) throws IOException;

    /// Creates a fresh decoder without operation-scoped safety limits.
    ///
    /// @return a fresh unlimited decoder with independent mutable state
    /// @throws IOException if the decoder's resources cannot be initialized
    default CompressionDecoder newDecoder() throws IOException {
        return newDecoder(DecompressionLimits.UNLIMITED);
    }

    /// Creates a compressing writable channel with explicit target ownership.
    ///
    /// @param target the channel that receives compressed bytes
    /// @param ownership whether closing the returned channel also closes `target`
    /// @return a new compressing channel
    /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
    default CompressingWritableByteChannel newWritableByteChannel(
            WritableByteChannel target,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.newWritableByteChannel(target, ownership, this::newEncoder);
    }

    /// Creates a compressing writable channel that borrows the target channel.
    ///
    /// @param target the channel that receives compressed bytes and remains open after the returned channel closes
    /// @return a new compressing channel
    /// @throws IOException if the encoder cannot be initialized
    default CompressingWritableByteChannel newWritableByteChannel(WritableByteChannel target) throws IOException {
        return newWritableByteChannel(target, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing readable channel with operation-scoped safety limits and explicit source ownership.
    ///
    /// @param source the channel that supplies compressed bytes
    /// @param limits the output, window, and memory limits for the decoding session
    /// @param ownership whether closing the returned channel also closes `source`
    /// @return a new limited decompressing channel
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            DecompressionLimits limits,
            ResourceOwnership ownership
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

    /// Creates a limited decompressing readable channel that borrows the source channel.
    ///
    /// @param source the channel that supplies compressed bytes and remains open after the returned channel closes
    /// @param limits the output, window, and memory limits for the decoding session
    /// @return a new limited decompressing channel
    /// @throws IOException if the decoder cannot be initialized
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            DecompressionLimits limits
    ) throws IOException {
        return newReadableByteChannel(source, limits, ResourceOwnership.BORROWED);
    }

    /// Creates an unlimited decompressing readable channel with explicit source ownership.
    ///
    /// @param source the channel that supplies compressed bytes
    /// @param ownership whether closing the returned channel also closes `source`
    /// @return a new unlimited decompressing channel
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates an unlimited decompressing readable channel that borrows the source channel.
    ///
    /// @param source the channel that supplies compressed bytes and remains open after the returned channel closes
    /// @return a new unlimited decompressing channel
    /// @throws IOException if the decoder cannot be initialized
    default DecompressingReadableByteChannel newReadableByteChannel(ReadableByteChannel source) throws IOException {
        return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
    }

    /// Creates a compressing output stream with explicit target ownership.
    ///
    /// @param target the stream that receives compressed bytes
    /// @param ownership whether closing the returned stream also closes `target`
    /// @return a new compressing output stream
    /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
    default OutputStream newOutputStream(
            OutputStream target,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.outputStream(
                newWritableByteChannel(StreamChannelAdapters.writableChannel(target), ownership)
        );
    }

    /// Creates a compressing output stream that borrows the target stream.
    ///
    /// @param target the stream that receives compressed bytes and remains open after the returned stream closes
    /// @return a new compressing output stream
    /// @throws IOException if the encoder cannot be initialized
    default OutputStream newOutputStream(OutputStream target) throws IOException {
        return newOutputStream(target, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing input stream with operation-scoped limits and explicit source ownership.
    ///
    /// @param source the stream that supplies compressed bytes
    /// @param limits the output, window, and memory limits for the decoding session
    /// @param ownership whether closing the returned stream also closes `source`
    /// @return a new limited decompressing input stream
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(StreamChannelAdapters.readableChannel(source), limits, ownership)
        );
    }

    /// Creates a limited decompressing input stream that borrows the source stream.
    ///
    /// @param source the stream that supplies compressed bytes and remains open after the returned stream closes
    /// @param limits the output, window, and memory limits for the decoding session
    /// @return a new limited decompressing input stream
    /// @throws IOException if the decoder cannot be initialized
    default InputStream newInputStream(
            InputStream source,
            DecompressionLimits limits
    ) throws IOException {
        return newInputStream(source, limits, ResourceOwnership.BORROWED);
    }

    /// Creates an unlimited decompressing input stream with explicit source ownership.
    ///
    /// @param source the stream that supplies compressed bytes
    /// @param ownership whether closing the returned stream also closes `source`
    /// @return a new unlimited decompressing input stream
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default InputStream newInputStream(
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ownership);
    }

    /// Creates an unlimited decompressing input stream that borrows the source stream.
    ///
    /// @param source the stream that supplies compressed bytes and remains open after the returned stream closes
    /// @return a new unlimited decompressing input stream
    /// @throws IOException if the decoder cannot be initialized
    default InputStream newInputStream(InputStream source) throws IOException {
        return newInputStream(source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
    }

    /// Compresses bytes through source end-of-input without closing either channel.
    ///
    /// The operation blocks until source EOF and all encoded output, including the trailer, has been accepted by the
    /// target. The result reports uncompressed bytes consumed and compressed bytes written.
    ///
    /// @param source the channel supplying uncompressed bytes until end-of-input
    /// @param target the channel receiving the complete compressed encoding
    /// @return the uncompressed input and compressed output byte counts
    /// @throws IOException if channel I/O, encoding, finalization, or transport progress fails
    default CodecTransferResult compress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return CodecTransferSupport.compress(this, source, target);
    }

    /// Decompresses through the logical end of compressed input without limits or channel ownership transfer.
    ///
    /// @param source the channel supplying compressed bytes
    /// @param target the channel receiving decoded bytes
    /// @return the compressed input and decoded output byte counts
    /// @throws IOException if channel I/O, decoding, or transport progress fails
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses with operation-scoped limits without closing either channel.
    ///
    /// Framed codecs continue across concatenated frames until physical source EOF. A decoder may read ahead past a
    /// logical frame boundary; use a readable channel context and [DecompressingReadableByteChannel#unconsumedInput()]
    /// when trailing compressed bytes must be recovered.
    ///
    /// @param source the channel supplying compressed bytes
    /// @param target the channel receiving decoded bytes
    /// @param limits the output, window, and memory limits for this operation
    /// @return the compressed input and decoded output byte counts
    /// @throws IOException if channel I/O, decoding, a configured limit, or transport progress fails
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
    /// equals the compressed size. The source limit is unchanged.
    ///
    /// @param source the buffer supplying all remaining uncompressed bytes
    /// @return a newly allocated heap buffer containing the complete encoding
    /// @throws IOException if encoding or finalization fails
    default ByteBuffer compress(ByteBuffer source) throws IOException {
        return ByteBufferCodecSupport.compressAllocating(this, source);
    }

    /// Decompresses a complete encoding into a newly allocated bounded heap buffer.
    ///
    /// `maximumOutputSize` must be between zero and [Integer#MAX_VALUE]. The returned buffer has position zero and its
    /// limit equals the decoded size.
    ///
    /// @param source the buffer supplying a complete compressed encoding
    /// @param maximumOutputSize the finite maximum number of decoded bytes to allocate
    /// @return a newly allocated heap buffer containing the decoded bytes
    /// @throws IOException if the encoding is invalid, truncated, or exceeds a configured limit
    /// @throws IllegalArgumentException if `maximumOutputSize` is negative or exceeds [Integer#MAX_VALUE]
    default ByteBuffer decompress(ByteBuffer source, long maximumOutputSize) throws IOException {
        return decompress(source, DecompressionLimits.ofMaximumOutputSize(maximumOutputSize));
    }

    /// Decompresses all remaining source bytes using operation-scoped limits.
    ///
    /// Allocating decompression requires a finite `maximumOutputSize` between zero and [Integer#MAX_VALUE]. The returned
    /// heap buffer has position zero and its limit equals the decoded size. A non-framed decoder leaves trailing bytes
    /// after its first completed encoding unconsumed; a framed decoder treats remaining input as concatenated frames.
    ///
    /// @param source the buffer supplying the compressed encoding
    /// @param limits the output, window, and memory limits for this operation
    /// @return a newly allocated heap buffer containing the decoded bytes
    /// @throws IOException if the encoding is invalid, truncated, or exceeds a configured limit
    /// @throws IllegalArgumentException if the maximum output limit is unlimited or exceeds [Integer#MAX_VALUE]
    default ByteBuffer decompress(ByteBuffer source, DecompressionLimits limits) throws IOException {
        return ByteBufferCodecSupport.decompressAllocating(this, source, limits);
    }

    /// Compresses all remaining bytes from source into target.
    ///
    /// Both positions are advanced by the number of bytes consumed and produced, and both limits are unchanged. The
    /// buffers must be distinct and the target must be writable and large enough for the complete encoding.
    ///
    /// @param source the buffer supplying all remaining uncompressed bytes
    /// @param target the distinct writable buffer receiving the complete encoding
    /// @throws java.nio.BufferOverflowException when the target cannot hold the complete encoding
    /// @throws java.nio.ReadOnlyBufferException when the target is read-only
    /// @throws IllegalArgumentException when source and target are the same buffer
    /// @throws IOException if encoding or finalization fails
    default void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target);
    }

    /// Decompresses a complete encoding from source into target without operation-scoped limits.
    ///
    /// @param source the buffer supplying the compressed encoding
    /// @param target the distinct writable buffer receiving decoded bytes
    /// @throws java.nio.BufferOverflowException when the target cannot hold the complete decoded output
    /// @throws java.nio.ReadOnlyBufferException when the target is read-only
    /// @throws IllegalArgumentException when source and target are the same buffer
    /// @throws IOException if the encoding is invalid, truncated, or cannot be decoded
    default void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        decompress(source, target, DecompressionLimits.UNLIMITED);
    }

    /// Decompresses a complete encoding from source into target using operation-scoped limits.
    ///
    /// Both positions report consumed and produced bytes; both limits are restored before this method returns or throws.
    /// The buffers must be distinct and the target must be writable. A non-framed decoder stops at its first completed
    /// encoding, while a framed decoder consumes concatenated frames until source exhaustion.
    ///
    /// @param source the buffer supplying the compressed encoding
    /// @param target the distinct writable buffer receiving decoded bytes
    /// @param limits the output, window, and memory limits for this operation
    /// @throws java.nio.BufferOverflowException when the target cannot hold the decoded output
    /// @throws java.nio.ReadOnlyBufferException when the target is read-only
    /// @throws IllegalArgumentException when source and target are the same buffer
    /// @throws IOException if the encoding is invalid, truncated, or exceeds a configured limit
    default void decompress(
            ByteBuffer source,
            ByteBuffer target,
            DecompressionLimits limits
    ) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target, limits);
    }

    /// Describes a format whose encoder can flush pending output without ending the active encoding.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface Flushable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Creates a fresh flush-capable encoder.
        @Override
        CompressionEncoder.Flushable newEncoder() throws IOException;

        /// Creates a flush-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a flush-capable compressing channel that borrows the target channel.
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ResourceOwnership.BORROWED);
        }
    }

    /// Describes a format composed of independently terminated, concatenable frames.
    ///
    /// The one-shot `decompressFrame` methods accept an empty source range as a sequence containing no available frame;
    /// they succeed without producing output. A nonempty source must begin with a complete frame.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface Framed<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Creates a fresh frame-capable encoder.
        @Override
        CompressionEncoder.Framed newEncoder() throws IOException;

        /// Creates a fresh frame-capable decoder using operation-scoped safety limits.
        @Override
        CompressionDecoder.Framed newDecoder(DecompressionLimits limits) throws IOException;

        /// Creates a fresh frame-capable decoder without operation-scoped safety limits.
        @Override
        default CompressionDecoder.Framed newDecoder() throws IOException {
            return newDecoder(DecompressionLimits.UNLIMITED);
        }

        /// Creates a frame-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFramedWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a frame-capable compressing channel that borrows the target channel.
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ResourceOwnership.BORROWED);
        }

        /// Creates a frame-capable decompressing channel with limits and explicit source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                DecompressionLimits limits,
                ResourceOwnership ownership
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

        /// Creates a limited frame-capable decompressing channel that borrows the source channel.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                DecompressionLimits limits
        ) throws IOException {
            return newReadableByteChannel(source, limits, ResourceOwnership.BORROWED);
        }

        /// Creates an unlimited frame-capable decompressing channel with explicit source ownership.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                ResourceOwnership ownership
        ) throws IOException {
            return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ownership);
        }

        /// Creates an unlimited frame-capable decompressing channel that borrows the source channel.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source
        ) throws IOException {
            return newReadableByteChannel(source, DecompressionLimits.UNLIMITED, ResourceOwnership.BORROWED);
        }

        /// Decompresses one frame into a newly allocated bounded heap buffer.
        ///
        /// `maximumOutputSize` must be between zero and [Integer#MAX_VALUE]. If `source` has no remaining bytes, this
        /// operation returns an empty buffer without creating a decoder.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @param maximumOutputSize the finite maximum number of decoded frame bytes to allocate
        /// @return a newly allocated heap buffer containing the decoded frame
        /// @throws IOException if the frame is invalid, truncated, or exceeds a configured limit
        /// @throws IllegalArgumentException if `maximumOutputSize` is negative or exceeds [Integer#MAX_VALUE]
        default ByteBuffer decompressFrame(ByteBuffer source, long maximumOutputSize) throws IOException {
            return decompressFrame(source, DecompressionLimits.ofMaximumOutputSize(maximumOutputSize));
        }

        /// Decompresses one frame using operation-scoped limits.
        ///
        /// The source position stops after the first complete frame, preserving following frames or trailing bytes. The
        /// returned heap buffer has position zero and its limit equals the decoded frame size. Allocating decompression
        /// requires a finite `maximumOutputSize` between zero and [Integer#MAX_VALUE]. If `source` has no remaining
        /// bytes, this operation returns an empty buffer without creating a decoder.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @param limits the output, window, and memory limits for this frame
        /// @return a newly allocated heap buffer containing the decoded frame
        /// @throws IOException if the frame is invalid, truncated, or exceeds a configured limit
        /// @throws IllegalArgumentException if the maximum output limit is unlimited or exceeds [Integer#MAX_VALUE]
        default ByteBuffer decompressFrame(ByteBuffer source, DecompressionLimits limits) throws IOException {
            return ByteBufferCodecSupport.decompressFrameAllocating(this, source, limits);
        }

        /// Decompresses one frame into target without operation-scoped limits.
        ///
        /// If `source` has no remaining bytes, neither buffer position changes and no decoder is created.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @param target the distinct writable buffer receiving the decoded frame
        /// @throws java.nio.BufferOverflowException when the target cannot hold the complete frame
        /// @throws java.nio.ReadOnlyBufferException when the target is read-only
        /// @throws IllegalArgumentException when source and target are the same buffer
        /// @throws IOException if the frame is invalid, truncated, or cannot be decoded
        default void decompressFrame(ByteBuffer source, ByteBuffer target) throws IOException {
            decompressFrame(source, target, DecompressionLimits.UNLIMITED);
        }

        /// Decompresses one frame into target using operation-scoped limits.
        ///
        /// The source position stops after the first complete frame. Both buffer limits are unchanged, and the target
        /// must be distinct from the source, writable, and large enough for the complete decoded frame.
        /// If `source` has no remaining bytes, neither buffer position changes and no decoder is created.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @param target the distinct writable buffer receiving the decoded frame
        /// @param limits the output, window, and memory limits for this frame
        /// @throws java.nio.BufferOverflowException when the target cannot hold the complete frame
        /// @throws java.nio.ReadOnlyBufferException when the target is read-only
        /// @throws IllegalArgumentException when source and target are the same buffer
        /// @throws IOException if the frame is invalid, truncated, or exceeds a configured limit
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
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface FlushableFramed<C extends CompressionCodec<C>> extends Flushable<C>, Framed<C> {
        /// Creates a fresh frame- and flush-capable encoder.
        @Override
        CompressionEncoder.FlushableFramed newEncoder() throws IOException;

        /// Creates a frame- and flush-capable compressing channel with explicit target ownership.
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableFramedWritableByteChannel(target, ownership, this::newEncoder);
        }

        /// Creates a frame- and flush-capable compressing channel that borrows the target channel.
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, ResourceOwnership.BORROWED);
        }
    }

    /// Describes an immutable codec configuration with a selectable compression level.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface LevelConfigurable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Returns the configured compression level.
        ///
        /// @return the configured compression level
        long compressionLevel();

        /// Returns the minimum supported compression level.
        ///
        /// @return the inclusive minimum supported compression level
        long minimumCompressionLevel();

        /// Returns the maximum supported compression level.
        ///
        /// @return the inclusive maximum supported compression level
        long maximumCompressionLevel();

        /// Returns the format implementation's default compression level.
        ///
        /// @return the default compression level
        long defaultCompressionLevel();

        /// Returns an immutable codec configured with the requested compression level.
        ///
        /// @param compressionLevel the requested level within the inclusive supported range
        /// @return an immutable codec with the requested compression level
        /// @throws IllegalArgumentException if `compressionLevel` is outside the supported range
        C withCompressionLevel(long compressionLevel);
    }

    /// Describes an immutable codec configuration with a selectable generic compression strategy.
    ///
    /// @param <C> the concrete immutable codec type returned by configuration methods
    @NotNullByDefault
    interface StrategyConfigurable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Returns the configured compression strategy.
        ///
        /// @return the configured compression strategy
        CompressionStrategy compressionStrategy();

        /// Returns an immutable codec configured with the requested compression strategy.
        ///
        /// @param compressionStrategy the requested generic compression strategy
        /// @return an immutable codec with the requested compression strategy
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
        ///
        /// @return the configured dictionary, or `null` when none is configured
        @Nullable D dictionary();

        /// Returns an immutable codec configured with the requested dictionary.
        ///
        /// @param dictionary the format-specific dictionary to configure
        /// @return an immutable codec with `dictionary` configured
        C withDictionary(D dictionary);

        /// Returns an immutable codec configured without a dictionary.
        ///
        /// @return an immutable codec with dictionary-free operation selected
        C withoutDictionary();
    }

    /// Creates encoders that can receive an exact uncompressed source size as operation-scoped metadata.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
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
        ///
        /// @param pledgedSourceSize the exact nonnegative input size, or [#UNKNOWN_SIZE] when unknown
        /// @return a fresh encoder configured with the pledged input size
        /// @throws IOException if the encoder's resources cannot be initialized
        /// @throws IllegalArgumentException if `pledgedSourceSize` is less than [#UNKNOWN_SIZE]
        E newEncoder(long pledgedSourceSize) throws IOException;

        /// Creates a compressing channel with exact uncompressed source-size metadata and explicit target ownership.
        ///
        /// @param target the channel that receives compressed bytes
        /// @param pledgedSourceSize the exact nonnegative input size, or [#UNKNOWN_SIZE] when unknown
        /// @param ownership whether closing the returned channel also closes `target`
        /// @return a new compressing channel configured with the pledged input size
        /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
        /// @throws IllegalArgumentException if `pledgedSourceSize` is less than [#UNKNOWN_SIZE]
        default CompressingWritableByteChannel newWritableByteChannel(
                WritableByteChannel target,
                long pledgedSourceSize,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newWritableByteChannel(
                    target,
                    ownership,
                    () -> newEncoder(pledgedSourceSize)
            );
        }

        /// Creates a compressing channel with exact source-size metadata that borrows the target channel.
        ///
        /// @param target the channel that receives compressed bytes and remains open after the returned channel closes
        /// @param pledgedSourceSize the exact nonnegative input size, or [#UNKNOWN_SIZE] when unknown
        /// @return a new compressing channel configured with the pledged input size
        /// @throws IOException if the encoder cannot be initialized
        /// @throws IllegalArgumentException if `pledgedSourceSize` is less than [#UNKNOWN_SIZE]
        default CompressingWritableByteChannel newWritableByteChannel(
                WritableByteChannel target,
                long pledgedSourceSize
        ) throws IOException {
            return newWritableByteChannel(target, pledgedSourceSize, ResourceOwnership.BORROWED);
        }

        /// Creates an encoder without a known source size.
        @Override
        default E newEncoder() throws IOException {
            return newEncoder(UNKNOWN_SIZE);
        }
    }
}
