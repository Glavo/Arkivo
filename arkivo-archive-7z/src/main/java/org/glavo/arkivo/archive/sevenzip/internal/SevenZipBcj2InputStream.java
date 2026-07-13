// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.archive.sevenzip.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/// Merges the four streams produced by the 7z BCJ2 x86 branch converter.
@NotNullByDefault
final class SevenZipBcj2InputStream extends InputStream {
    /// Indicates that the exact input stream sizes are unavailable.
    private static final long UNKNOWN_SIZE = -1L;

    /// The number of adaptive probability models used by BCJ2.
    private static final int PROBABILITY_COUNT = 258;

    /// The total probability scale used by the range decoder.
    private static final int PROBABILITY_TOTAL = 1 << 11;

    /// The adaptation shift used by each probability model.
    private static final int PROBABILITY_MOVE_BITS = 5;

    /// The threshold below which the range decoder consumes another byte.
    private static final long RANGE_TOP_VALUE = 1L << 24;

    /// The initial unsigned 32-bit range.
    private static final long INITIAL_RANGE = 0xffff_ffffL;

    /// The unconverted instruction stream.
    private final InputStream main;

    /// The stream of big-endian CALL target addresses.
    private final InputStream call;

    /// The stream of big-endian JUMP target addresses.
    private final InputStream jump;

    /// The range-coded branch decision stream.
    private final InputStream rangeInput;

    /// The reusable buffer for the usually dominant BCJ2 main stream.
    private final byte[] mainBuffer = new byte[8192];

    /// The exact number of bytes exposed by this stream.
    private final long outputLimit;

    /// The complete decoded stream size declared by the folder graph.
    private final long expectedOutputSize;

    /// The declared MAIN stream size, or `UNKNOWN_SIZE`.
    private final long mainSize;

    /// The declared CALL stream size, or `UNKNOWN_SIZE`.
    private final long callSize;

    /// The declared JUMP stream size, or `UNKNOWN_SIZE`.
    private final long jumpSize;

    /// The declared range stream size, or `UNKNOWN_SIZE`.
    private final long rangeSize;

    /// The adaptive BCJ2 probability models.
    private final int[] probabilities = new int[PROBABILITY_COUNT];

    /// The five-byte range decoder header while it is being read.
    private final byte[] rangeHeader = new byte[5];

    /// The current four-byte CALL or JUMP address while it is being read.
    private final byte[] branchAddress = new byte[4];

    /// The converted little-endian relative address waiting to be emitted.
    private final byte[] convertedAddress = new byte[4];

    /// The number of range header bytes read so far.
    private int rangeHeaderLength;

    /// The next unread byte in `mainBuffer`.
    private int mainBufferPosition;

    /// The exclusive end of valid bytes in `mainBuffer`.
    private int mainBufferLimit;

    /// The number of MAIN bytes fetched into the reusable buffer.
    private long mainBytesRead;

    /// The number of MAIN bytes consumed by the decoder.
    private long mainBytesConsumed;

    /// The number of CALL bytes consumed by the decoder.
    private long callBytesConsumed;

    /// The number of JUMP bytes consumed by the decoder.
    private long jumpBytesConsumed;

    /// The number of range bytes consumed by the decoder.
    private long rangeBytesConsumed;

    /// Whether the range decoder has been initialized.
    private boolean rangeInitialized;

    /// The current unsigned 32-bit range decoder range.
    private long range;

    /// The current unsigned 32-bit range decoder code.
    private long code;

    /// The number of decoded output bytes.
    private long outputPosition;

    /// The x86 instruction pointer, wrapping modulo 2^32.
    private int instructionPointer;

    /// The most recently emitted byte, or zero before the first byte.
    private int previousOutputByte;

    /// Whether an emitted marker still needs a range-coded decision.
    private boolean markerDecisionPending;

    /// The probability index for the pending marker.
    private int markerProbabilityIndex;

    /// Whether the pending marker is a CALL marker rather than a JUMP marker.
    private boolean markerUsesCallStream;

    /// Whether a selected branch address is still being read.
    private boolean branchAddressPending;

    /// Whether the selected branch address comes from the CALL stream.
    private boolean branchAddressUsesCallStream;

    /// The number of selected branch address bytes read so far.
    private int branchAddressLength;

    /// The next converted address byte to emit.
    private int convertedAddressPosition = convertedAddress.length;

    /// Whether this stream has been closed.
    private boolean closed;

    /// Whether full-stream terminal state has been validated.
    private boolean finished;

    /// Creates a BCJ2 stream that exposes exactly `outputLimit` decoded bytes.
    SevenZipBcj2InputStream(
            InputStream main,
            InputStream call,
            InputStream jump,
            InputStream range,
            long outputLimit
    ) {
        this(
                main,
                call,
                jump,
                range,
                UNKNOWN_SIZE,
                UNKNOWN_SIZE,
                UNKNOWN_SIZE,
                UNKNOWN_SIZE,
                outputLimit,
                outputLimit
        );
    }

    /// Creates a BCJ2 stream with exact input and complete output sizes from a 7z folder graph.
    SevenZipBcj2InputStream(
            InputStream main,
            InputStream call,
            InputStream jump,
            InputStream range,
            long mainSize,
            long callSize,
            long jumpSize,
            long rangeSize,
            long outputLimit,
            long expectedOutputSize
    ) {
        if (outputLimit < 0) {
            throw new IllegalArgumentException("outputLimit must be non-negative");
        }
        if (expectedOutputSize < outputLimit) {
            throw new IllegalArgumentException("expectedOutputSize must not be less than outputLimit");
        }
        boolean sizesKnown = mainSize != UNKNOWN_SIZE
                || callSize != UNKNOWN_SIZE
                || jumpSize != UNKNOWN_SIZE
                || rangeSize != UNKNOWN_SIZE;
        if (sizesKnown) {
            if (mainSize < 0 || callSize < 0 || jumpSize < 0 || rangeSize < 0) {
                throw new IllegalArgumentException("BCJ2 input sizes must all be known or all be UNKNOWN_SIZE");
            }
            if ((callSize & 3L) != 0L || (jumpSize & 3L) != 0L) {
                throw new IllegalArgumentException("BCJ2 CALL and JUMP stream sizes must be multiples of four");
            }
            if (rangeSize < rangeHeader.length) {
                throw new IllegalArgumentException("BCJ2 range stream must contain at least five bytes");
            }
            long calculatedOutputSize;
            try {
                calculatedOutputSize = Math.addExact(Math.addExact(mainSize, callSize), jumpSize);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("BCJ2 output size is too large", exception);
            }
            if (expectedOutputSize != calculatedOutputSize) {
                throw new IllegalArgumentException("BCJ2 output size must equal MAIN + CALL + JUMP stream sizes");
            }
        }
        this.main = Objects.requireNonNull(main, "main");
        this.call = Objects.requireNonNull(call, "call");
        this.jump = Objects.requireNonNull(jump, "jump");
        this.rangeInput = Objects.requireNonNull(range, "range");
        this.mainSize = mainSize;
        this.callSize = callSize;
        this.jumpSize = jumpSize;
        this.rangeSize = rangeSize;
        this.outputLimit = outputLimit;
        this.expectedOutputSize = expectedOutputSize;
        Arrays.fill(probabilities, PROBABILITY_TOTAL >>> 1);
    }

    /// Reads one decoded byte.
    @Override
    public int read() throws IOException {
        ensureOpen();
        return readDecodedByte();
    }

    /// Reads decoded bytes into the target array.
    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }
        if (outputPosition == outputLimit) {
            if (outputLimit == expectedOutputSize) {
                validateFinished();
            }
            return -1;
        }

        int count = 0;
        while (count < length && outputPosition < outputLimit) {
            buffer[offset + count] = (byte) readDecodedByte();
            count++;
        }
        return count;
    }

    /// Closes all four BCJ2 input streams while retaining later failures as suppressed exceptions.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;

        @Nullable Throwable failure = null;
        if (mainSize != UNKNOWN_SIZE
                && outputPosition == outputLimit
                && outputLimit == expectedOutputSize
                && !finished) {
            try {
                validateFinished();
            } catch (IOException | RuntimeException | Error exception) {
                failure = exception;
            }
        }
        failure = closeInput(main, failure);
        failure = closeInput(call, failure);
        failure = closeInput(jump, failure);
        failure = closeInput(rangeInput, failure);
        throwCloseFailure(failure);
    }

    /// Reads the next decoded byte without checking whether this stream is open.
    private int readDecodedByte() throws IOException {
        if (outputPosition == outputLimit) {
            if (outputLimit == expectedOutputSize) {
                validateFinished();
            }
            return -1;
        }
        ensureRangeInitialized();

        for (;;) {
            if (convertedAddressPosition < convertedAddress.length) {
                return emit(Byte.toUnsignedInt(convertedAddress[convertedAddressPosition++]));
            }
            if (branchAddressPending) {
                prepareConvertedAddress();
                continue;
            }
            if (markerDecisionPending) {
                decodeMarkerDecision();
                continue;
            }

            int value = readMainByte();
            int precedingByte = previousOutputByte;
            if ((value & 0xfe) == 0xe8) {
                markerDecisionPending = true;
                markerUsesCallStream = value == 0xe8;
                markerProbabilityIndex = markerUsesCallStream ? precedingByte + 2 : 1;
            } else if (precedingByte == 0x0f && (value & 0xf0) == 0x80) {
                markerDecisionPending = true;
                markerUsesCallStream = false;
                markerProbabilityIndex = 0;
            }
            return emit(value);
        }
    }

    /// Initializes the range decoder from its five-byte header.
    private void ensureRangeInitialized() throws IOException {
        if (rangeInitialized) {
            return;
        }
        while (rangeHeaderLength < rangeHeader.length) {
            int count = readAtLeastOne(
                    rangeInput,
                    rangeHeader,
                    rangeHeaderLength,
                    rangeHeader.length - rangeHeaderLength
            );
            if (count < 0) {
                throw new EOFException("Unexpected end of 7z BCJ2 range stream");
            }
            rangeHeaderLength += count;
            rangeBytesConsumed += count;
        }

        if (rangeHeader[0] != 0) {
            throw new IOException("Invalid 7z BCJ2 range stream header");
        }
        code = readUnsignedBigEndianInt(rangeHeader, 1);
        if (code == INITIAL_RANGE) {
            throw new IOException("Invalid 7z BCJ2 range stream code");
        }
        range = INITIAL_RANGE;
        rangeInitialized = true;
    }

    /// Applies the range-coded decision associated with the most recently emitted marker.
    private void decodeMarkerDecision() throws IOException {
        normalizeRange();

        int probability = probabilities[markerProbabilityIndex];
        long bound = (range >>> 11) * probability;
        if (code < bound) {
            range = bound;
            probabilities[markerProbabilityIndex] =
                    probability + ((PROBABILITY_TOTAL - probability) >>> PROBABILITY_MOVE_BITS);
            markerDecisionPending = false;
            return;
        }

        range -= bound;
        code -= bound;
        probabilities[markerProbabilityIndex] = probability - (probability >>> PROBABILITY_MOVE_BITS);
        markerDecisionPending = false;
        branchAddressPending = true;
        branchAddressUsesCallStream = markerUsesCallStream;
        branchAddressLength = 0;
    }

    /// Normalizes the range decoder before decoding one decision bit.
    private void normalizeRange() throws IOException {
        if (range >= RANGE_TOP_VALUE) {
            return;
        }
        range <<= 8;
        code = ((code << 8) | readRangeByte()) & INITIAL_RANGE;
    }

    /// Reads a selected absolute branch address and prepares its relative little-endian form.
    private void prepareConvertedAddress() throws IOException {
        InputStream input = branchAddressUsesCallStream ? call : jump;
        String failureMessage = branchAddressUsesCallStream
                ? "Unexpected end of 7z BCJ2 CALL stream"
                : "Unexpected end of 7z BCJ2 JUMP stream";
        while (branchAddressLength < branchAddress.length) {
            int count = readAtLeastOne(
                    input,
                    branchAddress,
                    branchAddressLength,
                    branchAddress.length - branchAddressLength
            );
            if (count < 0) {
                throw new EOFException(failureMessage);
            }
            branchAddressLength += count;
            if (branchAddressUsesCallStream) {
                callBytesConsumed += count;
            } else {
                jumpBytesConsumed += count;
            }
        }

        int absoluteAddress = (int) readUnsignedBigEndianInt(branchAddress, 0);
        int relativeAddress = absoluteAddress - (instructionPointer + 4);
        convertedAddress[0] = (byte) relativeAddress;
        convertedAddress[1] = (byte) (relativeAddress >>> 8);
        convertedAddress[2] = (byte) (relativeAddress >>> 16);
        convertedAddress[3] = (byte) (relativeAddress >>> 24);
        convertedAddressPosition = 0;
        branchAddressPending = false;
    }

    /// Reads one required main-stream byte through the reusable bulk buffer.
    private int readMainByte() throws IOException {
        if (mainBufferPosition == mainBufferLimit) {
            int length = mainBuffer.length;
            if (mainSize != UNKNOWN_SIZE) {
                long remaining = mainSize - mainBytesRead;
                if (remaining == 0) {
                    throw new EOFException("Unexpected end of 7z BCJ2 main stream");
                }
                length = (int) Math.min(length, remaining);
            }
            int count = readAtLeastOne(main, mainBuffer, 0, length);
            if (count < 0) {
                throw new EOFException("Unexpected end of 7z BCJ2 main stream");
            }
            mainBufferPosition = 0;
            mainBufferLimit = count;
            mainBytesRead += count;
        }
        mainBytesConsumed++;
        return Byte.toUnsignedInt(mainBuffer[mainBufferPosition++]);
    }

    /// Emits one byte and advances the wrapping x86 instruction pointer.
    private int emit(int value) throws IOException {
        outputPosition++;
        instructionPointer++;
        previousOutputByte = value;
        if (outputPosition == outputLimit && outputLimit == expectedOutputSize) {
            validateFinished();
        }
        return value;
    }

    /// Validates the range decoder state and exact four-stream consumption at complete logical EOF.
    private void validateFinished() throws IOException {
        if (finished) {
            return;
        }
        ensureRangeInitialized();
        if (markerDecisionPending) {
            decodeMarkerDecision();
        }
        if (branchAddressPending || convertedAddressPosition < convertedAddress.length) {
            throw new IOException("7z BCJ2 stream requires branch bytes beyond its declared output size");
        }
        if (range < RANGE_TOP_VALUE && (rangeSize == UNKNOWN_SIZE || rangeBytesConsumed < rangeSize)) {
            normalizeRange();
        }
        if (code != 0) {
            throw new IOException("7z BCJ2 range decoder did not finish with a zero code");
        }
        if (mainSize != UNKNOWN_SIZE) {
            if (mainBytesConsumed != mainSize) {
                throw new IOException("7z BCJ2 MAIN stream was not consumed exactly");
            }
            if (callBytesConsumed != callSize) {
                throw new IOException("7z BCJ2 CALL stream was not consumed exactly");
            }
            if (jumpBytesConsumed != jumpSize) {
                throw new IOException("7z BCJ2 JUMP stream was not consumed exactly");
            }
            if (rangeBytesConsumed != rangeSize) {
                throw new IOException("7z BCJ2 range stream was not consumed exactly");
            }
            requireEndOfInput(main, "MAIN");
            requireEndOfInput(call, "CALL");
            requireEndOfInput(jump, "JUMP");
            requireEndOfInput(rangeInput, "range");
        }
        finished = true;
    }

    /// Requires one declared BCJ2 input to contain no additional decoded bytes.
    private static void requireEndOfInput(InputStream input, String streamName) throws IOException {
        if (input.read() >= 0) {
            throw new IOException("7z BCJ2 " + streamName + " stream exceeds its declared size");
        }
    }

    /// Reads one required byte from the range stream without exceeding its declared size.
    private int readRangeByte() throws IOException {
        if (rangeSize != UNKNOWN_SIZE && rangeBytesConsumed == rangeSize) {
            throw new EOFException("Unexpected end of 7z BCJ2 range stream");
        }
        int value = readRequiredByte(rangeInput, "Unexpected end of 7z BCJ2 range stream");
        rangeBytesConsumed++;
        return value;
    }

    /// Reads at least one byte unless the source is at EOF.
    private static int readAtLeastOne(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int count = input.read(buffer, offset, length);
        if (count != 0) {
            return count;
        }
        int value = input.read();
        if (value < 0) {
            return -1;
        }
        buffer[offset] = (byte) value;
        return 1;
    }

    /// Reads one required byte from an input stream.
    private static int readRequiredByte(InputStream input, String failureMessage) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException(failureMessage);
        }
        return value;
    }

    /// Reads an unsigned big-endian 32-bit integer into a long.
    private static long readUnsignedBigEndianInt(byte[] bytes, int offset) {
        return ((long) Byte.toUnsignedInt(bytes[offset]) << 24)
                | ((long) Byte.toUnsignedInt(bytes[offset + 1]) << 16)
                | ((long) Byte.toUnsignedInt(bytes[offset + 2]) << 8)
                | Byte.toUnsignedInt(bytes[offset + 3]);
    }

    /// Closes one input and appends any failure to the current primary failure.
    private static @Nullable Throwable closeInput(InputStream input, @Nullable Throwable failure) {
        try {
            input.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                return exception;
            }
            if (failure != exception) {
                failure.addSuppressed(exception);
            }
        }
        return failure;
    }

    /// Rethrows a close failure with its original checked or unchecked type.
    private static void throwCloseFailure(@Nullable Throwable failure) throws IOException {
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error exception) {
            throw exception;
        }
    }

    /// Requires this stream to be open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("stream is closed");
        }
    }
}
