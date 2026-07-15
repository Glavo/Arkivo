// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/// Encodes and decodes the PPMd Variant H context model shared by PPMd7 and RAR3 streams.
@NotNullByDefault
public final class PPMd7Model {
    /// The sentinel returned when arithmetic decoding selects an escape interval.
    private static final int NO_STATE = -1;
    /// The frequency threshold that triggers context rescaling.
    private static final int MAX_FREQUENCY = 124;
    /// The binary arithmetic scale.
    private static final int BINARY_SCALE = 1 << 14;
    /// The binary probability integral-bit count.
    private static final int INTEGRAL_BITS = 7;
    /// The binary probability adaptation period-bit count.
    private static final int PERIOD_BITS = 7;
    /// Initial escape frequencies selected from an adapted binary probability.
    private static final byte @Unmodifiable [] EXP_ESCAPE = {
            25, 14, 9, 7, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 2
    };
    /// Initial binary escape probabilities for the eight binary-context classes.
    private static final int @Unmodifiable [] INITIAL_BINARY_ESCAPE = {
            0x3cdd, 0x1f3f, 0x59bf, 0x48f3, 0x64a1, 0x5abc, 0x6632, 0x6051
    };
    /// Maps a context state count to a SEE2 row.
    private static final byte @Unmodifiable [] STATE_COUNT_TO_INDEX = createStateCountIndexes();
    /// Maps a suffix state count to a binary-context class.
    private static final byte @Unmodifiable [] STATE_COUNT_TO_BINARY_INDEX = createBinaryStateCountIndexes();

    /// Builds the context-state-count to SEE2-row mapping.
    private static byte[] createStateCountIndexes() {
        byte[] indexes = new byte[256];
        int repetition = 0;
        int index = 0;
        for (int stateCount = 0; stateCount < indexes.length; stateCount++) {
            indexes[stateCount] = (byte) index;
            if (repetition <= 3) {
                index++;
                repetition = index;
            } else {
                repetition--;
            }
        }
        return indexes;
    }

    /// Builds the suffix-state-count to binary-context-class mapping.
    private static byte[] createBinaryStateCountIndexes() {
        byte[] indexes = new byte[256];
        indexes[1] = 2;
        Arrays.fill(indexes, 2, 11, (byte) 4);
        Arrays.fill(indexes, 11, 256, (byte) 6);
        return indexes;
    }

    /// The context and history suballocator.
    private final PPMd7Allocator allocator = new PPMd7Allocator();
    /// The arithmetic range decoder.
    private final @Nullable PPMdRangeDecoder rangeDecoder;
    /// The arithmetic range encoder.
    private final @Nullable PPMdRangeEncoder rangeEncoder;
    /// Symbol exclusion generations indexed by unsigned byte.
    private final byte[] symbolMasks = new byte[256];
    /// Binary probabilities indexed by frequency and context class.
    private final int[][] binaryProbabilities = new int[128][64];
    /// Secondary escape estimators indexed by context and suffix characteristics.
    private final SeeContext[][] seeContexts = new SeeContext[25][16];
    /// Reusable state indexes selected during exclusion decoding.
    private final int[] stateIndexes = new int[256];
    /// Reusable state indexes traversed while creating successor contexts.
    private final int[] successorStateIndexes = new int[256];
    /// Configured maximum model order.
    private int maximumOrder;
    /// Number of orders still falling while history is materialized.
    private int orderFall;
    /// Initial run-length value derived from the configured order.
    private int initialRunLength;
    /// Current binary-context run length.
    private int runLength;
    /// Whether the preceding symbol used a high-probability first state.
    private int previousSuccess;
    /// Current symbol-mask generation in the unsigned byte domain.
    private int escapeGeneration;
    /// Previously decoded unsigned symbol.
    private int previousSymbol;
    /// Initial escape frequency assigned to newly expanded contexts.
    private int initialEscape;
    /// Current maximum-order context, or zero when the model must restart.
    private int currentContext;
    /// Whether a reset header has initialized the memory arena.
    private boolean initialized;
    /// Whether decoding of one symbol is suspended inside its context chain.
    private boolean decodingSymbol;
    /// Current context being examined by a suspended symbol decode.
    private int decodingMinimumContext;
    /// Maximum-order context captured when the suspended symbol decode began.
    private int decodingMaximumContext;
    /// Number of symbols masked before the current escaped-context decode.
    private int decodingMaskedStateCount;
    /// Whether the current escaped-context SEE mean has already been consumed.
    private boolean decodingEscapePrepared;
    /// SEE estimator selected for the current escaped-context decode, or null for the root context.
    private @Nullable SeeContext decodingSeeContext;
    /// Escape frequency captured from the current escaped-context SEE estimator.
    private int decodingEscapeFrequency;
    /// Current phase of a resumable symbol decode.
    private DecodePhase decodingPhase = DecodePhase.FIRST_ORDER;

    /// Creates an uninitialized PPMd model.
    public PPMd7Model(PPMdRangeDecoder rangeDecoder) {
        this.rangeDecoder = Objects.requireNonNull(rangeDecoder, "rangeDecoder");
        this.rangeEncoder = null;
    }

    /// Creates an uninitialized PPMd encoding model.
    PPMd7Model(PPMdRangeEncoder rangeEncoder) {
        this.rangeDecoder = null;
        this.rangeEncoder = Objects.requireNonNull(rangeEncoder, "rangeEncoder");
    }

    /// Invalidates model memory before a non-solid sequence.
    public void reset() {
        initialized = false;
        currentContext = 0;
        previousSymbol = 0;
        clearDecodingSymbol();
    }

    /// Starts a range-coded block and optionally resets model memory and order.
    public void initialize(
            boolean reset,
            int requestedMaximumOrder,
            long memorySize
    ) throws IOException {
        if (!reset) {
            if (!initialized) throw new IOException("PPMd continuation has no initialized model");
            return;
        }
        if (requestedMaximumOrder < 2 || requestedMaximumOrder > 64) {
            throw new IOException("PPMd maximum order must be between 2 and 64");
        }
        allocator.initialize(memorySize);
        maximumOrder = requestedMaximumOrder;
        previousSymbol = 0;
        currentContext = 0;
        clearDecodingSymbol();
        initialized = true;
    }

    /// Decodes and updates one unsigned byte symbol.
    public int readByte() throws IOException {
        if (rangeDecoder == null) throw new IOException("PPMd model is not configured for decoding");
        if (!initialized) throw new IOException("PPMd model is not initialized");
        if (!decodingSymbol) beginDecodingSymbol();

        while (true) {
            int selectedState;
            if (decodingPhase == DecodePhase.FIRST_ORDER) {
                selectedState = allocator.contextStateCount(decodingMinimumContext) == 1
                        ? decodeBinarySymbol(decodingMinimumContext)
                        : decodeFirstOrderSymbol(decodingMinimumContext);
            } else {
                selectedState = decodeEscapedSymbolResumable(
                        decodingMinimumContext,
                        decodingMaskedStateCount
                );
            }
            if (selectedState != NO_STATE) {
                int symbol = allocator.symbol(selectedState);
                currentContext = updateModel(
                        decodingMinimumContext,
                        decodingMaximumContext,
                        selectedState
                );
                previousSymbol = symbol;
                clearDecodingSymbol();
                return symbol;
            }

            decodingMaskedStateCount = allocator.contextStateCount(decodingMinimumContext);
            do {
                orderFall++;
                decodingMinimumContext = allocator.contextSuffix(decodingMinimumContext);
                if (decodingMinimumContext == 0) {
                    clearDecodingSymbol();
                    throw new IOException("Corrupt PPMd suffix chain");
                }
            } while (allocator.contextStateCount(decodingMinimumContext) == decodingMaskedStateCount);
            decodingPhase = DecodePhase.ESCAPED;
            decodingEscapePrepared = false;
            decodingSeeContext = null;
        }
    }

    /// Captures the initial context for one symbol whose range operations may require more input.
    private void beginDecodingSymbol() throws IOException {
        if (currentContext == 0) restartModel();
        decodingSymbol = true;
        decodingMinimumContext = currentContext;
        decodingMaximumContext = currentContext;
        decodingPhase = DecodePhase.FIRST_ORDER;
        decodingEscapePrepared = false;
        decodingSeeContext = null;
    }

    /// Clears every transient field associated with a resumable symbol decode.
    private void clearDecodingSymbol() {
        decodingSymbol = false;
        decodingMinimumContext = 0;
        decodingMaximumContext = 0;
        decodingMaskedStateCount = 0;
        decodingEscapePrepared = false;
        decodingSeeContext = null;
        decodingEscapeFrequency = 0;
        decodingPhase = DecodePhase.FIRST_ORDER;
    }

    /// Encodes and updates one unsigned byte symbol.
    public void writeByte(int symbol) throws IOException {
        if (rangeEncoder == null) throw new IOException("PPMd model is not configured for encoding");
        if (!initialized) throw new IOException("PPMd model is not initialized");
        if (symbol < 0 || symbol > 0xff) throw new IOException("PPMd symbol is out of range");
        if (currentContext == 0) restartModel();

        int minimumContext = currentContext;
        int maximumContext = minimumContext;
        int selectedState = allocator.contextStateCount(minimumContext) == 1
                ? encodeBinarySymbol(minimumContext, symbol)
                : encodeFirstOrderSymbol(minimumContext, symbol);
        while (selectedState == NO_STATE) {
            int maskedStateCount = allocator.contextStateCount(minimumContext);
            do {
                orderFall++;
                minimumContext = allocator.contextSuffix(minimumContext);
                if (minimumContext == 0) throw new IOException("Corrupt PPMd suffix chain");
            } while (allocator.contextStateCount(minimumContext) == maskedStateCount);
            selectedState = encodeEscapedSymbol(minimumContext, maskedStateCount, symbol);
        }

        if (allocator.symbol(selectedState) != symbol) {
            throw new IOException("PPMd encoder selected a different symbol");
        }
        currentContext = updateModel(minimumContext, maximumContext, selectedState);
        previousSymbol = symbol;
    }

    /// Recreates root probabilities and allocator boundaries after reset or memory exhaustion.
    private void restartModel() throws IOException {
        Arrays.fill(symbolMasks, (byte) 0);
        escapeGeneration = 1;
        initialRunLength = -Math.min(maximumOrder, 12) - 1;
        orderFall = maximumOrder;
        runLength = initialRunLength;
        previousSuccess = 0;
        allocator.restart();

        currentContext = allocator.newRootContext();
        if (currentContext == 0) throw new IOException("Unable to allocate the PPMd root context");
        allocator.setContextSumFrequency(currentContext, 257);
        int rootStates = allocator.contextStates(currentContext);
        for (int symbol = 0; symbol < 256; symbol++) {
            allocator.setSymbol(rootStates + symbol, symbol);
            allocator.setFrequency(rootStates + symbol, 1);
            allocator.setSuccessor(rootStates + symbol, 0);
        }

        for (int frequency = 0; frequency < binaryProbabilities.length; frequency++) {
            for (int escapeClass = 0; escapeClass < INITIAL_BINARY_ESCAPE.length; escapeClass++) {
                int probability = BINARY_SCALE - INITIAL_BINARY_ESCAPE[escapeClass] / (frequency + 2);
                for (int contextClass = escapeClass; contextClass < 64; contextClass += 8) {
                    binaryProbabilities[frequency][contextClass] = probability;
                }
            }
        }
        for (int row = 0; row < seeContexts.length; row++) {
            for (int column = 0; column < seeContexts[row].length; column++) {
                seeContexts[row][column] = new SeeContext(5 * row + 10);
            }
        }
    }

    /// Decodes a symbol from a one-state context using an adaptive binary probability.
    private int decodeBinarySymbol(int context) throws IOException {
        int state = allocator.contextStates(context);
        int suffix = allocator.contextSuffix(context);
        if (suffix == 0) throw new IOException("Corrupt PPMd binary context");
        int suffixStateCount = allocator.contextStateCount(suffix);
        int contextClass = previousSuccess
                + (STATE_COUNT_TO_BINARY_INDEX[suffixStateCount - 1] & 0xff)
                + (runLength >> 26 & 0x20);
        if (previousSymbol >= 64) contextClass += 8;
        if (allocator.symbol(state) >= 64) contextClass += 16;

        int frequency = allocator.frequency(state);
        if (frequency <= 0 || frequency > binaryProbabilities.length) {
            throw new IOException("Corrupt PPMd binary frequency");
        }
        int probability = binaryProbabilities[frequency - 1][contextClass];
        int mean = probability + (1 << PERIOD_BITS - 2) >> PERIOD_BITS;
        if (!rangeDecoder.decodeBit(probability, BINARY_SCALE)) {
            if (frequency < 128) allocator.addFrequency(state, 1);
            binaryProbabilities[frequency - 1][contextClass] = probability + (1 << INTEGRAL_BITS) - mean;
            previousSuccess = 1;
            runLength++;
            return state;
        }
        probability -= mean;
        binaryProbabilities[frequency - 1][contextClass] = probability;
        initialEscape = EXP_ESCAPE[probability >>> 10] & 0xff;
        maskSymbol(allocator.symbol(state));
        previousSuccess = 0;
        return NO_STATE;
    }

    /// Encodes a symbol or escape from a one-state context.
    private int encodeBinarySymbol(int context, int symbol) throws IOException {
        PPMdRangeEncoder encoder = Objects.requireNonNull(rangeEncoder);
        int state = allocator.contextStates(context);
        int suffix = allocator.contextSuffix(context);
        if (suffix == 0) throw new IOException("Corrupt PPMd binary context");
        int suffixStateCount = allocator.contextStateCount(suffix);
        int contextClass = previousSuccess
                + (STATE_COUNT_TO_BINARY_INDEX[suffixStateCount - 1] & 0xff)
                + (runLength >> 26 & 0x20);
        if (previousSymbol >= 64) contextClass += 8;
        if (allocator.symbol(state) >= 64) contextClass += 16;

        int frequency = allocator.frequency(state);
        if (frequency <= 0 || frequency > binaryProbabilities.length) {
            throw new IOException("Corrupt PPMd binary frequency");
        }
        int probability = binaryProbabilities[frequency - 1][contextClass];
        int mean = probability + (1 << PERIOD_BITS - 2) >> PERIOD_BITS;
        if (allocator.symbol(state) == symbol) {
            encoder.encodeBit(false, probability, BINARY_SCALE);
            if (frequency < 128) allocator.addFrequency(state, 1);
            binaryProbabilities[frequency - 1][contextClass] =
                    probability + (1 << INTEGRAL_BITS) - mean;
            previousSuccess = 1;
            runLength++;
            return state;
        }
        encoder.encodeBit(true, probability, BINARY_SCALE);
        probability -= mean;
        binaryProbabilities[frequency - 1][contextClass] = probability;
        initialEscape = EXP_ESCAPE[probability >>> 10] & 0xff;
        maskSymbol(allocator.symbol(state));
        previousSuccess = 0;
        return NO_STATE;
    }

    /// Decodes from an unmasked multi-state context or selects its escape interval.
    private int decodeFirstOrderSymbol(int context) throws IOException {
        int scale = allocator.contextSumFrequency(context);
        if (scale == 0) throw new IOException("Corrupt PPMd zero-frequency context");
        int count = rangeDecoder.currentCount(scale);
        previousSuccess = 0;
        int states = allocator.contextStates(context);
        int stateCount = allocator.contextStateCount(context);
        int cumulative = 0;
        for (int offset = 0; offset < stateCount; offset++) {
            int state = states + offset;
            int frequency = allocator.frequency(state);
            cumulative += frequency;
            if (cumulative <= count) continue;
            rangeDecoder.decode(cumulative - frequency, cumulative);
            allocator.addFrequency(state, 4);
            allocator.setContextSumFrequency(context, scale + 4);
            if (offset == 0) {
                if (2 * cumulative > scale) {
                    previousSuccess = 1;
                    runLength++;
                }
            } else if (allocator.frequency(state) > allocator.frequency(state - 1)) {
                allocator.swapStates(state - 1, state);
                state--;
            }
            return rescaleContext(context, state);
        }
        for (int offset = 0; offset < stateCount; offset++) maskSymbol(allocator.symbol(states + offset));
        rangeDecoder.decode(cumulative, scale);
        return NO_STATE;
    }

    /// Encodes from an unmasked multi-state context or emits its escape interval.
    private int encodeFirstOrderSymbol(int context, int symbol) throws IOException {
        PPMdRangeEncoder encoder = Objects.requireNonNull(rangeEncoder);
        int scale = allocator.contextSumFrequency(context);
        if (scale == 0) throw new IOException("Corrupt PPMd zero-frequency context");
        previousSuccess = 0;
        int states = allocator.contextStates(context);
        int stateCount = allocator.contextStateCount(context);
        int cumulative = 0;
        for (int offset = 0; offset < stateCount; offset++) {
            int state = states + offset;
            int frequency = allocator.frequency(state);
            int highCount = cumulative + frequency;
            if (allocator.symbol(state) != symbol) {
                cumulative = highCount;
                continue;
            }
            encoder.encode(cumulative, highCount, scale);
            allocator.addFrequency(state, 4);
            allocator.setContextSumFrequency(context, scale + 4);
            if (offset == 0) {
                if (2 * highCount > scale) {
                    previousSuccess = 1;
                    runLength++;
                }
            } else if (allocator.frequency(state) > allocator.frequency(state - 1)) {
                allocator.swapStates(state - 1, state);
                state--;
            }
            return rescaleContext(context, state);
        }
        for (int offset = 0; offset < stateCount; offset++) {
            maskSymbol(allocator.symbol(states + offset));
        }
        encoder.encode(cumulative, scale, scale);
        return NO_STATE;
    }

    /// Resumes decoding from a suffix context after excluding symbols already escaped from higher orders.
    private int decodeEscapedSymbolResumable(int context, int maskedStateCount) throws IOException {
        if (!decodingEscapePrepared) {
            decodingSeeContext = selectSeeContext(context, maskedStateCount);
            decodingEscapeFrequency = decodingSeeContext == null ? 1 : decodingSeeContext.mean();
            decodingEscapePrepared = true;
        }
        @Nullable SeeContext see = decodingSeeContext;
        int escapeFrequency = decodingEscapeFrequency;
        int states = allocator.contextStates(context);
        int stateCount = allocator.contextStateCount(context);
        int availableCount = stateCount - maskedStateCount;
        if (availableCount <= 0) throw new IOException("Corrupt PPMd exclusion state");

        int sourceOffset = 0;
        int symbolFrequency = 0;
        for (int index = 0; index < availableCount; index++) {
            while (sourceOffset < stateCount && isMasked(allocator.symbol(states + sourceOffset))) sourceOffset++;
            if (sourceOffset >= stateCount) throw new IOException("Corrupt PPMd symbol mask");
            int state = states + sourceOffset++;
            stateIndexes[index] = state;
            symbolFrequency += allocator.frequency(state);
        }

        int scale = escapeFrequency + symbolFrequency;
        int count = rangeDecoder.currentCount(scale);
        if (count >= symbolFrequency) {
            rangeDecoder.decode(symbolFrequency, scale);
            if (see != null) see.addToSum(scale);
            for (int index = 0; index < availableCount; index++) {
                maskSymbol(allocator.symbol(stateIndexes[index]));
            }
            decodingEscapePrepared = false;
            decodingSeeContext = null;
            return NO_STATE;
        }

        int selected = 0;
        int cumulative = allocator.frequency(stateIndexes[0]);
        while (cumulative <= count) cumulative += allocator.frequency(stateIndexes[++selected]);
        int state = stateIndexes[selected];
        rangeDecoder.decode(cumulative - allocator.frequency(state), cumulative);
        if (see != null) see.update();
        escapeGeneration = escapeGeneration + 1 & 0xff;
        runLength = initialRunLength;
        allocator.addFrequency(state, 4);
        allocator.addContextSumFrequency(context, 4);
        decodingEscapePrepared = false;
        decodingSeeContext = null;
        return rescaleContext(context, state);
    }

    /// Encodes from a suffix context after excluding symbols escaped from higher orders.
    private int encodeEscapedSymbol(
            int context,
            int maskedStateCount,
            int symbol
    ) throws IOException {
        PPMdRangeEncoder encoder = Objects.requireNonNull(rangeEncoder);
        @Nullable SeeContext see = selectSeeContext(context, maskedStateCount);
        int escapeFrequency = see == null ? 1 : see.mean();
        int states = allocator.contextStates(context);
        int stateCount = allocator.contextStateCount(context);
        int availableCount = stateCount - maskedStateCount;
        if (availableCount <= 0) throw new IOException("Corrupt PPMd exclusion state");

        int sourceOffset = 0;
        int symbolFrequency = 0;
        int selected = -1;
        int selectedLowCount = 0;
        for (int index = 0; index < availableCount; index++) {
            while (sourceOffset < stateCount
                    && isMasked(allocator.symbol(states + sourceOffset))) {
                sourceOffset++;
            }
            if (sourceOffset >= stateCount) throw new IOException("Corrupt PPMd symbol mask");
            int state = states + sourceOffset++;
            stateIndexes[index] = state;
            if (allocator.symbol(state) == symbol) {
                selected = index;
                selectedLowCount = symbolFrequency;
            }
            symbolFrequency += allocator.frequency(state);
        }

        int scale = escapeFrequency + symbolFrequency;
        if (selected < 0) {
            encoder.encode(symbolFrequency, scale, scale);
            if (see != null) see.addToSum(scale);
            for (int index = 0; index < availableCount; index++) {
                maskSymbol(allocator.symbol(stateIndexes[index]));
            }
            return NO_STATE;
        }

        int state = stateIndexes[selected];
        encoder.encode(
                selectedLowCount,
                selectedLowCount + allocator.frequency(state),
                scale
        );
        if (see != null) see.update();
        escapeGeneration = escapeGeneration + 1 & 0xff;
        runLength = initialRunLength;
        allocator.addFrequency(state, 4);
        allocator.addContextSumFrequency(context, 4);
        return rescaleContext(context, state);
    }

    /// Selects the secondary escape estimator for one masked suffix context.
    private @Nullable SeeContext selectSeeContext(int context, int maskedStateCount) {
        int stateCount = allocator.contextStateCount(context);
        if (stateCount == 256) return null;
        int difference = stateCount - maskedStateCount;
        int column = previousSymbol >= 64 ? 8 : 0;
        int suffix = allocator.contextSuffix(context);
        if (suffix != 0 && difference < allocator.contextStateCount(suffix) - stateCount) column++;
        if (allocator.contextSumFrequency(context) < 11 * stateCount) column += 2;
        if (maskedStateCount > difference) column += 4;
        return seeContexts[STATE_COUNT_TO_INDEX[difference - 1] & 0xff][column];
    }

    /// Rescales and frequency-sorts a context after a selected symbol becomes too frequent.
    private int rescaleContext(int context, int selectedState) {
        if (allocator.frequency(selectedState) <= MAX_FREQUENCY) return selectedState;
        allocator.addFrequency(selectedState, 4);
        int states = allocator.contextStates(context);
        int stateCount = allocator.contextStateCount(context);
        int escapeFrequency = allocator.contextSumFrequency(context) + 4;
        int sumFrequency = 0;
        for (int offset = 0; offset < stateCount; offset++) {
            int state = states + offset;
            int frequency = allocator.frequency(state);
            escapeFrequency -= frequency;
            if (orderFall != 0) frequency++;
            frequency >>>= 1;
            allocator.setFrequency(state, frequency);
            sumFrequency += frequency;
            if (offset == 0 || frequency <= allocator.frequency(state - 1)) continue;

            int insertionOffset = offset - 1;
            while (insertionOffset > 0 && frequency > allocator.frequency(states + insertionOffset - 1)) {
                insertionOffset--;
            }
            int symbol = allocator.symbol(state);
            int successor = allocator.successor(state);
            allocator.copyStates(states + insertionOffset + 1, states + insertionOffset, offset - insertionOffset);
            allocator.setSymbol(states + insertionOffset, symbol);
            allocator.setFrequency(states + insertionOffset, frequency);
            allocator.setSuccessor(states + insertionOffset, successor);
        }

        int lastOffset = stateCount - 1;
        while (lastOffset > 0 && allocator.frequency(states + lastOffset) == 0) {
            lastOffset--;
            escapeFrequency++;
        }
        if (lastOffset != stateCount - 1) {
            states = allocator.shrinkStates(context, states, lastOffset + 1);
        }
        int firstState = states;
        if (lastOffset == 0) {
            while (true) {
                int frequency = allocator.frequency(firstState);
                frequency -= frequency >>> 1;
                allocator.setFrequency(firstState, frequency);
                escapeFrequency >>>= 1;
                if (escapeFrequency <= 1) return firstState;
            }
        }
        sumFrequency += escapeFrequency - (escapeFrequency >>> 1);
        allocator.setContextSumFrequency(context, sumFrequency);
        return firstState;
    }

    /// Updates suffix frequencies, materializes history, and expands contexts after one symbol.
    private int updateModel(int minimumContext, int maximumContext, int selectedState) throws IOException {
        int selectedSuccessor = allocator.successor(selectedState);
        if (orderFall == 0 && selectedSuccessor > 0) return selectedSuccessor;
        if (escapeGeneration == 0) {
            escapeGeneration = 1;
            Arrays.fill(symbolMasks, (byte) 0);
        }

        int matchingSuffixState = NO_STATE;
        int selectedSymbol = allocator.symbol(selectedState);
        if (allocator.frequency(selectedState) < MAX_FREQUENCY / 4
                && allocator.contextSuffix(minimumContext) != 0) {
            int suffix = allocator.contextSuffix(minimumContext);
            int states = allocator.contextStates(suffix);
            int stateCount = allocator.contextStateCount(suffix);
            if (stateCount > 1) {
                matchingSuffixState = allocator.findState(suffix, selectedSymbol);
                if (matchingSuffixState > states
                        && allocator.frequency(matchingSuffixState) >= allocator.frequency(matchingSuffixState - 1)) {
                    allocator.swapStates(matchingSuffixState - 1, matchingSuffixState);
                    matchingSuffixState--;
                }
                if (allocator.frequency(matchingSuffixState) < MAX_FREQUENCY - 9) {
                    allocator.addFrequency(matchingSuffixState, 2);
                    allocator.addContextSumFrequency(suffix, 2);
                }
            } else {
                matchingSuffixState = states;
                if (allocator.frequency(states) < 32) allocator.addFrequency(states, 1);
            }
        }

        if (orderFall == 0) {
            int successor = createSuccessors(minimumContext, selectedState, matchingSuffixState);
            allocator.setSuccessor(selectedState, successor);
            return successor;
        }

        int historyPointer = allocator.pushByte(selectedSymbol);
        if (historyPointer == 0) return 0;
        int newContext;
        if (allocator.successor(selectedState) == 0) {
            allocator.setSuccessor(selectedState, historyPointer);
            newContext = minimumContext;
        } else {
            if (allocator.successor(selectedState) > 0) {
                newContext = allocator.successor(selectedState);
            } else {
                newContext = createSuccessors(minimumContext, selectedState, matchingSuffixState);
                if (newContext == 0) return 0;
            }
            orderFall--;
            if (orderFall == 0) {
                historyPointer = newContext;
                if (maximumContext != minimumContext) allocator.popByte();
            }
        }

        int minimumStateCount = allocator.contextStateCount(minimumContext);
        int baseFrequency = allocator.contextSumFrequency(minimumContext)
                - minimumStateCount
                - (allocator.frequency(selectedState) - 1);
        for (int context = maximumContext; context != minimumContext; context = allocator.contextSuffix(context)) {
            int states = allocator.expandStates(context);
            if (states == 0) return 0;
            int oldStateCount = allocator.contextStateCount(context) - 1;
            int sumFrequency;
            if (oldStateCount != 1) {
                sumFrequency = allocator.contextSumFrequency(context);
                if (4 * oldStateCount <= minimumStateCount && sumFrequency <= 8 * oldStateCount) sumFrequency += 2;
                if (2 * oldStateCount < minimumStateCount) sumFrequency++;
            } else {
                int firstFrequency = allocator.frequency(states);
                allocator.setFrequency(
                        states,
                        firstFrequency < MAX_FREQUENCY / 4 - 1 ? firstFrequency * 2 : MAX_FREQUENCY - 4
                );
                sumFrequency = allocator.frequency(states) + initialEscape;
                if (minimumStateCount > 3) sumFrequency++;
            }

            int weightedFrequency = 2 * allocator.frequency(selectedState) * (sumFrequency + 6);
            int totalFrequency = baseFrequency + sumFrequency;
            int newFrequency;
            if (weightedFrequency >= 6 * totalFrequency) {
                if (weightedFrequency >= 15 * totalFrequency) newFrequency = 7;
                else if (weightedFrequency >= 12 * totalFrequency) newFrequency = 6;
                else if (weightedFrequency >= 9 * totalFrequency) newFrequency = 5;
                else newFrequency = 4;
                sumFrequency += newFrequency;
            } else {
                if (weightedFrequency >= 4 * totalFrequency) newFrequency = 3;
                else if (weightedFrequency > totalFrequency) newFrequency = 2;
                else newFrequency = 1;
                sumFrequency += 3;
            }
            int newState = states + oldStateCount;
            allocator.setSymbol(newState, selectedSymbol);
            allocator.setFrequency(newState, newFrequency);
            allocator.setSuccessor(newState, historyPointer);
            allocator.setContextSumFrequency(context, sumFrequency);
        }
        return newContext;
    }

    /// Builds missing successor contexts by walking matching suffix states from high to low order.
    private int createSuccessors(int context, int selectedState, int matchingSuffixState) throws IOException {
        int buffered = 0;
        if (orderFall != 0) successorStateIndexes[buffered++] = selectedState;
        while (allocator.contextSuffix(context) != 0) {
            context = allocator.contextSuffix(context);
            if (matchingSuffixState == NO_STATE) {
                matchingSuffixState = allocator.findState(context, allocator.symbol(selectedState));
            }
            if (allocator.successor(matchingSuffixState) != allocator.successor(selectedState)) {
                int successor = allocator.successor(matchingSuffixState);
                context = Math.max(successor, 0);
                break;
            }
            if (buffered >= successorStateIndexes.length) {
                throw new IOException("PPMd successor depth exceeds the symbol alphabet");
            }
            successorStateIndexes[buffered++] = matchingSuffixState;
            matchingSuffixState = NO_STATE;
        }
        if (buffered == 0) return context;
        if (context == 0 || allocator.successor(selectedState) >= 0) {
            throw new IOException("Corrupt PPMd successor history");
        }

        int successorSymbol = allocator.successorByte(allocator.successor(selectedState));
        int successorPointer = allocator.nextBytePointer(allocator.successor(selectedState));
        int successorFrequency;
        int contextStates = allocator.contextStates(context);
        int contextStateCount = allocator.contextStateCount(context);
        if (contextStateCount > 1) {
            int state = allocator.findState(context, successorSymbol);
            int symbolFrequency = allocator.frequency(state) - 1;
            int remainingFrequency = allocator.contextSumFrequency(context) - contextStateCount - symbolFrequency;
            if (2 * symbolFrequency <= remainingFrequency) {
                successorFrequency = 5 * symbolFrequency > remainingFrequency ? 2 : 1;
            } else {
                successorFrequency = 1
                        + (2 * symbolFrequency + 3 * remainingFrequency - 1) / (2 * remainingFrequency);
            }
        } else {
            successorFrequency = allocator.frequency(contextStates);
        }

        for (int index = buffered - 1; index >= 0; index--) {
            context = allocator.newContext(successorSymbol, successorFrequency, successorPointer, context);
            if (context == 0) return 0;
            allocator.setSuccessor(successorStateIndexes[index], context);
        }
        return context;
    }

    /// Returns whether one symbol is excluded in the current escape generation.
    private boolean isMasked(int symbol) {
        return (symbolMasks[symbol] & 0xff) == escapeGeneration;
    }

    /// Excludes one symbol in the current escape generation.
    private void maskSymbol(int symbol) {
        symbolMasks[symbol] = (byte) escapeGeneration;
    }

    /// Identifies the context-selection phase of one resumable decoded symbol.
    @NotNullByDefault
    private enum DecodePhase {
        /// The maximum-order context is being decoded.
        FIRST_ORDER,

        /// A suffix context is being decoded after excluding symbols from higher orders.
        ESCAPED
    }

    /// Maintains one secondary escape estimate with an adaptive period.
    @NotNullByDefault
    private static final class SeeContext {
        /// The scaled escape-frequency sum in the unsigned 16-bit domain.
        private int sum;
        /// The current scaling shift.
        private int shift;
        /// Symbols remaining before the next period expansion.
        private int count;

        /// Creates one estimator from its format-defined row seed.
        private SeeContext(int initial) {
            sum = initial << PERIOD_BITS - 4;
            shift = PERIOD_BITS - 4;
            count = 4;
        }

        /// Returns and subtracts the current nonzero mean escape frequency.
        private int mean() {
            int mean = sum >>> shift;
            if (mean == 0) return 1;
            sum = sum - mean & 0xffff;
            return mean;
        }

        /// Adds an escaped interval scale in the unsigned 16-bit domain.
        private void addToSum(int value) {
            sum = sum + value & 0xffff;
        }

        /// Advances the adaptation period after a successful symbol.
        private void update() {
            if (shift >= PERIOD_BITS) return;
            count--;
            if (count == 0) {
                sum = sum + sum & 0xffff;
                count = 3 << shift;
                shift++;
            }
        }
    }
}
