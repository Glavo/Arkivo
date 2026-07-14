// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Plans sparse frame-wide matches beyond the normal block hash-chain distance.
@NotNullByDefault
final class ZstdLongDistanceMatcher {
    /// Number of bytes hashed at each candidate position.
    private static final int HASH_BYTES = 8;

    /// Minimum match length emitted by the long-distance planner.
    private static final int MINIMUM_MATCH = 64;

    /// Number of low hash bits used for content-dependent sampling.
    private static final int SAMPLE_LOG = 6;

    /// Sentinel stored in hash slots without a prior sampled position.
    private static final long NO_POSITION = -1L;

    /// Initial retained-history allocation before geometric growth.
    private static final int INITIAL_HISTORY_SIZE = 64 * 1024;

    /// Maximum backward distance permitted by the frame window.
    private final int windowSize;

    /// Distance at or below which the normal block matcher is preferred.
    private final int minimumDistance;

    /// Mask selecting one slot in the sampled-position table.
    private final int tableMask;

    /// Latest absolute frame position assigned to each sampled hash slot.
    private final long[] positions;

    /// Dynamically sized circular buffer containing the retained frame tail.
    private byte[] history = new byte[0];

    /// Physical array index of the oldest retained history byte.
    private int historyStart;

    /// Number of valid bytes in the history ring.
    private int historySize;

    /// Absolute frame position immediately after the retained and discarded history.
    private long framePosition;

    /// Creates a planner for the effective window and ordinary match distance.
    ZstdLongDistanceMatcher(ZstdEncoderParameters parameters) {
        this.windowSize = parameters.windowLog() >= 31
                ? Integer.MAX_VALUE - 8
                : 1 << parameters.windowLog();
        this.minimumDistance = parameters.chainLimit();
        int tableSize = 1 << Math.min(parameters.hashLog(), 20);
        this.tableMask = tableSize - 1;
        this.positions = new long[tableSize];
        reset();
    }

    /// Clears frame-local positions while retaining allocated working memory.
    void reset() {
        Arrays.fill(positions, NO_POSITION);
        historyStart = 0;
        historySize = 0;
        framePosition = 0L;
    }

    /// Plans long-distance matches for one block and then commits its bytes to frame history.
    @Unmodifiable List<Match> plan(byte[] source, int length) {
        if (length < 0 || length > source.length) {
            throw new IllegalArgumentException("Invalid Zstandard long-distance block length");
        }

        long blockStart = framePosition;
        long historyFirstPosition = blockStart - historySize;
        ArrayList<Match> matches = new ArrayList<>();
        int position = 0;
        int lastPosition = length - HASH_BYTES;
        while (position <= lastPosition) {
            long hash = hash(source, position);
            if (isSample(hash)) {
                long candidate = positions[tableIndex(hash)];
                long distance = blockStart + position - candidate;
                if (candidate >= historyFirstPosition
                        && distance > minimumDistance
                        && distance <= windowSize
                        && distance <= Integer.MAX_VALUE) {
                    int matchLength = commonLength(
                            source,
                            length,
                            position,
                            candidate,
                            blockStart
                    );
                    if (matchLength >= MINIMUM_MATCH) {
                        matches.add(new Match(position, matchLength, (int) distance));
                        position += matchLength;
                        continue;
                    }
                }
            }
            position++;
        }

        indexBlock(source, length, blockStart);
        appendHistory(source, length);
        return List.copyOf(matches);
    }

    /// Indexes content-dependent samples from one block at absolute frame positions.
    private void indexBlock(byte[] source, int length, long blockStart) {
        for (int position = 0; position + HASH_BYTES <= length; position++) {
            long hash = hash(source, position);
            if (isSample(hash)) {
                positions[tableIndex(hash)] = blockStart + position;
            }
        }
    }

    /// Returns the common length between retained history and one current-block position.
    private int commonLength(
            byte[] source,
            int sourceLength,
            int position,
            long candidate,
            long blockStart
    ) {
        int maximum = sourceLength - position;
        int length = 0;
        while (length < maximum) {
            long candidatePosition = candidate + length;
            byte candidateByte = candidatePosition < blockStart
                    ? historyByte(candidatePosition)
                    : source[Math.toIntExact(candidatePosition - blockStart)];
            if (source[position + length] != candidateByte) {
                break;
            }
            length++;
        }
        return length;
    }

    /// Appends bytes to the bounded history ring and advances the absolute frame position.
    private void appendHistory(byte[] source, int length) {
        if (length == 0) {
            return;
        }

        int requiredCapacity = (int) Math.min(
                windowSize,
                (long) historySize + length
        );
        ensureHistoryCapacity(requiredCapacity);
        if (length >= history.length) {
            System.arraycopy(source, length - history.length, history, 0, history.length);
            historyStart = 0;
            historySize = history.length;
            framePosition += length;
            return;
        }

        int overflow = (int) Math.max(
                0L, (long) historySize + length - history.length
        );
        if (overflow != 0) {
            historyStart = (historyStart + overflow) % history.length;
            historySize -= overflow;
        }
        int writePosition = (historyStart + historySize) % history.length;
        int firstPart = Math.min(length, history.length - writePosition);
        System.arraycopy(source, 0, history, writePosition, firstPart);
        System.arraycopy(source, firstPart, history, 0, length - firstPart);
        historySize += length;
        framePosition += length;
    }

    /// Grows the history ring while preserving its logical byte order.
    private void ensureHistoryCapacity(int requiredCapacity) {
        if (history.length >= requiredCapacity) {
            return;
        }
        long doubled = Math.max(INITIAL_HISTORY_SIZE, (long) history.length * 2L);
        int newCapacity = (int) Math.min(
                windowSize,
                Math.max(requiredCapacity, doubled)
        );
        byte[] expanded = new byte[newCapacity];
        if (historySize != 0) {
            int firstPart = Math.min(historySize, history.length - historyStart);
            System.arraycopy(history, historyStart, expanded, 0, firstPart);
            System.arraycopy(history, 0, expanded, firstPart, historySize - firstPart);
        }
        history = expanded;
        historyStart = 0;
    }

    /// Returns one retained byte at an absolute frame position.
    private byte historyByte(long position) {
        long firstPosition = framePosition - historySize;
        if (position < firstPosition || position >= framePosition) {
            throw new AssertionError("Zstandard long-distance history position is unavailable");
        }
        int offset = Math.toIntExact(position - firstPosition);
        int index = (int) (((long) historyStart + offset) % history.length);
        return history[index];
    }

    /// Returns whether a mixed content hash participates in the sparse index.
    private static boolean isSample(long hash) {
        return (hash & ((1L << SAMPLE_LOG) - 1L)) == 0L;
    }

    /// Selects one sampled-position table slot from a mixed content hash.
    private int tableIndex(long hash) {
        return (int) (hash >>> SAMPLE_LOG) & tableMask;
    }

    /// Hashes eight bytes with a stable little-endian 64-bit avalanche.
    private static long hash(byte[] source, int offset) {
        long value = 0L;
        for (int index = 0; index < HASH_BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        value ^= value >>> 33;
        value *= 0xff51_afd7_ed55_8ccdL;
        value ^= value >>> 33;
        value *= 0xc4ce_b9fe_1a85_ec53L;
        return value ^ value >>> 33;
    }

    /// Describes one verified match against earlier frame content.
    ///
    /// @param position match start in the current block
    /// @param length match length
    /// @param distance backward distance from the current block position
    record Match(int position, int length, int distance) {
    }
}
