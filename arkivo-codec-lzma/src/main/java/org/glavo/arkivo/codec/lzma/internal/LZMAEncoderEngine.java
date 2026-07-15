// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.util.Objects;

/// Encodes raw LZMA symbols with a bounded streaming lookahead and hash-based match finder.
@NotNullByDefault
final class LZMAEncoderEngine {
    /// The number of LZMA state-machine states.
    private static final int STATE_COUNT = 12;

    /// The maximum number of position states.
    private static final int MAX_POSITION_STATES = 16;

    /// The minimum encoded match length.
    private static final int MINIMUM_MATCH_LENGTH = 2;

    /// The largest encoded match length.
    private static final int MAXIMUM_MATCH_LENGTH = 273;

    /// The number of low and middle length symbols per position state.
    private static final int LENGTH_LOW_SYMBOLS = 8;

    /// The number of high length symbols.
    private static final int LENGTH_HIGH_SYMBOLS = 256;

    /// The first distance slot with direct footer bits.
    private static final int START_POSITION_MODEL_INDEX = 4;

    /// The first distance slot using direct range-coded bits.
    private static final int END_POSITION_MODEL_INDEX = 14;

    /// The number of fully modeled distances.
    private static final int FULL_DISTANCE_COUNT = 1 << (END_POSITION_MODEL_INDEX / 2);

    /// The number of alignment bits at the end of large distances.
    private static final int ALIGNMENT_BITS = 4;

    /// The number of positions retained for each three-byte hash.
    private static final int HASH_CANDIDATE_COUNT = 4;

    /// The number of three-byte hash buckets.
    private static final int HASH_BUCKET_COUNT = 1 << 16;

    /// The amount encoded whenever the streaming lookahead fills.
    private static final int PROCESS_BLOCK_SIZE = 64 * 1024;

    /// The pending input capacity including maximum match lookahead.
    private static final int PENDING_CAPACITY = PROCESS_BLOCK_SIZE + MAXIMUM_MATCH_LENGTH;

    /// Match-versus-literal probabilities indexed by state and position state.
    private final short[] matchProbabilities = new short[STATE_COUNT * MAX_POSITION_STATES];

    /// Repetition-versus-new-match probabilities indexed by state.
    private final short[] repetitionProbabilities = new short[STATE_COUNT];

    /// First-repetition probabilities indexed by state.
    private final short[] repetitionGroup0Probabilities = new short[STATE_COUNT];

    /// Second-repetition probabilities indexed by state.
    private final short[] repetitionGroup1Probabilities = new short[STATE_COUNT];

    /// Third-repetition probabilities indexed by state.
    private final short[] repetitionGroup2Probabilities = new short[STATE_COUNT];

    /// Short-first-repetition probabilities indexed by state and position state.
    private final short[] shortRepetitionProbabilities = new short[STATE_COUNT * MAX_POSITION_STATES];

    /// Distance-slot bit trees indexed by match length state.
    private final short[] distanceSlotProbabilities = new short[4 * 64];

    /// Footer bit trees for the modeled distance slots.
    private final short[] distanceFooterProbabilities =
            new short[FULL_DISTANCE_COUNT - END_POSITION_MODEL_INDEX];

    /// The alignment footer bit tree for large distances.
    private final short[] alignmentProbabilities = new short[1 << ALIGNMENT_BITS];

    /// The ordinary match length encoder.
    private final LengthEncoder matchLengthEncoder = new LengthEncoder();

    /// The repetition match length encoder.
    private final LengthEncoder repetitionLengthEncoder = new LengthEncoder();

    /// Literal probabilities for every configured literal context.
    private final short[] literalProbabilities;

    /// The declared dictionary properties written outside the raw stream.
    private final LZMAProperties properties;

    /// The range coder receiving modeled bits.
    private final LZMARangeEncoder rangeEncoder;

    /// The dictionary ring containing bytes already encoded.
    private final byte[] dictionary;

    /// The pending uncompressed input and maximum-match lookahead.
    private final byte[] pending = new byte[PENDING_CAPACITY];

    /// The latest position for every two-byte value.
    private final int[] twoByteHeads = new int[1 << 16];

    /// Recent positions for every three-byte hash.
    private final int[] threeByteCandidates = new int[HASH_BUCKET_COUNT * HASH_CANDIDATE_COUNT];

    /// The four zero-based repetition distances, newest first.
    private final int[] repetitions = new int[4];

    /// The low-bit mask selecting a position state.
    private final int positionMask;

    /// The low-bit mask selecting a literal position context.
    private final int literalPositionMask;

    /// The current LZMA state-machine state.
    private int state;

    /// The next dictionary position to replace.
    private int dictionaryPosition;

    /// The number of initialized dictionary bytes.
    private int dictionaryFull;

    /// The logical output position.
    private long outputPosition;

    /// The normalized positive match-finder position.
    private int matchPosition = 1;

    /// The number of pending input bytes.
    private int pendingSize;

    /// Whether the range stream has been finalized.
    private boolean finished;

    /// Creates a configured streaming LZMA encoder.
    LZMAEncoderEngine(LZMAChannelOutput output, LZMAProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        rangeEncoder = new LZMARangeEncoder(Objects.requireNonNull(output, "output"));
        dictionary = new byte[properties.allocatedDictionarySize()];
        positionMask = (1 << properties.positionBits()) - 1;
        literalPositionMask = (1 << properties.literalPositionBits()) - 1;
        literalProbabilities = new short[
                0x300 << (properties.literalContextBits() + properties.literalPositionBits())
                ];
        initializeProbabilities();
    }

    /// Buffers and incrementally encodes uncompressed bytes.
    void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        if (finished) {
            throw new IOException("LZMA encoder has finished");
        }
        while (length > 0) {
            int copied = Math.min(length, pending.length - pendingSize);
            System.arraycopy(bytes, offset, pending, pendingSize, copied);
            pendingSize += copied;
            offset += copied;
            length -= copied;
            if (pendingSize == pending.length) {
                encodeAtLeast(PROCESS_BLOCK_SIZE);
            }
        }
    }

    /// Encodes every currently buffered source byte without ending the range-coded stream.
    void flush() throws IOException {
        if (finished) {
            throw new IOException("LZMA encoder has finished");
        }
        while (pendingSize > 0) {
            encodeAtLeast(pendingSize);
        }
    }

    /// Finalizes all input, optionally writes an LZMA end marker, and flushes the range coder.
    void finish(boolean writeEndMarker) throws IOException {
        if (finished) {
            return;
        }
        while (pendingSize > 0) {
            encodeAtLeast(pendingSize);
        }
        if (writeEndMarker) {
            encodeNewMatch(MINIMUM_MATCH_LENGTH, -1);
        }
        rangeEncoder.finish();
        finished = true;
    }

    /// Returns the number of uncompressed bytes encoded so far, including buffered bytes.
    long inputSize() {
        return outputPosition + pendingSize;
    }

    /// Initializes every adaptive model to one half.
    private void initializeProbabilities() {
        LZMARangeDecoder.initializeProbabilities(matchProbabilities);
        LZMARangeDecoder.initializeProbabilities(repetitionProbabilities);
        LZMARangeDecoder.initializeProbabilities(repetitionGroup0Probabilities);
        LZMARangeDecoder.initializeProbabilities(repetitionGroup1Probabilities);
        LZMARangeDecoder.initializeProbabilities(repetitionGroup2Probabilities);
        LZMARangeDecoder.initializeProbabilities(shortRepetitionProbabilities);
        LZMARangeDecoder.initializeProbabilities(distanceSlotProbabilities);
        LZMARangeDecoder.initializeProbabilities(distanceFooterProbabilities);
        LZMARangeDecoder.initializeProbabilities(alignmentProbabilities);
        LZMARangeDecoder.initializeProbabilities(literalProbabilities);
        matchLengthEncoder.reset();
        repetitionLengthEncoder.reset();
    }

    /// Encodes symbols until at least the requested pending prefix has been consumed.
    private void encodeAtLeast(int requested) throws IOException {
        int consumed = 0;
        while (consumed < requested) {
            normalizeMatchPositions();
            int available = pendingSize - consumed;
            Match normal = findNormalMatch(consumed, available);
            int bestRepetition = -1;
            int bestRepetitionLength = 0;
            for (int index = 0; index < repetitions.length; index++) {
                int length = matchLength(repetitions[index] + 1, consumed, available);
                if (length > bestRepetitionLength) {
                    bestRepetition = index;
                    bestRepetitionLength = length;
                }
            }

            int length;
            if (bestRepetitionLength >= MINIMUM_MATCH_LENGTH
                    && bestRepetitionLength >= normal.length()) {
                length = bestRepetitionLength;
                encodeRepetition(length, bestRepetition);
            } else if (normal.length() >= MINIMUM_MATCH_LENGTH) {
                length = normal.length();
                encodeNewMatch(length, normal.distance());
            } else if (bestRepetition == 0) {
                length = 1;
                encodeRepetition(1, 0);
            } else {
                length = 1;
                encodeLiteral(Byte.toUnsignedInt(pending[consumed]));
            }
            insertAndConsume(consumed, length);
            consumed += length;
        }
        System.arraycopy(pending, consumed, pending, 0, pendingSize - consumed);
        pendingSize -= consumed;
    }

    /// Encodes one literal using normal or matched-literal contexts.
    private void encodeLiteral(int value) throws IOException {
        int positionState = (int) outputPosition & positionMask;
        int statePositionIndex = (state << 4) + positionState;
        rangeEncoder.encodeBit(matchProbabilities, statePositionIndex, 0);

        int previous = dictionaryFull == 0 ? 0 : peekDistance(0);
        int context = ((int) outputPosition & literalPositionMask) << properties.literalContextBits();
        context += previous >>> (8 - properties.literalContextBits());
        int probabilityOffset = context * 0x300;
        int symbol = 1;
        int bitIndex = 7;
        if (state >= 7) {
            int matchByte = peekDistance(repetitions[0]);
            while (symbol < 0x100) {
                int matchBit = matchByte >>> 7 & 1;
                matchByte <<= 1;
                int bit = value >>> bitIndex-- & 1;
                rangeEncoder.encodeBit(
                        literalProbabilities,
                        probabilityOffset + ((1 + matchBit) << 8) + symbol,
                        bit
                );
                symbol = symbol << 1 | bit;
                if (matchBit != bit) {
                    break;
                }
            }
        }
        while (symbol < 0x100) {
            int bit = value >>> bitIndex-- & 1;
            rangeEncoder.encodeBit(literalProbabilities, probabilityOffset + symbol, bit);
            symbol = symbol << 1 | bit;
        }
        state = state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
    }

    /// Encodes a repetition match and updates the repetition queue.
    private void encodeRepetition(int length, int repetitionIndex) throws IOException {
        int positionState = (int) outputPosition & positionMask;
        int statePositionIndex = (state << 4) + positionState;
        rangeEncoder.encodeBit(matchProbabilities, statePositionIndex, 1);
        rangeEncoder.encodeBit(repetitionProbabilities, state, 1);

        if (repetitionIndex == 0) {
            rangeEncoder.encodeBit(repetitionGroup0Probabilities, state, 0);
            if (length == 1) {
                rangeEncoder.encodeBit(shortRepetitionProbabilities, statePositionIndex, 0);
                state = state < 7 ? 9 : 11;
                return;
            }
            rangeEncoder.encodeBit(shortRepetitionProbabilities, statePositionIndex, 1);
        } else {
            rangeEncoder.encodeBit(repetitionGroup0Probabilities, state, 1);
            int distance = repetitions[repetitionIndex];
            if (repetitionIndex == 1) {
                rangeEncoder.encodeBit(repetitionGroup1Probabilities, state, 0);
            } else {
                rangeEncoder.encodeBit(repetitionGroup1Probabilities, state, 1);
                rangeEncoder.encodeBit(repetitionGroup2Probabilities, state, repetitionIndex - 2);
                if (repetitionIndex == 3) {
                    repetitions[3] = repetitions[2];
                }
                repetitions[2] = repetitions[1];
            }
            repetitions[1] = repetitions[0];
            repetitions[0] = distance;
        }

        repetitionLengthEncoder.encode(rangeEncoder, positionState, length - MINIMUM_MATCH_LENGTH);
        state = state < 7 ? 8 : 11;
    }

    /// Encodes a new match, including the reserved all-ones end-marker distance.
    private void encodeNewMatch(int length, int distance) throws IOException {
        int positionState = (int) outputPosition & positionMask;
        int statePositionIndex = (state << 4) + positionState;
        rangeEncoder.encodeBit(matchProbabilities, statePositionIndex, 1);
        rangeEncoder.encodeBit(repetitionProbabilities, state, 0);
        matchLengthEncoder.encode(rangeEncoder, positionState, length - MINIMUM_MATCH_LENGTH);
        state = state < 7 ? 7 : 10;

        int distanceState = Math.min(length - MINIMUM_MATCH_LENGTH, 3);
        int distanceSlot = distanceSlot(distance);
        rangeEncoder.encodeBitTree(
                distanceSlotProbabilities,
                distanceState << 6,
                6,
                distanceSlot
        );
        if (distanceSlot >= START_POSITION_MODEL_INDEX) {
            int directBits = (distanceSlot >>> 1) - 1;
            int base = (2 | distanceSlot & 1) << directBits;
            int reduced = distance - base;
            if (distanceSlot < END_POSITION_MODEL_INDEX) {
                rangeEncoder.encodeReverseBitTree(
                        distanceFooterProbabilities,
                        base - distanceSlot - 1,
                        directBits,
                        reduced
                );
            } else {
                rangeEncoder.encodeDirectBits(reduced >>> ALIGNMENT_BITS, directBits - ALIGNMENT_BITS);
                rangeEncoder.encodeReverseBitTree(
                        alignmentProbabilities,
                        0,
                        ALIGNMENT_BITS,
                        reduced
                );
            }
        }

        if (distance != -1) {
            repetitions[3] = repetitions[2];
            repetitions[2] = repetitions[1];
            repetitions[1] = repetitions[0];
            repetitions[0] = distance;
        }
    }

    /// Finds the best normal match among recent positions sharing two- or three-byte hashes.
    private Match findNormalMatch(int offset, int available) {
        if (available < MINIMUM_MATCH_LENGTH || dictionaryFull == 0) {
            return Match.NONE;
        }
        int bestLength = 0;
        int bestDistance = 0;
        int candidate = twoByteHeads[twoByteHash(offset)];
        if (validCandidate(candidate)) {
            int distance = matchPosition - candidate;
            int length = matchLength(distance, offset, available);
            if (length > bestLength) {
                bestLength = length;
                bestDistance = distance - 1;
            }
        }
        if (available >= 3) {
            int bucket = threeByteHash(offset) * HASH_CANDIDATE_COUNT;
            for (int index = 0; index < HASH_CANDIDATE_COUNT; index++) {
                candidate = threeByteCandidates[bucket + index];
                if (!validCandidate(candidate)) {
                    continue;
                }
                int distance = matchPosition - candidate;
                int length = matchLength(distance, offset, available);
                if (length > bestLength || length == bestLength && distance - 1 < bestDistance) {
                    bestLength = length;
                    bestDistance = distance - 1;
                    if (bestLength == Math.min(available, MAXIMUM_MATCH_LENGTH)) {
                        break;
                    }
                }
            }
        }
        return bestLength >= MINIMUM_MATCH_LENGTH ? new Match(bestLength, bestDistance) : Match.NONE;
    }

    /// Returns the match length for one positive dictionary distance.
    private int matchLength(int distance, int offset, int available) {
        if (distance <= 0 || distance > dictionaryFull) {
            return 0;
        }
        int limit = Math.min(available, MAXIMUM_MATCH_LENGTH);
        int length = 0;
        while (length < limit) {
            int expected = length < distance
                    ? peekDistance(distance - length - 1)
                    : Byte.toUnsignedInt(pending[offset + length - distance]);
            if (Byte.toUnsignedInt(pending[offset + length]) != expected) {
                break;
            }
            length++;
        }
        return length;
    }

    /// Inserts each consumed position into hash tables and the dictionary ring.
    private void insertAndConsume(int offset, int length) {
        for (int index = 0; index < length; index++) {
            int currentOffset = offset + index;
            int available = pendingSize - currentOffset;
            if (available >= 2) {
                twoByteHeads[twoByteHash(currentOffset)] = matchPosition;
            }
            if (available >= 3) {
                int bucket = threeByteHash(currentOffset) * HASH_CANDIDATE_COUNT;
                System.arraycopy(
                        threeByteCandidates,
                        bucket,
                        threeByteCandidates,
                        bucket + 1,
                        HASH_CANDIDATE_COUNT - 1
                );
                threeByteCandidates[bucket] = matchPosition;
            }
            dictionary[dictionaryPosition++] = pending[currentOffset];
            if (dictionaryPosition == dictionary.length) {
                dictionaryPosition = 0;
            }
            if (dictionaryFull < dictionary.length) {
                dictionaryFull++;
            }
            matchPosition++;
            outputPosition++;
        }
    }

    /// Renormalizes stored match positions before signed integers overflow.
    private void normalizeMatchPositions() {
        if (matchPosition < Integer.MAX_VALUE - PENDING_CAPACITY) {
            return;
        }
        int offset = matchPosition - dictionaryFull - 1;
        normalizePositions(twoByteHeads, offset);
        normalizePositions(threeByteCandidates, offset);
        matchPosition -= offset;
    }

    /// Subtracts a normalization offset and discards positions outside retained history.
    private static void normalizePositions(int[] positions, int offset) {
        for (int index = 0; index < positions.length; index++) {
            positions[index] = positions[index] > offset ? positions[index] - offset : 0;
        }
    }

    /// Returns whether a candidate belongs to the initialized dictionary window.
    private boolean validCandidate(int candidate) {
        return candidate > 0 && matchPosition - candidate <= dictionaryFull;
    }

    /// Returns a previously encoded byte at a zero-based distance.
    private int peekDistance(int distance) {
        int index = dictionaryPosition - distance - 1;
        if (index < 0) {
            index += dictionary.length;
        }
        return Byte.toUnsignedInt(dictionary[index]);
    }

    /// Returns the exact two-byte hash at a pending offset.
    private int twoByteHash(int offset) {
        return Byte.toUnsignedInt(pending[offset]) << 8
                | Byte.toUnsignedInt(pending[offset + 1]);
    }

    /// Returns a mixed 16-bit hash of three pending bytes.
    private int threeByteHash(int offset) {
        int value = Byte.toUnsignedInt(pending[offset]) * 0x1f1f1f;
        value ^= Byte.toUnsignedInt(pending[offset + 1]) << 8;
        value ^= Byte.toUnsignedInt(pending[offset + 2]);
        return value & (HASH_BUCKET_COUNT - 1);
    }

    /// Returns the six-bit LZMA distance slot for a zero-based distance.
    private static int distanceSlot(int distance) {
        if (distance >= 0 && distance < START_POSITION_MODEL_INDEX) {
            return distance;
        }
        int highestBit = 31 - Integer.numberOfLeadingZeros(distance);
        return highestBit << 1 | distance >>> (highestBit - 1) & 1;
    }

    /// Describes one normal dictionary match.
    ///
    /// @param length   the matched byte count
    /// @param distance the zero-based dictionary distance
    private record Match(int length, int distance) {
        /// The singleton representing no encodable normal match.
        private static final Match NONE = new Match(0, 0);
    }

    /// Encodes LZMA low, middle, and high match-length symbols.
    @NotNullByDefault
    private static final class LengthEncoder {
        /// Selects low versus middle/high length symbols.
        private final short[] choice = new short[2];

        /// Low length bit trees indexed by position state.
        private final short[] low = new short[MAX_POSITION_STATES * LENGTH_LOW_SYMBOLS];

        /// Middle length bit trees indexed by position state.
        private final short[] middle = new short[MAX_POSITION_STATES * LENGTH_LOW_SYMBOLS];

        /// The shared high length bit tree.
        private final short[] high = new short[LENGTH_HIGH_SYMBOLS];

        /// Resets every adaptive length probability.
        private void reset() {
            LZMARangeDecoder.initializeProbabilities(choice);
            LZMARangeDecoder.initializeProbabilities(low);
            LZMARangeDecoder.initializeProbabilities(middle);
            LZMARangeDecoder.initializeProbabilities(high);
        }

        /// Encodes one zero-based match-length symbol.
        private void encode(
                LZMARangeEncoder range,
                int positionState,
                int symbol
        ) throws IOException {
            if (symbol < LENGTH_LOW_SYMBOLS) {
                range.encodeBit(choice, 0, 0);
                range.encodeBitTree(low, positionState * LENGTH_LOW_SYMBOLS, 3, symbol);
                return;
            }
            range.encodeBit(choice, 0, 1);
            symbol -= LENGTH_LOW_SYMBOLS;
            if (symbol < LENGTH_LOW_SYMBOLS) {
                range.encodeBit(choice, 1, 0);
                range.encodeBitTree(middle, positionState * LENGTH_LOW_SYMBOLS, 3, symbol);
                return;
            }
            range.encodeBit(choice, 1, 1);
            range.encodeBitTree(high, 0, 8, symbol - LENGTH_LOW_SYMBOLS);
        }
    }
}
