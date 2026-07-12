// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/// Encodes a BZip2 stream with configurable block sizes from 100 through 900 KiB.
///
/// The encoder performs the two BZip2 run-length stages, a cyclic Burrows-Wheeler transform, move-to-front coding,
/// and canonical length-limited Huffman coding without delegating any format stage to an external library.
@NotNullByDefault
public final class BZip2OutputStream extends OutputStream {
    /// The BZip2 block marker.
    private static final long BLOCK_MAGIC = 0x314159265359L;

    /// The BZip2 end-of-stream marker.
    private static final long END_MAGIC = 0x177245385090L;

    /// The number of bytes represented by one block-size unit.
    private static final int BLOCK_SIZE_UNIT = 100_000;

    /// The default BZip2 block size.
    public static final int DEFAULT_BLOCK_SIZE = 9;

    /// The number of symbols controlled by one Huffman selector.
    private static final int GROUP_SIZE = 50;

    /// The two identical Huffman groups emitted by this encoder.
    private static final int GROUP_COUNT = 2;

    /// The maximum Huffman code length accepted by the BZip2 format.
    private static final int MAX_CODE_LENGTH = 20;

    /// The RUNA symbol used by the second run-length stage.
    private static final int RUNA = 0;

    /// The RUNB symbol used by the second run-length stage.
    private static final int RUNB = 1;

    /// The wrapped compressed target.
    private final OutputStream output;

    /// The most-significant-bit-first writer over the compressed target.
    private final BitOutput bits;

    /// The first-stage RLE bytes collected for the current block.
    private final byte[] block;

    /// The number of first-stage RLE bytes in the current block.
    private int blockLength;

    /// The CRC state for original bytes represented by the current block.
    private int blockCrc = BZip2CRC.initial();

    /// The stream-level combined block CRC.
    private int combinedCrc;

    /// The original byte currently being accumulated into a first-stage run.
    private int pendingRunByte = -1;

    /// The number of original bytes in the pending first-stage run.
    private int pendingRunLength;

    /// Whether the complete BZip2 stream trailer has been written.
    private boolean finished;

    /// Whether this output stream has closed.
    private boolean closed;

    /// Creates an encoder with the default 900 KiB block size.
    public BZip2OutputStream(OutputStream output) throws IOException {
        this(output, DEFAULT_BLOCK_SIZE);
    }

    /// Creates an encoder with a block size from 1 through 9 units of 100 KiB.
    public BZip2OutputStream(OutputStream output, int blockSize) throws IOException {
        this.output = Objects.requireNonNull(output, "output");
        if (blockSize < 1 || blockSize > 9) {
            throw new IllegalArgumentException("BZip2 block size must be between 1 and 9");
        }
        block = new byte[blockSize * BLOCK_SIZE_UNIT];
        bits = new BitOutput(output);
        bits.writeBits(8, 'B');
        bits.writeBits(8, 'Z');
        bits.writeBits(8, 'h');
        bits.writeBits(8, '0' + blockSize);
    }

    /// Adds one original byte to the compressed stream.
    @Override
    public void write(int value) throws IOException {
        ensureWritable();
        addByte(value & 0xff);
    }

    /// Adds original bytes to the compressed stream.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureWritable();
        for (int index = 0; index < length; index++) {
            addByte(Byte.toUnsignedInt(bytes[offset + index]));
        }
    }

    /// Flushes complete compressed bytes already emitted to the wrapped target.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        output.flush();
    }

    /// Writes all pending data and the BZip2 stream trailer without closing the wrapped target.
    public void finish() throws IOException {
        ensureOpen();
        if (finished) {
            return;
        }
        flushPendingRun();
        writeBlock();
        bits.writeBits(48, END_MAGIC);
        bits.writeBits(32, Integer.toUnsignedLong(combinedCrc));
        bits.finish();
        finished = true;
    }

    /// Finishes this BZip2 stream and closes the wrapped target.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            finish();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        closed = true;
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        }
        throwFailure(failure);
    }

    /// Adds one byte to the pending first-stage run.
    private void addByte(int value) throws IOException {
        if (pendingRunLength == 0) {
            pendingRunByte = value;
            pendingRunLength = 1;
            return;
        }
        if (value == pendingRunByte) {
            pendingRunLength++;
            if (pendingRunLength == 259) {
                flushPendingRun();
            }
            return;
        }
        flushPendingRun();
        pendingRunByte = value;
        pendingRunLength = 1;
    }

    /// Encodes the pending original-byte run into the current first-stage RLE block.
    private void flushPendingRun() throws IOException {
        if (pendingRunLength == 0) {
            return;
        }
        int encodedLength = pendingRunLength < 4 ? pendingRunLength : 5;
        if (blockLength + encodedLength > block.length) {
            writeBlock();
        }
        int runByte = pendingRunByte;
        if (pendingRunLength < 4) {
            Arrays.fill(block, blockLength, blockLength + pendingRunLength, (byte) runByte);
            blockLength += pendingRunLength;
        } else {
            Arrays.fill(block, blockLength, blockLength + 4, (byte) runByte);
            blockLength += 4;
            block[blockLength++] = (byte) (pendingRunLength - 4);
        }
        for (int index = 0; index < pendingRunLength; index++) {
            blockCrc = BZip2CRC.update(blockCrc, runByte);
        }
        pendingRunByte = -1;
        pendingRunLength = 0;
    }

    /// Encodes and writes the current non-empty first-stage RLE block.
    private void writeBlock() throws IOException {
        if (blockLength == 0) {
            return;
        }
        BurrowsWheelerBlock transformed = burrowsWheelerTransform(block, blockLength);
        SymbolSequence sequence = createSymbolSequence(transformed.lastColumn());
        int[] codeLengths = createCodeLengths(sequence.frequencies());
        int[] codes = createCanonicalCodes(codeLengths);
        int blockCrcValue = BZip2CRC.finish(blockCrc);
        combinedCrc = BZip2CRC.combine(combinedCrc, blockCrcValue);

        bits.writeBits(48, BLOCK_MAGIC);
        bits.writeBits(32, Integer.toUnsignedLong(blockCrcValue));
        bits.writeBits(1, 0);
        bits.writeBits(24, transformed.originalPointer());
        writeUsedByteMap(sequence.usedBytes());

        int selectorCount = (sequence.length() + GROUP_SIZE - 1) / GROUP_SIZE;
        bits.writeBits(3, GROUP_COUNT);
        bits.writeBits(15, selectorCount);
        for (int selector = 0; selector < selectorCount; selector++) {
            bits.writeBits(1, 0);
        }
        writeCodeLengths(codeLengths);
        writeCodeLengths(codeLengths);
        for (int index = 0; index < sequence.length(); index++) {
            int symbol = sequence.symbols()[index];
            bits.writeBits(codeLengths[symbol], codes[symbol]);
        }

        blockLength = 0;
        blockCrc = BZip2CRC.initial();
    }

    /// Writes the two-level byte-presence bitmap for a block.
    private void writeUsedByteMap(boolean[] usedBytes) throws IOException {
        boolean[] usedGroups = new boolean[16];
        for (int value = 0; value < usedBytes.length; value++) {
            if (usedBytes[value]) {
                usedGroups[value >>> 4] = true;
            }
        }
        for (boolean usedGroup : usedGroups) {
            bits.writeBits(1, usedGroup ? 1 : 0);
        }
        for (int group = 0; group < usedGroups.length; group++) {
            if (!usedGroups[group]) {
                continue;
            }
            for (int offset = 0; offset < 16; offset++) {
                bits.writeBits(1, usedBytes[(group << 4) + offset] ? 1 : 0);
            }
        }
    }

    /// Writes one delta-coded BZip2 Huffman length table.
    private void writeCodeLengths(int[] lengths) throws IOException {
        int currentLength = lengths[0];
        bits.writeBits(5, currentLength);
        for (int length : lengths) {
            while (currentLength < length) {
                bits.writeBits(2, 0b10);
                currentLength++;
            }
            while (currentLength > length) {
                bits.writeBits(2, 0b11);
                currentLength--;
            }
            bits.writeBits(1, 0);
        }
    }

    /// Performs a cyclic Burrows-Wheeler transform over one first-stage RLE block.
    private static BurrowsWheelerBlock burrowsWheelerTransform(byte[] input, int length) {
        int[] order = sortCyclicShifts(input, length);
        byte[] lastColumn = new byte[length];
        int originalPointer = -1;
        for (int row = 0; row < length; row++) {
            int start = order[row];
            if (start == 0) {
                originalPointer = row;
            }
            lastColumn[row] = input[start == 0 ? length - 1 : start - 1];
        }
        if (originalPointer < 0) {
            throw new AssertionError("Cyclic suffix order does not contain the original block");
        }
        return new BurrowsWheelerBlock(lastColumn, originalPointer);
    }

    /// Sorts all cyclic shifts through prefix doubling and counting sort.
    private static int[] sortCyclicShifts(byte[] input, int length) {
        int[] order = new int[length];
        int[] shiftedOrder = new int[length];
        int[] classes = new int[length];
        int[] newClasses = new int[length];
        int[] counts = new int[Math.max(256, length)];
        for (int index = 0; index < length; index++) {
            counts[Byte.toUnsignedInt(input[index])]++;
        }
        for (int value = 1; value < 256; value++) {
            counts[value] += counts[value - 1];
        }
        for (int index = length - 1; index >= 0; index--) {
            int value = Byte.toUnsignedInt(input[index]);
            order[--counts[value]] = index;
        }

        int classCount = 1;
        classes[order[0]] = 0;
        for (int index = 1; index < length; index++) {
            if (input[order[index]] != input[order[index - 1]]) {
                classCount++;
            }
            classes[order[index]] = classCount - 1;
        }

        for (int prefixLength = 1; prefixLength < length; prefixLength <<= 1) {
            for (int index = 0; index < length; index++) {
                int shifted = order[index] - prefixLength;
                shiftedOrder[index] = shifted < 0 ? shifted + length : shifted;
            }
            Arrays.fill(counts, 0, classCount, 0);
            for (int index = 0; index < length; index++) {
                counts[classes[shiftedOrder[index]]]++;
            }
            for (int index = 1; index < classCount; index++) {
                counts[index] += counts[index - 1];
            }
            for (int index = length - 1; index >= 0; index--) {
                int shifted = shiftedOrder[index];
                int shiftClass = classes[shifted];
                order[--counts[shiftClass]] = shifted;
            }

            int newClassCount = 1;
            newClasses[order[0]] = 0;
            for (int index = 1; index < length; index++) {
                int current = order[index];
                int previous = order[index - 1];
                int currentSecond = current + prefixLength;
                int previousSecond = previous + prefixLength;
                if (currentSecond >= length) {
                    currentSecond -= length;
                }
                if (previousSecond >= length) {
                    previousSecond -= length;
                }
                if (classes[current] != classes[previous]
                        || classes[currentSecond] != classes[previousSecond]) {
                    newClassCount++;
                }
                newClasses[current] = newClassCount - 1;
            }
            int[] oldClasses = classes;
            classes = newClasses;
            newClasses = oldClasses;
            classCount = newClassCount;
            if (classCount == length) {
                break;
            }
        }
        return order;
    }

    /// Applies byte compaction, move-to-front coding, and the second run-length stage.
    private static SymbolSequence createSymbolSequence(byte[] lastColumn) {
        boolean[] usedBytes = new boolean[256];
        for (byte value : lastColumn) {
            usedBytes[Byte.toUnsignedInt(value)] = true;
        }
        int[] byteToCompact = new int[256];
        int usedCount = 0;
        for (int value = 0; value < usedBytes.length; value++) {
            if (usedBytes[value]) {
                byteToCompact[value] = usedCount++;
            }
        }
        int[] moveToFront = new int[usedCount];
        for (int index = 0; index < moveToFront.length; index++) {
            moveToFront[index] = index;
        }

        int[] symbols = new int[lastColumn.length + 1];
        int[] frequencies = new int[usedCount + 2];
        int symbolCount = 0;
        int zeroRun = 0;
        for (byte byteValue : lastColumn) {
            int compactValue = byteToCompact[Byte.toUnsignedInt(byteValue)];
            int position = 0;
            while (moveToFront[position] != compactValue) {
                position++;
            }
            if (position == 0) {
                zeroRun++;
                continue;
            }
            symbolCount = appendZeroRun(zeroRun, symbols, symbolCount, frequencies);
            zeroRun = 0;
            int value = moveToFront[position];
            System.arraycopy(moveToFront, 0, moveToFront, 1, position);
            moveToFront[0] = value;
            int symbol = position + 1;
            symbols[symbolCount++] = symbol;
            frequencies[symbol]++;
        }
        symbolCount = appendZeroRun(zeroRun, symbols, symbolCount, frequencies);
        int endSymbol = usedCount + 1;
        symbols[symbolCount++] = endSymbol;
        frequencies[endSymbol]++;
        return new SymbolSequence(symbols, symbolCount, frequencies, usedBytes);
    }

    /// Appends the bijective base-two representation of an MTF-zero run.
    private static int appendZeroRun(int zeroRun, int[] symbols, int symbolCount, int[] frequencies) {
        int remaining = zeroRun;
        while (remaining > 0) {
            remaining--;
            int symbol = (remaining & 1) == 0 ? RUNA : RUNB;
            symbols[symbolCount++] = symbol;
            frequencies[symbol]++;
            remaining >>>= 1;
        }
        return symbolCount;
    }

    /// Creates length-limited Huffman code lengths for one symbol alphabet.
    private static int[] createCodeLengths(int[] frequencies) {
        int symbolCount = frequencies.length;
        long[] adjustedWeights = new long[symbolCount];
        for (int symbol = 0; symbol < symbolCount; symbol++) {
            adjustedWeights[symbol] = Math.max(1, frequencies[symbol]);
        }

        while (true) {
            long[] treeWeights = new long[symbolCount * 2 - 1];
            int[] parents = new int[treeWeights.length];
            Arrays.fill(parents, -1);
            System.arraycopy(adjustedWeights, 0, treeWeights, 0, symbolCount);
            PriorityQueue<Integer> queue = new PriorityQueue<>(
                    Comparator.comparingLong((Integer node) -> treeWeights[node]).thenComparingInt(Integer::intValue)
            );
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                queue.add(symbol);
            }
            int nodeCount = symbolCount;
            while (queue.size() > 1) {
                int first = queue.remove();
                int second = queue.remove();
                int parent = nodeCount++;
                treeWeights[parent] = treeWeights[first] + treeWeights[second];
                parents[first] = parent;
                parents[second] = parent;
                queue.add(parent);
            }

            int[] lengths = new int[symbolCount];
            int maximumLength = 0;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                int length = 0;
                for (int node = symbol; parents[node] >= 0; node = parents[node]) {
                    length++;
                }
                lengths[symbol] = length;
                maximumLength = Math.max(maximumLength, length);
            }
            if (maximumLength <= MAX_CODE_LENGTH) {
                return lengths;
            }
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                adjustedWeights[symbol] = (adjustedWeights[symbol] >>> 1) + 1;
            }
        }
    }

    /// Assigns canonical most-significant-bit-first codes to a length table.
    private static int[] createCanonicalCodes(int[] lengths) {
        int minimumLength = Integer.MAX_VALUE;
        int maximumLength = 0;
        for (int length : lengths) {
            minimumLength = Math.min(minimumLength, length);
            maximumLength = Math.max(maximumLength, length);
        }
        int[] codes = new int[lengths.length];
        int code = 0;
        for (int length = minimumLength; length <= maximumLength; length++) {
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                if (lengths[symbol] == length) {
                    codes[symbol] = code++;
                }
            }
            code <<= 1;
        }
        return codes;
    }

    /// Requires this stream to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("BZip2 stream has already finished");
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Rethrows a close-time failure with its original checked or unchecked type.
    private static void throwFailure(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Stores one Burrows-Wheeler last column and the row containing the original block.
    ///
    /// @param lastColumn      the transformed byte column
    /// @param originalPointer the row containing the unrotated block
    private record BurrowsWheelerBlock(byte @Unmodifiable [] lastColumn, int originalPointer) {
    }

    /// Stores one Huffman symbol sequence and its coding metadata.
    ///
    /// @param symbols     the backing symbol array
    /// @param length      the number of populated symbols
    /// @param frequencies the symbol frequencies
    /// @param usedBytes   the original byte-presence map
    private record SymbolSequence(
            int @Unmodifiable [] symbols,
            int length,
            int @Unmodifiable [] frequencies,
            boolean @Unmodifiable [] usedBytes
    ) {
    }

    /// Writes BZip2 fields in most-significant-bit-first order.
    @NotNullByDefault
    private static final class BitOutput {
        /// The compressed target.
        private final OutputStream output;

        /// The partially populated output byte.
        private int currentByte;

        /// The number of high-order bits populated in `currentByte`.
        private int bitCount;

        /// Creates a bit writer over the given target.
        private BitOutput(OutputStream output) {
            this.output = output;
        }

        /// Writes up to 63 low-order bits from a value.
        private void writeBits(int count, long value) throws IOException {
            if (count < 0 || count > 63) {
                throw new IllegalArgumentException("Bit count must be between 0 and 63");
            }
            for (int bitIndex = count - 1; bitIndex >= 0; bitIndex--) {
                currentByte = (currentByte << 1) | (int) ((value >>> bitIndex) & 1L);
                bitCount++;
                if (bitCount == 8) {
                    output.write(currentByte);
                    currentByte = 0;
                    bitCount = 0;
                }
            }
        }

        /// Pads and writes the final partial byte.
        private void finish() throws IOException {
            if (bitCount != 0) {
                output.write(currentByte << (8 - bitCount));
                currentByte = 0;
                bitCount = 0;
            }
        }
    }
}
