// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionLimits;
import org.glavo.arkivo.codec.compress.UnixCompressFormat;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally decodes a Unix compress stream without retaining caller-owned buffers.
@NotNullByDefault
public final class UnixCompressDecoder implements CompressionDecoder {
    /// Operation-scoped output, history, and working-memory limits.
    private final DecompressionLimits limits;

    /// Incrementally collected fixed stream header.
    private final byte[] header = new byte[UnixCompressSupport.HEADER_SIZE];

    /// Number of collected header bytes.
    private int headerSize;

    /// Prefix code for every allocated LZW dictionary entry.
    private int @Nullable [] prefixes;

    /// Final byte for every literal and allocated LZW dictionary entry.
    private byte @Nullable [] suffixes;

    /// Reverse-order expansion storage for the current decoded code.
    private byte @Nullable [] outputStack;

    /// Number of bytes currently waiting in `outputStack`.
    private int outputStackSize;

    /// Declared maximum LZW code width.
    private int maximumCodeWidth;

    /// Exclusive upper bound for dictionary codes.
    private int tableLimit;

    /// Whether the stream permits dictionary clear codes.
    private boolean blockMode;

    /// Current LZW code width.
    private int codeWidth;

    /// First unused dictionary code.
    private int nextCode;

    /// Previously expanded code, or minus one before the first code after a reset.
    private int previousCode;

    /// First decoded byte of the previously expanded code.
    private byte previousFirstByte;

    /// Low-order encoded bits retained between decode calls.
    private long bitBuffer;

    /// Number of valid low-order bits in `bitBuffer`.
    private int bitCount;

    /// Number of codes read in the current eight-code alignment group.
    private int codesInGroup;

    /// Action applied after old-width alignment bytes have been consumed.
    private AlignmentAction alignmentAction;

    /// Current decoder lifecycle phase.
    private State state = State.HEADER;

    /// Creates a decoder that enforces the supplied operation-scoped limits after reading the header.
    public UnixCompressDecoder(DecompressionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
        initializeBitState();
    }

    /// Decodes while allowing additional compressed bytes in a later operation.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Decodes after all remaining physical stream bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Abandons current input and restores fixed-header collection for a new stream.
    @Override
    public void reset() {
        requireOpen();
        headerSize = 0;
        prefixes = null;
        suffixes = null;
        outputStack = null;
        outputStackSize = 0;
        initializeBitState();
        state = State.HEADER;
    }

    /// Releases dictionary arrays without consuming additional source bytes.
    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        prefixes = null;
        suffixes = null;
        outputStack = null;
        outputStackSize = 0;
        bitBuffer = 0L;
        bitCount = 0;
        state = State.CLOSED;
    }

    /// Implements incremental decoding with an explicit physical-input completion state.
    private CodecOutcome decodeInternal(
            ByteBuffer source,
            ByteBuffer target,
            boolean endOfInput
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        if (state == State.HEADER) {
            collectHeader(source);
            if (headerSize != header.length) {
                if (endOfInput) {
                    throw new EOFException("Truncated Unix compress stream header");
                }
                return CodecOutcome.NEEDS_INPUT;
            }
            initializeTables();
            state = State.CODES;
        }

        while (true) {
            if (outputStackSize != 0) {
                drainOutputStack(target);
                if (outputStackSize != 0) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
            }

            if (alignmentAction != AlignmentAction.NONE) {
                if (!consumeAlignment(source)) {
                    if (endOfInput) {
                        finishAtPhysicalEnd();
                        return CodecOutcome.FINISHED;
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                applyAlignmentAction();
                continue;
            }

            int code = tryReadCode(source);
            if (code < 0) {
                if (endOfInput) {
                    finishAtPhysicalEnd();
                    return CodecOutcome.FINISHED;
                }
                return CodecOutcome.NEEDS_INPUT;
            }

            if (blockMode && code == UnixCompressSupport.CLEAR_CODE) {
                nextCode = UnixCompressSupport.initialNextCode(true);
                previousCode = -1;
                alignmentAction = AlignmentAction.RESET_CODE_WIDTH;
                continue;
            }
            expandCode(code);
        }
    }

    /// Collects as many fixed header bytes as the current source provides.
    private void collectHeader(ByteBuffer source) {
        int count = Math.min(source.remaining(), header.length - headerSize);
        source.get(header, headerSize, count);
        headerSize += count;
    }

    /// Validates the header, enforces declared resource requirements, and allocates decoder tables.
    private void initializeTables() throws IOException {
        if (Byte.toUnsignedInt(header[0]) != UnixCompressFormat.MAGIC_FIRST_BYTE
                || Byte.toUnsignedInt(header[1]) != UnixCompressFormat.MAGIC_SECOND_BYTE) {
            throw new IOException("Invalid Unix compress stream signature");
        }
        int flags = Byte.toUnsignedInt(header[2]);
        if ((flags & UnixCompressSupport.RESERVED_FLAGS_MASK) != 0) {
            throw new IOException("Unsupported reserved Unix compress header flags");
        }

        int declaredCodeWidth = flags & UnixCompressSupport.MAXIMUM_CODE_WIDTH_MASK;
        try {
            maximumCodeWidth = UnixCompressSupport.requireMaximumCodeWidth(declaredCodeWidth);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid Unix compress maximum code width: " + declaredCodeWidth, exception);
        }
        blockMode = (flags & UnixCompressSupport.BLOCK_MODE_MASK) != 0;
        tableLimit = UnixCompressSupport.tableCapacity(maximumCodeWidth);
        limits.requireWindowSize(tableLimit);
        limits.requireMemorySize(UnixCompressSupport.decoderMemorySize(maximumCodeWidth));

        prefixes = new int[tableLimit];
        Arrays.fill(prefixes, -1);
        suffixes = new byte[tableLimit];
        for (int value = 0; value < 256; value++) {
            suffixes[value] = (byte) value;
        }
        outputStack = new byte[tableLimit];
        codeWidth = UnixCompressSupport.INITIAL_CODE_WIDTH;
        nextCode = UnixCompressSupport.initialNextCode(blockMode);
        previousCode = -1;
    }

    /// Reads one complete current-width code or returns minus one without consuming an incomplete code.
    private int tryReadCode(ByteBuffer source) {
        while (bitCount < codeWidth && source.hasRemaining()) {
            bitBuffer |= (long) Byte.toUnsignedInt(source.get()) << bitCount;
            bitCount += Byte.SIZE;
        }
        if (bitCount < codeWidth) {
            return -1;
        }
        int mask = (1 << codeWidth) - 1;
        int code = (int) bitBuffer & mask;
        bitBuffer >>>= codeWidth;
        bitCount -= codeWidth;
        codesInGroup = codesInGroup + 1 & 7;
        return code;
    }

    /// Consumes old-width filler codes until the next eight-code group boundary.
    private boolean consumeAlignment(ByteBuffer source) {
        while (codesInGroup != 0) {
            if (tryReadCode(source) < 0) {
                return false;
            }
        }
        if (bitCount != 0) {
            throw new IllegalStateException("Unix compress alignment did not end on a byte boundary");
        }
        return true;
    }

    /// Applies a pending code-width increment or clear-code reset after alignment.
    private void applyAlignmentAction() {
        switch (alignmentAction) {
            case INCREMENT_CODE_WIDTH -> codeWidth++;
            case RESET_CODE_WIDTH -> codeWidth = UnixCompressSupport.INITIAL_CODE_WIDTH;
            case NONE -> throw new IllegalStateException("No Unix compress alignment action is pending");
        }
        alignmentAction = AlignmentAction.NONE;
    }

    /// Expands one validated code, adds the inferred dictionary entry, and schedules width growth.
    private void expandCode(int encodedCode) throws IOException {
        byte[] stack = requireOutputStack();
        int[] prefixTable = requirePrefixes();
        byte[] suffixTable = requireSuffixes();
        int code = encodedCode;
        boolean repeatedPrevious = false;

        if (previousCode < 0) {
            if (code > 255) {
                throw new IOException("The first Unix compress LZW code is not a literal: " + code);
            }
        } else if (code == nextCode) {
            pushStack(stack, previousFirstByte);
            code = previousCode;
            repeatedPrevious = true;
        } else if (code > nextCode) {
            throw new IOException("Invalid Unix compress LZW code: " + encodedCode);
        }

        int traversedEntries = 0;
        while (code >= 256) {
            if (code >= nextCode || code >= tableLimit || prefixTable[code] < 0) {
                throw new IOException("Invalid Unix compress LZW dictionary reference: " + code);
            }
            pushStack(stack, suffixTable[code]);
            code = prefixTable[code];
            if (++traversedEntries > tableLimit) {
                throw new IOException("Cyclic Unix compress LZW dictionary reference");
            }
        }
        byte firstByte = (byte) code;
        pushStack(stack, firstByte);

        if (previousCode >= 0 && nextCode < tableLimit) {
            prefixTable[nextCode] = previousCode;
            suffixTable[nextCode] = firstByte;
            nextCode++;
            if (nextCode == 1 << codeWidth && codeWidth < maximumCodeWidth) {
                alignmentAction = AlignmentAction.INCREMENT_CODE_WIDTH;
            }
        }
        previousCode = repeatedPrevious ? nextCode - 1 : encodedCode;
        previousFirstByte = firstByte;
    }

    /// Pushes one reverse-order expansion byte with malformed-input overflow protection.
    private void pushStack(byte[] stack, byte value) throws IOException {
        if (outputStackSize == stack.length) {
            throw new IOException("Unix compress LZW expansion exceeds the declared dictionary size");
        }
        stack[outputStackSize++] = value;
    }

    /// Copies the current reverse-order expansion into caller target space.
    private void drainOutputStack(ByteBuffer target) {
        byte[] stack = requireOutputStack();
        while (outputStackSize != 0 && target.hasRemaining()) {
            target.put(stack[--outputStackSize]);
        }
    }

    /// Marks successful completion at physical EOF and discards sub-code padding bits.
    private void finishAtPhysicalEnd() {
        bitBuffer = 0L;
        bitCount = 0;
        alignmentAction = AlignmentAction.NONE;
        state = State.FINISHED;
    }

    /// Restores bit-packing fields shared by construction and reset.
    private void initializeBitState() {
        bitBuffer = 0L;
        bitCount = 0;
        codesInGroup = 0;
        alignmentAction = AlignmentAction.NONE;
        previousCode = -1;
    }

    /// Returns the allocated prefix table.
    private int[] requirePrefixes() {
        int @Nullable [] current = prefixes;
        if (current == null) {
            throw new IllegalStateException("Unix compress prefix table is unavailable");
        }
        return current;
    }

    /// Returns the allocated suffix table.
    private byte[] requireSuffixes() {
        byte @Nullable [] current = suffixes;
        if (current == null) {
            throw new IllegalStateException("Unix compress suffix table is unavailable");
        }
        return current;
    }

    /// Returns the allocated output stack.
    private byte[] requireOutputStack() {
        byte @Nullable [] current = outputStack;
        if (current == null) {
            throw new IllegalStateException("Unix compress output stack is unavailable");
        }
        return current;
    }

    /// Requires this decoder not to have been closed.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Unix compress decoder is closed");
        }
    }

    /// Enumerates actions that follow an old-width eight-code alignment group.
    @NotNullByDefault
    private enum AlignmentAction {
        /// No code-group alignment is pending.
        NONE,

        /// The next code uses a width one bit larger.
        INCREMENT_CODE_WIDTH,

        /// The next code uses the initial width after a clear code.
        RESET_CODE_WIDTH
    }

    /// Enumerates Unix compress decoder lifecycle phases.
    @NotNullByDefault
    private enum State {
        /// The fixed stream header is being collected.
        HEADER,

        /// Variable-width LZW codes are being decoded.
        CODES,

        /// Physical EOF completed the stream.
        FINISHED,

        /// Dictionary and output state have been released.
        CLOSED
    }
}
