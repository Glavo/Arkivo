// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Objects;

/// Supplies caller buffers to a range decoder without retaining them between operations.
///
/// Incomplete symbol prefixes are copied into a fixed-size owned lookahead. One LZMA symbol can normalize the range
/// coder at most 48 times, so 64 bytes cover every rollback without allowing input-sized staging.
@NotNullByDefault
final class LZMABufferInput implements LZMAInput {
    /// Maximum owned bytes retained for one incomplete symbol.
    private static final int LOOKAHEAD_CAPACITY = 64;

    /// Owned bytes preceding the currently attached caller fragment.
    private final byte[] lookahead = new byte[LOOKAHEAD_CAPACITY];

    /// First unread owned lookahead byte.
    private int lookaheadPosition;

    /// Position following the final owned lookahead byte.
    private int lookaheadLimit;

    /// Caller-owned source attached only for the duration of one decode operation.
    private @Nullable ByteBuffer source;

    /// Whether exhaustion of the attached source is physical end of input.
    private boolean endOfInput;

    /// Logical compressed bytes consumed by completed range-decoder operations.
    private long byteCount;

    /// Saved caller position for the active symbol transaction.
    private int transactionSourcePosition;

    /// Saved owned lookahead position for the active symbol transaction.
    private int transactionLookaheadPosition;

    /// Saved logical byte count for the active symbol transaction.
    private long transactionByteCount;

    /// Whether one symbol transaction is active.
    private boolean transactionActive;

    /// Creates an input containing the five-byte range-coder prefix.
    LZMABufferInput(byte[] prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.length != 5) {
            throw new IllegalArgumentException("LZMA range-coder prefix must contain five bytes");
        }
        System.arraycopy(prefix, 0, lookahead, 0, prefix.length);
        lookaheadLimit = prefix.length;
    }

    /// Attaches one caller source until `detach` is invoked.
    void attach(ByteBuffer input, boolean finalInput) {
        Objects.requireNonNull(input, "input");
        if (source != null) {
            throw new IllegalStateException("An LZMA source buffer is already attached");
        }
        source = input;
        endOfInput = finalInput;
    }

    /// Detaches the caller source after verifying no transaction escaped.
    void detach() {
        if (transactionActive) {
            throw new IllegalStateException("An LZMA input transaction remains active");
        }
        source = null;
        endOfInput = false;
    }

    /// Compacts bytes consumed outside a symbol transaction, such as the range prefix.
    void compact() {
        if (transactionActive) {
            throw new IllegalStateException("Cannot compact an active LZMA input transaction");
        }
        compactLookahead();
    }

    /// Copies the exhausted speculative fragment into bounded owned lookahead.
    void retainAttachedFragment() {
        if (transactionActive) {
            throw new IllegalStateException("Cannot retain an active LZMA input transaction");
        }
        compactLookahead();
        ByteBuffer input = Objects.requireNonNull(source, "source");
        int length = input.remaining();
        if (length > lookahead.length - lookaheadLimit) {
            throw new AssertionError("An LZMA symbol exceeded bounded lookahead");
        }
        input.get(lookahead, lookaheadLimit, length);
        lookaheadLimit += length;
    }

    /// Returns the number of logically consumed compressed bytes.
    long byteCount() {
        return byteCount;
    }

    /// Reads one byte from owned lookahead or the attached caller source.
    @Override
    public int read() throws LZMANeedInputException {
        if (lookaheadPosition < lookaheadLimit) {
            byteCount++;
            return Byte.toUnsignedInt(lookahead[lookaheadPosition++]);
        }
        ByteBuffer input = Objects.requireNonNull(source, "No LZMA source buffer is attached");
        if (input.hasRemaining()) {
            byteCount++;
            return Byte.toUnsignedInt(input.get());
        }
        if (endOfInput) {
            return -1;
        }
        throw LZMANeedInputException.INSTANCE;
    }

    /// Returns that this input supports symbol transactions.
    @Override
    public boolean transactional() {
        return true;
    }

    /// Saves caller and lookahead positions before parsing one symbol.
    @Override
    public void beginTransaction() {
        if (transactionActive) {
            throw new IllegalStateException("An LZMA input transaction is already active");
        }
        ByteBuffer input = Objects.requireNonNull(source, "No LZMA source buffer is attached");
        transactionSourcePosition = input.position();
        transactionLookaheadPosition = lookaheadPosition;
        transactionByteCount = byteCount;
        transactionActive = true;
    }

    /// Commits source progress and discards consumed owned lookahead.
    @Override
    public void commitTransaction() {
        requireTransaction();
        transactionActive = false;
        compactLookahead();
    }

    /// Restores source and lookahead positions preceding the speculative symbol.
    @Override
    public void rollbackTransaction() {
        requireTransaction();
        ByteBuffer input = Objects.requireNonNull(source, "No LZMA source buffer is attached");
        input.position(transactionSourcePosition);
        lookaheadPosition = transactionLookaheadPosition;
        byteCount = transactionByteCount;
        transactionActive = false;
    }

    /// Removes consumed owned bytes while preserving an incomplete symbol prefix.
    private void compactLookahead() {
        int remaining = lookaheadLimit - lookaheadPosition;
        if (remaining > 0) {
            System.arraycopy(lookahead, lookaheadPosition, lookahead, 0, remaining);
        }
        lookaheadPosition = 0;
        lookaheadLimit = remaining;
    }

    /// Requires one active symbol transaction.
    private void requireTransaction() {
        if (!transactionActive) {
            throw new IllegalStateException("No LZMA input transaction is active");
        }
    }
}
