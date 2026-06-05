// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.zstd;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.compress.CompressionCodec;
import org.glavo.arkivo.compress.CompressionParameters;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;

/// Provides Zstandard compression and decompression channels.
@NotNullByDefault
public final class ZstdCodec implements CompressionCodec {
    /// The stable Zstandard codec name.
    public static final String NAME = "zstd";

    /// The default Zstandard compression level sentinel.
    private static final int DEFAULT_LEVEL = CompressionParameters.DEFAULT_COMPRESSION_LEVEL;

    /// Creates a Zstandard codec.
    public ZstdCodec() {
    }

    /// Returns the stable Zstandard codec name.
    @Override
    public String name() {
        return NAME;
    }

    /// Returns common Zstandard file extensions.
    @Override
    public List<String> fileExtensions() {
        return List.of("zst", "zstd");
    }

    /// Returns whether Zstandard compression is supported.
    @Override
    public boolean canCompress() {
        return true;
    }

    /// Returns whether Zstandard decompression is supported.
    @Override
    public boolean canDecompress() {
        return true;
    }

    /// Returns whether Zstandard ByteBuffer compression is supported.
    @Override
    public boolean canCompressBuffers() {
        return true;
    }

    /// Returns whether Zstandard ByteBuffer decompression is supported.
    @Override
    public boolean canDecompressBuffers() {
        return true;
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

    /// Opens a Zstandard compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(WritableByteChannel target) throws IOException {
        return compressTo(target, CompressionParameters.defaults());
    }

    /// Opens a Zstandard compressor that writes compressed bytes to the target channel.
    @Override
    public WritableByteChannel compressTo(
            WritableByteChannel target,
            CompressionParameters parameters
    ) throws IOException {
        ZstdOutputStream output = new ZstdOutputStream(Channels.newOutputStream(target));
        if (parameters.compressionLevel() != DEFAULT_LEVEL) {
            output.setLevel(parameters.compressionLevel());
        }

        byte[] dictionary = parameters.dictionary();
        if (dictionary != null) {
            output.setDict(dictionary);
        }
        return Channels.newChannel(output);
    }

    /// Opens a Zstandard decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        return decompressFrom(source, CompressionParameters.defaults());
    }

    /// Opens a Zstandard decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(
            ReadableByteChannel source,
            CompressionParameters parameters
    ) throws IOException {
        ZstdInputStream input = new ZstdInputStream(Channels.newInputStream(source));
        byte[] dictionary = parameters.dictionary();
        if (dictionary != null) {
            input.setDict(dictionary);
        }
        return Channels.newChannel(input);
    }

    /// Compresses all remaining source bytes into the target buffer.
    @Override
    public void compress(
            ByteBuffer source,
            ByteBuffer target,
            CompressionParameters parameters
    ) throws IOException {
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer compression requires direct buffers");
        }
        byte[] dictionary = parameters.dictionary();
        if (dictionary != null) {
            throw new IOException("Zstandard ByteBuffer compression with dictionaries is not supported yet");
        }

        int level = parameters.compressionLevel() != DEFAULT_LEVEL
                ? parameters.compressionLevel()
                : Zstd.defaultCompressionLevel();
        long result = Zstd.compressDirectByteBuffer(
                target,
                target.position(),
                target.remaining(),
                source,
                source.position(),
                source.remaining(),
                level
        );
        if (Zstd.isError(result)) {
            throw new IOException("Zstandard ByteBuffer compression failed: " + Zstd.getErrorName(result));
        }

        source.position(source.limit());
        target.position(target.position() + (int) result);
    }

    /// Decompresses all remaining source bytes into the target buffer.
    @Override
    public void decompress(
            ByteBuffer source,
            ByteBuffer target,
            CompressionParameters parameters
    ) throws IOException {
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer decompression requires direct buffers");
        }
        if (parameters.dictionary() != null) {
            throw new IOException("Zstandard ByteBuffer decompression with dictionaries is not supported yet");
        }

        long result = Zstd.decompressDirectByteBuffer(
                target,
                target.position(),
                target.remaining(),
                source,
                source.position(),
                source.remaining()
        );
        if (Zstd.isError(result)) {
            throw new IOException("Zstandard ByteBuffer decompression failed: " + Zstd.getErrorName(result));
        }

        source.position(source.limit());
        target.position(target.position() + (int) result);
    }
}
