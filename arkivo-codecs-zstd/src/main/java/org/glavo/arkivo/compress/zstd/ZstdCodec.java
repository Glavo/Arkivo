// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.zstd;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.compress.CompressionCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.Objects;

/// Provides Zstandard compression and decompression channels.
@NotNullByDefault
public final class ZstdCodec implements CompressionCodec {
    /// The stable Zstandard codec name.
    public static final String NAME = "zstd";

    /// The sentinel compression level that asks Zstandard to use its default level.
    public static final int DEFAULT_COMPRESSION_LEVEL = -1;

    /// The requested Zstandard compression level.
    private final int compressionLevel;

    /// The dictionary bytes, or `null` when no dictionary is configured.
    private final byte @Nullable [] dictionary;

    /// Creates a Zstandard codec.
    public ZstdCodec() {
        this(DEFAULT_COMPRESSION_LEVEL, null);
    }

    /// Creates a configured Zstandard codec.
    private ZstdCodec(int compressionLevel, byte @Nullable [] dictionary) {
        if (compressionLevel < DEFAULT_COMPRESSION_LEVEL) {
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
    public List<String> fileExtensions() {
        return List.of("zst", "zstd");
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
        ZstdOutputStream output = new ZstdOutputStream(Channels.newOutputStream(target));
        if (compressionLevel != DEFAULT_COMPRESSION_LEVEL) {
            output.setLevel(compressionLevel);
        }

        if (dictionary != null) {
            output.setDict(dictionary);
        }
        return Channels.newChannel(output);
    }

    /// Opens a Zstandard decompressor that reads compressed bytes from the source channel.
    @Override
    public ReadableByteChannel decompressFrom(ReadableByteChannel source) throws IOException {
        ZstdInputStream input = new ZstdInputStream(Channels.newInputStream(source));
        if (dictionary != null) {
            input.setDict(dictionary);
        }
        return Channels.newChannel(input);
    }

    /// Compresses all remaining source bytes into the target buffer.
    @Override
    public void compress(ByteBuffer source, ByteBuffer target) throws IOException {
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer compression requires direct buffers");
        }
        if (dictionary != null) {
            throw new IOException("Zstandard ByteBuffer compression with dictionaries is not supported yet");
        }

        int level = compressionLevel != DEFAULT_COMPRESSION_LEVEL
                ? compressionLevel
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
    public void decompress(ByteBuffer source, ByteBuffer target) throws IOException {
        if (!source.isDirect() || !target.isDirect()) {
            throw new IOException("Zstandard ByteBuffer decompression requires direct buffers");
        }
        if (dictionary != null) {
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
