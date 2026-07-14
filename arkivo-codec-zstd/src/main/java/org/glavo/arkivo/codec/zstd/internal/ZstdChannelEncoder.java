// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/// Encodes Zstandard frames directly over a writable byte channel in pure Java.
@NotNullByDefault
public final class ZstdChannelEncoder implements CompressionEncoder {
    /// Compressed-data target.
    private final WritableByteChannel target;

    /// Tracks closure of the owned compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// Validated encoder parameters.
    private final ZstdEncoderParameters parameters;

    /// Pending uncompressed block retained until its last-block status is known.
    private final byte[] inputBuffer;

    /// Parallel block executor, or null for synchronous compression.
    private final @Nullable ExecutorService executor;

    /// Ordered parallel block results awaiting channel output.
    private final ArrayDeque<Future<byte[]>> pendingBlocks = new ArrayDeque<>();

    /// Maximum queued block count before the oldest result is drained.
    private final int maximumPendingBlocks;

    /// Number of valid bytes in the pending input block.
    private int inputSize;

    /// Checksum for the active frame.
    private ZstdXXHash64 checksum = new ZstdXXHash64();

    /// Number of uncompressed bytes accepted in the active frame.
    private long frameInputBytes;

    /// Number of uncompressed bytes consumed across all frames.
    private long inputBytes;

    /// Number of compressed bytes written across all frames.
    private long outputBytes;

    /// Whether one frame is active and will be finished on the next frame boundary.
    private boolean frameActive = true;

    /// Whether the active frame header has been written.
    private boolean headerWritten;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates a pure Java Zstandard channel encoder.
    ///
    /// @param target compressed-data target
    /// @param ownership whether this context closes the target
    /// @param parameters validated encoder parameters
    public ZstdChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            ZstdEncoderParameters parameters
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.inputBuffer = new byte[parameters.blockSize()];
        int workerCount = parameters.workerCount();
        this.executor = workerCount == 0
                ? null
                : Executors.newFixedThreadPool(workerCount);
        this.maximumPendingBlocks = Math.max(1, workerCount * 2);
    }

    /// Consumes uncompressed bytes while retaining one pending block for frame finalization.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }
        startFrame();
        requirePledgeCapacity(source.remaining());

        int start = source.position();
        while (source.hasRemaining()) {
            if (inputSize == inputBuffer.length) {
                writePendingBlock(false);
            }
            int count = Math.min(source.remaining(), inputBuffer.length - inputSize);
            source.get(inputBuffer, inputSize, count);
            checksum.update(inputBuffer, inputSize, count);
            inputSize += count;
            frameInputBytes += count;
            inputBytes += count;
        }
        return source.position() - start;
    }

    /// Flushes all currently accepted input as complete non-final blocks.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        if (!frameActive) {
            return;
        }
        writeHeader();
        if (inputSize != 0) {
            writePendingBlock(false);
        }
        drainPendingBlocks();
    }

    /// Finishes the active frame while retaining the encoder for another frame.
    @Override
    public void finishFrame() throws IOException {
        ensureOpen();
        if (!frameActive) {
            return;
        }
        endFrame();
        frameActive = false;
    }

    /// Finishes the active frame and closes an owned target.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }

        @Nullable Throwable failure = null;
        try {
            if (frameActive) {
                endFrame();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        closeExecutor(failure);
        targetCloser.closeAfter(failure);
    }

    /// Returns the consumed uncompressed byte count.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the emitted compressed byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes the encoder.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Starts a fresh frame after an earlier explicit frame boundary.
    private void startFrame() {
        if (frameActive) {
            return;
        }
        frameActive = true;
        headerWritten = false;
        frameInputBytes = 0L;
        inputSize = 0;
        checksum = new ZstdXXHash64();
    }

    /// Writes the active frame header once.
    private void writeHeader() throws IOException {
        if (!headerWritten) {
            writeFully(ZstdFrameEncoder.header(parameters));
            headerWritten = true;
        }
    }

    /// Finishes one active frame and validates its source-size pledge.
    private void endFrame() throws IOException {
        long pledgedSourceSize = parameters.pledgedSourceSize();
        if (pledgedSourceSize >= 0L && frameInputBytes != pledgedSourceSize) {
            throw new IOException(
                    "Zstandard frame source size " + frameInputBytes
                            + " does not match pledged size " + pledgedSourceSize
            );
        }
        writeHeader();
        writePendingBlock(true);
        drainPendingBlocks();
        if (parameters.checksum()) {
            writeFully(ZstdFrameEncoder.checksum(checksum));
        }
        inputSize = 0;
    }

    /// Writes the pending block and clears its staging range.
    private void writePendingBlock(boolean last) throws IOException {
        writeHeader();
        @Nullable ExecutorService blockExecutor = executor;
        if (blockExecutor == null) {
            writeFully(ZstdFrameEncoder.block(inputBuffer, inputSize, last, parameters));
        } else {
            byte[] blockInput = Arrays.copyOf(inputBuffer, inputSize);
            int blockLength = inputSize;
            pendingBlocks.addLast(blockExecutor.submit(
                    () -> ZstdFrameEncoder.block(blockInput, blockLength, last, parameters)
            ));
            if (pendingBlocks.size() >= maximumPendingBlocks) {
                drainPendingBlock();
            }
        }
        inputSize = 0;
    }

    /// Drains every queued parallel block in submission order.
    private void drainPendingBlocks() throws IOException {
        while (!pendingBlocks.isEmpty()) {
            drainPendingBlock();
        }
    }

    /// Waits for and writes the oldest queued parallel block.
    private void drainPendingBlock() throws IOException {
        Future<byte[]> future = pendingBlocks.removeFirst();
        try {
            writeFully(future.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while encoding a Zstandard block", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Parallel Zstandard block encoding failed", exception.getCause());
        }
    }

    /// Stops the block executor without obscuring an earlier failure.
    private void closeExecutor(@Nullable Throwable failure) {
        @Nullable ExecutorService blockExecutor = executor;
        if (blockExecutor == null) {
            return;
        }
        if (failure == null) {
            blockExecutor.shutdown();
        } else {
            blockExecutor.shutdownNow();
        }
    }

    /// Rejects input that would exceed an exact frame source-size pledge.
    private void requirePledgeCapacity(int count) throws IOException {
        long pledgedSourceSize = parameters.pledgedSourceSize();
        if (pledgedSourceSize >= 0L && count > pledgedSourceSize - frameInputBytes) {
            throw new IOException("Zstandard input exceeds the pledged frame source size");
        }
    }

    /// Writes every byte while rejecting a target channel that makes no progress.
    private void writeFully(byte[] bytes) throws IOException {
        ByteBuffer output = ByteBuffer.wrap(bytes);
        while (output.hasRemaining()) {
            int written = target.write(output);
            if (written == 0) {
                throw new IOException("Zstandard target channel made no progress");
            }
            outputBytes += written;
        }
    }

    /// Requires the encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
