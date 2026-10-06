// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally decodes one BZip2 frame, including legacy randomized blocks.
///
/// Each compressed block is expanded through Huffman decoding, run-length decoding, move-to-front decoding, and the
/// inverse Burrows-Wheeler transform. The final run-length stage is produced lazily so a highly compressible block does
/// not require an output-sized allocation.
@NotNullByDefault
public final class BZip2Decoder implements CompressionDecoder.Framed {
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


    /// The most-significant-bit-first reader over caller-provided source buffers.
    private final BitInput bits = new BitInput();

    /// The maximum post-RLE block size declared by the current stream header.
    private int blockSizeLimit;

    /// The inverse-BWT block awaiting final run-length expansion.
    private byte[] blockData = new byte[0];

    /// The next inverse-BWT byte to consume.
    private int blockPosition;

    /// The expected CRC of the current block.
    private int expectedBlockCrc;

    /// The accumulating CRC state of the current block's decoded bytes.
    private final ChecksumAccumulator.Width32 blockCrc = BZip2CRC32Algorithm.INSTANCE.newAccumulator();

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

    /// Whether the current BZip2 frame has reached its validated boundary.
    private boolean frameBoundaryPending;

    /// Whether this decoder has closed.
    private boolean closed;

    /// The next atomic parsing step.
    private ParseState parseState = ParseState.HEADER;
    /// The next stream-header byte to validate.
    private int headerPosition;
    /// The declared inverse-BWT starting position.
    private int originalPointer;
    /// The sixteen groups that contain used byte values.
    private int usedGroups;
    /// The next used-byte group to parse.
    private int usedGroup;
    /// The current byte alphabet.
    private final byte[] usedBytes = new byte[256];
    /// The current alphabet size excluding RUNA and RUNB.
    private int usedByteCount;
    /// The number of Huffman groups.
    private int groupCount;
    /// The number of declared selectors, including surplus selectors.
    private int declaredSelectors;
    /// The retained selector sequence.
    private byte[] selectors = new byte[0];
    /// The number of selectors usable by this block.
    private int retainedSelectors;
    /// The selector currently being parsed.
    private int selectorPosition;
    /// The unary MTF index currently being parsed.
    private int selectorMtfPosition;
    /// The selector MTF list.
    private final byte[] selectorMtf = new byte[MAX_GROUP_COUNT];
    /// The current Huffman group being constructed.
    private int treeGroup;
    /// The code length being adjusted.
    private int currentLength;
    /// The next alphabet symbol whose length is being parsed.
    private int lengthPosition;
    /// Code lengths for the current tree.
    private int[] lengths = new int[0];
    /// Validated trees for the current block.
    private final @Nullable HuffmanTree[] trees = new HuffmanTree[MAX_GROUP_COUNT];
    /// The byte MTF list for the current block.
    private final byte[] moveToFront = new byte[256];
    /// The post-BWT byte column.
    private byte[] lastColumn = new byte[0];
    /// The number of valid bytes in the post-BWT column.
    private int columnLength;
    /// Inverse-BWT links reused between blocks.
    private int[] bwtNext = new int[0];
    /// Inverse-BWT byte frequencies and prefix offsets.
    private final int[] bwtCounts = new int[256];
    /// The number of valid bytes in the decoded block buffer.
    private int blockLength;
    /// The next selector to use for Huffman data.
    private int dataSelector;
    /// The symbols remaining under the selected tree.
    private int groupRemaining;
    /// The selected data tree.
    private @Nullable HuffmanTree currentTree;
    /// The node of a partly read Huffman symbol.
    private int symbolNode;
    /// The accumulated RUNA/RUNB length.
    private long runValue;
    /// The weight of the next RUNA/RUNB digit.
    private long runPower = 1L;

    /// Atomic parser steps; a step advances only after its required bits are available.
    @NotNullByDefault
    private enum ParseState {
        /// The four-byte stream header.
        HEADER,
        /// A block or end marker.
        MARKER,
        /// The combined stream CRC.
        STREAM_CRC,
        /// The block CRC.
        BLOCK_CRC,
        /// The randomized-block flag.
        RANDOMIZED,
        /// The inverse-BWT pointer.
        POINTER,
        /// The used-byte group mask.
        GROUP_MASK,
        /// A group's used-byte mask.
        USED_BYTES,
        /// The Huffman group count.
        GROUP_COUNT,
        /// The declared selector count.
        SELECTOR_COUNT,
        /// Unary selector MTF values.
        SELECTORS,
        /// The initial code length of one tree.
        LENGTH_START,
        /// A code-length continuation bit.
        LENGTHS,
        /// A code-length adjustment bit.
        LENGTH_DELTA,
        /// Huffman-coded block symbols.
        SYMBOLS
    }

    /// Creates an empty BZip2 decoder.
    public BZip2Decoder() {
    }

    /// Decodes compressed bytes while permitting more source input in a later operation.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Decodes after the caller has supplied the final compressed source bytes.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Decodes between caller-owned buffers through the shared block state.
    ///
    /// Source and target positions are advanced by the bytes consumed and produced. Neither buffer is retained after
    /// this method returns.
    ///
    /// @param source compressed bytes available for this call
    /// @param target destination for decoded bytes
    /// @param endOfInput whether `source` contains the final compressed bytes available to this session
    /// @return the condition that requires caller action, or `FINISHED` at a validated stream boundary
    /// @throws IOException if the compressed data is malformed or ends before the current stream completes
    private CodecOutcome decodeInternal(
            ByteBuffer source,
            ByteBuffer target,
            boolean endOfInput
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (frameBoundaryPending) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            while (blockActive && target.hasRemaining()) {
                int value = readBufferedBlockByte();
                if (value >= 0) {
                    target.put((byte) value);
                }
            }
            if (blockActive) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (frameBoundaryPending) {
                return CodecOutcome.FINISHED;
            }

            boolean parsed = parseBufferedUnit(source, endOfInput);
            if (!parsed) {
                return CodecOutcome.NEEDS_INPUT;
            }
        }
    }

    /// Abandons the current frame and restores the initial decoder state.
    @Override
    public void reset() {
        if (closed) {
            throw new IllegalStateException("BZip2 decoder is closed");
        }
        blockSizeLimit = 0;
        blockLength = 0;
        blockPosition = 0;
        expectedBlockCrc = 0;
        blockCrc.reset();
        combinedCrc = 0;
        runByte = -1;
        runLength = 0;
        repeatRemaining = 0;
        randomized = false;
        randomPosition = 0;
        randomRemaining = 0;
        blockActive = false;
        frameBoundaryPending = false;
        parseState = ParseState.HEADER;
        headerPosition = 0;
        currentTree = null;
        bits.reset();
    }

    /// Releases decoder-owned state without consuming additional input.
    @Override
    public void close() {
        closed = true;
        blockData = new byte[0];
        lastColumn = new byte[0];
        bwtNext = new int[0];
        selectors = new byte[0];
        lengths = new int[0];
        Arrays.fill(trees, null);
        currentTree = null;
        bits.reset();
    }

    /// Emits one byte from the active inverse-BWT block or retires a completed block.
    private int readBufferedBlockByte() throws IOException {
        if (repeatRemaining > 0) {
            repeatRemaining--;
            return recordDecodedByte(runByte);
        }
        if (blockPosition >= blockLength) {
            finishBlock();
            return -1;
        }

        int value = readBlockByte();
        if (value == runByte) {
            runLength++;
        } else {
            runByte = value;
            runLength = 1;
        }
        if (runLength == 4) {
            if (blockPosition >= blockLength) {
                throw new IOException("Truncated BZip2 run-length sequence");
            }
            repeatRemaining = readBlockByte();
            runLength = 0;
        }
        return recordDecodedByte(value);
    }

    /// Resumes parsing without revisiting bytes consumed by earlier calls.
    private boolean parseBufferedUnit(ByteBuffer source, boolean endOfInput) throws IOException {
        try {
            while (!blockActive && !frameBoundaryPending) {
                switch (parseState) {
                    case HEADER -> {
                        int value = bits.readBits(8, source);
                        if (headerPosition < 3 && value != "BZh".charAt(headerPosition)) {
                            throw new IOException("Invalid BZip2 stream header");
                        }
                        if (++headerPosition == 4) {
                            int size = value - '0';
                            if (size < 1 || size > 9) {
                                throw new IOException("Invalid BZip2 block size: " + size);
                            }
                            blockSizeLimit = size * BLOCK_SIZE_UNIT;
                            parseState = ParseState.MARKER;
                        }
                    }
                    case MARKER -> {
                        long marker = bits.readLong(48, source);
                        if (marker == END_MAGIC) {
                            parseState = ParseState.STREAM_CRC;
                        } else if (marker == BLOCK_MAGIC) {
                            parseState = ParseState.BLOCK_CRC;
                        } else {
                            throw new IOException("Invalid BZip2 block marker");
                        }
                    }
                    case STREAM_CRC -> {
                        if (bits.readBits(32, source) != combinedCrc) {
                            throw new IOException("BZip2 combined CRC mismatch");
                        }
                        bits.finishFrame();
                        frameBoundaryPending = true;
                    }
                    case BLOCK_CRC -> {
                        expectedBlockCrc = bits.readBits(32, source);
                        parseState = ParseState.RANDOMIZED;
                    }
                    case RANDOMIZED -> {
                        randomized = bits.readBits(1, source) != 0;
                        parseState = ParseState.POINTER;
                    }
                    case POINTER -> {
                        originalPointer = bits.readBits(24, source);
                        parseState = ParseState.GROUP_MASK;
                    }
                    case GROUP_MASK -> {
                        usedGroups = bits.readBits(16, source);
                        usedGroup = 0;
                        usedByteCount = 0;
                        parseState = ParseState.USED_BYTES;
                    }
                    case USED_BYTES -> {
                        while (usedGroup < 16) {
                            if ((usedGroups & (1 << (15 - usedGroup))) != 0) {
                                int mask = bits.readBits(16, source);
                                for (int offset = 0; offset < 16; offset++) {
                                    if ((mask & (1 << (15 - offset))) != 0) {
                                        usedBytes[usedByteCount++] = (byte) (usedGroup * 16 + offset);
                                    }
                                }
                            }
                            usedGroup++;
                        }
                        if (usedByteCount == 0) {
                            throw new IOException("BZip2 block has an empty byte alphabet");
                        }
                        parseState = ParseState.GROUP_COUNT;
                    }
                    case GROUP_COUNT -> {
                        groupCount = bits.readBits(3, source);
                        if (groupCount < MIN_GROUP_COUNT || groupCount > MAX_GROUP_COUNT) {
                            throw new IOException("Invalid BZip2 Huffman group count: " + groupCount);
                        }
                        parseState = ParseState.SELECTOR_COUNT;
                    }
                    case SELECTOR_COUNT -> {
                        declaredSelectors = bits.readBits(15, source);
                        if (declaredSelectors == 0) {
                            throw new IOException("Invalid BZip2 selector count: 0");
                        }
                        retainedSelectors = Math.min(declaredSelectors, 2 + blockSizeLimit / GROUP_SIZE);
                        if (selectors.length < retainedSelectors) {
                            selectors = new byte[retainedSelectors];
                        }
                        for (int i = 0; i < groupCount; i++) selectorMtf[i] = (byte) i;
                        selectorPosition = 0;
                        selectorMtfPosition = 0;
                        parseState = ParseState.SELECTORS;
                    }
                    case SELECTORS -> {
                        while (selectorPosition < declaredSelectors) {
                            if (bits.readBits(1, source) != 0) {
                                if (++selectorMtfPosition >= groupCount) {
                                    throw new IOException("Invalid BZip2 selector MTF value");
                                }
                            } else {
                                if (selectorPosition < retainedSelectors) {
                                    byte selector = selectorMtf[selectorMtfPosition];
                                    System.arraycopy(selectorMtf, 0, selectorMtf, 1, selectorMtfPosition);
                                    selectorMtf[0] = selector;
                                    selectors[selectorPosition] = selector;
                                }
                                selectorPosition++;
                                selectorMtfPosition = 0;
                            }
                        }
                        treeGroup = 0;
                        if (lengths.length != usedByteCount + 2) lengths = new int[usedByteCount + 2];
                        parseState = ParseState.LENGTH_START;
                    }
                    case LENGTH_START -> {
                        currentLength = bits.readBits(5, source);
                        validateLength();
                        lengthPosition = 0;
                        parseState = ParseState.LENGTHS;
                    }
                    case LENGTHS -> {
                        if (bits.readBits(1, source) != 0) {
                            parseState = ParseState.LENGTH_DELTA;
                        } else {
                            lengths[lengthPosition++] = currentLength;
                            if (lengthPosition == lengths.length) {
                                trees[treeGroup++] = new HuffmanTree(lengths);
                                if (treeGroup < groupCount) {
                                    parseState = ParseState.LENGTH_START;
                                } else {
                                    prepareColumn();
                                    parseState = ParseState.SYMBOLS;
                                }
                            }
                        }
                    }
                    case LENGTH_DELTA -> {
                        currentLength += bits.readBits(1, source) != 0 ? -1 : 1;
                        validateLength();
                        parseState = ParseState.LENGTHS;
                    }
                    case SYMBOLS -> decodeColumn(source);
                }
            }
        } catch (NeedInputException exception) {
            if (endOfInput) throw new EOFException("Truncated BZip2 stream");
            return false;
        }
        return true;
    }

    /// Rejects a code length outside the BZip2 format range.
    private void validateLength() throws IOException {
        if (currentLength < 1 || currentLength > MAX_CODE_LENGTH) {
            throw new IOException("Invalid BZip2 Huffman code length: " + currentLength);
        }
    }

    /// Initializes bounded storage and state for the Huffman-coded column.
    private void prepareColumn() {
        if (lastColumn.length < blockSizeLimit) {
            lastColumn = new byte[blockSizeLimit];
            blockData = new byte[blockSizeLimit];
            bwtNext = new int[blockSizeLimit];
        }
        System.arraycopy(usedBytes, 0, moveToFront, 0, usedByteCount);
        columnLength = 0;
        dataSelector = 0;
        groupRemaining = 0;
        symbolNode = 0;
        runValue = 0;
        runPower = 1L;
    }

    /// Resumes Huffman, RLE2, and MTF decoding at the exact pending symbol.
    private void decodeColumn(ByteBuffer source) throws IOException {
        while (true) {
            if (groupRemaining == 0) {
                if (dataSelector >= retainedSelectors) {
                    throw new IOException("BZip2 selector sequence ended before the block");
                }
                currentTree = Objects.requireNonNull(trees[Byte.toUnsignedInt(selectors[dataSelector++])]);
                groupRemaining = GROUP_SIZE;
            }
            HuffmanTree tree = Objects.requireNonNull(currentTree);
            int symbol = readSymbol(tree, source);
            groupRemaining--;
            if (symbol == RUNA || symbol == RUNB) {
                runValue += symbol == RUNA ? runPower : runPower << 1;
                if (runValue > blockSizeLimit - columnLength) {
                    throw new IOException("BZip2 block exceeds its declared size");
                }
                runPower <<= 1;
                continue;
            }
            if (runValue != 0) {
                Arrays.fill(lastColumn, columnLength, columnLength + (int) runValue, moveToFront[0]);
                columnLength += (int) runValue;
                runValue = 0;
                runPower = 1L;
            }
            if (symbol == usedByteCount + 1) {
                activateBlock();
                parseState = ParseState.MARKER;
                return;
            }
            int index = symbol - 1;
            if (index <= 0 || index >= usedByteCount) throw new IOException("Invalid BZip2 MTF symbol: " + symbol);
            if (columnLength >= blockSizeLimit) throw new IOException("BZip2 block exceeds its declared size");
            byte value = moveToFront[index];
            System.arraycopy(moveToFront, 0, moveToFront, 1, index);
            moveToFront[0] = value;
            lastColumn[columnLength++] = value;
        }
    }

    /// Saves an unfinished tree traversal only when the caller's input runs out.
    private int readSymbol(HuffmanTree tree, ByteBuffer source) throws IOException {
        int node = symbolNode;
        long buffer = bits.buffer;
        int remaining = bits.bitCount;
        if (node == 0) {
            while (remaining < HuffmanTree.LOOKUP_BITS && source.hasRemaining()) {
                buffer = buffer << Byte.SIZE | Byte.toUnsignedLong(source.get());
                remaining += Byte.SIZE;
            }
            if (remaining >= HuffmanTree.LOOKUP_BITS) {
                int entry = tree.lookup[(int) (buffer >>> (remaining - HuffmanTree.LOOKUP_BITS))
                        & HuffmanTree.LOOKUP_MASK];
                if (entry >= 0) {
                    remaining -= entry & 31;
                    bits.buffer = buffer & ((1L << remaining) - 1);
                    bits.bitCount = remaining;
                    return entry >>> 5;
                }
                if (entry == HuffmanTree.INVALID_LOOKUP) {
                    bits.buffer = buffer & ((1L << remaining) - 1);
                    bits.bitCount = remaining;
                    throw new IOException("Invalid BZip2 Huffman code");
                }
                node = -entry - 1;
                remaining -= HuffmanTree.LOOKUP_BITS;
            }
        }
        while (tree.symbols[node] < 0) {
            if (remaining == 0) {
                if (!source.hasRemaining()) {
                    symbolNode = node;
                    bits.buffer = 0;
                    bits.bitCount = 0;
                    throw NeedInputException.INSTANCE;
                }
                buffer = Byte.toUnsignedLong(source.get());
                remaining = Byte.SIZE;
            }
            node = ((buffer >>> --remaining) & 1L) != 0 ? tree.right[node] : tree.left[node];
            if (node < 0) {
                bits.buffer = buffer & ((1L << remaining) - 1);
                bits.bitCount = remaining;
                throw new IOException("Invalid BZip2 Huffman code");
            }
        }
        bits.buffer = buffer & ((1L << remaining) - 1);
        bits.bitCount = remaining;
        symbolNode = 0;
        return tree.symbols[node];
    }

    /// Reverses BWT once after a complete column and starts incremental RLE output.
    private void activateBlock() throws IOException {
        if (columnLength == 0 || originalPointer < 0 || originalPointer >= columnLength) {
            throw new IOException("Invalid BZip2 original pointer: " + originalPointer);
        }
        Arrays.fill(bwtCounts, 0);
        for (int i = 0; i < columnLength; i++) bwtCounts[Byte.toUnsignedInt(lastColumn[i])]++;
        int total = 0;
        for (int i = 0; i < bwtCounts.length; i++) {
            int count = bwtCounts[i];
            bwtCounts[i] = total;
            total += count;
        }
        for (int i = 0; i < columnLength; i++) bwtNext[bwtCounts[Byte.toUnsignedInt(lastColumn[i])]++] = i;
        int position = bwtNext[originalPointer];
        for (int i = 0; i < columnLength; i++) {
            blockData[i] = lastColumn[position];
            position = bwtNext[position];
        }
        blockLength = columnLength;
        blockPosition = 0;
        blockCrc.reset();
        runByte = -1;
        runLength = repeatRemaining = randomPosition = randomRemaining = 0;
        blockActive = true;
    }

    /// Records one decoded byte in the current block CRC and returns it.
    private int recordDecodedByte(int value) {
        blockCrc.update((byte) value);
        return value;
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
        int actualBlockCrc = blockCrc.finishInt();
        if (actualBlockCrc != expectedBlockCrc) {
            throw new IOException("BZip2 block CRC mismatch");
        }
        // BZip2 rotates the stream CRC once for each completed block.
        combinedCrc = Integer.rotateLeft(combinedCrc, 1) ^ actualBlockCrc;
        blockLength = 0;
        blockPosition = 0;
        blockActive = false;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Reads only the bits needed by the active parser step; caller buffers are never retained.
    @NotNullByDefault
    private static final class BitInput {
        /// Unconsumed low-order bits.
        private long buffer;
        /// The number of buffered bits.
        private int bitCount;

        /// Clears the bit reservoir.
        private void reset() {
            buffer = 0L;
            bitCount = 0;
        }

        /// Validates the stream's byte-alignment padding.
        private void finishFrame() throws IOException {
            if (buffer != 0L) throw new IOException("Invalid BZip2 stream padding");
            bitCount = 0;
        }

        /// Reads an unsigned bit field of at most 48 bits without discarding partial input.
        private long readLong(int count, ByteBuffer source) throws NeedInputException {
            while (bitCount < count) {
                if (!source.hasRemaining()) throw NeedInputException.INSTANCE;
                buffer = (buffer << 8) | Byte.toUnsignedLong(source.get());
                bitCount += 8;
            }
            int remaining = bitCount - count;
            long value = (buffer >>> remaining) & ((1L << count) - 1);
            bitCount = remaining;
            buffer &= (1L << remaining) - 1;
            return value;
        }

        /// Reads a bit field of at most 32 bits.
        private int readBits(int count, ByteBuffer source) throws NeedInputException {
            return (int) readLong(count, source);
        }
    }

    /// Signals that the suspended parse needs another compressed byte.
    @NotNullByDefault
    private static final class NeedInputException extends IOException {
        /// Serialization identifier.
        private static final long serialVersionUID = 0L;

        /// Shared stackless control-flow exception.
        private static final NeedInputException INSTANCE = new NeedInputException();

        /// Creates the shared stackless signal.
        private NeedInputException() {
            super("BZip2 buffer input is incomplete");
        }

        /// Avoids rebuilding a stack trace for expected incremental input boundaries.
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }

    /// Decodes one canonical most-significant-bit-first Huffman alphabet.
    @NotNullByDefault
    private static final class HuffmanTree {
        /// Most-significant input bits covered by one direct lookup.
        private static final int LOOKUP_BITS = 8;

        /// Mask selecting one root-table prefix.
        private static final int LOOKUP_MASK = (1 << LOOKUP_BITS) - 1;

        /// An unused prefix in an incomplete alphabet.
        private static final int INVALID_LOOKUP = Integer.MIN_VALUE;

        /// Packed symbols and lengths, continuation nodes, or invalid prefixes.
        private final int[] lookup = new int[1 << LOOKUP_BITS];

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
            for (int prefix = 0; prefix < lookup.length; prefix++) {
                int node = 0;
                int depth = 0;
                while (node >= 0 && symbols[node] < 0 && depth < LOOKUP_BITS) {
                    node = (prefix >>> (LOOKUP_BITS - ++depth) & 1) == 0 ? left[node] : right[node];
                }
                lookup[prefix] = node < 0 ? INVALID_LOOKUP
                        : symbols[node] >= 0 ? symbols[node] << 5 | depth : -node - 1;
            }
        }

    }
}
