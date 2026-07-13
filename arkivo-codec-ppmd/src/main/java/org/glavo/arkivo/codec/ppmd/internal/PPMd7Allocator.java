// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.ppmd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;

/// Stores PPMd Variant H contexts in the format-defined split suballocator.
@NotNullByDefault
final class PPMd7Allocator {
    /// The number of bytes in one allocation unit containing two packed states.
    private static final int UNIT_SIZE = 12;
    /// The largest directly indexed allocation size in units.
    private static final int MAX_UNITS = 128;
    /// The marker stored in the successor field of a free block.
    private static final int FREE_MARKER = -1;
    /// The first number of allocation indexes with a common unit increment.
    private static final int INDEX_GROUP_0 = 1;
    /// The second number of allocation indexes with a common unit increment.
    private static final int INDEX_GROUP_1 = 4;
    /// The third number of allocation indexes with a common unit increment.
    private static final int INDEX_GROUP_2 = 4;
    /// The fourth number of allocation indexes with a common unit increment.
    private static final int INDEX_GROUP_3 = 4;
    /// The remaining number of allocation indexes.
    private static final int INDEX_GROUP_4 =
            (MAX_UNITS + 3 - INDEX_GROUP_1 - 2 * INDEX_GROUP_2 - 3 * INDEX_GROUP_3) / 4;
    /// The total number of free-list indexes.
    private static final int INDEX_COUNT =
            INDEX_GROUP_0 + INDEX_GROUP_1 + INDEX_GROUP_2 + INDEX_GROUP_3 + INDEX_GROUP_4;
    /// Maps a requested unit count to the smallest matching free-list index.
    private static final byte @Unmodifiable [] UNITS_TO_INDEX = new byte[MAX_UNITS + 1];
    /// Maps each free-list index to its represented unit count.
    private static final int @Unmodifiable [] INDEX_TO_UNITS = new int[INDEX_COUNT];

    static {
        int index = 0;
        int mappedUnits = 0;
        int units = 0;
        int[] groups = {INDEX_GROUP_0, INDEX_GROUP_1, INDEX_GROUP_2, INDEX_GROUP_3, INDEX_GROUP_4};
        for (int increment = 0; increment < groups.length; increment++) {
            for (int entry = 0; entry < groups[increment]; entry++) {
                units += increment;
                INDEX_TO_UNITS[index] = units;
                while (mappedUnits <= units) UNITS_TO_INDEX[mappedUnits++] = (byte) index;
                index++;
            }
        }
    }

    /// Packed state symbols.
    private byte[] symbols = new byte[0];
    /// Packed state frequencies.
    private byte[] frequencies = new byte[0];
    /// Packed state successor pointers and allocator links.
    private int[] successors = new int[0];
    /// The active arena length when reusable arrays are larger than the requested model memory.
    private int arenaStateCount;
    /// Free-list heads indexed by represented unit count.
    private final int[] freeLists = new int[INDEX_COUNT];
    /// Remaining allocations before another free-block coalescing pass.
    private int glueCountdown;
    /// Maximum byte count assigned to the lower byte heap.
    private int lowerHeapMaximumBytes;
    /// Next free byte in the lower byte heap.
    private int lowerHeapPosition;
    /// Exclusive upper byte address of the lower byte heap.
    private int lowerHeapLimit;
    /// Next free state index in the upward-growing unit heap.
    private int unitHeapPosition;
    /// Exclusive upper state index in the downward-growing context heap.
    private int contextHeapPosition;

    /// Creates an allocator without an assigned memory arena.
    PPMd7Allocator() {
    }

    /// Allocates an arena with the requested byte size.
    void initialize(long byteCount) throws IOException {
        if (byteCount < 1L << 11 || byteCount > 256L << 20) {
            throw new IOException("PPMd memory size must be between 2 KiB and 256 MiB");
        }
        long upperUnits = byteCount / 8L / UNIT_SIZE * 7L;
        lowerHeapMaximumBytes = Math.toIntExact(byteCount - upperUnits * UNIT_SIZE);
        long lowerUnits = lowerHeapMaximumBytes / UNIT_SIZE + 1L;
        long stateCount = (1L + lowerUnits + upperUnits) * 2L;
        try {
            int required = (int) stateCount;
            arenaStateCount = required;
            if (symbols.length < required) {
                symbols = new byte[required];
                frequencies = new byte[required];
                successors = new int[required];
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("Unable to allocate the PPMd memory arena", error);
        }
    }

    /// Restarts allocations at the initial split-heap boundaries.
    void restart() throws IOException {
        if (symbols.length == 0) throw new IOException("PPMd allocator is not initialized");
        lowerHeapPosition = UNIT_SIZE + (UNIT_SIZE - lowerHeapMaximumBytes % UNIT_SIZE);
        lowerHeapLimit = UNIT_SIZE + (lowerHeapMaximumBytes / UNIT_SIZE + 1) * UNIT_SIZE;
        unitHeapPosition = lowerHeapLimit / UNIT_SIZE * 2;
        contextHeapPosition = arenaStateCount;
        glueCountdown = 0;
        Arrays.fill(freeLists, 0);
    }

    /// Returns the unsigned symbol stored in one packed state.
    int symbol(int state) {
        return symbols[state] & 0xff;
    }

    /// Stores one symbol in a packed state.
    void setSymbol(int state, int symbol) {
        symbols[state] = (byte) symbol;
    }

    /// Returns the unsigned frequency stored in one packed state.
    int frequency(int state) {
        return frequencies[state] & 0xff;
    }

    /// Stores one frequency in a packed state.
    void setFrequency(int state, int frequency) {
        frequencies[state] = (byte) frequency;
    }

    /// Adds a signed amount to one unsigned byte frequency.
    void addFrequency(int state, int amount) {
        frequencies[state] = (byte) ((frequencies[state] & 0xff) + amount);
    }

    /// Returns the successor pointer stored in one packed state.
    int successor(int state) {
        return successors[state];
    }

    /// Stores a successor pointer in one packed state.
    void setSuccessor(int state, int successor) {
        successors[state] = successor;
    }

    /// Copies all packed fields between two states.
    void copyState(int target, int source) {
        symbols[target] = symbols[source];
        frequencies[target] = frequencies[source];
        successors[target] = successors[source];
    }

    /// Copies a contiguous sequence of packed states.
    void copyStates(int target, int source, int count) {
        System.arraycopy(symbols, source, symbols, target, count);
        System.arraycopy(frequencies, source, frequencies, target, count);
        System.arraycopy(successors, source, successors, target, count);
    }

    /// Swaps all packed fields of two states.
    void swapStates(int first, int second) {
        byte symbol = symbols[first];
        symbols[first] = symbols[second];
        symbols[second] = symbol;
        byte frequency = frequencies[first];
        frequencies[first] = frequencies[second];
        frequencies[second] = frequency;
        int successor = successors[first];
        successors[first] = successors[second];
        successors[second] = successor;
    }

    /// Clears all fields in one state.
    private void clearState(int state) {
        symbols[state] = 0;
        frequencies[state] = 0;
        successors[state] = 0;
    }

    /// Returns the packed unsigned 16-bit value stored in symbol and frequency fields.
    private int packedUnsigned16(int state) {
        return symbol(state) | frequency(state) << 8;
    }

    /// Stores an unsigned 16-bit value in symbol and frequency fields.
    private void setPackedUnsigned16(int state, int value) {
        symbols[state] = (byte) value;
        frequencies[state] = (byte) (value >>> 8);
    }

    /// Pushes one history byte and returns its negative successor pointer.
    int pushByte(int value) {
        int state = lowerHeapPosition / 6;
        int byteOffset = lowerHeapPosition % 6;
        if (byteOffset == 0) {
            symbols[state] = (byte) value;
        } else if (byteOffset == 1) {
            frequencies[state] = (byte) value;
        } else {
            int shift = (byteOffset - 2) * 8;
            int mask = ~(0xff << shift);
            successors[state] = successors[state] & mask | (value & 0xff) << shift;
        }
        lowerHeapPosition++;
        return lowerHeapPosition >= lowerHeapLimit ? 0 : -lowerHeapPosition;
    }

    /// Reverses the latest successful byte push.
    void popByte() {
        lowerHeapPosition--;
    }

    /// Reads one byte addressed by a negative history pointer.
    int successorByte(int pointer) {
        int byteAddress = -pointer;
        int state = byteAddress / 6;
        int byteOffset = byteAddress % 6;
        if (byteOffset == 0) return symbol(state);
        if (byteOffset == 1) return frequency(state);
        return successors[state] >>> ((byteOffset - 2) * 8) & 0xff;
    }

    /// Advances a negative history pointer by one byte.
    int nextBytePointer(int pointer) {
        return pointer - 1;
    }

    /// Returns the number of states in one context.
    int contextStateCount(int context) {
        return symbol(context) + 1;
    }

    /// Stores the number of states in one context.
    void setContextStateCount(int context, int count) {
        setSymbol(context, count - 1);
    }

    /// Returns one context's unsigned sum frequency.
    int contextSumFrequency(int context) {
        return packedUnsigned16(context + 1);
    }

    /// Stores one context's unsigned sum frequency.
    void setContextSumFrequency(int context, int value) {
        setPackedUnsigned16(context + 1, value);
    }

    /// Adds an amount to one context's unsigned sum frequency.
    void addContextSumFrequency(int context, int amount) {
        setContextSumFrequency(context, contextSumFrequency(context) + amount);
    }

    /// Returns one context's suffix context pointer.
    int contextSuffix(int context) {
        int successor = successors[context];
        return Math.max(successor, 0);
    }

    /// Returns the first state index belonging to one context.
    int contextStates(int context) {
        return contextStateCount(context) == 1 ? context + 1 : successors[context + 1];
    }

    /// Stores the external state-block pointer of a multi-state context.
    private void setContextStates(int context, int state) {
        successors[context + 1] = state;
    }

    /// Finds a symbol state in one context.
    int findState(int context, int symbol) throws IOException {
        int start = contextStates(context);
        int count = contextStateCount(context);
        for (int offset = 0; offset < count; offset++) {
            if (symbol(start + offset) == symbol) return start + offset;
        }
        throw new IOException("PPMd context does not contain the required symbol");
    }

    /// Allocates and initializes a one-state context.
    int newContext(int symbol, int frequency, int successor, int suffix) {
        int context;
        if (unitHeapPosition < contextHeapPosition) {
            contextHeapPosition -= 2;
            context = contextHeapPosition;
        } else {
            context = removeFreeBlock(1);
            if (context == 0) context = allocateUnitsRare(1);
            if (context == 0) return 0;
        }
        clearState(context);
        successors[context] = suffix;
        symbols[context + 1] = (byte) symbol;
        frequencies[context + 1] = (byte) frequency;
        successors[context + 1] = successor;
        return context;
    }

    /// Allocates the 256-state root context.
    int newRootContext() {
        int stateCount = 256;
        int context = newContext(0, 0, 0, 0);
        if (context == 0) return 0;
        setContextStateCount(context, stateCount);
        int index = UNITS_TO_INDEX[(stateCount + 1) >>> 1] & 0xff;
        int states = allocateUnits(index);
        if (states == 0) return 0;
        setContextStates(context, states);
        return context;
    }

    /// Expands one context's state block by a single state.
    int expandStates(int context) {
        int states = contextStates(context);
        int oldCount = contextStateCount(context);
        if (oldCount == 1) {
            int firstStateSymbol = symbol(states);
            int firstStateFrequency = frequency(states);
            int firstStateSuccessor = successor(states);
            int allocated = allocateUnits(1);
            if (allocated == 0) return 0;
            setContextStates(context, allocated);
            states = allocated;
            setSymbol(states, firstStateSymbol);
            setFrequency(states, firstStateFrequency);
            setSuccessor(states, firstStateSuccessor);
        } else if ((oldCount & 1) == 0) {
            int oldUnits = oldCount >>> 1;
            int oldIndex = UNITS_TO_INDEX[oldUnits] & 0xff;
            int newIndex = UNITS_TO_INDEX[oldUnits + 1] & 0xff;
            if (oldIndex != newIndex) {
                int allocated = allocateUnits(newIndex);
                if (allocated == 0) return 0;
                copyStates(allocated, states, oldCount);
                addFreeBlock(states, oldIndex);
                setContextStates(context, allocated);
                states = allocated;
            }
        }
        setContextStateCount(context, oldCount + 1);
        return states;
    }

    /// Shrinks one context's state block and returns its resulting first state.
    int shrinkStates(int context, int states, int newCount) {
        int oldCount = contextStateCount(context);
        int oldIndex = UNITS_TO_INDEX[(oldCount + 1) >>> 1] & 0xff;
        int newIndex = UNITS_TO_INDEX[(newCount + 1) >>> 1] & 0xff;
        if (newCount == 1) {
            int oldBlock = successors[context + 1];
            copyState(context + 1, states);
            addFreeBlock(oldBlock, oldIndex);
            states = context + 1;
        } else if (oldIndex != newIndex) {
            int replacement = removeFreeBlock(newIndex);
            if (replacement != 0) {
                copyStates(replacement, states, newCount);
                addFreeBlock(successors[context + 1], oldIndex);
                setContextStates(context, replacement);
                states = replacement;
            } else {
                int freeStart = successors[context + 1] + INDEX_TO_UNITS[newIndex] * 2;
                int freeUnits = INDEX_TO_UNITS[oldIndex] - INDEX_TO_UNITS[newIndex];
                freeUnits(freeStart, freeUnits);
            }
        }
        setContextStateCount(context, newCount);
        return states;
    }

    /// Removes one block from a free list.
    private int removeFreeBlock(int index) {
        int block = freeLists[index];
        if (block != 0) {
            freeLists[index] = successors[block];
            clearState(block);
        }
        return block;
    }

    /// Adds one block to a free list.
    private void addFreeBlock(int block, int index) {
        successors[block] = freeLists[index];
        freeLists[index] = block;
    }

    /// Splits an arbitrary unit count into one or two represented free blocks.
    private void freeUnits(int block, int unitCount) {
        int index = UNITS_TO_INDEX[unitCount] & 0xff;
        if (unitCount != INDEX_TO_UNITS[index]) {
            index--;
            addFreeBlock(block, index);
            unitCount -= INDEX_TO_UNITS[index];
            block += INDEX_TO_UNITS[index] * 2;
            index = UNITS_TO_INDEX[unitCount] & 0xff;
        }
        addFreeBlock(block, index);
    }

    /// Allocates a block represented by one free-list index.
    private int allocateUnits(int index) {
        int block = removeFreeBlock(index);
        if (block != 0) return block;
        int stateCount = INDEX_TO_UNITS[index] * 2;
        if (unitHeapPosition + stateCount <= contextHeapPosition) {
            block = unitHeapPosition;
            unitHeapPosition += stateCount;
            return block;
        }
        return allocateUnitsRare(index);
    }

    /// Allocates after coalescing free blocks, splitting larger blocks, or lowering the byte-heap ceiling.
    private int allocateUnitsRare(int index) {
        if (glueCountdown == 0) {
            glueCountdown = 255;
            glueFreeBlocks();
            int block = removeFreeBlock(index);
            if (block != 0) return block;
        }
        for (int larger = index + 1; larger < INDEX_COUNT; larger++) {
            int block = removeFreeBlock(larger);
            if (block != 0) {
                int remainingUnits = INDEX_TO_UNITS[larger] - INDEX_TO_UNITS[index];
                freeUnits(block + INDEX_TO_UNITS[index] * 2, remainingUnits);
                return block;
            }
        }
        glueCountdown--;
        int loweredLimit = lowerHeapLimit - INDEX_TO_UNITS[index] * UNIT_SIZE;
        if (loweredLimit > lowerHeapPosition) {
            lowerHeapLimit = loweredLimit;
            return lowerHeapLimit / UNIT_SIZE * 2;
        }
        return 0;
    }

    /// Coalesces physically adjacent free blocks and redistributes them by represented size.
    private void glueFreeBlocks() {
        int chain = 0;
        for (int index = 0; index < freeLists.length; index++) {
            int block = freeLists[index];
            while (block != 0) {
                int next = successors[block];
                successors[block + 1] = chain;
                chain = block;
                setPackedUnsigned16(block, INDEX_TO_UNITS[index]);
                successors[block] = FREE_MARKER;
                block = next;
            }
            freeLists[index] = 0;
        }

        for (int block = chain; block != 0; block = successors[block + 1]) {
            if (successors[block] != FREE_MARKER) continue;
            int units = packedUnsigned16(block);
            int adjacent = block + units * 2;
            while (adjacent < arenaStateCount && successors[adjacent] == FREE_MARKER) {
                int adjacentUnits = packedUnsigned16(adjacent);
                if (units + adjacentUnits > 0xffff) break;
                units += adjacentUnits;
                successors[adjacent] = 0;
                setPackedUnsigned16(block, units);
                adjacent = block + units * 2;
            }
        }

        for (int block = chain; block != 0; block = successors[block + 1]) {
            if (successors[block] != FREE_MARKER) continue;
            successors[block] = 0;
            int units = packedUnsigned16(block);
            int remainder = block;
            while (units > MAX_UNITS) {
                addFreeBlock(remainder, INDEX_COUNT - 1);
                units -= MAX_UNITS;
                remainder += MAX_UNITS * 2;
            }
            freeUnits(remainder, units);
        }
    }
}
