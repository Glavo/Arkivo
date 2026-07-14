// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.util.Arrays;

/// Implements the finite-state entropy and Huffman primitives used by Zstandard blocks.
@NotNullByDefault
final class ZstdEntropy {
    /// The maximum Huffman table logarithm accepted by the format.
    private static final int MAX_HUFFMAN_LOG = 12;

    /// Creates no instances.
    private ZstdEntropy() {
    }

    /// Parses one FSE table description.
    static FseParseResult readFseTable(
            byte[] source,
            int offset,
            int limit,
            int maximumSymbol,
            int maximumTableLog
    ) throws IOException {
        ForwardBitReader bits = new ForwardBitReader(source, offset, limit);
        int tableLog = bits.readBits(4) + 5;
        if (tableLog > maximumTableLog) {
            throw new IOException("Zstandard FSE table log exceeds its limit");
        }

        int tableSize = 1 << tableLog;
        int remaining = tableSize + 1;
        int threshold = tableSize;
        int numberOfBits = tableLog + 1;
        int symbol = 0;
        boolean previousZero = false;
        int[] normalized = new int[maximumSymbol + 1];

        while (remaining > 1 && symbol <= maximumSymbol) {
            if (previousZero) {
                int zeroEnd = symbol;
                while (bits.peekBits(16) == 0xffff) {
                    zeroEnd += 24;
                    bits.skipBits(16);
                }
                while (bits.peekBits(2) == 3) {
                    zeroEnd += 3;
                    bits.skipBits(2);
                }
                zeroEnd += bits.readBits(2);
                if (zeroEnd > maximumSymbol + 1) {
                    throw new IOException("Zstandard FSE table contains too many symbols");
                }
                while (symbol < zeroEnd) {
                    normalized[symbol++] = 0;
                }
                if (symbol > maximumSymbol) {
                    break;
                }
            }

            int maximum = (threshold << 1) - 1 - remaining;
            int count = bits.peekBits(numberOfBits - 1);
            if (count < maximum) {
                bits.skipBits(numberOfBits - 1);
            } else {
                count = bits.readBits(numberOfBits);
                if (count >= threshold) {
                    count -= maximum;
                }
            }
            count--;
            normalized[symbol++] = count;
            remaining -= Math.abs(count);
            previousZero = count == 0;
            while (remaining < threshold) {
                numberOfBits--;
                threshold >>>= 1;
            }
        }
        if (remaining != 1 || symbol == 0) {
            throw new IOException("Invalid Zstandard FSE distribution");
        }

        int bytesRead = (bits.bitPosition() + 7) >>> 3;
        return new FseParseResult(FseTable.fromNormalized(normalized, symbol, tableLog), bytesRead);
    }

    /// Reads a Huffman table description and returns the table and encoded byte count.
    static HuffmanParseResult readHuffmanTable(byte[] source, int offset, int limit) throws IOException {
        if (offset >= limit) {
            throw new IOException("Missing Zstandard Huffman table");
        }
        int header = Byte.toUnsignedInt(source[offset]);
        int[] weights = new int[256];
        int weightCount;
        int bytesRead;
        if (header >= 128) {
            weightCount = header - 127;
            bytesRead = 1 + ((weightCount + 1) >>> 1);
            if (offset + bytesRead > limit) {
                throw new IOException("Truncated Zstandard Huffman weights");
            }
            for (int index = 0; index < weightCount; index++) {
                int packed = Byte.toUnsignedInt(source[offset + 1 + (index >>> 1)]);
                weights[index] = (index & 1) == 0 ? packed >>> 4 : packed & 15;
            }
        } else {
            if (header == 0 || offset + 1 + header > limit) {
                throw new IOException("Invalid Zstandard compressed Huffman weights");
            }
            weightCount = decodeCompressedWeights(
                    source,
                    offset + 1,
                    offset + 1 + header,
                    weights
            );
            bytesRead = 1 + header;
        }
        if (weightCount >= weights.length) {
            throw new IOException("Zstandard Huffman alphabet is too large");
        }

        long weightTotal = 0L;
        int largestWeight = 0;
        for (int index = 0; index < weightCount; index++) {
            int weight = weights[index];
            if (weight < 0 || weight > MAX_HUFFMAN_LOG) {
                throw new IOException("Invalid Zstandard Huffman weight");
            }
            if (weight != 0) {
                weightTotal += 1L << (weight - 1);
                largestWeight = Math.max(largestWeight, weight);
            }
        }
        if (weightTotal == 0L) {
            throw new IOException("Empty Zstandard Huffman table");
        }
        long total = Long.highestOneBit(weightTotal) << 1;
        long remainder = total - weightTotal;
        if (remainder == 0L || (remainder & (remainder - 1L)) != 0L) {
            throw new IOException("Invalid Zstandard Huffman weight sum");
        }
        int inferredWeight = Long.numberOfTrailingZeros(remainder) + 1;
        if (inferredWeight > MAX_HUFFMAN_LOG) {
            throw new IOException("Zstandard Huffman table log exceeds its limit");
        }
        weights[weightCount++] = inferredWeight;
        largestWeight = Math.max(largestWeight, inferredWeight);
        int tableLog = Long.numberOfTrailingZeros(total);
        int weightOneCount = 0;
        for (int index = 0; index < weightCount; index++) {
            if (weights[index] == 1) {
                weightOneCount++;
            }
        }
        if (largestWeight > tableLog || weightOneCount < 2 || (weightOneCount & 1) != 0) {
            throw new IOException("Invalid Zstandard Huffman table");
        }
        return new HuffmanParseResult(HuffmanTable.fromWeights(weights, weightCount, tableLog), bytesRead);
    }

    /// Decodes FSE-compressed Huffman weights.
    private static int decodeCompressedWeights(
            byte[] source,
            int offset,
            int limit,
            int[] weights
    ) throws IOException {
        FseParseResult parsed = readFseTable(source, offset, limit, 255, 6);
        int streamOffset = offset + parsed.bytesRead();
        if (streamOffset >= limit) {
            throw new IOException("Missing Zstandard Huffman weight bitstream");
        }
        ReverseBitReader bits = new ReverseBitReader(source, streamOffset, limit);
        FseTable table = parsed.table();
        int state1 = bits.readBits(table.tableLog());
        int state2 = bits.readBits(table.tableLog());
        int count = 0;
        while (true) {
            if (count >= weights.length - 2) {
                throw new IOException("Zstandard Huffman alphabet is too large");
            }
            weights[count++] = table.symbol(state1);
            int state1Bits = table.numberOfBits(state1);

            if (state1Bits > bits.remainingBits()) {
                bits.readBits(bits.remainingBits());
                weights[count++] = table.symbol(state2);
                break;
            }
            state1 = table.nextState(state1, bits);

            weights[count++] = table.symbol(state2);
            int state2Bits = table.numberOfBits(state2);

            if (state2Bits > bits.remainingBits()) {
                bits.readBits(bits.remainingBits());
                weights[count++] = table.symbol(state1);
                break;
            }
            state2 = table.nextState(state2, bits);
        }
        bits.requireFullyConsumed();
        return count;
    }
    /// Holds a parsed FSE table and its encoded size.
    record FseParseResult(FseTable table, int bytesRead) {
        /// Creates a parsed FSE result.
        FseParseResult {
        }
    }

    /// Holds a parsed Huffman table and its encoded size.
    record HuffmanParseResult(HuffmanTable table, int bytesRead) {
        /// Creates a parsed Huffman result.
        HuffmanParseResult {
        }
    }

    /// Reads little-endian fields from a forward table-description bitstream.
    @NotNullByDefault
    private static final class ForwardBitReader {
        /// Source bytes.
        private final byte @Unmodifiable [] source;

        /// First source byte.
        private final int offset;

        /// Exclusive source limit.
        private final int limit;

        /// Current bit position relative to the first byte.
        private int bitPosition;

        /// Creates a forward bit reader.
        private ForwardBitReader(byte[] source, int offset, int limit) {
            if (offset < 0 || limit < offset || limit > source.length) {
                throw new IndexOutOfBoundsException();
            }
            this.source = source;
            this.offset = offset;
            this.limit = limit;
        }

        /// Returns the current relative bit position.
        private int bitPosition() {
            return bitPosition;
        }

        /// Reads the requested number of bits.
        private int readBits(int count) throws IOException {
            int value = peekBits(count);
            bitPosition += count;
            return value;
        }

        /// Skips the requested number of bits.
        private void skipBits(int count) throws IOException {
            peekBits(count);
            bitPosition += count;
        }

        /// Peeks at the requested number of bits.
        private int peekBits(int count) throws IOException {
            if (count < 0 || count > 24 || bitPosition + count > (limit - offset) * 8) {
                throw new IOException("Truncated Zstandard FSE table");
            }
            int byteIndex = offset + (bitPosition >>> 3);
            int shift = bitPosition & 7;
            long value = 0L;
            int available = Math.min(4, limit - byteIndex);
            for (int index = 0; index < available; index++) {
                value |= (long) Byte.toUnsignedInt(source[byteIndex + index]) << (index * 8);
            }
            return (int) ((value >>> shift) & ((1L << count) - 1L));
        }
    }

    /// Reads a Zstandard bitstream from its terminating marker toward the beginning.
    @NotNullByDefault
    static final class ReverseBitReader {
        /// Source bytes.
        private final byte @Unmodifiable [] source;

        /// Absolute first useful bit position.
        private final int startBit;

        /// Absolute exclusive bit position for the next read.
        private int bitPosition;

        /// Creates a reverse reader and consumes its terminal marker.
        ReverseBitReader(byte[] source, int offset, int limit) throws IOException {
            if (offset < 0 || limit <= offset || limit > source.length) {
                throw new IOException("Empty Zstandard reverse bitstream");
            }
            int last = Byte.toUnsignedInt(source[limit - 1]);
            if (last == 0) {
                throw new IOException("Zstandard reverse bitstream has no end marker");
            }
            this.source = source;
            this.startBit = offset * 8;
            this.bitPosition = (limit - 1) * 8 + (31 - Integer.numberOfLeadingZeros(last));
        }

        /// Returns the number of useful unread bits.
        int remainingBits() {
            return bitPosition - startBit;
        }

        /// Reads a little-endian field while traversing the stream backward.
        int readBits(int count) throws IOException {
            if (count < 0 || count > 31 || count > remainingBits()) {
                throw new IOException("Truncated Zstandard reverse bitstream");
            }
            bitPosition -= count;
            if (count == 0) {
                return 0;
            }
            int byteIndex = bitPosition >>> 3;
            int shift = bitPosition & 7;
            long value = 0L;
            int end = Math.min(source.length, byteIndex + 5);
            for (int index = byteIndex; index < end; index++) {
                value |= (long) Byte.toUnsignedInt(source[index]) << ((index - byteIndex) * 8);
            }
            return (int) ((value >>> shift) & ((1L << count) - 1L));
        }

        /// Requires every useful bit to have been consumed.
        void requireFullyConsumed() throws IOException {
            if (remainingBits() != 0) {
                throw new IOException("Zstandard reverse bitstream contains unused bits");
            }
        }
    }

    /// An immutable finite-state entropy decoding table.
    @NotNullByDefault
    static final class FseTable {
        /// The decoding-table logarithm.
        private final int tableLog;

        /// Symbol for each state.
        private final int @Unmodifiable [] symbols;

        /// Number of transition bits for each state.
        private final int @Unmodifiable [] numbersOfBits;

        /// Baseline for each state transition.
        private final int @Unmodifiable [] baselines;

        /// Creates an FSE decoding table.
        private FseTable(int tableLog, int[] symbols, int[] numbersOfBits, int[] baselines) {
            this.tableLog = tableLog;
            this.symbols = symbols;
            this.numbersOfBits = numbersOfBits;
            this.baselines = baselines;
        }

        /// Builds a table from normalized probabilities.
        static FseTable fromNormalized(
                int[] normalized,
                int symbolCount,
                int tableLog
        ) throws IOException {
            int tableSize = 1 << tableLog;
            int[] symbols = new int[tableSize];
            int tableMask = tableSize - 1;
            int highThreshold = tableSize - 1;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (normalized[symbol] == -1) {
                    symbols[highThreshold--] = symbol;
                } else if (normalized[symbol] < -1) {
                    throw new IOException("Invalid Zstandard FSE probability");
                }
            }

            int position = 0;
            int step = (tableSize >>> 1) + (tableSize >>> 3) + 3;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                for (int count = normalized[symbol]; count > 0; count--) {
                    symbols[position] = symbol;
                    do {
                        position = (position + step) & tableMask;
                    } while (position > highThreshold);
                }
            }
            if (position != 0) {
                throw new IOException("Invalid Zstandard FSE probability total");
            }

            int[] symbolNext = new int[symbolCount];
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                symbolNext[symbol] = normalized[symbol] == -1 ? 1 : normalized[symbol];
            }
            int[] numbersOfBits = new int[tableSize];
            int[] baselines = new int[tableSize];
            for (int state = 0; state < tableSize; state++) {
                int symbol = symbols[state];
                int next = symbolNext[symbol]++;
                int numberOfBits = tableLog - (31 - Integer.numberOfLeadingZeros(next));
                numbersOfBits[state] = numberOfBits;
                baselines[state] = (next << numberOfBits) - tableSize;
            }
            return new FseTable(tableLog, symbols, numbersOfBits, baselines);
        }

        /// Creates a one-symbol table.
        static FseTable rle(int symbol) {
            return new FseTable(0, new int[]{symbol}, new int[]{0}, new int[]{0});
        }

        /// Returns the table logarithm.
        int tableLog() {
            return tableLog;
        }

        /// Returns the symbol represented by one state.
        int symbol(int state) throws IOException {
            if (state < 0 || state >= symbols.length) {
                throw new IOException("Invalid Zstandard FSE state");
            }
            return symbols[state];
        }

        /// Returns the transition-bit count for one state.
        int numberOfBits(int state) throws IOException {
            if (state < 0 || state >= symbols.length) {
                throw new IOException("Invalid Zstandard FSE state");
            }
            return numbersOfBits[state];
        }

        /// Returns the baseline for one state transition.
        int baseline(int state) throws IOException {
            if (state < 0 || state >= symbols.length) {
                throw new IOException("Invalid Zstandard FSE state");
            }
            return baselines[state];
        }

        /// Advances one state using the reverse bitstream.
        int nextState(int state, ReverseBitReader bits) throws IOException {
            if (state < 0 || state >= symbols.length) {
                throw new IOException("Invalid Zstandard FSE state");
            }
            return baselines[state] + bits.readBits(numbersOfBits[state]);
        }
    }

    /// An immutable canonical Huffman decoding trie.
    @NotNullByDefault
    static final class HuffmanTable {
        /// Child reached by a zero bit.
        private final int @Unmodifiable [] zeroChildren;

        /// Child reached by a one bit.
        private final int @Unmodifiable [] oneChildren;

        /// Symbol stored at each leaf, or -1 for branches.
        private final int @Unmodifiable [] symbols;

        /// Number of used trie nodes.
        private final int nodeCount;

        /// Creates a decoding trie.
        private HuffmanTable(int[] zeroChildren, int[] oneChildren, int[] symbols, int nodeCount) {
            this.zeroChildren = zeroChildren;
            this.oneChildren = oneChildren;
            this.symbols = symbols;
            this.nodeCount = nodeCount;
        }

        /// Builds a canonical trie from Zstandard weights.
        static HuffmanTable fromWeights(int[] weights, int symbolCount, int tableLog) throws IOException {
            int capacity = 1 + symbolCount * (tableLog + 1);
            int[] zero = new int[capacity];
            int[] one = new int[capacity];
            int[] leaves = new int[capacity];
            Arrays.fill(zero, -1);
            Arrays.fill(one, -1);
            Arrays.fill(leaves, -1);
            int nodes = 1;
            int nextCode = 0;
            for (int weight = 1; weight <= tableLog; weight++) {
                int codeLength = tableLog + 1 - weight;
                int count = 0;
                for (int symbol = 0; symbol < symbolCount; symbol++) {
                    if (weights[symbol] != weight) {
                        continue;
                    }
                    int code = nextCode + count;
                    if (code >= (1 << codeLength)) {
                        throw new IOException("Oversubscribed Zstandard Huffman table");
                    }
                    int node = 0;
                    for (int bit = codeLength - 1; bit >= 0; bit--) {
                        boolean set = ((code >>> bit) & 1) != 0;
                        int child = set ? one[node] : zero[node];
                        if (child < 0) {
                            child = nodes++;
                            if (set) {
                                one[node] = child;
                            } else {
                                zero[node] = child;
                            }
                        }
                        node = child;
                    }
                    if (leaves[node] >= 0 || zero[node] >= 0 || one[node] >= 0) {
                        throw new IOException("Conflicting Zstandard Huffman codes");
                    }
                    leaves[node] = symbol;
                    count++;
                }
                nextCode = (nextCode + count) >>> 1;
            }
            return new HuffmanTable(
                    Arrays.copyOf(zero, nodes),
                    Arrays.copyOf(one, nodes),
                    Arrays.copyOf(leaves, nodes),
                    nodes
            );
        }

        /// Decodes exactly the requested number of symbols from one stream.
        void decode(
                byte[] source,
                int offset,
                int limit,
                byte[] target,
                int targetOffset,
                int count
        ) throws IOException {
            ReverseBitReader bits = new ReverseBitReader(source, offset, limit);
            for (int index = 0; index < count; index++) {
                int node = 0;
                while (symbols[node] < 0) {
                    int bit = bits.readBits(1);
                    node = bit == 0 ? zeroChildren[node] : oneChildren[node];
                    if (node < 0 || node >= nodeCount) {
                        throw new IOException("Invalid Zstandard Huffman code");
                    }
                }
                target[targetOffset + index] = (byte) symbols[node];
            }
            bits.requireFullyConsumed();
        }
    }
}
