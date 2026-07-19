// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.ByteBufferCodecSupport;
import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.internal.CodecChannelAdapters;
import org.glavo.arkivo.codec.internal.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Describes an immutable compression-format configuration and creates transport-independent engines.
///
/// Implementations must be safe for concurrent use. Stateful encoding and decoding progress belongs exclusively to
/// engines and channel or stream contexts created by a codec; those operation objects are not safe for concurrent use.
///
/// Endpoint factories validate arguments before applying [ResourceOwnership]. During ordinary idle closure, `BORROWED`
/// releases codec state but leaves the endpoint open, while `OWNED` also closes it after codec finalization or release.
/// Setup failure closes an owned endpoint without hiding the primary failure.
///
/// Channel-to-channel convenience operations are blocking, reject zero-progress transports with `IOException`, and
/// leave both caller channels open. One-shot buffer operations never retain buffers or change their limits. They advance
/// positions to report partial as well as successful progress.
///
/// The default channel factories preserve [InterruptibleChannel]: their result implements it exactly when the supplied
/// channel does. Interrupting an active operation or closing its context concurrently is terminal and can close a
/// borrowed endpoint, as specified by [CompressingWritableByteChannel] and [DecompressingReadableByteChannel].
///
/// @param <C> the concrete immutable codec type represented by this configuration
@NotNullByDefault
public interface CompressionCodec<C extends CompressionCodec<C>> {
    /// The sentinel returned when a size cannot be calculated or is not known.
    long UNKNOWN_SIZE = -1L;

    /// The sentinel used for an unrestricted decoding size.
    long UNLIMITED_SIZE = -1L;

    /// Returns the maximum decoded byte count permitted by decoding operations created from this codec.
    ///
    /// A decoder engine applies this limit to the current encoding and starts a fresh count when reset. Higher-level
    /// channel, stream, transfer, and complete-buffer operations apply it across the complete operation, including all
    /// concatenated frames.
    ///
    /// @return the nonnegative decoded-output limit, or [#UNLIMITED_SIZE] when unrestricted
    long maximumOutputSize();

    /// Returns the maximum algorithm history-window size permitted while decoding.
    ///
    /// @return the nonnegative history-window limit, or [#UNLIMITED_SIZE] when unrestricted
    long maximumWindowSize();

    /// Returns the maximum codec-accounted working-memory size permitted while decoding.
    ///
    /// This is neither a JVM allocation budget nor a guarantee that every allocation made while decoding is included.
    /// Each codec documents the format structures and allocations it can account for.
    ///
    /// @return the nonnegative decoder-memory limit, or [#UNLIMITED_SIZE] when unrestricted
    long maximumMemorySize();

    /// Returns an immutable codec configured with the requested decoded-output limit.
    ///
    /// @param maximumOutputSize the nonnegative decoded-output limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a codec with the requested limit
    /// @throws IllegalArgumentException if `maximumOutputSize` is less than [#UNLIMITED_SIZE]
    C withMaximumOutputSize(long maximumOutputSize);

    /// Returns an immutable codec configured with the requested history-window limit.
    ///
    /// @param maximumWindowSize the nonnegative history-window limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a codec with the requested limit
    /// @throws IllegalArgumentException if `maximumWindowSize` is less than [#UNLIMITED_SIZE]
    C withMaximumWindowSize(long maximumWindowSize);

    /// Returns an immutable codec configured with the requested decoder-memory limit.
    ///
    /// @param maximumMemorySize the nonnegative decoder-memory limit, or [#UNLIMITED_SIZE]
    /// @return this instance when unchanged, otherwise a codec with the requested limit
    /// @throws IllegalArgumentException if `maximumMemorySize` is less than [#UNLIMITED_SIZE]
    C withMaximumMemorySize(long maximumMemorySize);

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

    /// Creates a fresh transport-independent encoder using operation-scoped options.
    ///
    /// Each successful call returns independent mutable state owned by the caller. Closing the engine does not affect
    /// this codec or engines created by other calls. For a framed encoder, [EncodingOptions#sourceSize()] describes the
    /// initial frame; later frames receive independent options through
    /// [CompressionEncoder.Framed#startFrame(EncodingOptions)] or default options when started implicitly.
    ///
    /// @param options the parameters for this encoder session
    /// @return a fresh encoder with independent mutable state
    /// @throws IOException if the encoder's resources cannot be initialized
    CompressionEncoder newEncoder(EncodingOptions options) throws IOException;

    /// Creates a fresh encoder using default operation options.
    ///
    /// @return a fresh encoder with independent mutable state
    /// @throws IOException if the encoder's resources cannot be initialized
    default CompressionEncoder newEncoder() throws IOException {
        return newEncoder(EncodingOptions.DEFAULT);
    }

    /// Creates a fresh transport-independent decoder using this codec's immutable configuration.
    ///
    /// The returned engine owns its algorithm resources but no caller buffer or transport. Codecs enforce the finite
    /// safety limits applicable to the structures and allocations they can account for and document format-specific
    /// exclusions. The configured limits are restored when the engine itself is reset.
    ///
    /// @return a fresh decoder with independent mutable state
    /// @throws IOException if the decoder's resources cannot be initialized
    CompressionDecoder newDecoder() throws IOException;

    /// Creates a compressing channel using operation options and explicit target ownership.
    ///
    /// @param target    the channel that receives compressed bytes
    /// @param options   the parameters for this encoding session
    /// @param ownership whether closing the returned channel also closes `target`
    /// @return a new compressing channel
    /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
    default CompressingWritableByteChannel newWritableByteChannel(
            WritableByteChannel target,
            EncodingOptions options,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.newWritableByteChannel(target, ownership, () -> newEncoder(options));
    }

    /// Creates a compressing channel using default options and borrowing the target channel.
    ///
    /// @param target the channel that receives compressed bytes and remains open after the returned channel closes
    /// @return a new compressing channel
    /// @throws IOException if the encoder cannot be initialized
    default CompressingWritableByteChannel newWritableByteChannel(WritableByteChannel target) throws IOException {
        return newWritableByteChannel(target, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing channel using this codec's configuration and explicit source ownership.
    ///
    /// @param source    the channel that supplies compressed bytes
    /// @param ownership whether closing the returned channel also closes `source`
    /// @return a new decompressing channel
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default DecompressingReadableByteChannel newReadableByteChannel(
            ReadableByteChannel source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");

        // Enforce output across the complete channel session rather than resetting the limit at each frame.
        CompressionCodec<?> engineCodec = withMaximumOutputSize(UNLIMITED_SIZE);
        DecompressingReadableByteChannel decoder = CodecChannelAdapters.newReadableByteChannel(
                source,
                ownership,
                engineCodec::newDecoder
        );
        return CompressionDecoderSupport.limitChannelOutput(decoder, maximumOutputSize());
    }

    /// Creates a decompressing channel using default options and borrowing the source channel.
    ///
    /// @param source the channel that supplies compressed bytes and remains open after the returned channel closes
    /// @return a new decompressing channel
    /// @throws IOException if the decoder cannot be initialized
    default DecompressingReadableByteChannel newReadableByteChannel(ReadableByteChannel source) throws IOException {
        return newReadableByteChannel(source, ResourceOwnership.BORROWED);
    }

    /// Creates a compressing output stream using operation options and explicit target ownership.
    ///
    /// @param target    the stream that receives compressed bytes
    /// @param options   the parameters for this encoding session
    /// @param ownership whether closing the returned stream also closes `target`
    /// @return a new compressing output stream
    /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
    default OutputStream newOutputStream(
            OutputStream target,
            EncodingOptions options,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.outputStream(
                newWritableByteChannel(StreamChannelAdapters.writableChannel(target), options, ownership)
        );
    }

    /// Creates a compressing output stream using default options and borrowing the target stream.
    ///
    /// @param target the stream that receives compressed bytes and remains open after the returned stream closes
    /// @return a new compressing output stream
    /// @throws IOException if the encoder cannot be initialized
    default OutputStream newOutputStream(OutputStream target) throws IOException {
        return newOutputStream(target, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED);
    }

    /// Creates a decompressing input stream using this codec's configuration and explicit source ownership.
    ///
    /// @param source    the stream that supplies compressed bytes
    /// @param ownership whether closing the returned stream also closes `source`
    /// @return a new decompressing input stream
    /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
    default InputStream newInputStream(
            InputStream source,
            ResourceOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownership, "ownership");
        return StreamChannelAdapters.inputStream(
                newReadableByteChannel(StreamChannelAdapters.readableChannel(source), ownership)
        );
    }

    /// Creates a decompressing input stream using default options and borrowing the source stream.
    ///
    /// @param source the stream that supplies compressed bytes and remains open after the returned stream closes
    /// @return a new decompressing input stream
    /// @throws IOException if the decoder cannot be initialized
    default InputStream newInputStream(InputStream source) throws IOException {
        return newInputStream(source, ResourceOwnership.BORROWED);
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
        return compress(source, target, EncodingOptions.DEFAULT);
    }

    /// Compresses bytes with operation-scoped options without closing either channel.
    ///
    /// @param source  the channel supplying uncompressed bytes until end-of-input
    /// @param target  the channel receiving the complete compressed encoding
    /// @param options the parameters for this encoding operation
    /// @return the uncompressed input and compressed output byte counts
    /// @throws IOException if channel I/O, encoding, finalization, or transport progress fails
    default CodecTransferResult compress(
            ReadableByteChannel source,
            WritableByteChannel target,
            EncodingOptions options
    ) throws IOException {
        return CodecTransferSupport.compress(this, source, target, options);
    }

    /// Decompresses through the logical end of compressed input without transferring channel ownership.
    ///
    /// Framed codecs continue across concatenated frames until physical source EOF. A decoder may read ahead past a
    /// logical frame boundary; use a readable channel context and [DecompressingReadableByteChannel#unconsumedInput()]
    /// when trailing compressed bytes must be recovered.
    ///
    /// @param source the channel supplying compressed bytes
    /// @param target the channel receiving decoded bytes
    /// @return the compressed input and decoded output byte counts
    /// @throws IOException if channel I/O, decoding, a configured limit, or transport progress fails
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return CodecTransferSupport.decompress(this, source, target);
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

    /// Decompresses all remaining source bytes using this codec's configured limits.
    ///
    /// Allocating decompression requires a finite `maximumOutputSize` between zero and [Integer#MAX_VALUE]. The returned
    /// heap buffer has position zero and its limit equals the decoded size. A non-framed decoder leaves trailing bytes
    /// after its first completed encoding unconsumed; a framed decoder treats remaining input as concatenated frames.
    ///
    /// @param source the buffer supplying the compressed encoding
    /// @return a newly allocated heap buffer containing the decoded bytes
    /// @throws IOException              if the encoding is invalid, truncated, or exceeds a configured limit
    /// @throws IllegalArgumentException if the maximum output limit is unlimited or exceeds [Integer#MAX_VALUE]
    default ByteBuffer decompress(ByteBuffer source) throws IOException {
        return ByteBufferCodecSupport.decompressAllocating(this, source);
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
    /// @throws IllegalArgumentException         when source and target are the same buffer
    /// @throws IOException                      if encoding or finalization fails
    default void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target);
    }

    /// Decompresses a complete encoding from source into target using this codec's configured limits.
    ///
    /// Both positions report consumed and produced bytes; both limits are restored before this method returns or throws.
    /// The buffers must be distinct and the target must be writable. A non-framed decoder stops at its first completed
    /// encoding, while a framed decoder consumes concatenated frames until source exhaustion.
    ///
    /// @param source the buffer supplying the compressed encoding
    /// @param target the distinct writable buffer receiving decoded bytes
    /// @throws java.nio.BufferOverflowException when the target cannot hold the decoded output
    /// @throws java.nio.ReadOnlyBufferException when the target is read-only
    /// @throws IllegalArgumentException         when source and target are the same buffer
    /// @throws IOException                      if the encoding is invalid, truncated, or exceeds a configured limit
    default void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target);
    }

    /// Describes a format whose encoder can flush pending output without ending the active encoding.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface Flushable<C extends CompressionCodec<C>> extends CompressionCodec<C> {
        /// Creates a fresh flush-capable encoder using operation-scoped options.
        ///
        /// @param options the parameters for this encoder session
        /// @return a fresh flush-capable encoder
        /// @throws IOException if the encoder's resources cannot be initialized
        @Override
        CompressionEncoder.Flushable newEncoder(EncodingOptions options) throws IOException;

        /// Creates a fresh flush-capable encoder using default operation options.
        @Override
        default CompressionEncoder.Flushable newEncoder() throws IOException {
            return newEncoder(EncodingOptions.DEFAULT);
        }

        /// Creates a flush-capable channel using operation options and explicit target ownership.
        ///
        /// @param target    the channel that receives compressed bytes
        /// @param options   the parameters for this encoding session
        /// @param ownership whether closing the returned channel also closes `target`
        /// @return a new flush-capable compressing channel
        /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target,
                EncodingOptions options,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableWritableByteChannel(
                    target,
                    ownership,
                    () -> newEncoder(options)
            );
        }

        /// Creates a flush-capable channel using default options and borrowing the target channel.
        @Override
        default CompressingWritableByteChannel.Flushable newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED);
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
        /// Creates a fresh frame-capable encoder using operation-scoped options.
        ///
        /// [EncodingOptions#sourceSize()] describes the initial frame only. Frames started after
        /// [CompressionEncoder.Framed#finishFrame(ByteBuffer)] do not inherit that source size and may be configured
        /// independently through [CompressionEncoder.Framed#startFrame(EncodingOptions)].
        ///
        /// @param options the parameters for this encoder session
        /// @return a fresh frame-capable encoder
        /// @throws IOException if the encoder's resources cannot be initialized
        @Override
        CompressionEncoder.Framed newEncoder(EncodingOptions options) throws IOException;

        /// Creates a fresh frame-capable encoder using default operation options.
        @Override
        default CompressionEncoder.Framed newEncoder() throws IOException {
            return newEncoder(EncodingOptions.DEFAULT);
        }

        /// Creates a fresh frame-capable decoder using this codec's immutable configuration.
        ///
        /// @return a fresh frame-capable decoder
        /// @throws IOException if the decoder's resources cannot be initialized
        @Override
        CompressionDecoder.Framed newDecoder() throws IOException;

        /// Creates a frame-capable channel using operation options and explicit target ownership.
        ///
        /// @param target    the channel that receives compressed bytes
        /// @param options   the parameters for this encoding session
        /// @param ownership whether closing the returned channel also closes `target`
        /// @return a new frame-capable compressing channel
        /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target,
                EncodingOptions options,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFramedWritableByteChannel(
                    target,
                    ownership,
                    () -> newEncoder(options)
            );
        }

        /// Creates a frame-capable channel using default options and borrowing the target channel.
        @Override
        default CompressingWritableByteChannel.Framed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED);
        }

        /// Creates a frame-capable decompressing channel using this codec's configuration and source ownership.
        ///
        /// @param source    the channel that supplies compressed bytes
        /// @param ownership whether closing the returned channel also closes `source`
        /// @return a new frame-capable decompressing channel
        /// @throws IOException if the decoder cannot be initialized or ownership cleanup fails
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(ownership, "ownership");

            CompressionCodec<?> engineCodec = withMaximumOutputSize(UNLIMITED_SIZE);
            DecompressingReadableByteChannel.Framed decoder = CodecChannelAdapters.newFramedReadableByteChannel(
                    source,
                    ownership,
                    () -> (CompressionDecoder.Framed) engineCodec.newDecoder()
            );
            return CompressionDecoderSupport.limitChannelOutput(decoder, maximumOutputSize());
        }

        /// Creates a frame-capable decompressing channel using default options and borrowing the source channel.
        @Override
        default DecompressingReadableByteChannel.Framed newReadableByteChannel(
                ReadableByteChannel source
        ) throws IOException {
            return newReadableByteChannel(source, ResourceOwnership.BORROWED);
        }

        /// Decompresses one frame using this codec's configured limits.
        ///
        /// The source position stops after the first complete frame, preserving following frames or trailing bytes. The
        /// returned heap buffer has position zero and its limit equals the decoded frame size. Allocating decompression
        /// requires a finite `maximumOutputSize` between zero and [Integer#MAX_VALUE]. If `source` has no remaining
        /// bytes, this operation returns an empty buffer without creating a decoder.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @return a newly allocated heap buffer containing the decoded frame
        /// @throws IOException              if the frame is invalid, truncated, or exceeds a configured limit
        /// @throws IllegalArgumentException if the maximum output limit is unlimited or exceeds [Integer#MAX_VALUE]
        default ByteBuffer decompressFrame(ByteBuffer source) throws IOException {
            return ByteBufferCodecSupport.decompressFrameAllocating(this, source);
        }

        /// Decompresses one frame into target using this codec's configured limits.
        ///
        /// The source position stops after the first complete frame. Both buffer limits are unchanged, and the target
        /// must be distinct from the source, writable, and large enough for the complete decoded frame.
        /// If `source` has no remaining bytes, neither buffer position changes and no decoder is created.
        ///
        /// @param source the buffer beginning with the compressed frame, or an empty buffer representing no frame
        /// @param target the distinct writable buffer receiving the decoded frame
        /// @throws java.nio.BufferOverflowException when the target cannot hold the complete frame
        /// @throws java.nio.ReadOnlyBufferException when the target is read-only
        /// @throws IllegalArgumentException         when source and target are the same buffer
        /// @throws IOException                      if the frame is invalid, truncated, or exceeds a configured limit
        default void decompressFrame(ByteBuffer source, ByteBuffer target) throws IOException {
            ByteBufferCodecSupport.decompressFrame(this, source, target);
        }
    }

    /// Describes a framed format with a terminal index that maps compressed frames to logical offsets.
    ///
    /// Seekable encodings remain valid concatenated-frame streams for sequential decoders. Random access requires the
    /// terminal index returned by [#readIndex(SeekableByteChannel)]. An absent index is distinct from a
    /// malformed recognized index: absence returns `null`, while malformed index data fails with `IOException`.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface Seekable<C extends CompressionCodec<C>> extends Framed<C> {
        /// Returns whether this concrete codec configuration can produce and consume its seekable representation.
        ///
        /// A format may expose nonstandard physical configurations that cannot carry its interoperable index. Such a
        /// configuration returns `false` and rejects the seekable factories while retaining ordinary framed operation.
        ///
        /// @return whether seekable operations are available for this configuration
        default boolean supportsSeekableEncoding() {
            return true;
        }

        /// Creates a channel that writes independently decodable frames followed by a random-access index.
        ///
        /// Closing or finishing the returned channel writes the index after the final frame. A source with no bytes
        /// still produces a complete indexed encoding. The result implements [InterruptibleChannel] when `target`
        /// does. Calling `finishFrame` may end the active frame before the configured maximum size; it does not finish
        /// the complete indexed encoding.
        ///
        /// @param target    the channel that receives the complete seekable encoding
        /// @param options   the logical source metadata and frame-size policy
        /// @param ownership whether closing the returned channel also closes `target`
        /// @return a new indexed compressing channel
        /// @throws IOException if encoder initialization or owned-target cleanup fails
        CompressingWritableByteChannel.Framed newSeekableWritableByteChannel(
                WritableByteChannel target,
                SeekableEncodingOptions options,
                ResourceOwnership ownership
        ) throws IOException;

        /// Creates a seekable compressing channel using default options and borrowing the target.
        ///
        /// @param target the channel that receives the complete seekable encoding and remains open after closure
        /// @return a new indexed compressing channel
        /// @throws IOException if encoder initialization fails
        default CompressingWritableByteChannel.Framed newSeekableWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newSeekableWritableByteChannel(
                    target,
                    SeekableEncodingOptions.DEFAULT,
                    ResourceOwnership.BORROWED
            );
        }

        /// Reads and validates the terminal random-access index at the source's current logical origin.
        ///
        /// The source position is restored before this method returns or throws. The returned immutable index does not
        /// retain `source`; it can be shared safely by channels created for independent copies or views of the same
        /// encoded byte sequence.
        ///
        /// @param source the seekable encoded source from its current origin through its current size
        /// @return the immutable index, or `null` when no recognized terminal index is present
        /// @throws IOException if source I/O fails or a recognized index is malformed or exceeds a configured limit
        @Nullable Index readIndex(SeekableByteChannel source) throws IOException;

        /// Describes one validated immutable mapping between compressed frames and logical offsets.
        @NotNullByDefault
        interface Index {
            /// Returns the encoded byte count covered by this index, including the terminal index representation.
            ///
            /// @return the complete indexed encoding size
            long compressedSize();

            /// Returns the complete logical uncompressed size.
            ///
            /// @return the logical decoded byte count
            long uncompressedSize();

            /// Returns the number of independently decodable frames.
            ///
            /// @return the non-negative frame count
            int frameCount();

            /// Returns one frame's compressed offset relative to the indexed encoding origin.
            ///
            /// @param frameIndex the zero-based frame index
            /// @return the frame's relative compressed offset
            /// @throws IndexOutOfBoundsException if `frameIndex` is outside the frame sequence
            long frameCompressedOffset(int frameIndex);

            /// Returns one frame's compressed byte count.
            ///
            /// @param frameIndex the zero-based frame index
            /// @return the frame's compressed size
            /// @throws IndexOutOfBoundsException if `frameIndex` is outside the frame sequence
            long frameCompressedSize(int frameIndex);

            /// Returns one frame's logical uncompressed offset.
            ///
            /// @param frameIndex the zero-based frame index
            /// @return the frame's relative uncompressed offset
            /// @throws IndexOutOfBoundsException if `frameIndex` is outside the frame sequence
            long frameUncompressedOffset(int frameIndex);

            /// Returns one frame's uncompressed byte count.
            ///
            /// @param frameIndex the zero-based frame index
            /// @return the frame's uncompressed size
            /// @throws IndexOutOfBoundsException if `frameIndex` is outside the frame sequence
            long frameUncompressedSize(int frameIndex);

            /// Creates a read-only logical channel over the encoded source at its current origin.
            ///
            /// The bytes from the source's current position through its size must be the same complete encoding from
            /// which this index was read. The returned channel has an independent logical position, decodes frames on
            /// demand, and implements [InterruptibleChannel] when `source` does.
            ///
            /// @param source    the complete seekable encoding at its current logical origin
            /// @param ownership whether closing the returned channel also closes `source`
            /// @return a new read-only logical seekable channel
            /// @throws IOException if source validation or decoder initialization fails
            SeekableByteChannel newReadableByteChannel(
                    SeekableByteChannel source,
                    ResourceOwnership ownership
            ) throws IOException;

            /// Creates a read-only logical channel that borrows the encoded source.
            ///
            /// @param source the complete seekable encoding at its current logical origin
            /// @return a new read-only logical seekable channel
            /// @throws IOException if source validation or decoder initialization fails
            default SeekableByteChannel newReadableByteChannel(SeekableByteChannel source) throws IOException {
                return newReadableByteChannel(source, ResourceOwnership.BORROWED);
            }
        }
    }

    /// Describes a framed format whose encoder also supports nonterminal flushing.
    ///
    /// @param <C> the concrete immutable codec type represented by this configuration
    @NotNullByDefault
    interface FlushableFramed<C extends CompressionCodec<C>> extends Flushable<C>, Framed<C> {
        /// Creates a fresh frame- and flush-capable encoder using operation-scoped options.
        ///
        /// @param options the parameters for this encoder session
        /// @return a fresh frame- and flush-capable encoder
        /// @throws IOException if the encoder's resources cannot be initialized
        @Override
        CompressionEncoder.FlushableFramed newEncoder(EncodingOptions options) throws IOException;

        /// Creates a fresh frame- and flush-capable encoder using default operation options.
        @Override
        default CompressionEncoder.FlushableFramed newEncoder() throws IOException {
            return newEncoder(EncodingOptions.DEFAULT);
        }

        /// Creates a frame- and flush-capable channel using operation options and explicit target ownership.
        ///
        /// @param target    the channel that receives compressed bytes
        /// @param options   the parameters for this encoding session
        /// @param ownership whether closing the returned channel also closes `target`
        /// @return a new frame- and flush-capable compressing channel
        /// @throws IOException if the encoder cannot be initialized or ownership cleanup fails
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target,
                EncodingOptions options,
                ResourceOwnership ownership
        ) throws IOException {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(options, "options");
            Objects.requireNonNull(ownership, "ownership");
            return CodecChannelAdapters.newFlushableFramedWritableByteChannel(
                    target,
                    ownership,
                    () -> newEncoder(options)
            );
        }

        /// Creates a frame- and flush-capable channel using default options and borrowing the target channel.
        @Override
        default CompressingWritableByteChannel.FlushableFramed newWritableByteChannel(
                WritableByteChannel target
        ) throws IOException {
            return newWritableByteChannel(target, EncodingOptions.DEFAULT, ResourceOwnership.BORROWED);
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

}
