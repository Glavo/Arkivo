// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes the 7z PPMd7 arithmetic representation from caller-owned buffers.
///
/// An arithmetic interval may require normalization bytes after its model context has already been selected. This
/// decoder retains only numeric operation state across calls and replays the completed interval exactly once when the
/// model resumes; the attached source buffer is always detached before the public decode operation returns.
@NotNullByDefault
final class PPMd7BufferRangeDecoder implements PPMdRangeDecoder {
    /// The unsigned 32-bit mask.
    private static final long UINT_MASK = 0xffff_ffffL;

    /// The normalization threshold.
    private static final long RANGE_TOP = 1L << 24;

    /// Caller-owned source attached only for the duration of one public decode operation.
    private @Nullable ByteBuffer source;

    /// Whether exhaustion of the attached source is terminal.
    private boolean endOfInput;

    /// Number of mandatory range-prefix bytes already consumed.
    private int prefixBytes;

    /// Whether the five-byte range prefix has been validated.
    private boolean initialized;

    /// Current unsigned arithmetic code.
    private long code;

    /// Current unsigned arithmetic range.
    private long range = UINT_MASK;

    /// Current resumable arithmetic operation.
    private Operation operation = Operation.IDLE;

    /// Scale captured by the current cumulative-count operation.
    private int preparedScale;

    /// Cumulative count captured before an interval normalization suspension.
    private int preparedCount;

    /// Low endpoint of the interval awaiting replay.
    private int preparedLowCount;

    /// High endpoint of the interval awaiting replay.
    private int preparedHighCount;

    /// Zero-branch size of a suspended binary interval.
    private int preparedZeroSize;

    /// Scale of a suspended binary interval.
    private int preparedBitScale;

    /// Branch selected by a suspended binary interval.
    private boolean preparedBit;

    /// Creates an empty incremental range decoder.
    PPMd7BufferRangeDecoder() {
    }

    /// Attaches one caller-owned source for the duration of a public decode operation.
    void attach(ByteBuffer source, boolean endOfInput) {
        Objects.requireNonNull(source, "source");
        if (this.source != null) {
            throw new IllegalStateException("PPMd range decoder already has an attached source");
        }
        this.source = source;
        this.endOfInput = endOfInput;
    }

    /// Detaches the caller-owned source without changing arithmetic progress.
    void detach() {
        source = null;
        endOfInput = false;
    }

    /// Consumes and validates the mandatory five-byte range-code prefix.
    void initialize() throws IOException {
        if (initialized) {
            return;
        }
        while (prefixBytes < 1 + Integer.BYTES) {
            int value = readByte();
            if (prefixBytes == 0) {
                if (value != 0) {
                    throw new IOException("Malformed PPMd7 range-code prefix");
                }
            } else {
                code = (code << Byte.SIZE | value) & UINT_MASK;
            }
            prefixBytes++;
        }
        if (code == UINT_MASK) {
            throw new IOException("Malformed PPMd7 initial range code");
        }
        initialized = true;
    }

    /// Returns the current cumulative-frequency position for the given scale.
    @Override
    public int currentCount(int scale) throws IOException {
        if (scale <= 0) {
            throw new IOException("PPMd range scale must be positive");
        }
        if (operation == Operation.INTERVAL_NORMALIZING) {
            requireScale(scale);
            normalize();
            operation = Operation.INTERVAL_REPLAY;
            return preparedCount;
        }
        if (operation == Operation.COUNT_READY || operation == Operation.INTERVAL_REPLAY) {
            requireScale(scale);
            return preparedCount;
        }
        if (operation != Operation.IDLE) {
            throw new IOException("Invalid PPMd arithmetic operation sequence");
        }

        range /= scale;
        long count = code / range;
        if (count >= scale) {
            throw new IOException("Corrupt PPMd7 arithmetic interval");
        }
        preparedScale = scale;
        preparedCount = (int) count;
        operation = Operation.COUNT_READY;
        return preparedCount;
    }

    /// Commits or resumes the selected half-open cumulative-frequency interval.
    @Override
    public void decode(int lowCount, int highCount) throws IOException {
        if (lowCount < 0 || highCount <= lowCount || highCount > preparedScale) {
            throw new IOException("Invalid PPMd arithmetic interval");
        }
        if (operation == Operation.INTERVAL_REPLAY) {
            requireInterval(lowCount, highCount);
            operation = Operation.IDLE;
            return;
        }
        if (operation != Operation.COUNT_READY) {
            throw new IOException("PPMd interval has no prepared cumulative count");
        }

        preparedLowCount = lowCount;
        preparedHighCount = highCount;
        code = (code - range * lowCount) & UINT_MASK;
        range = range * (highCount - lowCount) & UINT_MASK;
        operation = Operation.INTERVAL_NORMALIZING;
        normalize();
        operation = Operation.IDLE;
    }

    /// Decodes or resumes one binary context with the 7z range coder's format-defined rounding.
    @Override
    public boolean decodeBit(int zeroSize, int scale) throws IOException {
        if (scale != 1 << 14 || zeroSize <= 0 || zeroSize >= scale) {
            throw new IOException("Invalid PPMd7 binary arithmetic interval");
        }
        if (operation == Operation.BIT_NORMALIZING) {
            if (zeroSize != preparedZeroSize || scale != preparedBitScale) {
                throw new IOException("Resumed PPMd binary interval does not match its suspended operation");
            }
            normalize();
            operation = Operation.IDLE;
            return preparedBit;
        }
        if (operation != Operation.IDLE) {
            throw new IOException("Invalid PPMd binary arithmetic operation sequence");
        }

        long zeroRange = (range >>> 14) * zeroSize & UINT_MASK;
        preparedBit = code >= zeroRange;
        if (preparedBit) {
            code = (code - zeroRange) & UINT_MASK;
            range = (range - zeroRange) & UINT_MASK;
        } else {
            range = zeroRange;
        }
        preparedZeroSize = zeroSize;
        preparedBitScale = scale;
        operation = Operation.BIT_NORMALIZING;
        normalize();
        operation = Operation.IDLE;
        return preparedBit;
    }

    /// Restores the initial range-code state without retaining a caller source.
    void reset() {
        source = null;
        endOfInput = false;
        prefixBytes = 0;
        initialized = false;
        code = 0L;
        range = UINT_MASK;
        operation = Operation.IDLE;
        preparedScale = 0;
        preparedCount = 0;
        preparedLowCount = 0;
        preparedHighCount = 0;
        preparedZeroSize = 0;
        preparedBitScale = 0;
        preparedBit = false;
    }

    /// Renormalizes the current arithmetic interval, suspending when temporary input is exhausted.
    private void normalize() throws IOException {
        while (range < RANGE_TOP) {
            int value = readByte();
            code = (code << Byte.SIZE | value) & UINT_MASK;
            range = range << Byte.SIZE & UINT_MASK;
        }
    }

    /// Requires the resumed cumulative-count scale to match the suspended operation.
    private void requireScale(int scale) throws IOException {
        if (scale != preparedScale) {
            throw new IOException("Resumed PPMd range scale does not match its suspended operation");
        }
    }

    /// Requires a replayed interval to match the operation normalized across source buffers.
    private void requireInterval(int lowCount, int highCount) throws IOException {
        if (lowCount != preparedLowCount || highCount != preparedHighCount) {
            throw new IOException("Replayed PPMd interval does not match its suspended operation");
        }
    }

    /// Reads one byte from the currently attached caller source.
    private int readByte() throws IOException {
        @Nullable ByteBuffer current = source;
        if (current == null) {
            throw new IllegalStateException("PPMd range decoder has no attached source");
        }
        if (current.hasRemaining()) {
            return Byte.toUnsignedInt(current.get());
        }
        if (endOfInput) {
            throw new EOFException("Truncated PPMd7 range-coded stream");
        }
        throw PPMdInputUnavailableException.INSTANCE;
    }

    /// Identifies the resumable stage of the current arithmetic operation.
    @NotNullByDefault
    private enum Operation {
        /// No arithmetic operation is in progress.
        IDLE,

        /// A cumulative count has divided the range and awaits interval selection.
        COUNT_READY,

        /// A selected general interval awaits one or more normalization bytes.
        INTERVAL_NORMALIZING,

        /// A general interval finished normalization and must be replayed once into the model call.
        INTERVAL_REPLAY,

        /// A selected binary interval awaits one or more normalization bytes.
        BIT_NORMALIZING
    }
}
