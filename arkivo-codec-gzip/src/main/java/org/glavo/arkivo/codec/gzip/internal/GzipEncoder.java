// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.spi.DeflateStrategySupport;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/// Incrementally encodes one gzip member without binding the codec state to an output channel.
@NotNullByDefault
public final class GzipEncoder implements CompressionEncoder {
    /// Internal flush staging size used to make JDK flush completion observable with arbitrarily small targets.
    private static final int FLUSH_BUFFER_SIZE = 8192;

    /// Empty input used to detach caller-owned buffers from the JDK context after every operation.
    private static final ByteBuffer EMPTY_INPUT = ByteBuffer.allocate(0);

    /// Configured compression level restored by reset.
    private final int compressionLevel;

    /// Configured compression strategy restored by reset.
    private final CompressionStrategy strategy;

    /// JDK raw Deflate context used for the member body.
    private final Deflater deflater;

    /// CRC-32 of uncompressed member content consumed by the encoder.
    private final CRC32 checksum = new CRC32();

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Fixed member header bytes not yet copied to caller-owned targets.
    private ByteBuffer pendingHeader;

    /// Sync-flush bytes not yet copied to caller-owned targets.
    private ByteBuffer pendingFlush = EMPTY_INPUT;

    /// Member trailer bytes not yet copied to caller-owned targets.
    private ByteBuffer pendingTrailer = EMPTY_INPUT;

    /// Uncompressed member size modulo 2^32.
    private long memberSize;

    /// Whether a strategy update may require an otherwise zero-progress Deflate invocation.
    private boolean strategyUpdatePending;

    /// Creates a gzip encoder with immutable member configuration.
    ///
    /// @param compressionLevel JDK Deflate compression level
    /// @param strategy compression strategy
    public GzipEncoder(int compressionLevel, CompressionStrategy strategy) {
        this.compressionLevel = compressionLevel;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.deflater = new Deflater(compressionLevel, true);
        this.pendingHeader = createHeader();
        try {
            configure();
        } catch (RuntimeException | Error exception) {
            deflater.end();
            state = State.CLOSED;
            throw exception;
        }
    }

    /// Encodes source bytes until the source or target is exhausted.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");

        copyPending(pendingHeader, target);
        if (pendingHeader.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        while (true) {
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }

            int sourcePosition = source.position();
            int targetPosition = target.position();
            boolean applyingStrategy = strategyUpdatePending;
            deflater.setInput(source);
            try {
                deflater.deflate(target, Deflater.NO_FLUSH);
                strategyUpdatePending = false;
            } finally {
                deflater.setInput(EMPTY_INPUT);
            }
            updateContentChecksum(source, sourcePosition);

            if (source.position() == sourcePosition
                    && target.position() == targetPosition
                    && !applyingStrategy) {
                throw new IOException("Gzip encoder made no progress");
            }
        }
    }

    /// Flushes pending gzip member output without ending the member.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished gzip member");
        }
        if (state == State.ACTIVE) {
            pendingFlush = collectFlushOutput();
            state = State.FLUSHING;
        }

        copyPending(pendingHeader, target);
        if (!pendingHeader.hasRemaining()) {
            copyPending(pendingFlush, target);
        }
        if (pendingHeader.hasRemaining() || pendingFlush.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        pendingFlush = EMPTY_INPUT;
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the gzip member without releasing the native context.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the gzip member");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            deflater.finish();
            state = State.FINISHING;
        }

        copyPending(pendingHeader, target);
        if (pendingHeader.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        while (!deflater.finished()) {
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            int targetPosition = target.position();
            boolean applyingStrategy = strategyUpdatePending;
            deflater.deflate(target, Deflater.NO_FLUSH);
            strategyUpdatePending = false;
            if (target.position() == targetPosition && !deflater.finished() && !applyingStrategy) {
                throw new IOException("Gzip encoder could not finish the member");
            }
        }

        if (pendingTrailer == EMPTY_INPUT) {
            pendingTrailer = createTrailer();
        }
        copyPending(pendingTrailer, target);
        if (pendingTrailer.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current member and restores the configured gzip state.
    @Override
    public void reset() {
        requireOpen();
        deflater.reset();
        configure();
        checksum.reset();
        memberSize = 0L;
        pendingHeader = createHeader();
        pendingFlush = EMPTY_INPUT;
        pendingTrailer = EMPTY_INPUT;
        state = State.ACTIVE;
    }

    /// Releases the JDK Deflate context without finishing pending data.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            deflater.end();
        }
    }

    /// Applies immutable compression configuration to a fresh JDK context session.
    private void configure() {
        deflater.setLevel(compressionLevel);
        deflater.setStrategy(DeflateStrategySupport.toJdkValue(strategy));
        strategyUpdatePending = strategy != CompressionStrategy.DEFAULT;
    }

    /// Creates the standard fixed gzip member header.
    private ByteBuffer createHeader() {
        int extraFlags = compressionLevel == Deflater.BEST_COMPRESSION
                ? 2
                : compressionLevel == Deflater.BEST_SPEED ? 4 : 0;
        return ByteBuffer.wrap(new byte[]{
                0x1f, (byte) 0x8b, 8, 0,
                0, 0, 0, 0,
                (byte) extraFlags, (byte) 0xff
        });
    }

    /// Creates the checksum and uncompressed-size trailer in little-endian order.
    private ByteBuffer createTrailer() {
        ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putInt((int) checksum.getValue());
        trailer.putInt((int) memberSize);
        trailer.flip();
        return trailer;
    }

    /// Collects one complete JDK sync flush before exposing it through caller-sized buffers.
    private ByteBuffer collectFlushOutput() {
        byte[] chunk = new byte[FLUSH_BUFFER_SIZE];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int produced = deflater.deflate(chunk, 0, chunk.length, Deflater.SYNC_FLUSH);
            strategyUpdatePending = false;
            if (produced != 0) {
                output.write(chunk, 0, produced);
            }
            if (produced < chunk.length) {
                return ByteBuffer.wrap(output.toByteArray());
            }
        }
    }

    /// Updates member accounting for the source range consumed by the last Deflate invocation.
    private void updateContentChecksum(ByteBuffer source, int start) {
        int end = source.position();
        if (start == end) {
            return;
        }
        ByteBuffer consumed = source.duplicate();
        consumed.position(start);
        consumed.limit(end);
        checksum.update(consumed);
        memberSize = (memberSize + end - start) & 0xffff_ffffL;
    }

    /// Copies as many staged bytes as fit in the caller-owned target.
    private static void copyPending(ByteBuffer pending, ByteBuffer target) {
        int length = Math.min(pending.remaining(), target.remaining());
        if (length == 0) {
            return;
        }
        int originalLimit = pending.limit();
        pending.limit(pending.position() + length);
        try {
            target.put(pending);
        } finally {
            pending.limit(originalLimit);
        }
    }

    /// Requires the exact active state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while gzip encoder state is " + state);
        }
    }

    /// Requires the native context to remain available.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Gzip encoder is closed");
        }
    }

    /// Tracks the explicit gzip member lifecycle.
    private enum State {
        /// The encoder accepts source bytes.
        ACTIVE,

        /// A flush must complete before source bytes can be accepted again.
        FLUSHING,

        /// Member finalization has started and must be drained.
        FINISHING,

        /// The member completed and may only be reset or closed.
        FINISHED,

        /// Native resources were released.
        CLOSED
    }
}
