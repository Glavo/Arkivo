// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Objects;

/// Exposes one exactly sized raw PPMd7 stream as a decoded byte channel.
@NotNullByDefault
public final class PPMd7ChannelDecoder implements DecompressingReadableByteChannel {
    /// Tracks closure of the compressed source.
    private final OwnedChannelCloser sourceCloser;

    /// The 7z range decoder over the compressed source.
    private final PPMd7RangeDecoder rangeDecoder;

    /// The initialized Variant H context model.
    private final PPMd7Model model;

    /// Number of decoded bytes still expected.
    private long remainingOutput;

    /// Number of bytes returned to callers.
    private long outputBytes;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates an initialized raw PPMd7 decoder.
    ///
    /// @param source the channel supplying the raw arithmetic stream
    /// @param ownership whether closing this decoder also closes {@code source}
    /// @param maximumOrder the Variant H context order, from {@code 2} through {@code 64}
    /// @param memorySize the model arena size, from 2 KiB through 256 MiB
    /// @param decodedSize the exact nonnegative number of bytes to decode
    /// @throws IOException if the range prefix or model configuration is invalid, or the model cannot be allocated
    /// @throws NullPointerException if {@code source} or {@code ownership} is {@code null}
    public PPMd7ChannelDecoder(
            ReadableByteChannel source,
            ResourceOwnership ownership,
            int maximumOrder,
            long memorySize,
            long decodedSize
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        sourceCloser = new OwnedChannelCloser(source, ownership);
        try {
            rangeDecoder = new PPMd7RangeDecoder(source);
            model = new PPMd7Model(rangeDecoder);
            model.initialize(true, maximumOrder, memorySize);
            remainingOutput = decodedSize;
        } catch (IOException | RuntimeException | Error failure) {
            sourceCloser.closeAfter(failure);
            throw new AssertionError("Unreachable after rethrowing decoder setup failure");
        }
    }

    /// Decodes as many bytes as fit in the target, or reports exact-size completion.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        if (!open) throw new ClosedChannelException();
        if (remainingOutput == 0L) return -1;
        if (!target.hasRemaining()) return 0;

        int decoded = (int) Math.min(remainingOutput, target.remaining());
        for (int index = 0; index < decoded; index++) {
            target.put((byte) model.readByte());
        }
        remainingOutput -= decoded;
        outputBytes += decoded;
        return decoded;
    }

    /// Returns the number of compressed bytes consumed by the range decoder.
    @Override
    public long inputBytes() {
        return rangeDecoder.inputBytes();
    }

    /// Returns the number of uncompressed bytes returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether the decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes an owned source and invalidates this decoder.
    @Override
    public void close() throws IOException {
        open = false;
        sourceCloser.close();
    }
}
