// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.zip.CRC32;

/// Incrementally encodes independently terminated lzip members without retaining caller-owned buffers.
@NotNullByDefault
public final class LzipEncoder implements CompressionEncoder.Framed {
    /// The exactly representable dictionary size written to every member header.
    private final int dictionarySize;

    /// The CRC-32 of uncompressed bytes in the active member.
    private final CRC32 checksum = new CRC32();

    /// The active member header awaiting caller-owned output space.
    private ByteBuffer header = ByteBuffer.allocate(0);

    /// The active raw LZMA payload encoder, or null between members and after closure.
    private @Nullable CompressionEncoder payloadEncoder;

    /// The completed member trailer awaiting caller-owned output space.
    private @Nullable ByteBuffer trailer;

    /// The number of uncompressed bytes accepted for the active member.
    private long dataSize;

    /// The number of encoded bytes in the active member, including its pending trailer.
    private long memberSize;

    /// The current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an encoder that writes the requested lzip dictionary size.
    ///
    /// @param dictionarySize the exactly representable dictionary size to write, in bytes
    /// @throws IllegalArgumentException if {@code dictionarySize} has no exact lzip header representation
    public LzipEncoder(int dictionarySize) {
        LzipSupport.encodeDictionarySize(dictionarySize);
        this.dictionarySize = dictionarySize;
        initializeMember();
    }

    /// Consumes uncompressed bytes and produces the active lzip member.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_MEMBERS) {
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            initializeMember();
        }
        requireState(State.ACTIVE, "encode");

        if (drain(header, target)) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        if (!source.hasRemaining()) {
            return CodecOutcome.NEEDS_INPUT;
        }

        int inputStart = source.position();
        int outputStart = target.position();
        ByteBuffer inputView = source.duplicate();
        CodecOutcome outcome = requirePayloadEncoder().encode(source, target);
        recordInput(inputView, inputStart, source.position());
        addMemberBytes(target.position() - outputStart);
        return outcome;
    }

    /// Finishes the current member and retains the encoder for another member.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        return finishMember(Objects.requireNonNull(target, "target"), false);
    }

    /// Finishes the complete lzip stream without adding an empty member after a completed boundary.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        return finishMember(Objects.requireNonNull(target, "target"), true);
    }

    /// Abandons pending data and restores a fresh initial lzip member.
    @Override
    public void reset() {
        requireOpen();
        closePayload();
        initializeMember();
    }

    /// Releases the nested LZMA encoder without implicitly finishing the active member.
    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        closePayload();
        trailer = null;
        header = ByteBuffer.allocate(0);
        state = State.CLOSED;
    }

    /// Finishes either a nonterminal member boundary or the complete encoding session.
    private CodecOutcome finishMember(ByteBuffer target, boolean terminal) throws IOException {
        requireOpen();
        if (terminal && state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.BETWEEN_MEMBERS) {
            if (terminal) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }
            return CodecOutcome.BOUNDARY_REACHED;
        }

        State payloadState = terminal ? State.FINISHING_PAYLOAD : State.FINISHING_FRAME_PAYLOAD;
        State trailerState = terminal ? State.FINISHING_TRAILER : State.FINISHING_FRAME_TRAILER;
        if (state == State.ACTIVE) {
            state = payloadState;
        } else if (state != payloadState && state != trailerState) {
            throw new IllegalStateException(
                    "Cannot " + (terminal ? "finish" : "finish a member") + " while lzip encoder state is " + state
            );
        }

        if (state == payloadState) {
            if (drain(header, target)) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            int outputStart = target.position();
            CodecOutcome outcome = requirePayloadEncoder().finish(target);
            addMemberBytes(target.position() - outputStart);
            if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                return outcome;
            }
            if (outcome != CodecOutcome.FINISHED) {
                throw new IOException("Unexpected raw LZMA finish outcome in lzip member: " + outcome);
            }

            closePayload();
            addMemberBytes(LzipSupport.TRAILER_SIZE);
            if (memberSize > LzipSupport.MAXIMUM_MEMBER_SIZE) {
                throw new IOException("Lzip member exceeds the maximum supported member size");
            }
            trailer = createTrailer();
            state = trailerState;
        }

        ByteBuffer activeTrailer = requireTrailer();
        if (drain(activeTrailer, target)) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        trailer = null;
        if (terminal) {
            state = State.FINISHED;
            return CodecOutcome.FINISHED;
        }
        state = State.BETWEEN_MEMBERS;
        return CodecOutcome.BOUNDARY_REACHED;
    }

    /// Creates all mutable state for one new lzip member.
    private void initializeMember() {
        checksum.reset();
        dataSize = 0L;
        memberSize = LzipSupport.HEADER_SIZE;
        trailer = null;

        byte[] bytes = Arrays.copyOf(LzipSupport.MAGIC, LzipSupport.HEADER_SIZE);
        bytes[4] = (byte) LzipSupport.VERSION;
        bytes[5] = (byte) LzipSupport.encodeDictionarySize(dictionarySize);
        header = ByteBuffer.wrap(bytes);
        payloadEncoder = new RawLZMACodec()
                .withDictionarySize(dictionarySize)
                .withEndMarker(true)
                .newEncoder(CompressionCodec.UNKNOWN_SIZE);
        state = State.ACTIVE;
    }

    /// Records exactly the source range consumed by the nested LZMA encoder.
    private void recordInput(ByteBuffer inputView, int start, int end) throws IOException {
        if (end == start) {
            return;
        }
        inputView.position(start);
        inputView.limit(end);
        checksum.update(inputView);
        try {
            dataSize = Math.addExact(dataSize, end - start);
        } catch (ArithmeticException exception) {
            throw new IOException("Lzip uncompressed member size overflow", exception);
        }
    }

    /// Adds encoded bytes to the active member size with overflow protection.
    private void addMemberBytes(int count) throws IOException {
        try {
            memberSize = Math.addExact(memberSize, count);
        } catch (ArithmeticException exception) {
            throw new IOException("Lzip member size overflow", exception);
        }
    }

    /// Creates the fixed little-endian integrity trailer for the completed member.
    private ByteBuffer createTrailer() {
        byte[] bytes = new byte[LzipSupport.TRAILER_SIZE];
        ByteArrayAccess.writeIntLittleEndian(bytes, 0, (int) checksum.getValue());
        ByteArrayAccess.writeLongLittleEndian(bytes, 4, dataSize);
        ByteArrayAccess.writeLongLittleEndian(bytes, 12, memberSize);
        return ByteBuffer.wrap(bytes);
    }

    /// Copies pending private bytes to the caller target and reports whether bytes remain.
    private static boolean drain(ByteBuffer source, ByteBuffer target) {
        int count = Math.min(source.remaining(), target.remaining());
        if (count != 0) {
            ByteBuffer view = source.slice();
            view.limit(count);
            target.put(view);
            source.position(source.position() + count);
        }
        return source.hasRemaining();
    }

    /// Returns the active raw LZMA payload encoder.
    private CompressionEncoder requirePayloadEncoder() {
        @Nullable CompressionEncoder current = payloadEncoder;
        if (current == null) {
            throw new IllegalStateException("Lzip payload encoder is unavailable");
        }
        return current;
    }

    /// Returns the completed trailer awaiting output space.
    private ByteBuffer requireTrailer() {
        @Nullable ByteBuffer current = trailer;
        if (current == null) {
            throw new IllegalStateException("Lzip member trailer is unavailable");
        }
        return current;
    }

    /// Releases the active nested LZMA encoder.
    private void closePayload() {
        @Nullable CompressionEncoder current = payloadEncoder;
        payloadEncoder = null;
        if (current != null) {
            current.close();
        }
    }

    /// Requires the exact lifecycle state for an operation.
    private void requireState(State required, String operation) {
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while lzip encoder state is " + state);
        }
    }

    /// Requires this encoder not to have been closed.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Lzip encoder is closed");
        }
    }

    /// Enumerates lzip encoder lifecycle phases.
    @NotNullByDefault
    private enum State {
        /// The active member accepts uncompressed source bytes.
        ACTIVE,

        /// The active member payload is being terminally finalized.
        FINISHING_PAYLOAD,

        /// The terminal member trailer is being emitted.
        FINISHING_TRAILER,

        /// The active member payload is being finalized at a nonterminal boundary.
        FINISHING_FRAME_PAYLOAD,

        /// The nonterminal member trailer is being emitted.
        FINISHING_FRAME_TRAILER,

        /// One member ended and no following source bytes have started another.
        BETWEEN_MEMBERS,

        /// The complete lzip encoding session finished.
        FINISHED,

        /// The encoder released its mutable state.
        CLOSED
    }
}
