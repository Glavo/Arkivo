// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.ByteBufferCodecSupport;
import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.glavo.arkivo.codec.internal.StreamChannelAdapters;
import org.glavo.arkivo.codec.internal.CodecChannelAdapters;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;

/// Describes a compression algorithm and creates configured buffer engines and channel adapters.
@NotNullByDefault
public interface CompressionCodec {
    /// The sentinel returned when a size cannot be calculated or is not known.
    long UNKNOWN_SIZE = -1L;

    /// Returns the stable codec name.
    String name();

    /// Returns alternative stable names accepted for this codec.
    default @Unmodifiable List<String> aliases() {
        return List.of();
    }

    /// Returns common file extensions for streams encoded by this codec, without leading dots.
    default @Unmodifiable List<String> fileExtensions() {
        return List.of(name());
    }

    /// Returns the immutable capability and option description.
    CompressionCapabilities capabilities();

    /// Returns whether this codec can compress uncompressed bytes.
    default boolean canCompress() {
        return capabilities().supports(CompressionFeature.COMPRESSION);
    }

    /// Returns whether this codec can decompress compressed bytes.
    default boolean canDecompress() {
        return capabilities().supports(CompressionFeature.DECOMPRESSION);
    }

    /// Returns whether this codec supports ByteBuffer one-shot compression.
    default boolean canCompressBuffers() {
        return capabilities().supports(CompressionFeature.ONE_SHOT_COMPRESSION);
    }

    /// Returns whether this codec supports ByteBuffer one-shot decompression.
    default boolean canDecompressBuffers() {
        return capabilities().supports(CompressionFeature.ONE_SHOT_DECOMPRESSION);
    }

    /// Returns the preferred number of leading bytes requested by generic codec detection.
    ///
    /// A codec may recognize a prefix containing fewer bytes. The returned value must not be negative.
    default int probeSize() {
        return 0;
    }

    /// Returns whether the given byte prefix matches this codec's stream signature.
    ///
    /// The returned value is `false` when the codec has no reliable fixed signature or the prefix is too short.
    default boolean matches(ByteBuffer prefix) {
        Objects.requireNonNull(prefix, "prefix");
        return false;
    }

    /// Returns the maximum compressed size for an input of `sourceSize` bytes, or `UNKNOWN_SIZE` when unknown.
    default long maxCompressedSize(long sourceSize) {
        if (sourceSize < 0) {
            throw new IllegalArgumentException("sourceSize must not be negative");
        }
        return UNKNOWN_SIZE;
    }

    /// Returns the minimum supported compression level.
    ///
    /// The default implementation rejects codecs that do not advertise compression-level configuration.
    default long minimumCompressionLevel() {
        throw new UnsupportedOperationException("Compression levels are not supported by " + name());
    }

    /// Returns the maximum supported compression level.
    ///
    /// The default implementation rejects codecs that do not advertise compression-level configuration.
    default long maximumCompressionLevel() {
        throw new UnsupportedOperationException("Compression levels are not supported by " + name());
    }

    /// Returns the codec's default compression level.
    ///
    /// The default implementation rejects codecs that do not advertise compression-level configuration.
    default long defaultCompressionLevel() {
        throw new UnsupportedOperationException("Compression levels are not supported by " + name());
    }

    /// Creates a configured transport-independent encoder.
    ///
    /// The default implementation rejects codecs that do not advertise CompressionFeature.BUFFER_COMPRESSION.
    default CompressionEncoder newEncoder(CodecOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        throw new UnsupportedOperationException("Buffer compression is not supported by " + name());
    }

    /// Creates a default transport-independent encoder.
    default CompressionEncoder newEncoder() throws IOException {
        return newEncoder(CodecOptions.EMPTY);
    }

    /// Creates a configured transport-independent decoder.
    ///
    /// The default implementation rejects codecs that do not advertise CompressionFeature.BUFFER_DECOMPRESSION.
    default CompressionDecoder newDecoder(CodecOptions options) throws IOException {
        Objects.requireNonNull(options, "options");
        throw new UnsupportedOperationException("Buffer decompression is not supported by " + name());
    }

    /// Creates a default transport-independent decoder.
    default CompressionDecoder newDecoder() throws IOException {
        return newDecoder(CodecOptions.EMPTY);
    }

    /// Opens a configured encoder context over the target channel.
    default CompressingWritableByteChannel openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.openEncoder(target, ownership, () -> newEncoder(options));
    }

    /// Opens a default encoder and retains ownership of the target channel.
    default CompressingWritableByteChannel openEncoder(WritableByteChannel target) throws IOException {
        return openEncoder(target, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a configured decoder context over the source channel.
    default DecompressingReadableByteChannel openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(ownership, "ownership");
        return CodecChannelAdapters.openDecoder(source, ownership, () -> newDecoder(options));
    }

    /// Opens a default decoder and retains ownership of the source channel.
    default DecompressingReadableByteChannel openDecoder(ReadableByteChannel source) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a compatibility channel that closes its target when compression finishes.
    default WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return openEncoder(target, CodecOptions.EMPTY, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its target when compression finishes.
    default OutputStream compressTo(OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        return StreamChannelAdapters.outputStream(
                openEncoder(
                        StreamChannelAdapters.writableChannel(target),
                        CodecOptions.EMPTY,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Opens a compatibility channel that closes its source with the decoder.
    default ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its source with the decoder.
    default InputStream decompressFrom(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        return StreamChannelAdapters.inputStream(
                openDecoder(
                        StreamChannelAdapters.readableChannel(source),
                        CodecOptions.EMPTY,
                        ChannelOwnership.CLOSE
                )
        );
    }

    /// Compresses all bytes from the source channel to the target channel without closing either channel.
    default CodecTransferResult compress(
            ReadableByteChannel source,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        return CodecTransferSupport.compress(this, source, target, options);
    }

    /// Compresses all bytes with default options without closing either channel.
    default CodecTransferResult compress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return compress(source, target, CodecOptions.EMPTY);
    }

    /// Decompresses all bytes from the source channel to the target channel without closing either channel.
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target,
            CodecOptions options
    ) throws IOException {
        return CodecTransferSupport.decompress(this, source, target, options);
    }

    /// Decompresses all bytes with default options without closing either channel.
    default CodecTransferResult decompress(
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        return decompress(source, target, CodecOptions.EMPTY);
    }

    /// Compresses all remaining source bytes into a newly allocated heap buffer.
    ///
    /// The source is advanced by the number of bytes consumed. The returned buffer has position zero and its limit
    /// equals the compressed size.
    default ByteBuffer compress(ByteBuffer source) throws IOException {
        return compress(source, CodecOptions.EMPTY);
    }

    /// Compresses all remaining source bytes with options into a newly allocated heap buffer.
    ///
    /// The source is advanced by the number of bytes consumed. The returned buffer has position zero and its limit
    /// equals the compressed size.
    default ByteBuffer compress(ByteBuffer source, CodecOptions options) throws IOException {
        return ByteBufferCodecSupport.compressAllocating(this, source, options);
    }

    /// Decompresses all remaining source bytes into a newly allocated bounded heap buffer.
    ///
    /// The source is advanced by the number of bytes consumed. The returned buffer has position zero and its limit
    /// equals the decompressed size.
    default ByteBuffer decompress(ByteBuffer source, long maximumOutputSize) throws IOException {
        return decompress(source, maximumOutputSize, CodecOptions.EMPTY);
    }

    /// Decompresses all remaining source bytes with options into a newly allocated bounded heap buffer.
    ///
    /// `maximumOutputSize` must fit in a Java `ByteBuffer`. Output exceeding the limit causes a
    /// `DecompressionLimitException`.
    default ByteBuffer decompress(
            ByteBuffer source,
            long maximumOutputSize,
            CodecOptions options
    ) throws IOException {
        return ByteBufferCodecSupport.decompressAllocating(
                this,
                source,
                maximumOutputSize,
                options
        );
    }

    /// Decompresses one frame into a newly allocated bounded heap buffer.
    ///
    /// The source position stops after the first complete frame, preserving following frames or trailing bytes.
    default ByteBuffer decompressFrame(ByteBuffer source, long maximumOutputSize) throws IOException {
        return decompressFrame(source, maximumOutputSize, CodecOptions.EMPTY);
    }

    /// Decompresses one configured frame into a newly allocated bounded heap buffer.
    ///
    /// The source position stops after the first complete frame, preserving following frames or trailing bytes.
    default ByteBuffer decompressFrame(
            ByteBuffer source,
            long maximumOutputSize,
            CodecOptions options
    ) throws IOException {
        return ByteBufferCodecSupport.decompressFrameAllocating(
                this,
                source,
                maximumOutputSize,
                options
        );
    }

    /// Compresses all remaining bytes from `source` into `target`.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    default void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target);
    }

    /// Compresses all remaining source bytes into the target with operation-specific options.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    default void compress(ByteBuffer source, ByteBuffer target, CodecOptions options) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target, options);
    }

    /// Decompresses all remaining bytes from `source` into `target`.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    default void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target);
    }

    /// Decompresses all remaining source bytes into the target with operation-specific options.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    default void decompress(ByteBuffer source, ByteBuffer target, CodecOptions options) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target, options);
    }

    /// Decompresses one frame into the target buffer.
    ///
    /// The source position stops after the first complete frame. The target must hold the complete decoded frame.
    default void decompressFrame(ByteBuffer source, ByteBuffer target) throws IOException {
        decompressFrame(source, target, CodecOptions.EMPTY);
    }

    /// Decompresses one configured frame into the target buffer.
    ///
    /// The source position stops after the first complete frame. The target must hold the complete decoded frame.
    default void decompressFrame(
            ByteBuffer source,
            ByteBuffer target,
            CodecOptions options
    ) throws IOException {
        ByteBufferCodecSupport.decompressFrame(this, source, target, options);
    }
}
