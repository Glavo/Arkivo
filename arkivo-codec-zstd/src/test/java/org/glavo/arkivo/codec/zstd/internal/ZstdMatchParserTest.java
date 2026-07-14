// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies strategy-specific Zstandard match parsing decisions.
@NotNullByDefault
public final class ZstdMatchParserTest {
    /// Verifies greedy, lazy, and optimal parsers choose distinct paths over competing matches.
    @Test
    public void selectsStrategySpecificMatches() throws IOException {
        Fixture fixture = fixture();
        @Unmodifiable List<ZstdMatchParser.Match> greedy =
                targetMatches(parse(fixture.bytes(), 3), fixture.targetPosition());
        @Unmodifiable List<ZstdMatchParser.Match> lazy =
                targetMatches(parse(fixture.bytes(), 4), fixture.targetPosition());
        @Unmodifiable List<ZstdMatchParser.Match> optimal =
                targetMatches(parse(fixture.bytes(), 7), fixture.targetPosition());

        assertEquals(2, greedy.size());
        assertEquals(fixture.targetPosition(), greedy.get(0).position());
        assertEquals(4, greedy.get(0).length());
        assertEquals(fixture.targetPosition() + 4, greedy.get(1).position());

        assertEquals(1, lazy.size());
        assertEquals(fixture.targetPosition() + 1, lazy.get(0).position());
        assertEquals(fixture.patternLength(), lazy.get(0).length());

        assertEquals(lazy, optimal);
    }

    /// Verifies DFAST recovers an older long match through its secondary eight-byte hash.
    @Test
    public void dFastUsesSecondaryHashCandidate() throws IOException {
        byte[] pattern = new byte[64];
        new Random(0xd6fa_5720_2026L).nextBytes(pattern);
        byte[] firstSeparator = new byte[17];
        byte[] secondSeparator = new byte[19];
        java.util.Arrays.fill(firstSeparator, (byte) 0x2a);
        java.util.Arrays.fill(secondSeparator, (byte) 0x3b);

        int targetPosition = pattern.length + firstSeparator.length + 5 + secondSeparator.length;
        byte[] input = new byte[targetPosition + pattern.length];
        int position = 0;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        position += pattern.length;
        System.arraycopy(firstSeparator, 0, input, position, firstSeparator.length);
        position += firstSeparator.length;
        System.arraycopy(pattern, 0, input, position, 4);
        position += 4;
        input[position++] = (byte) (pattern[4] ^ 0xff);
        System.arraycopy(secondSeparator, 0, input, position, secondSeparator.length);
        position += secondSeparator.length;
        System.arraycopy(pattern, 0, input, position, pattern.length);

        @Unmodifiable List<ZstdMatchParser.Match> fast =
                targetMatches(parse(input, 1), targetPosition);
        @Unmodifiable List<ZstdMatchParser.Match> dFast =
                targetMatches(parse(input, 2), targetPosition);
        assertEquals(4, fast.get(0).length());
        assertEquals(pattern.length, dFast.get(0).length());
    }

    /// Verifies two-step lazy parsing sees a long match beyond the one-step lookahead.
    @Test
    public void lazy2LooksAheadTwoPositions() throws IOException {
        byte[] pattern = new byte[128];
        new Random(0x1a22_2026L).nextBytes(pattern);
        byte[] firstSeparator = new byte[17];
        byte[] secondSeparator = new byte[19];
        java.util.Arrays.fill(firstSeparator, (byte) 0x4c);
        java.util.Arrays.fill(secondSeparator, (byte) 0x6d);

        int targetPosition = pattern.length + firstSeparator.length + 5 + secondSeparator.length;
        byte[] input = new byte[targetPosition + 2 + pattern.length];
        int position = 0;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        position += pattern.length;
        System.arraycopy(firstSeparator, 0, input, position, firstSeparator.length);
        position += firstSeparator.length;
        input[position++] = 0x41;
        input[position++] = 0x42;
        System.arraycopy(pattern, 0, input, position, 2);
        position += 2;
        input[position++] = (byte) (pattern[2] ^ 0xff);
        System.arraycopy(secondSeparator, 0, input, position, secondSeparator.length);
        position += secondSeparator.length;
        input[position++] = 0x41;
        input[position++] = 0x42;
        System.arraycopy(pattern, 0, input, position, pattern.length);

        @Unmodifiable List<ZstdMatchParser.Match> lazy =
                targetMatches(parse(input, 4), targetPosition);
        @Unmodifiable List<ZstdMatchParser.Match> lazy2 =
                targetMatches(parse(input, 5), targetPosition);
        assertEquals(targetPosition, lazy.get(0).position());
        assertEquals(4, lazy.get(0).length());
        assertEquals(targetPosition + 2, lazy2.get(0).position());
        assertEquals(pattern.length, lazy2.get(0).length());
    }

    /// Verifies every parser strategy returns ordered, non-overlapping, in-range matches.
    @Test
    public void everyStrategyProducesValidMatches() throws IOException {
        Fixture fixture = fixture();
        for (int strategy = 1; strategy <= 9; strategy++) {
            int end = 0;
            for (ZstdMatchParser.Match match : parse(fixture.bytes(), strategy)) {
                assertTrue(match.position() >= end, "strategy=" + strategy);
                assertTrue(match.length() >= 4, "strategy=" + strategy);
                assertTrue(match.distance() > 0, "strategy=" + strategy);
                assertTrue(
                        match.position() + match.length() <= fixture.bytes().length,
                        "strategy=" + strategy
                );
                end = match.position() + match.length();
            }
        }
    }

    /// Verifies binary-tree search reaches an older long match hidden beyond the hash-chain depth.
    @Test
    public void binaryTreeFindsLexicallyAdjacentOlderMatch() throws IOException {
        byte[] pattern = new byte[64];
        new Random(0xb17e_2026L).nextBytes(pattern);
        pattern[0] = 0x41;
        pattern[1] = 0x42;
        pattern[2] = 0x43;
        pattern[3] = 0x44;
        pattern[4] = (byte) 0x80;

        int decoyCount = 6;
        int separatorLength = 9;
        byte[] input = new byte[
                pattern.length + decoyCount * (separatorLength + 5) + pattern.length
        ];
        int position = 0;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        position += pattern.length;
        for (int decoy = 0; decoy < decoyCount; decoy++) {
            java.util.Arrays.fill(
                    input,
                    position,
                    position + separatorLength,
                    (byte) (0x20 + decoy)
            );
            position += separatorLength;
            System.arraycopy(pattern, 0, input, position, 4);
            position += 4;
            input[position++] = (byte) (0x10 + decoy * 0x10);
        }
        int targetPosition = position;
        System.arraycopy(pattern, 0, input, position, pattern.length);

        @Unmodifiable List<ZstdMatchParser.Match> chainMatches =
                targetMatches(parse(input, 3, 1), targetPosition);
        @Unmodifiable List<ZstdMatchParser.Match> treeMatches =
                targetMatches(parse(input, 6, 1), targetPosition);
        assertEquals(4, chainMatches.get(0).length());
        assertEquals(pattern.length, treeMatches.get(0).length());
        assertEquals(targetPosition, treeMatches.get(0).position());
    }

    /// Verifies binary-tree indexing skips the interior of a block-spanning repetitive match.
    @Test
    public void binaryTreeBoundsRepetitiveInputWork() throws IOException {
        byte[] input = new byte[128 * 1024];
        java.util.Arrays.fill(input, (byte) 0x5a);

        @Unmodifiable List<ZstdMatchParser.Match> matches = assertTimeout(
                Duration.ofSeconds(5),
                () -> parse(input, 9)
        );
        assertEquals(1, matches.size());
        assertEquals(1, matches.get(0).position());
        assertEquals(input.length - 1, matches.get(0).length());
        assertEquals(1, matches.get(0).distance());
    }

    /// Parses one fixture with an explicitly selected strategy.
    private static @Unmodifiable List<ZstdMatchParser.Match> parse(
            byte[] input,
            int strategy
    ) throws IOException {
        return parse(input, strategy, 6);
    }

    /// Parses one fixture with an explicitly selected strategy and search logarithm.
    private static @Unmodifiable List<ZstdMatchParser.Match> parse(
            byte[] input,
            int strategy,
            int searchLog
    ) throws IOException {
        return ZstdMatchParser.parse(
                input,
                input.length,
                new byte[0],
                1 << 17,
                parameters(strategy, searchLog),
                List.of()
        );
    }

    /// Returns matches starting in the final competing-match region.
    private static @Unmodifiable List<ZstdMatchParser.Match> targetMatches(
            @Unmodifiable List<ZstdMatchParser.Match> matches,
            int targetPosition
    ) {
        ArrayList<ZstdMatchParser.Match> selected = new ArrayList<>();
        for (ZstdMatchParser.Match match : matches) {
            if (match.position() >= targetPosition) {
                selected.add(match);
            }
        }
        return List.copyOf(selected);
    }

    /// Creates parameters for one explicit strategy and search logarithm.
    private static ZstdEncoderParameters parameters(int strategy, int searchLog) throws IOException {
        return new ZstdEncoderParameters(
                3,
                17,
                15,
                17,
                searchLog,
                4,
                0,
                strategy,
                false,
                false,
                false,
                false,
                0,
                0,
                0,
                -1L,
                null
        );
    }

    /// Creates an input where a four-byte match hides a much longer match one byte later.
    private static Fixture fixture() {
        int patternLength = 4_096;
        byte[] pattern = new byte[patternLength];
        new Random(0x57a7_e6d1_2026L).nextBytes(pattern);
        byte[] separator = new byte[31];
        java.util.Arrays.fill(separator, (byte) 0x5a);
        byte[] secondSeparator = new byte[31];
        java.util.Arrays.fill(secondSeparator, (byte) 0x6b);

        int targetPosition = pattern.length + separator.length + 5 + separator.length;
        byte[] input = new byte[targetPosition + 1 + pattern.length];
        int position = 0;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        position += pattern.length;
        System.arraycopy(secondSeparator, 0, input, position, secondSeparator.length);
        position += separator.length;
        input[position++] = 0x41;
        System.arraycopy(pattern, 0, input, position, 3);
        position += 3;
        input[position++] = 0x51;
        System.arraycopy(separator, 0, input, position, separator.length);
        position += separator.length;
        input[position++] = 0x41;
        System.arraycopy(pattern, 0, input, position, pattern.length);
        return new Fixture(input, targetPosition, patternLength);
    }

    /// Holds a strategy fixture and its competing-match region.
    ///
    /// @param bytes complete fixture bytes
    /// @param targetPosition start of the final competing-match region
    /// @param patternLength length of the long deferred match
    private record Fixture(
            byte @Unmodifiable [] bytes,
            int targetPosition,
            int patternLength
    ) {
    }
}
