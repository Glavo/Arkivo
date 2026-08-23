// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.xxhash.XXHash64;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/// Encodes Zstandard frames into a borrowed channel in pure Java.
@NotNullByDefault
final class ZstdStreamEncoder {
    /// Compressed-data target.
    private final WritableByteChannel target;

    /// Validated encoder parameters.
    private ZstdEncoderParameters parameters;

    /// Whether standard frame magic is omitted.
    private final boolean magicless;

    /// Stateful synchronous block encoder.
    private final ZstdBlockEncoder blockEncoder = new ZstdBlockEncoder();

    /// Frame-level long-distance planner, or null when disabled.
    private final @Nullable ZstdLongDistanceMatcher longDistanceMatcher;

    /// Pending uncompressed block retained until its last-block status is known.
    private final byte[] inputBuffer;

    /// Parallel job executor, or null for synchronous compression.
    private final @Nullable ExecutorService executor;

    /// Uncompressed blocks collected for the current parallel job.
    private final ArrayList<ZstdFrameEncoder.BlockInput> pendingJobBlocks = new ArrayList<>();

    /// Ordered parallel job results awaiting channel output.
    private final ArrayDeque<Future<ZstdFrameEncoder.JobEncoding>> pendingJobs = new ArrayDeque<>();

    /// Maximum queued job count before the oldest result is drained.
    private final int maximumPendingJobs;

    /// Number of valid bytes in the pending input block.
    private int inputSize;

    /// Number of uncompressed bytes collected for the current parallel job.
    private int pendingJobSize;

    /// Tail reloaded as the match prefix of the next parallel job.
    private byte @Unmodifiable [] nextJobPrefix = new byte[0];

    /// Number of frame bytes already submitted to parallel jobs.
    private long parallelFrameSubmitted;

    /// Checksum for the active frame.
    private ChecksumAccumulator.Width64 checksum = XXHash64.DEFAULT.newAccumulator();

    /// Number of uncompressed bytes accepted in the active frame.
    private long frameInputBytes;

    /// Whether one frame is active and will be finished on the next frame boundary.
    private boolean frameActive = true;

    /// Whether the active frame header has been written.
    private boolean headerWritten;

    /// Whether worker resources have been stopped.
    private boolean stopped;

    /// Creates a pure Java Zstandard channel encoder.
    ///
    /// @param target compressed-data target
    /// @param parameters validated encoder parameters
    /// @param magicless whether standard frame magic is omitted
    ZstdStreamEncoder(
            WritableByteChannel target,
            ZstdEncoderParameters parameters,
            boolean magicless
    ) {
        this.target = Objects.requireNonNull(target, "target");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.magicless = magicless;
        this.blockEncoder.reset(parameters);
        this.longDistanceMatcher = parameters.longDistanceMatching()
                ? new ZstdLongDistanceMatcher(parameters)
                : null;
        this.inputBuffer = new byte[parameters.blockSize()];
        int workerCount = parameters.workerCount();
        this.executor = workerCount == 0
                ? null
                : Executors.newFixedThreadPool(workerCount);
        this.maximumPendingJobs = Math.max(1, workerCount * 2);
    }

    /// Consumes uncompressed bytes while retaining one pending block for frame finalization.
    int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        if (!source.hasRemaining()) {
            return 0;
        }
        startFrameIfNeeded();
        requirePledgeCapacity(source.remaining());

        int start = source.position();
        while (source.hasRemaining()) {
            int inputLimit = pendingInputLimit();
            if (inputSize == inputLimit) {
                writePendingBlock(false);
                inputLimit = pendingInputLimit();
            }
            int count = Math.min(source.remaining(), inputLimit - inputSize);
            source.get(inputBuffer, inputSize, count);
            checksum.update(inputBuffer, inputSize, count);
            inputSize += count;
            frameInputBytes += count;
        }
        return source.position() - start;
    }

    /// Flushes all currently accepted input as complete non-final blocks.
    void flush() throws IOException {
        if (!frameActive) {
            return;
        }
        writeHeader();
        if (inputSize != 0) {
            writePendingBlock(false);
        }
        if (!pendingJobBlocks.isEmpty()) {
            submitPendingJob(false);
        }
        drainPendingJobs();
    }

    /// Explicitly starts another frame with independent source-size metadata.
    void startFrame(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        if (frameActive) {
            throw new IllegalStateException("A Zstandard frame is already active");
        }
        parameters = parameters.withPledgedSourceSize(options.sourceSize());
        initializeFrame();
    }

    /// Finishes the active frame while retaining the encoder for another frame.
    void finishFrame() throws IOException {
        if (!frameActive) {
            return;
        }
        endFrame();
        frameActive = false;
        parameters = parameters.withPledgedSourceSize(CompressionCodec.UNKNOWN_SIZE);
    }

    /// Finishes the active frame and stops worker resources without closing the borrowed target.
    void finish() throws IOException {
        if (stopped) {
            return;
        }
        try {
            if (frameActive) {
                endFrame();
                frameActive = false;
            }
            stopped = true;
            closeExecutor(null);
        } catch (IOException | RuntimeException | Error exception) {
            stopped = true;
            closeExecutor(exception);
            throw exception;
        }
    }

    /// Abandons pending frame and worker state without finishing output or closing the retained target.
    void abort() {
        if (stopped) {
            return;
        }
        stopped = true;
        for (Future<ZstdFrameEncoder.JobEncoding> pendingJob : pendingJobs) {
            pendingJob.cancel(true);
        }
        pendingJobs.clear();
        @Nullable ExecutorService blockExecutor = executor;
        if (blockExecutor != null) {
            blockExecutor.shutdownNow();
        }
    }

    /// Starts a fresh frame after an earlier explicit frame boundary.
    private void startFrameIfNeeded() {
        if (frameActive) {
            return;
        }
        initializeFrame();
    }

    /// Restores mutable state for one newly active frame.
    private void initializeFrame() {
        frameActive = true;
        blockEncoder.reset(parameters);
        @Nullable ZstdLongDistanceMatcher matcher = longDistanceMatcher;
        if (matcher != null) {
            matcher.reset();
        }
        headerWritten = false;
        frameInputBytes = 0L;
        inputSize = 0;
        pendingJobBlocks.clear();
        pendingJobSize = 0;
        nextJobPrefix = new byte[0];
        parallelFrameSubmitted = 0L;
        checksum = XXHash64.DEFAULT.newAccumulator();
    }

    /// Writes the active frame header once.
    private void writeHeader() throws IOException {
        if (!headerWritten) {
            writeFully(ZstdFrameEncoder.header(parameters, magicless));
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
        drainPendingJobs();
        if (parameters.checksum()) {
            writeFully(ZstdFrameEncoder.checksum(checksum));
        }
        inputSize = 0;
    }

    /// Returns the staging limit for the current synchronous block or parallel job.
    private int pendingInputLimit() {
        if (executor == null) {
            return inputBuffer.length;
        }
        int remainingJobSize = parameters.jobSize() - pendingJobSize;
        if (remainingJobSize <= 0) {
            throw new AssertionError("Zstandard parallel job exceeded its target size");
        }
        return Math.min(inputBuffer.length, remainingJobSize);
    }

    /// Writes the pending block and clears its staging range.
    private void writePendingBlock(boolean last) throws IOException {
        writeHeader();
        @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches =
                planLongDistanceMatches(
                        inputBuffer,
                        inputSize
                );
        @Nullable ExecutorService jobExecutor = executor;
        if (jobExecutor == null) {
            writeFully(blockEncoder.encode(
                    inputBuffer,
                    inputSize,
                    last,
                    parameters,
                    longDistanceMatches
            ));
        } else {
            byte @Unmodifiable [] blockInput = Arrays.copyOf(inputBuffer, inputSize);
            pendingJobBlocks.add(new ZstdFrameEncoder.BlockInput(
                    blockInput,
                    longDistanceMatches
            ));
            pendingJobSize += inputSize;
            if (last || pendingJobSize == parameters.jobSize()) {
                submitPendingJob(last);
            }
        }
        inputSize = 0;
    }

    /// Plans verified frame-history matches for one block when long-distance mode is enabled.
    private @Unmodifiable List<ZstdLongDistanceMatcher.Match> planLongDistanceMatches(
            byte[] source,
            int length
    ) {
        @Nullable ZstdLongDistanceMatcher matcher = longDistanceMatcher;
        return matcher == null ? List.of() : matcher.plan(source, length);
    }

    /// Submits the collected blocks as one independently encoded parallel job.
    private void submitPendingJob(boolean last) throws IOException {
        @Nullable ExecutorService jobExecutor = executor;
        if (jobExecutor == null || pendingJobBlocks.isEmpty()) {
            throw new AssertionError("Missing Zstandard parallel job state");
        }

        @Unmodifiable List<ZstdFrameEncoder.BlockInput> blocks = List.copyOf(pendingJobBlocks);
        byte @Unmodifiable [] prefix = nextJobPrefix;
        long frameOffset = parallelFrameSubmitted;
        pendingJobs.addLast(jobExecutor.submit(
                () -> ZstdFrameEncoder.job(blocks, prefix, frameOffset, last, parameters)
        ));

        nextJobPrefix = retainOverlap(prefix, blocks, parameters.overlapSize());
        parallelFrameSubmitted += pendingJobSize;
        pendingJobBlocks.clear();
        pendingJobSize = 0;
        if (pendingJobs.size() >= maximumPendingJobs) {
            drainPendingJob();
        }
    }

    /// Retains the configured tail from one completed uncompressed job.
    private static byte @Unmodifiable [] retainOverlap(
            byte @Unmodifiable [] prefix,
            @Unmodifiable List<ZstdFrameEncoder.BlockInput> blocks,
            int overlapSize
    ) {
        if (overlapSize == 0) {
            return new byte[0];
        }

        long totalSize = prefix.length;
        for (ZstdFrameEncoder.BlockInput block : blocks) {
            totalSize += block.bytes().length;
        }
        int retainedSize = (int) Math.min(totalSize, overlapSize);
        byte[] retained = new byte[retainedSize];
        int target = retainedSize;
        for (int index = blocks.size() - 1; index >= 0 && target > 0; index--) {
            byte @Unmodifiable [] block = blocks.get(index).bytes();
            int count = Math.min(target, block.length);
            target -= count;
            System.arraycopy(block, block.length - count, retained, target, count);
        }
        if (target > 0) {
            System.arraycopy(prefix, prefix.length - target, retained, 0, target);
        }
        return retained;
    }

    /// Drains every queued parallel job in submission order.
    private void drainPendingJobs() throws IOException {
        while (!pendingJobs.isEmpty()) {
            drainPendingJob();
        }
    }

    /// Waits for and writes the oldest queued parallel job.
    private void drainPendingJob() throws IOException {
        Future<ZstdFrameEncoder.JobEncoding> future = pendingJobs.removeFirst();
        try {
            for (byte @Unmodifiable [] block : future.get().blocks()) {
                writeFully(block);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while encoding a Zstandard job", exception);
        } catch (ExecutionException exception) {
            throw new IOException("Parallel Zstandard job encoding failed", exception.getCause());
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
        }
    }
}
