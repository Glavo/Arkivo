// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.glavo.arkivo.codec.spi.DeflateStrategySupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.Deflater;

/// Incrementally encodes one raw Deflate stream without binding the codec state to an output channel.
@NotNullByDefault
public final class DeflateEncoder implements CompressionEncoder {
    /// Internal flush staging size used to make JDK flush completion observable with arbitrarily small caller targets.
    private static final int FLUSH_BUFFER_SIZE = 8192;

    /// Empty input used to detach caller-owned buffers from the JDK context after every operation.
    private static final ByteBuffer EMPTY_INPUT = ByteBuffer.allocate(0);

    /// Configured compression level restored by reset.
    private final int compressionLevel;

    /// Configured preset dictionary bytes, or null.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// Configured compression strategy restored by reset.
    private final CompressionStrategy strategy;

    /// JDK raw Deflate context.
    private final Deflater deflater;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Whether a strategy update may require an otherwise zero-progress Deflate invocation.
    private boolean strategyUpdatePending;

    /// Flush bytes waiting to be copied into caller-owned target buffers.
    private ByteBuffer pendingFlush = EMPTY_INPUT;

    /// Creates a raw Deflate encoder with immutable stream configuration.
    ///
    /// @param compressionLevel JDK Deflate compression level
    /// @param dictionary preset dictionary, or null
    /// @param strategy compression strategy
    public DeflateEncoder(
            int compressionLevel,
            @Nullable CompressionDictionary dictionary,
            CompressionStrategy strategy
    ) {
        this.compressionLevel = compressionLevel;
        this.dictionary = dictionary != null ? dictionary.bytes() : null;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.deflater = new Deflater(compressionLevel, true);
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

            if (source.position() == sourcePosition
                    && target.position() == targetPosition
                    && !applyingStrategy) {
                throw new IOException("Raw deflate encoder made no progress");
            }
        }
    }

    /// Flushes pending raw Deflate output without ending the stream.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished raw Deflate stream");
        }
        if (state == State.ACTIVE) {
            pendingFlush = collectFlushOutput();
            state = State.FLUSHING;
        }
        copyPendingFlush(target);
        if (pendingFlush.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingFlush = EMPTY_INPUT;
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the raw Deflate stream without releasing the native context.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the raw Deflate stream");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            deflater.finish();
            state = State.FINISHING;
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
                throw new IOException("Raw deflate encoder could not finish the stream");
            }
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current stream and restores the configured Deflate state.
    @Override
    public void reset() {
        requireOpen();
        deflater.reset();
        configure();
        pendingFlush = EMPTY_INPUT;
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

    /// Applies immutable level-independent configuration to a fresh JDK context session.
    private void configure() {
        deflater.setLevel(compressionLevel);
        deflater.setStrategy(DeflateStrategySupport.toJdkValue(strategy));
        byte @Nullable [] selectedDictionary = dictionary;
        if (selectedDictionary != null) {
            deflater.setDictionary(selectedDictionary);
        }
        strategyUpdatePending = strategy != CompressionStrategy.DEFAULT;
    }

    /// Collects one complete JDK sync flush before exposing it through caller-sized buffers.
    private ByteBuffer collectFlushOutput() throws IOException {
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

    /// Copies as many staged flush bytes as fit in the caller-owned target.
    private void copyPendingFlush(ByteBuffer target) {
        int length = Math.min(pendingFlush.remaining(), target.remaining());
        if (length == 0) {
            return;
        }
        int originalLimit = pendingFlush.limit();
        pendingFlush.limit(pendingFlush.position() + length);
        try {
            target.put(pendingFlush);
        } finally {
            pendingFlush.limit(originalLimit);
        }
    }

    /// Requires the exact active state for an operation that accepts new input.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while raw Deflate encoder state is " + state);
        }
    }

    /// Requires the native context to remain available.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Raw Deflate encoder is closed");
        }
    }

    /// Tracks the explicit raw Deflate stream lifecycle.
    private enum State {
        /// The encoder accepts source bytes.
        ACTIVE,

        /// A flush must complete before source bytes can be accepted again.
        FLUSHING,

        /// Stream finalization has started and must be drained.
        FINISHING,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Native resources were released.
        CLOSED
    }
}
