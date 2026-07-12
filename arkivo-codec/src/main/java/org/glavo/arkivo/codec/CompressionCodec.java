// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec;

import org.glavo.arkivo.codec.internal.ByteBufferCodecSupport;
import org.glavo.arkivo.codec.internal.CodecTransferSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;

/// Describes a compression algorithm and creates configured channel contexts.
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

    /// Opens a configured encoder context over the target channel.
    CompressionEncoder openEncoder(
            WritableByteChannel target,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException;

    /// Opens a default encoder and retains ownership of the target channel.
    default CompressionEncoder openEncoder(WritableByteChannel target) throws IOException {
        return openEncoder(target, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a configured decoder context over the source channel.
    CompressionDecoder openDecoder(
            ReadableByteChannel source,
            CodecOptions options,
            ChannelOwnership ownership
    ) throws IOException;

    /// Opens a default decoder and retains ownership of the source channel.
    default CompressionDecoder openDecoder(ReadableByteChannel source) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ChannelOwnership.RETAIN);
    }

    /// Opens a compatibility channel that closes its target when compression finishes.
    default WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return openEncoder(target, CodecOptions.EMPTY, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its target when compression finishes.
    default OutputStream compressTo(OutputStream target) throws IOException {
        Objects.requireNonNull(target, "target");
        return Channels.newOutputStream(
                openEncoder(Channels.newChannel(target), CodecOptions.EMPTY, ChannelOwnership.CLOSE)
        );
    }

    /// Opens a compatibility channel that closes its source with the decoder.
    default ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return openDecoder(source, CodecOptions.EMPTY, ChannelOwnership.CLOSE);
    }

    /// Opens a compatibility stream that closes its source with the decoder.
    default InputStream decompressFrom(InputStream source) throws IOException {
        Objects.requireNonNull(source, "source");
        return Channels.newInputStream(
                openDecoder(Channels.newChannel(source), CodecOptions.EMPTY, ChannelOwnership.CLOSE)
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
}
