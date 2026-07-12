// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Objects;

/// Decodes a complete BZip2 stream, including legacy randomized blocks.
///
/// Each compressed block is expanded through Huffman decoding, run-length decoding, move-to-front decoding, and the
/// inverse Burrows-Wheeler transform. The final run-length stage is produced lazily so a highly compressible block does
/// not require an output-sized allocation.
@NotNullByDefault
public final class BZip2InputStream extends InputStream {
    /// The BZip2 block marker.
    private static final long BLOCK_MAGIC = 0x314159265359L;

    /// The BZip2 end-of-stream marker.
    private static final long END_MAGIC = 0x177245385090L;

    /// The number of bytes represented by one block-size unit.
    private static final int BLOCK_SIZE_UNIT = 100_000;

    /// The number of symbols controlled by one Huffman selector.
    private static final int GROUP_SIZE = 50;

    /// The smallest permitted Huffman group count.
    private static final int MIN_GROUP_COUNT = 2;

    /// The largest permitted Huffman group count.
    private static final int MAX_GROUP_COUNT = 6;

    /// The largest permitted Huffman code length.
    private static final int MAX_CODE_LENGTH = 20;

    /// The RUNA symbol used by the second run-length stage.
    private static final int RUNA = 0;

    /// The RUNB symbol used by the second run-length stage.
    private static final int RUNB = 1;

    /// The legacy BZip2 randomization sequence.
    private static final int @Unmodifiable [] RANDOM_NUMBERS = {
            619, 720, 127, 481, 931, 816, 813, 233, 566, 247,
            985, 724, 205, 454, 863, 491, 741, 242, 949, 214,
            733, 859, 335, 708, 621, 574, 73, 654, 730, 472,
            419, 436, 278, 496, 867, 210, 399, 680, 480, 51,
            878, 465, 811, 169, 869, 675, 611, 697, 867, 561,
            862, 687, 507, 283, 482, 129, 807, 591, 733, 623,
            150, 238, 59, 379, 684, 877, 625, 169, 643, 105,
            170, 607, 520, 932, 727, 476, 693, 425, 174, 647,
            73, 122, 335, 530, 442, 853, 695, 249, 445, 515,
            909, 545, 703, 919, 874, 474, 882, 500, 594, 612,
            641, 801, 220, 162, 819, 984, 589, 513, 495, 799,
            161, 604, 958, 533, 221, 400, 386, 867, 600, 782,
            382, 596, 414, 171, 516, 375, 682, 485, 911, 276,
            98, 553, 163, 354, 666, 933, 424, 341, 533, 870,
            227, 730, 475, 186, 263, 647, 537, 686, 600, 224,
            469, 68, 770, 919, 190, 373, 294, 822, 808, 206,
            184, 943, 795, 384, 383, 461, 404, 758, 839, 887,
            715, 67, 618, 276, 204, 918, 873, 777, 604, 560,
            951, 160, 578, 722, 79, 804, 96, 409, 713, 940,
            652, 934, 970, 447, 318, 353, 859, 672, 112, 785,
            645, 863, 803, 350, 139, 93, 354, 99, 820, 908,
            609, 772, 154, 274, 580, 184, 79, 626, 630, 742,
            653, 282, 762, 623, 680, 81, 927, 626, 789, 125,
            411, 521, 938, 300, 821, 78, 343, 175, 128, 250,
            170, 774, 972, 275, 999, 639, 495, 78, 352, 126,
            857, 956, 358, 619, 580, 124, 737, 594, 701, 612,
            669, 112, 134, 694, 363, 992, 809, 743, 168, 974,
            944, 375, 748, 52, 600, 747, 642, 182, 862, 81,
            344, 805, 988, 739, 511, 655, 814, 334, 249, 515,
            897, 955, 664, 981, 649, 113, 974, 459, 893, 228,
            433, 837, 553, 268, 926, 240, 102, 654, 459, 51,
            686, 754, 806, 760, 493, 403, 415, 394, 687, 700,
            946, 670, 656, 610, 738, 392, 760, 799, 887, 653,
            978, 321, 576, 617, 626, 502, 894, 679, 243, 440,
            680, 879, 194, 572, 640, 724, 926, 56, 204, 700,
            707, 151, 457, 449, 797, 195, 791, 558, 945, 679,
            297, 59, 87, 824, 713, 663, 412, 693, 342, 606,
            134, 108, 571, 364, 631, 212, 174, 643, 304, 329,
            343, 97, 430, 751, 497, 314, 983, 374, 822, 928,
            140, 206, 73, 263, 980, 736, 876, 478, 430, 305,
            170, 514, 364, 692, 829, 82, 855, 953, 676, 246,
            369, 970, 294, 750, 807, 827, 150, 790, 288, 923,
            804, 378, 215, 828, 592, 281, 565, 555, 710, 82,
            896, 831, 547, 261, 524, 462, 293, 465, 502, 56,
            661, 821, 976, 991, 658, 869, 905, 758, 745, 193,
            768, 550, 608, 933, 378, 286, 215, 979, 792, 961,
            61, 688, 793, 644, 986, 403, 106, 366, 905, 644,
            372, 567, 466, 434, 645, 210, 389, 550, 919, 135,
            780, 773, 635, 389, 707, 100, 626, 958, 165, 504,
            920, 176, 193, 713, 857, 265, 203, 50, 668, 108,
            645, 990, 626, 197, 510, 357, 358, 850, 858, 364,
            936, 638
    };

    /// The compressed source.
    private final InputStream input;

    /// The most-significant-bit-first reader over the compressed source.
    private final BitInput bits;

    /// The maximum post-RLE block size declared by the stream header.
    private final int blockSizeLimit;

    /// The inverse-BWT block awaiting final run-length expansion.
    private byte[] blockData = new byte[0];

    /// The next inverse-BWT byte to consume.
    private int blockPosition;

    /// The expected CRC of the current block.
    private int expectedBlockCrc;

    /// The accumulating CRC state of the current block's decoded bytes.
    private int blockCrc = BZip2CRC.initial();

    /// The stream-level combined block CRC.
    private int combinedCrc;

    /// The previous byte observed by the final run-length decoder.
    private int runByte = -1;

    /// The number of immediately preceding encoded copies of `runByte`.
    private int runLength;

    /// The number of expanded copies of `runByte` still to emit.
    private int repeatRemaining;

    /// Whether the current block uses the legacy randomization transform.
    private boolean randomized;

    /// The next randomization-table position.
    private int randomPosition;

    /// The number of bytes until the next randomization toggle.
    private int randomRemaining;

    /// Whether an inverse-BWT block is currently active.
    private boolean blockActive;

    /// Whether the end marker has been consumed.
    private boolean endReached;

    /// Whether this input stream has closed.
    private boolean closed;

    /// Creates a decoder and validates the BZip2 stream header.
    public BZip2InputStream(InputStream input) throws IOException {
        this.input = Objects.requireNonNull(input, "input");
        this.bits = new BitInput(input);
        if (bits.readBits(8) != 'B' || bits.readBits(8) != 'Z' || bits.readBits(8) != 'h') {
            throw new IOException("Invalid BZip2 stream header");
        }
        int blockSize = bits.readBits(8) - '0';
        if (blockSize < 1 || blockSize > 9) {
            throw new IOException("Invalid BZip2 block size: " + blockSize);
        }
        blockSizeLimit = blockSize * BLOCK_SIZE_UNIT;
    }

    /// Reads one decoded byte.
    @Override
    public int read() throws IOException {
        ensureOpen();
        return readDecodedByte();
    }

    /// Reads decoded bytes into the destination array.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }

        int count = 0;
        while (count < length) {
            int value = readDecodedByte();
            if (value < 0) {
                return count == 0 ? -1 : count;
            }
            bytes[offset + count] = (byte) value;
            count++;
        }
        return count;
    }

    /// Skips decoded bytes while preserving block CRC validation.
    @Override
    public long skip(long count) throws IOException {
        ensureOpen();
        if (count <= 0L) {
            return 0L;
        }
        byte[] discard = new byte[(int) Math.min(8192L, count)];
        long skipped = 0L;
        while (skipped < count) {
            int read = read(discard, 0, (int) Math.min(discard.length, count - skipped));
            if (read < 0) {
                break;
            }
            skipped += read;
        }
        return skipped;
    }

    /// Returns the number of decoded repeat bytes immediately available without parsing another block field.
    @Override
    public int available() throws IOException {
        ensureOpen();
        return repeatRemaining;
    }

    /// Returns the exact number of compressed source bytes consumed so far.
    public long compressedByteCount() {
        return bits.byteCount();
    }

    /// Closes the compressed source.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        input.close();
    }

    /// Reads one fully decoded byte or returns end-of-stream.
    private int readDecodedByte() throws IOException {
        while (true) {
            if (repeatRemaining > 0) {
                repeatRemaining--;
                return recordDecodedByte(runByte);
            }
            if (!blockActive) {
                if (endReached) {
                    return -1;
                }
                openNextBlock();
                continue;
            }
            if (blockPosition >= blockData.length) {
                finishBlock();
                continue;
            }

            int value = readBlockByte();
            if (value == runByte) {
                runLength++;
            } else {
                runByte = value;
                runLength = 1;
            }
            if (runLength == 4) {
                if (blockPosition >= blockData.length) {
                    throw new IOException("Truncated BZip2 run-length sequence");
                }
                repeatRemaining = readBlockByte();
                runLength = 0;
            }
            return recordDecodedByte(value);
        }
    }

    /// Records one decoded byte in the current block CRC and returns it.
    private int recordDecodedByte(int value) {
        blockCrc = BZip2CRC.update(blockCrc, value);
        return value;
    }

    /// Reads and decodes the next compressed block or stream terminator.
    private void openNextBlock() throws IOException {
        long marker = bits.readMarker();
        if (marker == END_MAGIC) {
            int expectedCombinedCrc = bits.readBits(32);
            if (combinedCrc != expectedCombinedCrc) {
                throw new IOException("BZip2 combined CRC mismatch");
            }
            endReached = true;
            return;
        }
        if (marker != BLOCK_MAGIC) {
            throw new IOException("Invalid BZip2 block marker");
        }

        expectedBlockCrc = bits.readBits(32);
        randomized = bits.readBit();
        int originalPointer = bits.readBits(24);
        byte[] usedBytes = readUsedBytes();
        int alphabetSize = usedBytes.length + 2;
        int groupCount = bits.readBits(3);
        if (groupCount < MIN_GROUP_COUNT || groupCount > MAX_GROUP_COUNT) {
            throw new IOException("Invalid BZip2 Huffman group count: " + groupCount);
        }
        int selectorCount = bits.readBits(15);
        int maximumSelectorCount = 2 + blockSizeLimit / GROUP_SIZE;
        if (selectorCount < 1 || selectorCount > maximumSelectorCount) {
            throw new IOException("Invalid BZip2 selector count: " + selectorCount);
        }

        byte[] selectors = readSelectors(groupCount, selectorCount);
        HuffmanTree[] trees = readHuffmanTrees(groupCount, alphabetSize);
        byte[] lastColumn = decodeLastColumn(usedBytes, selectors, trees);
        if (lastColumn.length == 0 || originalPointer < 0 || originalPointer >= lastColumn.length) {
            throw new IOException("Invalid BZip2 original pointer: " + originalPointer);
        }

        blockData = inverseBurrowsWheeler(lastColumn, originalPointer);
        blockPosition = 0;
        blockCrc = BZip2CRC.initial();
        runByte = -1;
        runLength = 0;
        repeatRemaining = 0;
        randomPosition = 0;
        randomRemaining = 0;
        blockActive = true;
    }

    /// Reads the byte alphabet used by the current block.
    private byte[] readUsedBytes() throws IOException {
        boolean[] usedGroups = new boolean[16];
        int groupCount = 0;
        for (int group = 0; group < usedGroups.length; group++) {
            usedGroups[group] = bits.readBit();
            if (usedGroups[group]) {
                groupCount++;
            }
        }

        byte[] values = new byte[groupCount * 16];
        int count = 0;
        for (int group = 0; group < usedGroups.length; group++) {
            if (!usedGroups[group]) {
                continue;
            }
            for (int offset = 0; offset < 16; offset++) {
                if (bits.readBit()) {
                    values[count++] = (byte) (group * 16 + offset);
                }
            }
        }
        if (count == 0) {
            throw new IOException("BZip2 block has an empty byte alphabet");
        }
        return Arrays.copyOf(values, count);
    }

    /// Reads and expands the move-to-front encoded Huffman selectors.
    private byte[] readSelectors(int groupCount, int selectorCount) throws IOException {
        byte[] selectors = new byte[selectorCount];
        byte[] moveToFront = new byte[groupCount];
        for (int index = 0; index < groupCount; index++) {
            moveToFront[index] = (byte) index;
        }
        for (int index = 0; index < selectorCount; index++) {
            int position = 0;
            while (bits.readBit()) {
                position++;
                if (position >= groupCount) {
                    throw new IOException("Invalid BZip2 selector MTF value");
                }
            }
            byte selector = moveToFront[position];
            System.arraycopy(moveToFront, 0, moveToFront, 1, position);
            moveToFront[0] = selector;
            selectors[index] = selector;
        }
        return selectors;
    }

    /// Reads all canonical Huffman trees declared by the block.
    private HuffmanTree[] readHuffmanTrees(int groupCount, int alphabetSize) throws IOException {
        HuffmanTree[] trees = new HuffmanTree[groupCount];
        for (int group = 0; group < groupCount; group++) {
            int currentLength = bits.readBits(5);
            if (currentLength < 1 || currentLength > MAX_CODE_LENGTH) {
                throw new IOException("Invalid BZip2 Huffman code length: " + currentLength);
            }
            int[] lengths = new int[alphabetSize];
            for (int symbol = 0; symbol < alphabetSize; symbol++) {
                while (bits.readBit()) {
                    currentLength += bits.readBit() ? -1 : 1;
                    if (currentLength < 1 || currentLength > MAX_CODE_LENGTH) {
                        throw new IOException("Invalid BZip2 Huffman code length: " + currentLength);
                    }
                }
                lengths[symbol] = currentLength;
            }
            trees[group] = new HuffmanTree(lengths);
        }
        return trees;
    }

    /// Decodes the post-BWT byte column through Huffman, RLE2, and move-to-front stages.
    private byte[] decodeLastColumn(byte[] usedBytes, byte[] selectors, HuffmanTree[] trees) throws IOException {
        int endSymbol = usedBytes.length + 1;
        byte[] moveToFront = usedBytes.clone();
        byte[] output = new byte[blockSizeLimit];
        int outputLength = 0;
        SymbolReader reader = new SymbolReader(bits, selectors, trees);
        int symbol = reader.nextSymbol();
        while (symbol != endSymbol) {
            if (symbol == RUNA || symbol == RUNB) {
                long runLengthValue = 0L;
                long power = 1L;
                do {
                    runLengthValue += symbol == RUNA ? power : power << 1;
                    if (runLengthValue > blockSizeLimit - outputLength) {
                        throw new IOException("BZip2 block exceeds its declared size");
                    }
                    symbol = reader.nextSymbol();
                    if (symbol == RUNA || symbol == RUNB) {
                        if (power > blockSizeLimit / 2L) {
                            throw new IOException("Invalid BZip2 run-length sequence");
                        }
                        power <<= 1;
                    }
                } while (symbol == RUNA || symbol == RUNB);
                Arrays.fill(output, outputLength, outputLength + (int) runLengthValue, moveToFront[0]);
                outputLength += (int) runLengthValue;
                if (symbol == endSymbol) {
                    break;
                }
            }

            int moveToFrontIndex = symbol - 1;
            if (moveToFrontIndex <= 0 || moveToFrontIndex >= moveToFront.length) {
                throw new IOException("Invalid BZip2 MTF symbol: " + symbol);
            }
            if (outputLength >= output.length) {
                throw new IOException("BZip2 block exceeds its declared size");
            }
            byte value = moveToFront[moveToFrontIndex];
            System.arraycopy(moveToFront, 0, moveToFront, 1, moveToFrontIndex);
            moveToFront[0] = value;
            output[outputLength++] = value;
            symbol = reader.nextSymbol();
        }
        return Arrays.copyOf(output, outputLength);
    }

    /// Reverses the Burrows-Wheeler transform for one block.
    private static byte[] inverseBurrowsWheeler(byte[] lastColumn, int originalPointer) {
        int[] counts = new int[256];
        for (byte value : lastColumn) {
            counts[Byte.toUnsignedInt(value)]++;
        }
        int total = 0;
        for (int value = 0; value < counts.length; value++) {
            int count = counts[value];
            counts[value] = total;
            total += count;
        }

        int[] next = new int[lastColumn.length];
        for (int index = 0; index < lastColumn.length; index++) {
            int value = Byte.toUnsignedInt(lastColumn[index]);
            next[counts[value]++] = index;
        }
        byte[] output = new byte[lastColumn.length];
        int position = next[originalPointer];
        for (int index = 0; index < output.length; index++) {
            output[index] = lastColumn[position];
            position = next[position];
        }
        return output;
    }

    /// Reads one inverse-BWT byte and applies legacy derandomization when requested.
    private int readBlockByte() {
        int value = Byte.toUnsignedInt(blockData[blockPosition++]);
        if (randomized) {
            if (randomRemaining == 0) {
                randomRemaining = RANDOM_NUMBERS[randomPosition];
                randomPosition = (randomPosition + 1) % RANDOM_NUMBERS.length;
            }
            randomRemaining--;
            if (randomRemaining == 1) {
                value ^= 1;
            }
        }
        return value;
    }

    /// Validates and retires the fully consumed current block.
    private void finishBlock() throws IOException {
        int actualBlockCrc = BZip2CRC.finish(blockCrc);
        if (actualBlockCrc != expectedBlockCrc) {
            throw new IOException("BZip2 block CRC mismatch");
        }
        combinedCrc = BZip2CRC.combine(combinedCrc, actualBlockCrc);
        blockData = new byte[0];
        blockPosition = 0;
        blockActive = false;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Reads BZip2 fields in most-significant-bit-first order without reading past the current byte.
    @NotNullByDefault
    private static final class BitInput {
        /// The compressed source.
        private final InputStream input;

        /// The unread low-order bits from source bytes already consumed.
        private long buffer;

        /// The number of unread bits held in `buffer`.
        private int bitCount;

        /// The number of source bytes consumed.
        private long byteCount;

        /// Creates a bit reader over the given source.
        private BitInput(InputStream input) {
            this.input = input;
        }

        /// Reads one bit as a boolean value.
        private boolean readBit() throws IOException {
            return readBits(1) != 0;
        }

        /// Reads up to 32 bits as an integer.
        private int readBits(int count) throws IOException {
            if (count < 0 || count > 32) {
                throw new IllegalArgumentException("Bit count must be between 0 and 32");
            }
            while (bitCount < count) {
                int value = input.read();
                if (value < 0) {
                    throw new EOFException("Truncated BZip2 stream");
                }
                buffer = (buffer << 8) | value;
                bitCount += 8;
                byteCount++;
            }
            int remaining = bitCount - count;
            long mask = count == 32 ? 0xffff_ffffL : (1L << count) - 1L;
            int value = (int) ((buffer >>> remaining) & mask);
            bitCount = remaining;
            buffer = remaining == 0 ? 0L : buffer & ((1L << remaining) - 1L);
            return value;
        }

        /// Reads one 48-bit block or end marker.
        private long readMarker() throws IOException {
            return (Integer.toUnsignedLong(readBits(16)) << 32)
                    | Integer.toUnsignedLong(readBits(32));
        }

        /// Returns the exact number of source bytes consumed.
        private long byteCount() {
            return byteCount;
        }
    }

    /// Decodes one canonical most-significant-bit-first Huffman alphabet.
    @NotNullByDefault
    private static final class HuffmanTree {
        /// The left child index for each tree node.
        private final int[] left;

        /// The right child index for each tree node.
        private final int[] right;

        /// The decoded symbol at each leaf, or `-1` for internal nodes.
        private final int[] symbols;

        /// Creates and validates a canonical tree from symbol lengths.
        private HuffmanTree(int[] lengths) throws IOException {
            int[] lengthCounts = new int[MAX_CODE_LENGTH + 1];
            for (int length : lengths) {
                if (length < 1 || length > MAX_CODE_LENGTH) {
                    throw new IOException("Invalid BZip2 Huffman code length: " + length);
                }
                lengthCounts[length]++;
            }
            int[] nextCodes = new int[MAX_CODE_LENGTH + 1];
            int code = 0;
            for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
                code = (code + lengthCounts[length - 1]) << 1;
                if ((long) code + lengthCounts[length] > 1L << length) {
                    throw new IOException("Oversubscribed BZip2 Huffman tree");
                }
                nextCodes[length] = code;
            }

            int capacity = lengths.length * MAX_CODE_LENGTH + 1;
            left = new int[capacity];
            right = new int[capacity];
            symbols = new int[capacity];
            Arrays.fill(left, -1);
            Arrays.fill(right, -1);
            Arrays.fill(symbols, -1);
            int nodeCount = 1;
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int length = lengths[symbol];
                int symbolCode = nextCodes[length]++;
                int node = 0;
                for (int bitIndex = length - 1; bitIndex >= 0; bitIndex--) {
                    if (symbols[node] >= 0) {
                        throw new IOException("Invalid BZip2 Huffman prefix tree");
                    }
                    boolean rightBranch = ((symbolCode >>> bitIndex) & 1) != 0;
                    int child = rightBranch ? right[node] : left[node];
                    if (child < 0) {
                        child = nodeCount++;
                        if (rightBranch) {
                            right[node] = child;
                        } else {
                            left[node] = child;
                        }
                    }
                    node = child;
                }
                if (symbols[node] >= 0 || left[node] >= 0 || right[node] >= 0) {
                    throw new IOException("Invalid BZip2 Huffman prefix tree");
                }
                symbols[node] = symbol;
            }
        }

        /// Reads one symbol from this tree.
        private int readSymbol(BitInput bits) throws IOException {
            int node = 0;
            for (int length = 1; length <= MAX_CODE_LENGTH; length++) {
                node = bits.readBit() ? right[node] : left[node];
                if (node < 0) {
                    throw new IOException("Invalid BZip2 Huffman code");
                }
                if (symbols[node] >= 0) {
                    return symbols[node];
                }
            }
            throw new IOException("Invalid BZip2 Huffman code");
        }
    }

    /// Applies the block's selector sequence while reading Huffman symbols.
    @NotNullByDefault
    private static final class SymbolReader {
        /// The compressed bit source.
        private final BitInput bits;

        /// The expanded Huffman selector sequence.
        private final byte[] selectors;

        /// The available Huffman trees.
        private final HuffmanTree[] trees;

        /// The next selector index.
        private int selectorIndex;

        /// The number of symbols remaining under the current selector.
        private int groupRemaining;

        /// The current Huffman tree.
        private HuffmanTree currentTree;

        /// Creates a selector-aware symbol reader.
        private SymbolReader(BitInput bits, byte[] selectors, HuffmanTree[] trees) {
            this.bits = bits;
            this.selectors = selectors;
            this.trees = trees;
            this.currentTree = trees[0];
        }

        /// Reads the next Huffman symbol.
        private int nextSymbol() throws IOException {
            if (groupRemaining == 0) {
                if (selectorIndex >= selectors.length) {
                    throw new IOException("BZip2 selector sequence ended before the block");
                }
                currentTree = trees[Byte.toUnsignedInt(selectors[selectorIndex++])];
                groupRemaining = GROUP_SIZE;
            }
            groupRemaining--;
            return currentTree.readSymbol(bits);
        }
    }
}
