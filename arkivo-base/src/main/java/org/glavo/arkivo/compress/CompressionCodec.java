// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress;

import org.glavo.arkivo.internal.ByteBufferCodecSupport;
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

/// Describes a compression codec and its supported operations.
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

    /// Returns whether this codec can compress uncompressed bytes.
    boolean canCompress();

    /// Returns whether this codec can decompress compressed bytes.
    boolean canDecompress();

    /// Returns whether this codec supports ByteBuffer one-shot compression.
    default boolean canCompressBuffers() {
        return canCompress();
    }

    /// Returns whether this codec supports ByteBuffer one-shot decompression.
    default boolean canDecompressBuffers() {
        return canDecompress();
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

    /// Opens a channel that accepts uncompressed bytes and writes compressed bytes to the target channel.
    WritableByteChannel compressTo(WritableByteChannel target) throws IOException;

    /// Opens a stream that accepts uncompressed bytes and writes compressed bytes to the target stream.
    default OutputStream compressTo(OutputStream target) throws IOException {
        return Channels.newOutputStream(compressTo(Channels.newChannel(target)));
    }

    /// Opens a channel that reads compressed bytes from the source channel and exposes uncompressed bytes.
    ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException;

    /// Opens a stream that reads compressed bytes from the source stream and exposes uncompressed bytes.
    default InputStream decompressFrom(InputStream source) throws IOException {
        return Channels.newInputStream(decompressFrom(Channels.newChannel(source)));
    }

    /// Compresses all remaining bytes from `source` into `target`.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    /// The default implementation adapts the codec's channel operation and accepts heap or direct buffers.
    /// Implementations may override this method with a path that requires specific buffer forms.
    default void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.compress(this, source, target);
    }

    /// Decompresses all remaining bytes from `source` into `target`.
    ///
    /// Both buffers are advanced by the number of bytes consumed and produced.
    /// The default implementation adapts the codec's channel operation and accepts heap or direct buffers.
    /// Implementations may override this method with a path that requires specific buffer forms.
    default void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        ByteBufferCodecSupport.decompress(this, source, target);
    }
}
