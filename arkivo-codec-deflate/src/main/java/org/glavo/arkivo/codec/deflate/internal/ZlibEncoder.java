// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.deflate.ZlibDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.zip.Adler32;

/// Incrementally encodes one zlib stream with the shared pure Java Deflate engine.
@NotNullByDefault
public final class ZlibEncoder implements CompressionEncoder.Flushable {
    /// Shared empty output marker.
    private static final @Unmodifiable ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0);

    /// Configured compression level restored by reset.
    private final int compressionLevel;

    /// Whether a preset dictionary is configured.
    private final boolean dictionaryConfigured;

    /// Adler-32 dictionary identifier when a dictionary is configured.
    private final long dictionaryId;

    /// Shared raw Deflate encoder used for the stream body.
    private final DeflateEncoderEngine body;

    /// Adler-32 checksum of uncompressed stream content.
    private final Adler32 checksum = new Adler32();

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Stream header bytes not yet copied to caller-owned targets.
    private ByteBuffer pendingHeader;

    /// Stream trailer bytes not yet copied to caller-owned targets.
    private ByteBuffer pendingTrailer = EMPTY_OUTPUT;

    /// Creates a zlib encoder with immutable stream configuration.
    ///
    /// @param compressionLevel bounded Deflate compression level from zero through nine
    /// @param dictionary       preset dictionary, or null
    /// @param strategy         Deflate strategy
    public ZlibEncoder(
            int compressionLevel,
            @Nullable ZlibDictionary dictionary,
            DeflateStrategy strategy
    ) {
        this.compressionLevel = compressionLevel;
        this.dictionaryConfigured = dictionary != null;
        this.dictionaryId = dictionary != null ? dictionary.adler32() : 0L;
        this.body = new DeflateEncoderEngine(
                DeflateEncoderEngine.Format.DEFLATE,
                compressionLevel,
                dictionary != null ? dictionary.bytes() : null,
                Objects.requireNonNull(strategy, "strategy")
        );
        this.pendingHeader = createHeader();
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

        int sourcePosition = source.position();
        try {
            return body.encode(source, target);
        } finally {
            updateContentChecksum(source, sourcePosition);
        }
    }

    /// Flushes pending zlib output without ending the stream.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.TRAILER || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished zlib stream");
        }
        if (state == State.ACTIVE) {
            state = State.FLUSHING;
        }

        copyPending(pendingHeader, target);
        if (pendingHeader.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        CodecOutcome outcome = body.flush(target);
        if (outcome == CodecOutcome.FLUSHED) {
            state = State.ACTIVE;
        }
        return outcome;
    }

    /// Finishes the zlib stream without releasing encoder-owned state.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the zlib stream");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            state = State.FINISHING;
        }

        copyPending(pendingHeader, target);
        if (pendingHeader.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        if (state == State.FINISHING) {
            CodecOutcome outcome = body.finish(target);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                return outcome;
            }
            if (outcome != CodecOutcome.FINISHED) {
                throw new IOException("Unexpected zlib Deflate finish outcome: " + outcome);
            }
            pendingTrailer = createTrailer();
            state = State.TRAILER;
        }

        copyPending(pendingTrailer, target);
        if (pendingTrailer.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current stream and restores the configured zlib state.
    @Override
    public void reset() {
        requireOpen();
        body.reset();
        checksum.reset();
        pendingHeader = createHeader();
        pendingTrailer = EMPTY_OUTPUT;
        state = State.ACTIVE;
    }

    /// Releases encoder-owned state without finishing pending data.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            body.close();
            state = State.CLOSED;
        }
    }

    /// Creates the RFC 1950 header and optional preset-dictionary identifier.
    private ByteBuffer createHeader() {
        int compressionMethodAndInfo = 0x78;
        int compressionLevelFlags = compressionLevel <= 1
                ? 0
                : compressionLevel <= 5 ? 1 : compressionLevel == 6 ? 2 : 3;
        int flags = compressionLevelFlags << 6;
        if (dictionaryConfigured) {
            flags |= 0x20;
        }
        flags |= (31 - ((compressionMethodAndInfo << 8 | flags) % 31)) % 31;

        ByteBuffer header = ByteBuffer.allocate(
                dictionaryConfigured ? 6 : 2
        ).order(ByteOrder.BIG_ENDIAN);
        header.put((byte) compressionMethodAndInfo);
        header.put((byte) flags);
        if (dictionaryConfigured) {
            header.putInt((int) dictionaryId);
        }
        return header.flip();
    }

    /// Creates the Adler-32 stream trailer in network byte order.
    private ByteBuffer createTrailer() {
        ByteBuffer trailer = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN);
        trailer.putInt((int) checksum.getValue());
        return trailer.flip();
    }

    /// Updates the stream checksum for the source range consumed by the Deflate engine.
    private void updateContentChecksum(ByteBuffer source, int start) {
        int end = source.position();
        if (start == end) {
            return;
        }
        ByteBuffer consumed = source.duplicate();
        consumed.position(start);
        consumed.limit(end);
        checksum.update(consumed);
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
            throw new IllegalStateException("Cannot " + operation + " while zlib encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Zlib encoder is closed");
        }
    }

    /// Tracks the explicit zlib stream lifecycle.
    @NotNullByDefault
    private enum State {
        /// The encoder accepts source bytes.
        ACTIVE,

        /// A flush must complete before source bytes can be accepted again.
        FLUSHING,

        /// Deflate stream finalization has started.
        FINISHING,

        /// The stream trailer must be drained.
        TRAILER,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Encoder-owned state was released.
        CLOSED
    }
}
