// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies configurable frame-wide Zstandard long-distance match planning.
@NotNullByDefault
public final class ZstdLongDistanceMatcherTest {
    /// Verifies matches beyond the ordinary chain limit are planned within the frame window.
    @Test
    public void plansRetainedLongDistanceMatches() throws IOException {
        int blockSize = 1 << 17;
        byte[] first = new byte[blockSize];
        byte[] separator = new byte[blockSize];
        Random random = new Random(0x1d15_7a6c_2026L);
        random.nextBytes(first);
        random.nextBytes(separator);

        ZstdLongDistanceMatcher matcher = new ZstdLongDistanceMatcher(parameters());
        assertTrue(matcher.plan(first, first.length).isEmpty());
        assertTrue(matcher.plan(separator, separator.length).isEmpty());
        List<ZstdLongDistanceMatcher.Match> matches = matcher.plan(first, first.length);

        assertFalse(matches.isEmpty());
        ZstdLongDistanceMatcher.Match firstMatch = matches.get(0);
        assertEquals(blockSize * 2, firstMatch.distance());
        assertTrue(firstMatch.length() >= blockSize / 2);
    }

    /// Verifies resetting removes frame history and retained-window eviction rejects old candidates.
    @Test
    public void resetsAndEvictsFrameHistory() throws IOException {
        int blockSize = 1 << 17;
        byte[] first = new byte[blockSize];
        byte[] separator = new byte[blockSize];
        Random random = new Random(0x6e71_57ad_2026L);
        random.nextBytes(first);
        random.nextBytes(separator);

        ZstdLongDistanceMatcher matcher = new ZstdLongDistanceMatcher(parameters());
        matcher.plan(first, first.length);
        for (int index = 0; index < 4; index++) {
            separator[0] = (byte) index;
            matcher.plan(separator, separator.length);
        }
        assertTrue(matcher.plan(first, first.length).isEmpty());

        matcher.reset();
        assertTrue(matcher.plan(first, first.length).isEmpty());
    }

    /// Verifies the configured minimum match length controls emitted LDM sequences.
    @Test
    public void honorsConfiguredMinimumMatchLength() throws IOException {
        int blockSize = 1 << 17;
        byte[] first = new byte[blockSize];
        byte[] separator = new byte[blockSize];
        Random random = new Random(0x1d6d_2026L);
        random.nextBytes(first);
        random.nextBytes(separator);
        byte[] fragmentedCopy = first.clone();
        for (int position = 48; position < fragmentedCopy.length; position += 49) {
            fragmentedCopy[position] ^= 0x5a;
        }

        ZstdLongDistanceMatcher shortMatcher =
                new ZstdLongDistanceMatcher(parameters(18, 32, 3, 1));
        shortMatcher.plan(first, first.length);
        shortMatcher.plan(separator, separator.length);
        List<ZstdLongDistanceMatcher.Match> shortMatches =
                shortMatcher.plan(fragmentedCopy, fragmentedCopy.length);
        assertFalse(shortMatches.isEmpty());
        assertTrue(shortMatches.stream().allMatch(match -> match.length() >= 32));
        assertTrue(shortMatches.stream().allMatch(match -> match.length() < 64));

        ZstdLongDistanceMatcher longMatcher =
                new ZstdLongDistanceMatcher(parameters(18, 64, 3, 1));
        longMatcher.plan(first, first.length);
        longMatcher.plan(separator, separator.length);
        assertTrue(longMatcher.plan(fragmentedCopy, fragmentedCopy.length).isEmpty());
    }

    /// Verifies a bucket retains and searches a matching candidate behind a newer collision.
    @Test
    public void searchesAllCandidatesInCollisionBucket() throws IOException {
        int blockSize = 4_096;
        int matchLength = 32;
        int hashRateLog = 1;
        int bucketMask = (1 << (6 - 1)) - 1;
        byte[] history = new byte[blockSize];
        new Random(0x1d6d_bac4L).nextBytes(history);

        int[] lastPositions = new int[bucketMask + 1];
        int[] precedingPositions = new int[bucketMask + 1];
        Arrays.fill(lastPositions, -1);
        Arrays.fill(precedingPositions, -1);
        for (int position = 0; position + Long.BYTES <= history.length; position++) {
            long hash = hash(history, position);
            if ((hash & ((1L << hashRateLog) - 1L)) != 0L) {
                continue;
            }
            int bucket = (int) (hash >>> hashRateLog) & bucketMask;
            precedingPositions[bucket] = lastPositions[bucket];
            lastPositions[bucket] = position;
        }

        int matchingPosition = -1;
        int collidingPosition = -1;
        for (int bucket = 0; bucket <= bucketMask; bucket++) {
            int candidate = precedingPositions[bucket];
            int collision = lastPositions[bucket];
            if (candidate >= 0
                    && candidate + matchLength <= history.length
                    && history.length - candidate > 64
                    && mismatch(history, candidate, collision, matchLength)) {
                matchingPosition = candidate;
                collidingPosition = collision;
                break;
            }
        }
        assertTrue(matchingPosition >= 0);

        byte[] repeated = Arrays.copyOfRange(
                history,
                matchingPosition,
                matchingPosition + matchLength
        );
        ZstdLongDistanceMatcher matcher =
                new ZstdLongDistanceMatcher(parameters(6, matchLength, 1, hashRateLog, 6));
        assertTrue(matcher.plan(history, history.length).isEmpty());
        List<ZstdLongDistanceMatcher.Match> matches = matcher.plan(repeated, repeated.length);

        assertFalse(matches.isEmpty());
        assertEquals(history.length - matchingPosition, matches.get(0).distance());
        assertEquals(matchLength, matches.get(0).length());
        assertTrue(collidingPosition > matchingPosition);
    }

    /// Creates default long-distance parameters with a 512 KiB window.
    private static ZstdEncoderParameters parameters() throws IOException {
        return parameters(0, 0, 0, 0);
    }

    /// Creates long-distance parameters with explicit LDM controls.
    private static ZstdEncoderParameters parameters(
            int hashLog,
            int minimumMatch,
            int bucketSizeLog,
            int hashRateLog
    ) throws IOException {
        return parameters(hashLog, minimumMatch, bucketSizeLog, hashRateLog, 17);
    }

    /// Creates long-distance parameters with explicit LDM and ordinary chain controls.
    private static ZstdEncoderParameters parameters(
            int hashLog,
            int minimumMatch,
            int bucketSizeLog,
            int hashRateLog,
            int chainLog
    ) throws IOException {
        return new ZstdEncoderParameters(
                3,
                19,
                18,
                chainLog,
                6,
                4,
                0,
                1,
                false,
                false,
                false,
                true,
                hashLog,
                minimumMatch,
                bucketSizeLog,
                hashRateLog,
                0,
                0,
                0,
                -1L,
                null
        );
    }

    /// Returns whether two retained regions differ before the requested match length.
    private static boolean mismatch(byte[] source, int first, int second, int length) {
        if (second + length > source.length) {
            return true;
        }
        return !Arrays.equals(
                source,
                first,
                first + length,
                source,
                second,
                second + length
        );
    }

    /// Reproduces the matcher's eight-byte hash for deterministic collision selection.
    private static long hash(byte[] source, int offset) {
        long value = 0L;
        for (int index = 0; index < Long.BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        return value ^ value >>> 33;
    }
}
