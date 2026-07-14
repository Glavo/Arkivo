// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Arrays;

/// Builds bounded lexicographic binary trees and records their best source matches.
@NotNullByDefault
final class ZstdBinaryTreeMatchFinder {
    /// Number of bytes required by the tree hash.
    private static final int HASH_BYTES = 4;

    /// Number of trailing bytes retained when skipping positions covered by a long match.
    private static final int SKIP_MARGIN = 8;

    /// Sentinel for an absent tree position.
    private static final int NO_POSITION = -1;

    /// Finds the best bounded binary-tree match at every indexed source position.
    static ZstdMatchParser.Match @Unmodifiable [] findMatches(
            byte @Unmodifiable [] data,
            int sourceStart,
            int sourceLength,
            int distanceLimit,
            ZstdEncoderParameters parameters
    ) {
        int[] heads = new int[1 << parameters.hashLog()];
        int[] smallerChildren = new int[data.length];
        int[] largerChildren = new int[data.length];
        ZstdMatchParser.Match[] matches = new ZstdMatchParser.Match[sourceLength];
        Arrays.fill(heads, NO_POSITION);
        Arrays.fill(smallerChildren, NO_POSITION);
        Arrays.fill(largerChildren, NO_POSITION);
        Arrays.fill(matches, ZstdMatchParser.Match.NONE);

        int lastHashPosition = data.length - HASH_BYTES;
        int nextPosition = 0;
        for (int current = 0; current <= lastHashPosition; current++) {
            if (current == sourceStart) {
                nextPosition = sourceStart;
            }
            if (current < nextPosition) {
                continue;
            }

            int hash = hash(data, current, heads.length - 1);
            int candidate = heads[hash];
            heads[hash] = current;

            int segmentEnd = current < sourceStart ? sourceStart : data.length;
            SearchResult result = insertAndFind(
                    data,
                    current,
                    segmentEnd,
                    candidate,
                    distanceLimit,
                    parameters.searchDepth(),
                    smallerChildren,
                    largerChildren
            );
            if (current >= sourceStart) {
                int position = current - sourceStart;
                matches[position] = new ZstdMatchParser.Match(
                        position,
                        result.length(),
                        result.distance()
                );
            }

            if (result.length() > SKIP_MARGIN) {
                int matchEnd = current - result.distance() + result.length();
                nextPosition = Math.max(current + 1, matchEnd - SKIP_MARGIN);
            }
        }
        return matches;
    }

    /// Inserts one position as the new bucket root while finding its longest visited match.
    private static SearchResult insertAndFind(
            byte @Unmodifiable [] data,
            int current,
            int segmentEnd,
            int candidate,
            int distanceLimit,
            int searchDepth,
            int[] smallerChildren,
            int[] largerChildren
    ) {
        int smallerParent = current;
        boolean smallerUsesLargerChild = false;
        int largerParent = current;
        boolean largerUsesLargerChild = true;
        int commonSmaller = 0;
        int commonLarger = 0;
        int maximumLength = segmentEnd - current;
        int bestLength = 0;
        int bestDistance = 0;

        int remaining = searchDepth;
        while (candidate != NO_POSITION && remaining-- > 0) {
            int distance = current - candidate;
            if (distance <= 0) {
                throw new AssertionError("Zstandard binary-tree candidate is not behind the source");
            }
            if (distance > distanceLimit) {
                break;
            }

            int matchLength = commonLength(
                    data,
                    current,
                    candidate,
                    Math.min(commonSmaller, commonLarger),
                    maximumLength
            );
            if (matchLength > bestLength) {
                bestLength = matchLength;
                bestDistance = distance;
            }
            if (matchLength == maximumLength) {
                break;
            }

            if (Byte.toUnsignedInt(data[candidate + matchLength])
                    < Byte.toUnsignedInt(data[current + matchLength])) {
                setChild(
                        smallerChildren,
                        largerChildren,
                        smallerParent,
                        smallerUsesLargerChild,
                        candidate
                );
                commonSmaller = matchLength;
                smallerParent = candidate;
                smallerUsesLargerChild = true;
                candidate = largerChildren[candidate];
            } else {
                setChild(
                        smallerChildren,
                        largerChildren,
                        largerParent,
                        largerUsesLargerChild,
                        candidate
                );
                commonLarger = matchLength;
                largerParent = candidate;
                largerUsesLargerChild = false;
                candidate = smallerChildren[candidate];
            }
        }

        setChild(
                smallerChildren,
                largerChildren,
                smallerParent,
                smallerUsesLargerChild,
                NO_POSITION
        );
        setChild(
                smallerChildren,
                largerChildren,
                largerParent,
                largerUsesLargerChild,
                NO_POSITION
        );
        return new SearchResult(bestLength, bestDistance);
    }

    /// Stores one pending child link selected during tree splitting.
    private static void setChild(
            int[] smallerChildren,
            int[] largerChildren,
            int parent,
            boolean largerChild,
            int child
    ) {
        if (largerChild) {
            largerChildren[parent] = child;
        } else {
            smallerChildren[parent] = child;
        }
    }

    /// Counts common bytes starting at a known common-prefix length.
    private static int commonLength(
            byte @Unmodifiable [] data,
            int first,
            int second,
            int knownLength,
            int maximumLength
    ) {
        int length = knownLength;
        while (length < maximumLength && data[first + length] == data[second + length]) {
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

    /// Holds the best match found while splitting one tree bucket.
    ///
    /// @param length common-byte count
    /// @param distance backward match distance
    private record SearchResult(int length, int distance) {
    }

    /// Creates no instances.
    private ZstdBinaryTreeMatchFinder() {
    }
}
