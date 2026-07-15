// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.internal.deflate.DeflateDecoderEngine;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.CRC32;

/// Incrementally decodes and validates one gzip member without binding codec state to an input channel.
@NotNullByDefault
public final class GzipDecoder implements CompressionDecoder {
    /// Gzip flag indicating an extra field.
    private static final int FLAG_EXTRA = 0x04;

    /// Gzip flag indicating an original file name.
    private static final int FLAG_NAME = 0x08;

    /// Gzip flag indicating a comment.
    private static final int FLAG_COMMENT = 0x10;

    /// Gzip flag indicating a header checksum.
    private static final int FLAG_HEADER_CRC = 0x02;

    /// Reserved gzip flag bits rejected by the format.
    private static final int RESERVED_FLAGS = 0xe0;

    /// Shared pure Java raw Deflate decoder used for the member body.
    private final DeflateDecoderEngine body = new DeflateDecoderEngine(
            DeflateDecoderEngine.Format.DEFLATE,
            null
    );

    /// CRC-32 of the complete encoded member header.
    private final CRC32 headerChecksum = new CRC32();

    /// CRC-32 of uncompressed member content produced by the decoder.
    private final CRC32 contentChecksum = new CRC32();

    /// Mutable storage for the fixed-size member trailer while it arrives incrementally.
    private final byte[] trailer = new byte[8];

    /// Current decoder lifecycle and parser state.
    private State state = State.HEADER_FIXED;

    /// Number of fixed header bytes already parsed.
    private int fixedHeaderBytes;

    /// Optional header flags not yet parsed.
    private int remainingHeaderFlags;

    /// Number of little-endian extra-length bytes already parsed.
    private int extraLengthBytes;

    /// Parsed extra-field length.
    private int extraLength;

    /// Number of extra-field payload bytes still expected.
    private int extraBytesRemaining;

    /// Number of little-endian header-checksum bytes already parsed.
    private int headerChecksumBytes;

    /// Parsed expected 16-bit header checksum.
    private int expectedHeaderChecksum;

    /// Uncompressed member size modulo 2^32.
    private long memberSize;

    /// Number of member trailer bytes already parsed.
    private int trailerBytes;

    /// Creates a gzip member decoder.
    public GzipDecoder() {
    }

    /// Decodes source bytes until input, output space, or the member boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            if (isHeaderState()) {
                if (!parseHeader(source, endOfInput)) {
                    return CodecOutcome.NEEDS_INPUT;
                }
                continue;
            }

            if (state == State.BODY) {
                int targetPosition = target.position();
                CodecOutcome outcome;
                try {
                    outcome = body.decode(source, target, endOfInput);
                } catch (EOFException exception) {
                    EOFException translated = new EOFException("Unexpected end of gzip member data");
                    translated.initCause(exception);
                    throw translated;
                } catch (IOException exception) {
                    throw new IOException("Invalid gzip deflate data", exception);
                }
                updateContentChecksum(target, targetPosition);
                if (outcome == CodecOutcome.FINISHED) {
                    state = State.TRAILER;
                    continue;
                }
                return outcome;
            }

            if (state == State.TRAILER) {
                while (source.hasRemaining() && trailerBytes < trailer.length) {
                    trailer[trailerBytes++] = source.get();
                }
                if (trailerBytes < trailer.length) {
                    if (endOfInput) {
                        throw new EOFException("Unexpected end of gzip member trailer");
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                validateTrailer();
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }

            throw new AssertionError("Unexpected gzip decoder state: " + state);
        }
    }

    /// Abandons the current member and restores a fresh gzip parser and Deflate context.
    @Override
    public void reset() {
        requireOpen();
        body.reset();
        headerChecksum.reset();
        contentChecksum.reset();
        fixedHeaderBytes = 0;
        remainingHeaderFlags = 0;
        extraLengthBytes = 0;
        extraLength = 0;
        extraBytesRemaining = 0;
        headerChecksumBytes = 0;
        expectedHeaderChecksum = 0;
        memberSize = 0L;
        trailerBytes = 0;
        state = State.HEADER_FIXED;
    }

    /// Releases decoder-owned state without consuming additional input.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            body.close();
            state = State.CLOSED;
        }
    }

    /// Parses all currently available member header bytes.
    private boolean parseHeader(ByteBuffer source, boolean endOfInput) throws IOException {
        while (isHeaderState()) {
            if (!source.hasRemaining()) {
                if (endOfInput) {
                    throw new EOFException("Unexpected end of gzip member header");
                }
                return false;
            }

            switch (state) {
                case HEADER_FIXED -> parseFixedHeaderByte(source);
                case HEADER_EXTRA_LENGTH -> parseExtraLengthByte(source);
                case HEADER_EXTRA -> parseExtraByte(source);
                case HEADER_NAME, HEADER_COMMENT -> parseZeroTerminatedFieldByte(source);
                case HEADER_CRC -> parseHeaderChecksumByte(source);
                default -> throw new AssertionError("Unexpected gzip header state: " + state);
            }
        }
        return true;
    }

    /// Parses and validates one byte of the ten-byte fixed gzip header.
    private void parseFixedHeaderByte(ByteBuffer source) throws IOException {
        int value = readChecksummedHeaderByte(source);
        switch (fixedHeaderBytes) {
            case 0 -> {
                if (value != 0x1f) {
                    throw new IOException("Invalid gzip member signature");
                }
            }
            case 1 -> {
                if (value != 0x8b) {
                    throw new IOException("Invalid gzip member signature");
                }
            }
            case 2 -> {
                if (value != 8) {
                    throw new IOException("Unsupported gzip compression method");
                }
            }
            case 3 -> {
                if ((value & RESERVED_FLAGS) != 0) {
                    throw new IOException("Gzip member uses reserved flags");
                }
                remainingHeaderFlags = value;
            }
            default -> {
                // Remaining fixed metadata does not affect decompression.
            }
        }

        fixedHeaderBytes++;
        if (fixedHeaderBytes == 10) {
            selectNextHeaderField();
        }
    }

    /// Parses one byte of the optional little-endian extra-field length.
    private void parseExtraLengthByte(ByteBuffer source) {
        int value = readChecksummedHeaderByte(source);
        extraLength |= value << (extraLengthBytes * 8);
        extraLengthBytes++;
        if (extraLengthBytes == 2) {
            extraBytesRemaining = extraLength;
            if (extraBytesRemaining == 0) {
                selectNextHeaderField();
            } else {
                state = State.HEADER_EXTRA;
            }
        }
    }

    /// Parses one byte of the optional extra-field payload.
    private void parseExtraByte(ByteBuffer source) {
        readChecksummedHeaderByte(source);
        extraBytesRemaining--;
        if (extraBytesRemaining == 0) {
            selectNextHeaderField();
        }
    }

    /// Parses one byte of an optional zero-terminated name or comment field.
    private void parseZeroTerminatedFieldByte(ByteBuffer source) {
        if (readChecksummedHeaderByte(source) == 0) {
            selectNextHeaderField();
        }
    }

    /// Parses and validates one byte of the optional header checksum.
    private void parseHeaderChecksumByte(ByteBuffer source) throws IOException {
        expectedHeaderChecksum |= Byte.toUnsignedInt(source.get()) << (headerChecksumBytes * 8);
        headerChecksumBytes++;
        if (headerChecksumBytes == 2) {
            if (expectedHeaderChecksum != ((int) headerChecksum.getValue() & 0xffff)) {
                throw new IOException("Gzip member header checksum mismatch");
            }
            beginBody();
        }
    }

    /// Chooses the next optional header field in gzip format order or begins the member body.
    private void selectNextHeaderField() {
        if ((remainingHeaderFlags & FLAG_EXTRA) != 0) {
            remainingHeaderFlags &= ~FLAG_EXTRA;
            extraLengthBytes = 0;
            extraLength = 0;
            state = State.HEADER_EXTRA_LENGTH;
        } else if ((remainingHeaderFlags & FLAG_NAME) != 0) {
            remainingHeaderFlags &= ~FLAG_NAME;
            state = State.HEADER_NAME;
        } else if ((remainingHeaderFlags & FLAG_COMMENT) != 0) {
            remainingHeaderFlags &= ~FLAG_COMMENT;
            state = State.HEADER_COMMENT;
        } else if ((remainingHeaderFlags & FLAG_HEADER_CRC) != 0) {
            remainingHeaderFlags &= ~FLAG_HEADER_CRC;
            headerChecksumBytes = 0;
            expectedHeaderChecksum = 0;
            state = State.HEADER_CRC;
        } else {
            beginBody();
        }
    }

    /// Begins raw Deflate decoding after the complete member header is validated.
    private void beginBody() {
        contentChecksum.reset();
        memberSize = 0L;
        state = State.BODY;
    }

    /// Reads one header byte and includes it in the running header checksum.
    private int readChecksummedHeaderByte(ByteBuffer source) {
        int value = Byte.toUnsignedInt(source.get());
        headerChecksum.update(value);
        return value;
    }

    /// Updates member accounting for the target range produced by the Deflate engine.
    private void updateContentChecksum(ByteBuffer target, int start) {
        int end = target.position();
        if (start == end) {
            return;
        }
        ByteBuffer produced = target.duplicate();
        produced.position(start);
        produced.limit(end);
        contentChecksum.update(produced);
        memberSize = (memberSize + end - start) & 0xffff_ffffL;
    }

    /// Validates the completed member checksum and uncompressed-size trailer.
    private void validateTrailer() throws IOException {
        long expectedChecksum = littleEndianUnsignedInt(trailer, 0);
        long expectedSize = littleEndianUnsignedInt(trailer, 4);
        if (expectedChecksum != contentChecksum.getValue()) {
            throw new IOException("Gzip member checksum mismatch");
        }
        if (expectedSize != memberSize) {
            throw new IOException("Gzip member size mismatch");
        }
    }

    /// Reads one unsigned little-endian 32-bit value from a fixed byte array.
    private static long littleEndianUnsignedInt(byte[] source, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(source[offset])
                        | Byte.toUnsignedInt(source[offset + 1]) << 8
                        | Byte.toUnsignedInt(source[offset + 2]) << 16
                        | Byte.toUnsignedInt(source[offset + 3]) << 24
        );
    }

    /// Returns whether the decoder is currently parsing member header metadata.
    private boolean isHeaderState() {
        return switch (state) {
            case HEADER_FIXED, HEADER_EXTRA_LENGTH, HEADER_EXTRA, HEADER_NAME, HEADER_COMMENT, HEADER_CRC -> true;
            default -> false;
        };
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Gzip decoder is closed");
        }
    }

    /// Tracks the explicit gzip member parser and decoder lifecycle.
    private enum State {
        /// The fixed ten-byte member header is being parsed.
        HEADER_FIXED,

        /// The optional two-byte extra-field length is being parsed.
        HEADER_EXTRA_LENGTH,

        /// The optional extra-field payload is being skipped.
        HEADER_EXTRA,

        /// The optional zero-terminated original file name is being skipped.
        HEADER_NAME,

        /// The optional zero-terminated comment is being skipped.
        HEADER_COMMENT,

        /// The optional header checksum is being parsed and validated.
        HEADER_CRC,

        /// Raw Deflate member content is being decoded.
        BODY,

        /// The fixed eight-byte member trailer is being parsed and validated.
        TRAILER,

        /// The member completed and may only be reset or closed.
        FINISHED,

        /// Native resources were released.
        CLOSED
    }
}
