// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.compress.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.compress.UnixCompressFormat;
import org.jetbrains.annotations.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally encodes a Unix compress stream without retaining caller-owned buffers.
@NotNullByDefault
public final class UnixCompressEncoder implements CompressionEncoder {
    /// The maximum number of privately buffered header, code, and alignment bytes.
    private static final int PENDING_CAPACITY = 32;

    /// The selected maximum LZW code width.
    private final int maximumCodeWidth;

    /// Whether the stream header permits dictionary clear codes.
    private final boolean blockMode;

    /// The exclusive upper bound for dictionary codes.
    private final int tableLimit;

    /// Stored prefix-and-byte keys plus one, with zero denoting an unused slot.
    private final int[] hashKeys;

    /// Dictionary codes corresponding to occupied hash slots.
    private final int[] hashValues;

    /// Mask used for open-addressed hash-table wrapping.
    private final int hashMask;

    /// Owned encoded bytes awaiting caller-provided target space.
    private final byte[] pending = new byte[PENDING_CAPACITY];

    /// First pending byte not yet copied to a caller target.
    private int pendingStart;

    /// Exclusive end of pending encoded bytes.
    private int pendingEnd;

    /// Bits awaiting completion of the next encoded byte, stored least-significant bit first.
    private long bitBuffer;

    /// Number of valid low bits in `bitBuffer`.
    private int bitCount;

    /// Number of codes written in the current eight-code alignment group.
    private int codesInGroup;

    /// Current LZW code width.
    private int codeWidth;

    /// First unused dictionary code.
    private int nextCode;

    /// Current input phrase code, or minus one before any source byte is accepted.
    private int prefixCode;

    /// Whether the fixed stream header has been generated.
    private boolean headerGenerated;

    /// Current encoder lifecycle phase.
    private State state;

    /// Creates a Unix compress encoder for the supplied header parameters.
    ///
    /// @param maximumCodeWidth the largest LZW code width from 9 through 16
    /// @param blockMode whether the stream header permits dictionary clear codes
    /// @throws IllegalArgumentException if `maximumCodeWidth` is outside the supported range
    public UnixCompressEncoder(int maximumCodeWidth, boolean blockMode) {
        this.maximumCodeWidth = UnixCompressSupport.requireMaximumCodeWidth(maximumCodeWidth);
        this.blockMode = blockMode;
        this.tableLimit = UnixCompressSupport.tableCapacity(maximumCodeWidth);
        int hashTableSize = tableLimit << 1;
        this.hashKeys = new int[hashTableSize];
        this.hashValues = new int[hashTableSize];
        this.hashMask = hashTableSize - 1;
        initializeState();
    }

    /// Consumes uncompressed bytes and emits available Unix compress bytes.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");

        generateHeader();
        if (drainPending(target)) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        while (source.hasRemaining()) {
            int symbol = Byte.toUnsignedInt(source.get());
            if (prefixCode < 0) {
                prefixCode = symbol;
            } else {
                int key = prefixCode << Byte.SIZE | symbol;
                int existingCode = findCode(key);
                if (existingCode >= 0) {
                    prefixCode = existingCode;
                } else {
                    emitCode(prefixCode);
                    advanceCodeWidthIfNeeded();
                    if (nextCode < tableLimit) {
                        addCode(key, nextCode++);
                    }
                    prefixCode = symbol;
                }
            }

            if (drainPending(target)) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Emits the final phrase and byte-aligns the complete Unix compress stream.
    @Override
    public CodecOutcome finish(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            generateHeader();
            if (prefixCode >= 0) {
                emitCode(prefixCode);
                prefixCode = -1;
            }
            flushPartialByte();
            state = State.FINISHING;
        }

        if (drainPending(target)) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons current input and restores the encoder's immutable configuration.
    @Override
    public void reset() {
        requireOpen();
        initializeState();
    }

    /// Releases mutable dictionary and pending-output state without finalizing the stream.
    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        Arrays.fill(hashKeys, 0);
        pendingStart = 0;
        pendingEnd = 0;
        bitBuffer = 0L;
        bitCount = 0;
        prefixCode = -1;
        state = State.CLOSED;
    }

    /// Restores all per-stream LZW and bit-packing state.
    private void initializeState() {
        Arrays.fill(hashKeys, 0);
        pendingStart = 0;
        pendingEnd = 0;
        bitBuffer = 0L;
        bitCount = 0;
        codesInGroup = 0;
        codeWidth = UnixCompressSupport.INITIAL_CODE_WIDTH;
        nextCode = UnixCompressSupport.initialNextCode(blockMode);
        prefixCode = -1;
        headerGenerated = false;
        state = State.ACTIVE;
    }

    /// Generates the fixed signature and flag bytes once per stream.
    private void generateHeader() {
        if (headerGenerated) {
            return;
        }
        appendPending(UnixCompressFormat.MAGIC_FIRST_BYTE);
        appendPending(UnixCompressFormat.MAGIC_SECOND_BYTE);
        appendPending(UnixCompressSupport.headerFlags(maximumCodeWidth, blockMode));
        headerGenerated = true;
    }

    /// Emits one real LZW code at the current width.
    private void emitCode(int code) {
        if (code < 0 || code >= 1 << codeWidth) {
            throw new IllegalStateException("LZW code does not fit current width: " + code);
        }
        writeBits(code, codeWidth);
        codesInGroup = codesInGroup + 1 & 7;
    }

    /// Aligns an old-width code group and advances to the next width when its table fills.
    private void advanceCodeWidthIfNeeded() {
        if (codeWidth >= maximumCodeWidth || nextCode != 1 << codeWidth) {
            return;
        }
        while (codesInGroup != 0) {
            writeBits(0, codeWidth);
            codesInGroup = codesInGroup + 1 & 7;
        }
        if (bitCount != 0) {
            throw new IllegalStateException("Unix compress code-group alignment did not end on a byte boundary");
        }
        codeWidth++;
    }

    /// Appends low-order bits to the little-endian Unix compress bit stream.
    private void writeBits(int value, int width) {
        bitBuffer |= (long) value << bitCount;
        bitCount += width;
        while (bitCount >= Byte.SIZE) {
            appendPending((int) bitBuffer);
            bitBuffer >>>= Byte.SIZE;
            bitCount -= Byte.SIZE;
        }
    }

    /// Emits the final partially occupied byte with zero-valued high padding bits.
    private void flushPartialByte() {
        if (bitCount != 0) {
            appendPending((int) bitBuffer);
            bitBuffer = 0L;
            bitCount = 0;
        }
    }

    /// Finds a dictionary code for one packed prefix-and-symbol key.
    private int findCode(int key) {
        int storedKey = key + 1;
        int index = hashIndex(key);
        while (hashKeys[index] != 0) {
            if (hashKeys[index] == storedKey) {
                return hashValues[index];
            }
            index = index + 1 & hashMask;
        }
        return -1;
    }

    /// Adds one packed prefix-and-symbol key to the primitive dictionary hash table.
    private void addCode(int key, int code) {
        int index = hashIndex(key);
        while (hashKeys[index] != 0) {
            index = index + 1 & hashMask;
        }
        hashKeys[index] = key + 1;
        hashValues[index] = code;
    }

    /// Computes the first open-addressing slot for one packed phrase key.
    private int hashIndex(int key) {
        return Integer.rotateLeft(key * 0x9e37_79b9, 13) & hashMask;
    }

    /// Appends one byte to bounded private pending-output storage.
    private void appendPending(int value) {
        if (pendingEnd == pending.length) {
            throw new IllegalStateException("Unix compress pending-output capacity was exceeded");
        }
        pending[pendingEnd++] = (byte) value;
    }

    /// Copies private pending bytes and returns whether caller output space was exhausted first.
    private boolean drainPending(ByteBuffer target) {
        int count = Math.min(pendingEnd - pendingStart, target.remaining());
        target.put(pending, pendingStart, count);
        pendingStart += count;
        if (pendingStart == pendingEnd) {
            pendingStart = 0;
            pendingEnd = 0;
            return false;
        }
        return true;
    }

    /// Requires the exact encoder state for an input-producing operation.
    private void requireState(State requiredState, String operation) {
        requireOpen();
        if (state != requiredState) {
            throw new IllegalStateException(
                    "Cannot " + operation + " while Unix compress encoder state is " + state
            );
        }
    }

    /// Requires this encoder not to have been closed.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Unix compress encoder is closed");
        }
    }

    /// Enumerates Unix compress encoder lifecycle phases.
    @NotNullByDefault
    private enum State {
        /// The encoder accepts uncompressed source bytes.
        ACTIVE,

        /// Final bytes are waiting for caller-provided output space.
        FINISHING,

        /// The complete stream has been emitted.
        FINISHED,

        /// The encoder has released its mutable state.
        CLOSED
    }
}
