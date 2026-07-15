// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/// Plans configurable frame-wide matches beyond the normal block match-finder distance.
@NotNullByDefault
final class ZstdLongDistanceMatcher {
    /// Maximum number of bytes mixed into one content hash.
    private static final int MAX_HASH_BYTES = 8;

    /// Sentinel stored in hash slots without a prior sampled position.
    private static final long NO_POSITION = -1L;

    /// Initial retained-history allocation before geometric growth.
    private static final int INITIAL_HISTORY_SIZE = 64 * 1024;

    /// Maximum backward distance permitted by the frame window.
    private final int windowSize;

    /// Distance at or below which the normal block matcher is preferred.
    private final int minimumDistance;

    /// Number of source bytes mixed into one content hash.
    private final int hashBytes;

    /// Minimum match length emitted by the long-distance planner.
    private final int minimumMatch;

    /// Number of low hash bits required to be zero for a sampled position.
    private final int hashRateLog;

    /// Number of candidate positions retained in each collision bucket.
    private final int bucketSize;

    /// Mask wrapping a candidate index inside one collision bucket.
    private final int bucketMask;

    /// Mask selecting one collision bucket from a sampled hash.
    private final int bucketCountMask;

    /// Retained absolute positions grouped into collision buckets.
    private final long[] positions;

    /// Next replacement slot inside each collision bucket.
    private final int[] bucketOffsets;

    /// Dynamically sized circular buffer containing the retained frame tail.
    private byte[] history = new byte[0];

    /// Physical array index of the oldest retained history byte.
    private int historyStart;

    /// Number of valid bytes in the history ring.
    private int historySize;

    /// Absolute frame position immediately after the retained and discarded history.
    private long framePosition;

    /// Creates a planner from effective long-distance and ordinary match parameters.
    ZstdLongDistanceMatcher(ZstdEncoderParameters parameters) {
        this.windowSize = parameters.windowLog() >= 31
                ? Integer.MAX_VALUE - MAX_HASH_BYTES
                : 1 << parameters.windowLog();
        this.minimumDistance = parameters.chainLimit();
        this.minimumMatch = parameters.longDistanceMinimumMatch();
        this.hashBytes = Math.min(MAX_HASH_BYTES, minimumMatch);
        this.hashRateLog = parameters.longDistanceHashRateLog();

        int hashLog = parameters.longDistanceHashLog();
        int bucketSizeLog = parameters.longDistanceBucketSizeLog();
        int entryCount = 1 << hashLog;
        this.bucketSize = 1 << bucketSizeLog;
        this.bucketMask = bucketSize - 1;
        this.bucketCountMask = (entryCount >>> bucketSizeLog) - 1;
        this.positions = new long[entryCount];
        this.bucketOffsets = new int[bucketCountMask + 1];
        reset();
    }

    /// Clears frame-local positions while retaining allocated working memory.
    void reset() {
        Arrays.fill(positions, NO_POSITION);
        Arrays.fill(bucketOffsets, 0);
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
        int lastPosition = length - hashBytes;
        while (position <= lastPosition) {
            long hash = hash(source, position);
            if (isSample(hash)) {
                Candidate best = findBestCandidate(
                        source,
                        length,
                        position,
                        hash,
                        historyFirstPosition,
                        blockStart
                );
                if (best.length() >= minimumMatch) {
                    matches.add(new Match(position, best.length(), best.distance()));
                    position += best.length();
                    continue;
                }
            }
            position++;
        }

        indexBlock(source, length, blockStart);
        appendHistory(source, length);
        return List.copyOf(matches);
    }

    /// Finds the longest valid retained candidate in one collision bucket.
    private Candidate findBestCandidate(
            byte[] source,
            int sourceLength,
            int position,
            long hash,
            long historyFirstPosition,
            long blockStart
    ) {
        int bucket = bucketIndex(hash);
        int bucketBase = bucket * bucketSize;
        int next = bucketOffsets[bucket];
        int bestLength = 0;
        int bestDistance = 0;
        for (int offset = 0; offset < bucketSize; offset++) {
            int slot = bucketBase + ((next - 1 - offset) & bucketMask);
            long candidate = positions[slot];
            long distance = blockStart + position - candidate;
            if (candidate < historyFirstPosition
                    || distance <= minimumDistance
                    || distance > windowSize
                    || distance > Integer.MAX_VALUE) {
                continue;
            }

            int matchLength = commonLength(
                    source,
                    sourceLength,
                    position,
                    candidate,
                    blockStart
            );
            if (matchLength > bestLength) {
                bestLength = matchLength;
                bestDistance = (int) distance;
                if (matchLength == sourceLength - position) {
                    break;
                }
            }
        }
        return new Candidate(bestLength, bestDistance);
    }

    /// Indexes content-dependent samples from one block at absolute frame positions.
    private void indexBlock(byte[] source, int length, long blockStart) {
        for (int position = 0; position + hashBytes <= length; position++) {
            long hash = hash(source, position);
            if (isSample(hash)) {
                insert(hash, blockStart + position);
            }
        }
    }

    /// Inserts one sampled position into its collision bucket.
    private void insert(long hash, long position) {
        int bucket = bucketIndex(hash);
        int offset = bucketOffsets[bucket];
        positions[bucket * bucketSize + offset] = position;
        bucketOffsets[bucket] = (offset + 1) & bucketMask;
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
    private boolean isSample(long hash) {
        return hashRateLog == 0
                || (hash & ((1L << hashRateLog) - 1L)) == 0L;
    }

    /// Selects one collision bucket from a sampled content hash.
    private int bucketIndex(long hash) {
        return (int) (hash >>> hashRateLog) & bucketCountMask;
    }

    /// Hashes the configured number of bytes with a stable little-endian 64-bit avalanche.
    private long hash(byte[] source, int offset) {
        long value = 0L;
        for (int index = 0; index < hashBytes; index++) {
            value |= (long) Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        value ^= value >>> 33;
        value *= 0xff51_afd7_ed55_8ccdL;
        value ^= value >>> 33;
        value *= 0xc4ce_b9fe_1a85_ec53L;
        return value ^ value >>> 33;
    }

    /// Holds the best retained candidate in one collision bucket.
    ///
    /// @param length common-byte count
    /// @param distance backward match distance
    private record Candidate(int length, int distance) {
    }

    /// Describes one verified match against earlier frame content.
    ///
    /// @param position match start in the current block
    /// @param length match length
    /// @param distance backward distance from the current block position
    record Match(int position, int length, int distance) {
    }
}
