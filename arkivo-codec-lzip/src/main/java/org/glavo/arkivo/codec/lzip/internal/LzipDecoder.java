// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzip.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.lzma.RawLZMACodec;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32;

/// Incrementally decodes one checksummed lzip member while preserving following member bytes.
@NotNullByDefault
public final class LzipDecoder implements CompressionDecoder.Framed {
    /// Operation-scoped limits applied when a member declares its LZMA dictionary.
    private final DecompressionLimits payloadLimits;

    /// Collected fixed member header.
    private final byte[] header = new byte[LzipSupport.HEADER_SIZE];

    /// Collected fixed member trailer.
    private final byte[] trailer = new byte[LzipSupport.TRAILER_SIZE];

    /// CRC-32 of bytes returned from the active member.
    private final CRC32 checksum = new CRC32();

    /// Active raw LZMA decoder, or null outside the payload phase.
    private @Nullable CompressionDecoder payloadDecoder;

    /// Number of collected member header bytes.
    private int headerSize;

    /// Number of collected member trailer bytes.
    private int trailerSize;

    /// Number of uncompressed member bytes returned to callers.
    private long dataSize;

    /// Number of compressed member bytes consumed, including header and trailer.
    private long memberSize;

    /// Current member decoding phase.
    private Phase phase = Phase.HEADER;

    /// Creates a lzip member decoder with limits for dynamically declared dictionaries.
    ///
    /// @param payloadLimits the limits to enforce on each member's LZMA payload
    /// @throws NullPointerException if {@code payloadLimits} is {@code null}
    public LzipDecoder(DecompressionLimits payloadLimits) {
        this.payloadLimits = Objects.requireNonNull(payloadLimits, "payloadLimits");
    }

    /// Decodes a lzip member while allowing additional compressed bytes later.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Decodes a lzip member after the caller has supplied all remaining bytes.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Abandons the current member and restores fixed-header collection.
    @Override
    public void reset() {
        requireOpen();
        releasePayload();
        headerSize = 0;
        trailerSize = 0;
        dataSize = 0L;
        memberSize = 0L;
        checksum.reset();
        phase = Phase.HEADER;
    }

    /// Releases nested LZMA state without consuming further source bytes.
    @Override
    public void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        releasePayload();
        phase = Phase.CLOSED;
    }

    /// Implements incremental decoding with explicit physical-input completion.
    private CodecOutcome decodeInternal(
            ByteBuffer source,
            ByteBuffer target,
            boolean endOfInput
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (phase == Phase.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            switch (phase) {
                case HEADER -> {
                    headerSize = collect(source, header, headerSize);
                    if (headerSize < header.length) {
                        return requireMoreInput(endOfInput, "Truncated lzip member header");
                    }
                    initializePayload();
                    phase = Phase.PAYLOAD;
                }
                case PAYLOAD -> {
                    if (!target.hasRemaining()) {
                        return CodecOutcome.NEEDS_OUTPUT;
                    }
                    CompressionDecoder decoder = requirePayloadDecoder();
                    int inputStart = source.position();
                    int outputStart = target.position();
                    CodecOutcome outcome = endOfInput
                            ? decoder.finish(source, target)
                            : decoder.decode(source, target);
                    addMemberBytes(source.position() - inputStart);
                    recordOutput(target, outputStart, target.position());

                    if (outcome == CodecOutcome.FINISHED) {
                        releasePayload();
                        phase = Phase.TRAILER;
                        continue;
                    }
                    if (outcome == CodecOutcome.NEEDS_INPUT) {
                        return requireMoreInput(endOfInput, "Truncated lzip LZMA payload");
                    }
                    if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                        return outcome;
                    }
                    throw new IOException("Unexpected raw LZMA decode outcome in lzip member: " + outcome);
                }
                case TRAILER -> {
                    trailerSize = collect(source, trailer, trailerSize);
                    if (trailerSize < trailer.length) {
                        return requireMoreInput(endOfInput, "Truncated lzip member trailer");
                    }
                    validateTrailer();
                    phase = Phase.FINISHED;
                    return CodecOutcome.FINISHED;
                }
                case FINISHED -> {
                    return CodecOutcome.FINISHED;
                }
                case CLOSED -> throw new IllegalStateException("Lzip decoder is closed");
            }
        }
    }

    /// Validates the collected header and creates its raw LZMA decoder.
    private void initializePayload() throws IOException {
        for (int index = 0; index < LzipSupport.MAGIC.length; index++) {
            if (header[index] != LzipSupport.MAGIC[index]) {
                throw new IOException("Invalid lzip member signature");
            }
        }
        int version = Byte.toUnsignedInt(header[4]);
        if (version != LzipSupport.VERSION) {
            throw new IOException("Unsupported lzip member version: " + version);
        }

        int dictionarySize;
        try {
            dictionarySize = LzipSupport.decodeDictionarySize(Byte.toUnsignedInt(header[5]));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid lzip member dictionary size", exception);
        }
        payloadDecoder = new RawLZMACodec()
                .withDictionarySize(dictionarySize)
                .withDecodedSize(CompressionCodec.UNKNOWN_SIZE)
                .newDecoder(payloadLimits);
    }

    /// Validates CRC-32, uncompressed size, and exact member size from the trailer.
    private void validateTrailer() throws IOException {
        int expectedChecksum = ByteArrayAccess.readIntLittleEndian(trailer, 0);
        long expectedDataSize = ByteArrayAccess.readLongLittleEndian(trailer, 4);
        long expectedMemberSize = ByteArrayAccess.readLongLittleEndian(trailer, 12);

        if (expectedDataSize < 0L) {
            throw new IOException("Unsupported unsigned lzip data size");
        }
        if (expectedMemberSize < 0L || expectedMemberSize > LzipSupport.MAXIMUM_MEMBER_SIZE) {
            throw new IOException("Unsupported lzip member size");
        }
        if (expectedMemberSize != memberSize) {
            throw new IOException(
                    "Lzip member size mismatch: expected " + expectedMemberSize + " but consumed " + memberSize
            );
        }
        if (expectedDataSize != dataSize) {
            throw new IOException(
                    "Lzip data size mismatch: expected " + expectedDataSize + " but decoded " + dataSize
            );
        }
        if (expectedChecksum != (int) checksum.getValue()) {
            throw new IOException("Lzip member CRC-32 mismatch");
        }
    }

    /// Copies available source bytes into one fixed metadata field.
    private int collect(ByteBuffer source, byte[] target, int collected) throws IOException {
        int count = Math.min(source.remaining(), target.length - collected);
        source.get(target, collected, count);
        addMemberBytes(count);
        return collected + count;
    }

    /// Records a newly produced target range in member size and checksum state.
    private void recordOutput(ByteBuffer target, int start, int end) throws IOException {
        if (end == start) {
            return;
        }
        ByteBuffer view = target.duplicate();
        view.position(start);
        view.limit(end);
        checksum.update(view);
        try {
            dataSize = Math.addExact(dataSize, end - start);
        } catch (ArithmeticException exception) {
            throw new IOException("Lzip decoded data size overflow", exception);
        }
    }

    /// Adds compressed bytes to the active member size with overflow protection.
    private void addMemberBytes(int count) throws IOException {
        try {
            memberSize = Math.addExact(memberSize, count);
        } catch (ArithmeticException exception) {
            throw new IOException("Lzip member size overflow", exception);
        }
    }

    /// Returns an input request or a phase-specific truncation failure.
    private static CodecOutcome requireMoreInput(boolean endOfInput, String message) throws EOFException {
        if (endOfInput) {
            throw new EOFException(message);
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Returns the active nested raw LZMA decoder.
    private CompressionDecoder requirePayloadDecoder() {
        @Nullable CompressionDecoder current = payloadDecoder;
        if (current == null) {
            throw new IllegalStateException("Lzip payload decoder is unavailable");
        }
        return current;
    }

    /// Releases the active nested raw LZMA decoder.
    private void releasePayload() {
        @Nullable CompressionDecoder current = payloadDecoder;
        payloadDecoder = null;
        if (current != null) {
            current.close();
        }
    }

    /// Requires this decoder not to have been closed.
    private void requireOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("Lzip decoder is closed");
        }
    }

    /// Enumerates the fixed lzip member decoding phases.
    @NotNullByDefault
    private enum Phase {
        /// The six-byte member header is being collected.
        HEADER,

        /// The EOS-terminated raw LZMA payload is being decoded.
        PAYLOAD,

        /// The fixed integrity trailer is being collected.
        TRAILER,

        /// One complete member has been validated.
        FINISHED,

        /// Decoder resources have been released.
        CLOSED
    }
}
