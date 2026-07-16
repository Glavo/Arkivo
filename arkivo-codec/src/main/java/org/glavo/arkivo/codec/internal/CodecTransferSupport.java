// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecTransferResult;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Implements blocking channel-to-channel compression and decompression transfers.
@NotNullByDefault
public final class CodecTransferSupport {
    /// The transfer buffer size.
    private static final int BUFFER_SIZE = 8192;

    /// Creates no instances.
    private CodecTransferSupport() {
    }

    /// Compresses bytes between channels without taking ownership of either channel.
    public static CodecTransferResult compress(
            CompressionCodec codec,
            ReadableByteChannel source,
            WritableByteChannel target
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");

        try (CompressingWritableByteChannel encoder =
                     codec.openEncoder(target, ChannelOwnership.RETAIN)) {
            transferIntoEncoder(source, encoder);
            encoder.finish();
            return new CodecTransferResult(encoder.inputBytes(), encoder.outputBytes());
        }
    }

    /// Decompresses bytes between channels without taking ownership of either channel.
    public static CodecTransferResult decompress(
            CompressionCodec codec,
            ReadableByteChannel source,
            WritableByteChannel target,
            DecompressionLimits limits
    ) throws IOException {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(limits, "limits");

        try (DecompressingReadableByteChannel decoder =
                     codec.openDecoder(source, limits, ChannelOwnership.RETAIN)) {
            return decompress(decoder, target);
        }
    }

    /// Transfers every decoded byte without closing the decoder or target channel.
    public static CodecTransferResult decompress(
            DecompressingReadableByteChannel decoder,
            WritableByteChannel target
    ) throws IOException {
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(target, "target");
        long outputBytes = transferFromDecoder(decoder, target);
        return new CodecTransferResult(decoder.inputBytes(), outputBytes);
    }

    /// Copies all source bytes into an encoder with deterministic progress checks.
    private static void transferIntoEncoder(
            ReadableByteChannel source,
            CompressingWritableByteChannel encoder
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        while (true) {
            int read = source.read(buffer);
            if (read < 0) {
                return;
            }
            if (read == 0) {
                throw new IOException("Compression source channel made no progress");
            }

            buffer.flip();
            writeFully(encoder, buffer, "Compression encoder made no progress");
            buffer.clear();
        }
    }

    /// Copies all decoded bytes to the target and returns the written byte count.
    private static long transferFromDecoder(
            DecompressingReadableByteChannel decoder,
            WritableByteChannel target
    ) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        long outputBytes = 0L;
        while (true) {
            int read = decoder.read(buffer);
            if (read < 0) {
                return outputBytes;
            }
            if (read == 0) {
                throw new IOException("Compression decoder made no progress");
            }

            outputBytes += read;
            buffer.flip();
            writeFully(target, buffer, "Decompression target channel made no progress");
            buffer.clear();
        }
    }

    /// Writes all remaining bytes and rejects zero-progress channels.
    private static void writeFully(
            WritableByteChannel target,
            ByteBuffer source,
            String noProgressMessage
    ) throws IOException {
        while (source.hasRemaining()) {
            if (target.write(source) == 0) {
                throw new IOException(noProgressMessage);
            }
        }
    }
}
