// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.compress.lzma.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/// Decodes raw LZMA symbols while retaining dictionary and probability state across optional LZMA2 chunks.
@NotNullByDefault
final class LzmaDecoderEngine {
    /// The number of LZMA state-machine states.
    private static final int STATE_COUNT = 12;

    /// The maximum number of position states.
    private static final int MAX_POSITION_STATES = 16;

    /// The minimum match length.
    private static final int MINIMUM_MATCH_LENGTH = 2;

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

    /// The dictionary ring containing previously decoded bytes.
    private final byte[] dictionary;

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

    /// The ordinary match length decoder.
    private final LengthDecoder matchLengthDecoder = new LengthDecoder();

    /// The repetition match length decoder.
    private final LengthDecoder repetitionLengthDecoder = new LengthDecoder();

    /// Literal probabilities for every configured literal context.
    private short[] literalProbabilities = new short[0];

    /// The active range decoder, or `null` between chunks.
    private @Nullable LzmaRangeDecoder rangeDecoder;

    /// The low-bit mask selecting a position state.
    private int positionMask;

    /// The low-bit mask selecting a literal position context.
    private int literalPositionMask;

    /// The number of previous-byte high bits selecting a literal context.
    private int literalContextBits;

    /// The current LZMA state-machine state.
    private int state;

    /// The four zero-based repetition distances, newest first.
    private final int[] repetitions = new int[4];

    /// The next dictionary position to replace.
    private int dictionaryPosition;

    /// The number of initialized dictionary bytes.
    private int dictionaryFull;

    /// The logical output position since the last dictionary reset.
    private long outputPosition;

    /// Remaining output bytes in the active chunk, or `-1` for an EOS-terminated raw stream.
    private long chunkRemaining;

    /// The zero-based distance of the pending dictionary copy.
    private int pendingDistance;

    /// The number of pending dictionary-copy bytes.
    private int pendingLength;

    /// Whether the active raw stream may terminate with the LZMA end marker.
    private boolean endMarkerAllowed;

    /// Whether the active range-coded chunk has ended.
    private boolean chunkEnded = true;

    /// Creates an engine with the requested dictionary allocation.
    LzmaDecoderEngine(int dictionarySize) {
        if (dictionarySize < 0 || dictionarySize > LzmaProperties.MAXIMUM_DICTIONARY_SIZE) {
            throw new IllegalArgumentException("Unsupported LZMA dictionary size: "
                    + Integer.toUnsignedLong(dictionarySize));
        }
        dictionary = new byte[Math.max(dictionarySize, LzmaProperties.MINIMUM_DICTIONARY_SIZE)];
    }

    /// Configures a packed property byte and resets all probability state.
    void configure(int propertyByte) {
        LzmaProperties properties = LzmaProperties.decode(propertyByte, dictionary.length);
        literalContextBits = properties.literalContextBits();
        literalPositionMask = (1 << properties.literalPositionBits()) - 1;
        positionMask = (1 << properties.positionBits()) - 1;
        literalProbabilities = new short[0x300 << (literalContextBits + properties.literalPositionBits())];
        resetState();
    }

    /// Clears the LZ dictionary and logical output position.
    void resetDictionary() {
        dictionaryPosition = 0;
        dictionaryFull = 0;
        outputPosition = 0L;
        pendingLength = 0;
    }

    /// Resets probabilities, repetitions, and the LZMA state machine.
    void resetState() {
        LzmaRangeDecoder.initializeProbabilities(matchProbabilities);
        LzmaRangeDecoder.initializeProbabilities(repetitionProbabilities);
        LzmaRangeDecoder.initializeProbabilities(repetitionGroup0Probabilities);
        LzmaRangeDecoder.initializeProbabilities(repetitionGroup1Probabilities);
        LzmaRangeDecoder.initializeProbabilities(repetitionGroup2Probabilities);
        LzmaRangeDecoder.initializeProbabilities(shortRepetitionProbabilities);
        LzmaRangeDecoder.initializeProbabilities(distanceSlotProbabilities);
        LzmaRangeDecoder.initializeProbabilities(distanceFooterProbabilities);
        LzmaRangeDecoder.initializeProbabilities(alignmentProbabilities);
        LzmaRangeDecoder.initializeProbabilities(literalProbabilities);
        matchLengthDecoder.reset();
        repetitionLengthDecoder.reset();
        state = 0;
        java.util.Arrays.fill(repetitions, 0);
        pendingLength = 0;
    }

    /// Starts one range-coded raw stream or LZMA2 chunk.
    void startChunk(InputStream input, long expectedSize, boolean allowEndMarker) throws IOException {
        Objects.requireNonNull(input, "input");
        if (expectedSize < -1L) {
            throw new IllegalArgumentException("LZMA expected size must be nonnegative or -1");
        }
        rangeDecoder = new LzmaRangeDecoder(input);
        chunkRemaining = expectedSize;
        endMarkerAllowed = allowEndMarker;
        chunkEnded = false;
        pendingLength = 0;
    }

    /// Returns whether the active chunk has ended.
    boolean chunkEnded() {
        return chunkEnded;
    }

    /// Returns whether the active range coder reached its canonical final state.
    boolean rangeFinished() {
        LzmaRangeDecoder range = rangeDecoder;
        return range != null && range.finished();
    }

    /// Returns the number of pending match bytes available without parsing another symbol.
    int available() {
        return pendingLength;
    }

    /// Decodes one byte from the active range-coded chunk.
    int readByte() throws IOException {
        if (chunkEnded) {
            return -1;
        }
        if (chunkRemaining == 0L) {
            if (pendingLength != 0) {
                throw new IOException("LZMA match exceeds the expected output size");
            }
            chunkEnded = true;
            return -1;
        }
        if (pendingLength > 0) {
            return copyPendingByte();
        }

        LzmaRangeDecoder range = Objects.requireNonNull(rangeDecoder, "rangeDecoder");
        int positionState = (int) outputPosition & positionMask;
        int statePositionIndex = (state << 4) + positionState;
        if (range.decodeBit(matchProbabilities, statePositionIndex) == 0) {
            return decodeLiteral(range);
        }

        int matchLength;
        if (range.decodeBit(repetitionProbabilities, state) == 1) {
            if (dictionaryFull == 0) {
                throw new IOException("LZMA repetition appears before dictionary data");
            }
            if (range.decodeBit(repetitionGroup0Probabilities, state) == 0) {
                if (range.decodeBit(shortRepetitionProbabilities, statePositionIndex) == 0) {
                    state = state < 7 ? 9 : 11;
                    pendingDistance = repetitions[0];
                    pendingLength = 1;
                    return copyPendingByte();
                }
            } else {
                int distance;
                if (range.decodeBit(repetitionGroup1Probabilities, state) == 0) {
                    distance = repetitions[1];
                } else {
                    if (range.decodeBit(repetitionGroup2Probabilities, state) == 0) {
                        distance = repetitions[2];
                    } else {
                        distance = repetitions[3];
                        repetitions[3] = repetitions[2];
                    }
                    repetitions[2] = repetitions[1];
                }
                repetitions[1] = repetitions[0];
                repetitions[0] = distance;
            }
            matchLength = repetitionLengthDecoder.decode(range, positionState) + MINIMUM_MATCH_LENGTH;
            state = state < 7 ? 8 : 11;
        } else {
            repetitions[3] = repetitions[2];
            repetitions[2] = repetitions[1];
            repetitions[1] = repetitions[0];
            matchLength = matchLengthDecoder.decode(range, positionState) + MINIMUM_MATCH_LENGTH;
            state = state < 7 ? 7 : 10;
            int distanceSlot = range.decodeBitTree(
                    distanceSlotProbabilities,
                    lengthToDistanceState(matchLength) << 6,
                    6
            );
            int distance;
            if (distanceSlot < START_POSITION_MODEL_INDEX) {
                distance = distanceSlot;
            } else {
                int directBits = (distanceSlot >>> 1) - 1;
                distance = (2 | distanceSlot & 1) << directBits;
                if (distanceSlot < END_POSITION_MODEL_INDEX) {
                    distance += range.decodeReverseBitTree(
                            distanceFooterProbabilities,
                            distance - distanceSlot - 1,
                            directBits
                    );
                } else {
                    distance += range.decodeDirectBits(directBits - ALIGNMENT_BITS) << ALIGNMENT_BITS;
                    distance += range.decodeReverseBitTree(alignmentProbabilities, 0, ALIGNMENT_BITS);
                    if (distance == -1) {
                        if (!endMarkerAllowed || chunkRemaining >= 0L) {
                            throw new IOException("Unexpected LZMA end marker");
                        }
                        chunkEnded = true;
                        return -1;
                    }
                }
            }
            repetitions[0] = distance;
        }

        pendingDistance = repetitions[0];
        pendingLength = matchLength;
        validatePendingMatch();
        return copyPendingByte();
    }

    /// Adds one uncompressed LZMA2 byte directly to the shared dictionary.
    int putUncompressedByte(int value) {
        return putByte(value);
    }

    /// Decodes one literal with optional matched-literal context.
    private int decodeLiteral(LzmaRangeDecoder range) throws IOException {
        int previous = dictionaryFull == 0 ? 0 : peekDistance(0);
        int context = ((int) outputPosition & literalPositionMask) << literalContextBits;
        context += previous >>> (8 - literalContextBits);
        int probabilityOffset = context * 0x300;
        int symbol = 1;
        if (state < 7) {
            while (symbol < 0x100) {
                symbol = symbol << 1 | range.decodeBit(literalProbabilities, probabilityOffset + symbol);
            }
        } else {
            int matchByte = peekDistance(repetitions[0]);
            while (symbol < 0x100) {
                int matchBit = matchByte >>> 7 & 1;
                matchByte <<= 1;
                int bit = range.decodeBit(
                        literalProbabilities,
                        probabilityOffset + ((1 + matchBit) << 8) + symbol
                );
                symbol = symbol << 1 | bit;
                if (matchBit != bit) {
                    while (symbol < 0x100) {
                        symbol = symbol << 1 | range.decodeBit(
                                literalProbabilities,
                                probabilityOffset + symbol
                        );
                    }
                    break;
                }
            }
        }
        state = state < 4 ? 0 : state < 10 ? state - 3 : state - 6;
        return putByte(symbol - 0x100);
    }

    /// Validates a pending dictionary copy against history and chunk limits.
    private void validatePendingMatch() throws IOException {
        long distance = Integer.toUnsignedLong(pendingDistance);
        if (distance >= dictionaryFull) {
            throw new IOException("Invalid LZMA match distance: " + (distance + 1L));
        }
        if (chunkRemaining >= 0L && pendingLength > chunkRemaining) {
            throw new IOException("LZMA match exceeds the expected output size");
        }
    }

    /// Copies one pending dictionary byte.
    private int copyPendingByte() throws IOException {
        validatePendingMatch();
        int value = peekDistance(pendingDistance);
        pendingLength--;
        return putByte(value);
    }

    /// Returns a previously decoded byte at a zero-based distance.
    private int peekDistance(int distance) throws IOException {
        long unsignedDistance = Integer.toUnsignedLong(distance);
        if (unsignedDistance >= dictionaryFull) {
            throw new IOException("Invalid LZMA dictionary distance: " + (unsignedDistance + 1L));
        }
        int index = dictionaryPosition - (int) unsignedDistance - 1;
        if (index < 0) {
            index += dictionary.length;
        }
        return Byte.toUnsignedInt(dictionary[index]);
    }

    /// Adds one decoded byte to the dictionary and active chunk accounting.
    private int putByte(int value) {
        dictionary[dictionaryPosition++] = (byte) value;
        if (dictionaryPosition == dictionary.length) {
            dictionaryPosition = 0;
        }
        if (dictionaryFull < dictionary.length) {
            dictionaryFull++;
        }
        outputPosition++;
        if (chunkRemaining > 0L) {
            chunkRemaining--;
        }
        return value & 0xff;
    }

    /// Returns the distance-slot tree state for a match length.
    private static int lengthToDistanceState(int matchLength) {
        return Math.min(matchLength - MINIMUM_MATCH_LENGTH, 3);
    }

    /// Decodes LZMA low, middle, and high match-length symbols.
    @NotNullByDefault
    private static final class LengthDecoder {
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
            LzmaRangeDecoder.initializeProbabilities(choice);
            LzmaRangeDecoder.initializeProbabilities(low);
            LzmaRangeDecoder.initializeProbabilities(middle);
            LzmaRangeDecoder.initializeProbabilities(high);
        }

        /// Decodes one zero-based match-length symbol.
        private int decode(LzmaRangeDecoder range, int positionState) throws IOException {
            if (range.decodeBit(choice, 0) == 0) {
                return range.decodeBitTree(low, positionState * LENGTH_LOW_SYMBOLS, 3);
            }
            if (range.decodeBit(choice, 1) == 0) {
                return LENGTH_LOW_SYMBOLS
                        + range.decodeBitTree(middle, positionState * LENGTH_LOW_SYMBOLS, 3);
            }
            return LENGTH_LOW_SYMBOLS * 2 + range.decodeBitTree(high, 0, 8);
        }
    }
}
