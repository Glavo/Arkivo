// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Exposes one raw PPMd7 arithmetic stream as an encoding byte channel.
@NotNullByDefault
public final class PPMd7ChannelEncoder implements CompressingWritableByteChannel {
    /// Tracks closure of the compressed target.
    private final OwnedChannelCloser targetCloser;

    /// The 7z range encoder over the compressed target.
    private final PPMd7RangeEncoder rangeEncoder;

    /// The initialized Variant H context model.
    private final PPMd7Model model;

    /// Configured maximum context order restored by internal buffer-engine reset.
    private final int maximumOrder;

    /// Configured model arena size restored by internal buffer-engine reset.
    private final long memorySize;

    /// Number of uncompressed bytes accepted.
    private long inputBytes;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an initialized raw PPMd7 encoder.
    public PPMd7ChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int maximumOrder,
            long memorySize
    ) throws IOException {
        Objects.requireNonNull(target, "target");
        targetCloser = new OwnedChannelCloser(target, ownership);
        this.maximumOrder = maximumOrder;
        this.memorySize = memorySize;
        try {
            rangeEncoder = new PPMd7RangeEncoder(target);
            model = new PPMd7Model(rangeEncoder);
            model.initialize(true, maximumOrder, memorySize);
        } catch (IOException | RuntimeException | Error failure) {
            targetCloser.closeAfter(failure);
            throw new AssertionError("Unreachable after rethrowing encoder setup failure");
        }
    }

    /// Encodes every remaining source byte.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        int start = source.position();
        while (source.hasRemaining()) {
            model.writeByte(Byte.toUnsignedInt(source.get()));
            inputBytes++;
        }
        return source.position() - start;
    }

    /// Flushes complete range-coded bytes without ending the stream.
    public void flush() throws IOException {
        ensureOpen();
        rangeEncoder.flushOutput();
    }

    /// Finishes the arithmetic stream and closes an owned target.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }

        @Nullable Throwable failure = null;
        try {
            rangeEncoder.finish();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        targetCloser.closeAfter(failure);
    }

    /// Abandons the current representation and reuses its arena for the buffer engine.
    void resetForBufferEngine() throws IOException {
        rangeEncoder.reset();
        model.reset();
        model.initialize(true, maximumOrder, memorySize);
        inputBytes = 0L;
        open = true;
    }

    /// Returns the number of uncompressed bytes accepted.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the number of compressed bytes produced.
    @Override
    public long outputBytes() {
        return rangeEncoder.outputBytes();
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes this encoder.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
