// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/// Builds block-local match candidates and parses them according to the selected Zstandard strategy.
@NotNullByDefault
final class ZstdMatchParser {
    /// Number of bytes in the primary match hash.
    private static final int HASH_BYTES = 4;

    /// Number of bytes in the secondary DFAST match hash.
    private static final int LONG_HASH_BYTES = 8;

    /// Sentinel for an empty hash-chain position.
    private static final int NO_POSITION = -1;

    /// Approximate bit cost assigned to one literal during optimal parsing.
    private static final int LITERAL_COST = 8;

    /// Approximate fixed bit cost assigned to one sequence during optimal parsing.
    private static final int MATCH_BASE_COST = 6;

    /// Parses non-overlapping matches for one source block.
    static @Unmodifiable List<Match> parse(
            byte[] source,
            int length,
            byte @Unmodifiable [] prefix,
            int distanceLimit,
            ZstdEncoderParameters parameters,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
        if (length < 0 || length > source.length || distanceLimit < 0) {
            throw new IllegalArgumentException("Invalid Zstandard match-parser input");
        }
        if (length < parameters.minimumMatch()) {
            return List.of();
        }

        MatchIndex index = buildIndex(
                source,
                length,
                prefix,
                distanceLimit,
                parameters,
                longDistanceMatches
        );
        int strategy = parameters.strategy();
        return strategy >= 7
                ? parseOptimal(index, length, parameters.minimumMatch(), strategy - 6)
                : parseLazy(index, length, parameters.minimumMatch(), lazyDepth(strategy));
    }

    /// Builds immutable hash-chain candidates and mutable lazy match caches for one block.
    private static MatchIndex buildIndex(
            byte[] source,
            int length,
            byte @Unmodifiable [] prefix,
            int distanceLimit,
            ZstdEncoderParameters parameters,
            @Unmodifiable List<ZstdLongDistanceMatcher.Match> longDistanceMatches
    ) {
        byte[] data = new byte[prefix.length + length];
        System.arraycopy(prefix, 0, data, 0, prefix.length);
        System.arraycopy(source, 0, data, prefix.length, length);

        int[] heads = new int[1 << parameters.hashLog()];
        int[] previous = new int[data.length];
        int[] shortCandidates = new int[length];
        int[] longCandidates = parameters.strategy() == 2 ? new int[length] : new int[0];
        Match[] binaryTreeMatches = parameters.strategy() >= 6
                ? ZstdBinaryTreeMatchFinder.findMatches(
                        data,
                        prefix.length,
                        length,
                        distanceLimit,
                        parameters
                )
                : new Match[0];
        Arrays.fill(heads, NO_POSITION);
        Arrays.fill(previous, NO_POSITION);
        Arrays.fill(shortCandidates, NO_POSITION);
        if (longCandidates.length != 0) {
            Arrays.fill(longCandidates, NO_POSITION);
        }

        int[] longHeads = longCandidates.length != 0 ? new int[heads.length] : new int[0];
        if (longHeads.length != 0) {
            Arrays.fill(longHeads, NO_POSITION);
        }

        int sourceStart = prefix.length;
        int lastHashPosition = data.length - HASH_BYTES;
        for (int absolute = 0; absolute <= lastHashPosition; absolute++) {
            int shortHash = hash(data, absolute, heads.length - 1);
            int shortCandidate = heads[shortHash];
            previous[absolute] = shortCandidate;
            heads[shortHash] = absolute;

            int longCandidate = NO_POSITION;
            if (longHeads.length != 0 && absolute + LONG_HASH_BYTES <= data.length) {
                int longHash = longHash(data, absolute, longHeads.length - 1);
                longCandidate = longHeads[longHash];
                longHeads[longHash] = absolute;
            }

            if (absolute >= sourceStart) {
                int position = absolute - sourceStart;
                shortCandidates[position] = shortCandidate;
                if (longCandidates.length != 0) {
                    longCandidates[position] = longCandidate;
                }
            }
        }

        Match[] plannedLongMatches = new Match[length];
        Match[] cache = new Match[length];
        boolean[] computed = new boolean[length];
        Arrays.fill(plannedLongMatches, Match.NONE);
        Arrays.fill(cache, Match.NONE);
        for (ZstdLongDistanceMatcher.Match longMatch : longDistanceMatches) {
            int position = longMatch.position();
            if (position < 0
                    || longMatch.length() <= 0
                    || longMatch.length() > length - position
                    || longMatch.distance() <= 0) {
                throw new AssertionError("Invalid preplanned Zstandard long-distance match");
            }
            plannedLongMatches[position] = new Match(
                    position,
                    longMatch.length(),
                    longMatch.distance()
            );
        }
        return new MatchIndex(
                data,
                sourceStart,
                distanceLimit,
                parameters,
                previous,
                shortCandidates,
                longCandidates,
                binaryTreeMatches,
                plannedLongMatches,
                cache,
                computed
        );
    }

    /// Computes and caches the best ordinary or preplanned match at one source position.
    private static Match matchAt(MatchIndex index, int position) {
        if (index.computed()[position]) {
            return index.cache()[position];
        }

        ZstdEncoderParameters parameters = index.parameters();
        int strategy = parameters.strategy();
        int candidateLimit = strategy <= 2 ? 1 : parameters.searchDepth();
        Match best = strategy >= 6
                ? index.binaryTreeMatches()[position]
                : findBest(
                        index.data(),
                        index.sourceStart(),
                        position,
                        index.shortCandidates()[position],
                        index.previous(),
                        index.distanceLimit(),
                        candidateLimit,
                        parameters.targetLength()
                );
        if (strategy == 2) {
            Match longMatch = findBest(
                    index.data(),
                    index.sourceStart(),
                    position,
                    index.longCandidates()[position],
                    index.previous(),
                    index.distanceLimit(),
                    1,
                    parameters.targetLength()
            );
            if (longMatch.length() > best.length()) {
                best = longMatch;
            }
        }

        Match planned = index.plannedLongMatches()[position];
        if (planned.length() > best.length()) {
            best = planned;
        }
        index.cache()[position] = best;
        index.computed()[position] = true;
        return best;
    }

    /// Finds the longest match on one bounded candidate chain.
    private static Match findBest(
            byte @Unmodifiable [] data,
            int sourceStart,
            int position,
            int candidate,
            int @Unmodifiable [] previous,
            int distanceLimit,
            int candidateLimit,
            int targetLength
    ) {
        int absolute = sourceStart + position;
        int maximum = data.length - absolute;
        Match best = Match.NONE;
        int searched = 0;
        while (candidate != NO_POSITION && searched++ < candidateLimit) {
            int distance = absolute - candidate;
            if (distance <= 0) {
                throw new AssertionError("Zstandard match candidate is not behind the source");
            }
            if (distance > distanceLimit) {
                break;
            }

            int matchLength = commonLength(data, absolute, candidate, maximum);
            if (matchLength > best.length()) {
                best = new Match(position, matchLength, distance);
                if (matchLength == maximum
                        || targetLength > 0 && matchLength >= targetLength) {
                    break;
                }
            }
            candidate = previous[candidate];
        }
        return best;
    }

    /// Selects matches greedily with the configured number of deferred positions.
    private static @Unmodifiable List<Match> parseLazy(
            MatchIndex index,
            int length,
            int minimumMatch,
            int lazyDepth
    ) {
        ArrayList<Match> matches = new ArrayList<>();
        int position = 0;
        while (position < length) {
            Match current = matchAt(index, position);
            if (current.length() < minimumMatch) {
                position++;
                continue;
            }

            boolean defer = false;
            int maximumLookahead = Math.min(lazyDepth, length - position - 1);
            for (int offset = 1; offset <= maximumLookahead; offset++) {
                Match future = matchAt(index, position + offset);
                if (future.length() >= minimumMatch
                        && future.length() > current.length() + offset) {
                    defer = true;
                    break;
                }
            }
            if (defer) {
                position++;
                continue;
            }

            matches.add(current);
            position += current.length();
        }
        return List.copyOf(matches);
    }

    /// Uses dynamic programming to minimize an approximate block bit cost.
    private static @Unmodifiable List<Match> parseOptimal(
            MatchIndex index,
            int length,
            int minimumMatch,
            int strength
    ) {
        int[] costs = new int[length + 1];
        int[] previousPositions = new int[length + 1];
        Match[] transitions = new Match[length + 1];
        Arrays.fill(costs, Integer.MAX_VALUE);
        Arrays.fill(previousPositions, NO_POSITION);
        Arrays.fill(transitions, Match.NONE);
        costs[0] = 0;

        for (int position = 0; position < length; position++) {
            int currentCost = costs[position];
            if (currentCost == Integer.MAX_VALUE) {
                continue;
            }

            relax(
                    costs,
                    previousPositions,
                    transitions,
                    position + 1,
                    currentCost + LITERAL_COST,
                    position,
                    Match.NONE
            );

            Match match = matchAt(index, position);
            if (match.length() < minimumMatch) {
                continue;
            }
            relaxMatch(
                    costs,
                    previousPositions,
                    transitions,
                    position,
                    match,
                    match.length(),
                    currentCost
            );
            if (strength >= 2 && match.length() > minimumMatch) {
                relaxMatch(
                        costs,
                        previousPositions,
                        transitions,
                        position,
                        match,
                        match.length() - 1,
                        currentCost
                );
            }
            if (strength >= 3) {
                int deltaLimit = Math.min(8, match.length() - minimumMatch);
                for (int delta = 2; delta <= deltaLimit; delta *= 2) {
                    relaxMatch(
                            costs,
                            previousPositions,
                            transitions,
                            position,
                            match,
                            match.length() - delta,
                            currentCost
                    );
                }
            }
        }

        ArrayList<Match> reversed = new ArrayList<>();
        int position = length;
        while (position > 0) {
            int previousPosition = previousPositions[position];
            if (previousPosition < 0 || previousPosition >= position) {
                throw new AssertionError("Incomplete Zstandard optimal parse");
            }
            Match transition = transitions[position];
            if (transition.length() != 0) {
                reversed.add(transition);
            }
            position = previousPosition;
        }
        Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    /// Relaxes one selected match transition.
    private static void relaxMatch(
            int[] costs,
            int[] previousPositions,
            Match[] transitions,
            int position,
            Match match,
            int matchLength,
            int currentCost
    ) {
        int end = position + matchLength;
        int cost = currentCost + matchCost(matchLength, match.distance());
        relax(
                costs,
                previousPositions,
                transitions,
                end,
                cost,
                position,
                new Match(position, matchLength, match.distance())
        );
    }

    /// Stores a lower-cost parser transition.
    private static void relax(
            int[] costs,
            int[] previousPositions,
            Match[] transitions,
            int end,
            int cost,
            int previousPosition,
            Match transition
    ) {
        if (cost < costs[end]) {
            costs[end] = cost;
            previousPositions[end] = previousPosition;
            transitions[end] = transition;
        }
    }

    /// Estimates the encoded bit cost of one sequence match.
    private static int matchCost(int length, int distance) {
        return MATCH_BASE_COST
                + Integer.SIZE - Integer.numberOfLeadingZeros(distance)
                + Integer.SIZE - Integer.numberOfLeadingZeros(length - 2);
    }

    /// Returns the number of future positions examined before accepting a match.
    private static int lazyDepth(int strategy) {
        return switch (strategy) {
            case 4 -> 1;
            case 5, 6 -> 2;
            default -> 0;
        };
    }

    /// Returns the common suffix length for two earlier-indexed positions.
    private static int commonLength(byte @Unmodifiable [] data, int first, int second, int maximum) {
        int length = 0;
        while (length < maximum && data[first + length] == data[second + length]) {
            length++;
        }
        return length;
    }

    /// Hashes four bytes into a power-of-two table.
    private static int hash(byte @Unmodifiable [] source, int offset, int mask) {
        int value = Byte.toUnsignedInt(source[offset])
                | Byte.toUnsignedInt(source[offset + 1]) << 8
                | Byte.toUnsignedInt(source[offset + 2]) << 16
                | source[offset + 3] << 24;
        int tableLog = Integer.bitCount(mask);
        return (int) (Integer.toUnsignedLong(value * 0x9e37_79b1) >>> (32 - tableLog));
    }

    /// Hashes eight bytes into the secondary DFAST table.
    private static int longHash(byte @Unmodifiable [] source, int offset, int mask) {
        long value = 0L;
        for (int index = 0; index < LONG_HASH_BYTES; index++) {
            value |= (long) Byte.toUnsignedInt(source[offset + index]) << (index * 8);
        }
        int tableLog = Integer.bitCount(mask);
        return (int) (value * 0x9e37_79b1_85eb_ca87L >>> (64 - tableLog));
    }

    /// Holds immutable candidate chains and the mutable per-position match cache.
    ///
    /// @param data contiguous prefix and source bytes
    /// @param sourceStart absolute start of source bytes in data
    /// @param distanceLimit maximum ordinary match distance
    /// @param parameters effective encoder parameters
    /// @param previous previous position in each primary hash chain
    /// @param shortCandidates primary hash candidate for each source position
    /// @param longCandidates secondary DFAST candidate for each source position
    /// @param binaryTreeMatches eagerly indexed match for each source position under BT strategies
    /// @param plannedLongMatches verified frame-wide match for each source position
    /// @param cache lazily computed best match for each source position
    /// @param computed whether the corresponding cache entry has been computed
    private record MatchIndex(
            byte @Unmodifiable [] data,
            int sourceStart,
            int distanceLimit,
            ZstdEncoderParameters parameters,
            int @Unmodifiable [] previous,
            int @Unmodifiable [] shortCandidates,
            int @Unmodifiable [] longCandidates,
            Match @Unmodifiable [] binaryTreeMatches,
            Match @Unmodifiable [] plannedLongMatches,
            Match[] cache,
            boolean[] computed
    ) {
    }

    /// Describes one selected non-overlapping block match.
    ///
    /// @param position match start in the source block
    /// @param length match length
    /// @param distance backward match distance
    record Match(int position, int length, int distance) {
        /// Sentinel representing a literal parser transition or absent match.
        static final Match NONE = new Match(0, 0, 0);
    }

    /// Creates no instances.
    private ZstdMatchParser() {
    }
}
