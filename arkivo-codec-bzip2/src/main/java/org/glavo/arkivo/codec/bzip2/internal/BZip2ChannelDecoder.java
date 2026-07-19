// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.bzip2.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.internal.OwnedChannelCloser;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.Objects;

/// Decodes concatenated BZip2 streams, including legacy randomized blocks.
///
/// Each compressed block is expanded through Huffman decoding, run-length decoding, move-to-front decoding, and the
/// inverse Burrows-Wheeler transform. The final run-length stage is produced lazily so a highly compressible block does
/// not require an output-sized allocation.
@NotNullByDefault
public class BZip2ChannelDecoder implements DecompressingReadableByteChannel.Framed {
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


    /// Tracks closure of the owned compressed-data source.
    private final @Nullable OwnedChannelCloser sourceCloser;

    /// The most-significant-bit-first reader over the compressed source.
    private final BitInput bits;

    /// The maximum post-RLE block size declared by the current stream header.
    private int blockSizeLimit;

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

    /// Whether a validated stream boundary awaits concatenation processing.
    private boolean frameBoundaryPending;

    /// Whether the last decode operation completed one stream.
    private boolean lastFrameFinished;

    /// Whether no further concatenated stream remains.
    private boolean endReached;

    /// Whether the current stream header has been parsed.
    private boolean frameHeaderRead;

    /// Whether this decoder has closed.
    private boolean closed;

    /// The number of uncompressed bytes returned through channel reads.
    private long outputBytes;

    /// Creates a concatenated-stream decoder over a compressed channel with explicit ownership.
    ///
    /// @param source the channel supplying BZip2 bytes
    /// @param ownership whether closing this decoder also closes `source`
    /// @throws IOException if the first stream header cannot be read or is invalid
    public BZip2ChannelDecoder(ReadableByteChannel source, ResourceOwnership ownership) throws IOException {
        Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.bits = new BitInput(source, 8192);
        try {
            readFrameHeader(bits.readBits(8));
            frameHeaderRead = true;
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Creates a buffer-driven decoder core without an attached source channel.
    BZip2ChannelDecoder() {
        sourceCloser = null;
        bits = new BitInput();
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
    protected final CodecOutcome decodeBuffers(
            ByteBuffer source,
            ByteBuffer target,
            boolean endOfInput
    ) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!bits.isBufferDriven()) {
            throw new IllegalStateException("BZip2 decoder is attached to a source channel");
        }
        if (frameBoundaryPending) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            while (blockActive && target.hasRemaining()) {
                int value = readBufferedBlockByte();
                if (value >= 0) {
                    target.put((byte) value);
                    outputBytes++;
                }
            }
            if (blockActive) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (frameBoundaryPending) {
                return CodecOutcome.FINISHED;
            }

            boolean parsed = parseBufferedUnit(source, endOfInput, !frameHeaderRead);
            if (!parsed) {
                return CodecOutcome.NEEDS_INPUT;
            }
            if (!frameHeaderRead) {
                frameHeaderRead = true;
            }
        }
    }

    /// Abandons buffer-driven decoding and restores the initial stream state.
    protected final void resetBuffers() {
        if (closed) {
            throw new IllegalStateException("BZip2 decoder is closed");
        }
        if (!bits.isBufferDriven()) {
            throw new IllegalStateException("BZip2 decoder is attached to a source channel");
        }
        blockSizeLimit = 0;
        blockData = new byte[0];
        blockPosition = 0;
        expectedBlockCrc = 0;
        blockCrc = BZip2CRC.initial();
        combinedCrc = 0;
        runByte = -1;
        runLength = 0;
        repeatRemaining = 0;
        randomized = false;
        randomPosition = 0;
        randomRemaining = 0;
        blockActive = false;
        frameBoundaryPending = false;
        lastFrameFinished = false;
        endReached = false;
        frameHeaderRead = false;
        outputBytes = 0L;
        bits.resetBuffer();
    }

    /// Releases buffer-driven decoder state without consuming more input.
    protected final void closeBuffers() {
        closed = true;
        blockData = new byte[0];
        bits.resetBuffer();
    }

    /// Emits one byte from the active inverse-BWT block or retires a completed block.
    private int readBufferedBlockByte() throws IOException {
        if (repeatRemaining > 0) {
            repeatRemaining--;
            return recordDecodedByte(runByte);
        }
        if (blockPosition >= blockData.length) {
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
            if (blockPosition >= blockData.length) {
                throw new IOException("Truncated BZip2 run-length sequence");
            }
            repeatRemaining = readBlockByte();
            runLength = 0;
        }
        return recordDecodedByte(value);
    }

    /// Transactionally parses one stream header, compressed block, or stream trailer.
    private boolean parseBufferedUnit(
            ByteBuffer source,
            boolean endOfInput,
            boolean header
    ) throws IOException {
        int appended = bits.append(source);
        BitInput.Snapshot snapshot = bits.snapshot();
        try {
            if (header) {
                readFrameHeader(bits.readBits(8));
            } else {
                openNextBlock();
            }
        } catch (NeedInputException exception) {
            bits.restore(snapshot);
            if (endOfInput) {
                throw new EOFException("Truncated BZip2 stream");
            }
            return false;
        }
        bits.commit(source, appended);
        return true;
    }

    /// Reads decoded bytes directly into the destination buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }

        int start = target.position();
        while (target.hasRemaining()) {
            int value = readDecodedByte(false);
            if (value < 0) {
                break;
            }
            target.put((byte) value);
        }
        int count = target.position() - start;
        outputBytes += count;
        return count == 0 ? -1 : count;
    }

    /// Decodes one increment and continues across BZip2 stream boundaries.
    @Override
    public CodecResult decode(ByteBuffer target) throws IOException {
        return decodeInternal(target, false);
    }

    /// Decodes one increment without beginning a following BZip2 stream.
    @Override
    public CodecResult decodeFrame(ByteBuffer target) throws IOException {
        return decodeInternal(target, true);
    }

    /// Performs one decode operation with explicit stream-boundary behavior.
    private CodecResult decodeInternal(ByteBuffer target, boolean stopAtFrame) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        lastFrameFinished = false;
        long inputBefore = bits.byteCount();
        long outputBefore = outputBytes;
        if (!target.hasRemaining()) {
            return new CodecResult(0L, 0L, CodecResult.Status.ACTIVE);
        }

        while (target.hasRemaining()) {
            int value = readDecodedByte(stopAtFrame);
            if (value < 0) {
                break;
            }
            target.put((byte) value);
            outputBytes++;
        }
        CodecResult.Status status = stopAtFrame && lastFrameFinished
                ? CodecResult.Status.FRAME_FINISHED
                : endReached ? CodecResult.Status.END_OF_INPUT : CodecResult.Status.ACTIVE;
        return new CodecResult(
                bits.byteCount() - inputBefore,
                outputBytes - outputBefore,
                status
        );
    }

    /// Returns the exact number of compressed source bytes consumed so far.
    @Override
    public long inputBytes() {
        return bits.byteCount();
    }

    /// Returns the number of compressed bytes obtained from the source.
    @Override
    public long sourceBytes() {
        return bits.sourceByteCount();
    }

    /// Returns a read-only view of compressed bytes not yet consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return bits.unconsumedInput();
    }

    /// Returns the number of uncompressed bytes returned through channel reads.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return !closed;
    }

    /// Closes the compressed source.
    @Override
    public void close() throws IOException {
        closed = true;
        @Nullable OwnedChannelCloser closer = sourceCloser;
        if (closer != null) {
            closer.close();
        }
    }

    /// Reads and validates one BZip2 stream header.
    private void readFrameHeader(int firstByte) throws IOException {
        if (firstByte != 'B' || bits.readBits(8) != 'Z' || bits.readBits(8) != 'h') {
            throw new IOException("Invalid BZip2 stream header");
        }
        int blockSize = bits.readBits(8) - '0';
        if (blockSize < 1 || blockSize > 9) {
            throw new IOException("Invalid BZip2 block size: " + blockSize);
        }
        blockSizeLimit = blockSize * BLOCK_SIZE_UNIT;
        combinedCrc = 0;
        frameBoundaryPending = false;
    }

    /// Advances from a validated stream footer to another stream or physical end-of-input.
    private void advanceAfterFrame() throws IOException {
        int firstByte = bits.readOptionalByte();
        if (firstByte < 0) {
            frameBoundaryPending = false;
            endReached = true;
            return;
        }
        readFrameHeader(firstByte);
    }

    /// Reads one fully decoded byte or returns the requested stream boundary or end-of-input.
    private int readDecodedByte(boolean stopAtFrame) throws IOException {
        while (true) {
            if (frameBoundaryPending) {
                if (stopAtFrame && lastFrameFinished) {
                    return -1;
                }
                advanceAfterFrame();
            }
            if (endReached) {
                return -1;
            }
            if (repeatRemaining > 0) {
                repeatRemaining--;
                return recordDecodedByte(runByte);
            }
            if (!blockActive) {
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
            bits.finishFrame();
            lastFrameFinished = true;
            frameBoundaryPending = true;
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
        if (selectorCount < 1) {
            throw new IOException("Invalid BZip2 selector count: " + selectorCount);
        }

        byte[] selectors = readSelectors(
                groupCount,
                selectorCount,
                Math.min(selectorCount, maximumSelectorCount)
        );
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

    /// Reads every declared selector while retaining the prefix that the current block can use.
    private byte[] readSelectors(
            int groupCount,
            int declaredSelectorCount,
            int retainedSelectorCount
    ) throws IOException {
        byte[] selectors = new byte[retainedSelectorCount];
        byte[] moveToFront = new byte[groupCount];
        for (int index = 0; index < groupCount; index++) {
            moveToFront[index] = (byte) index;
        }
        for (int index = 0; index < declaredSelectorCount; index++) {
            int position = 0;
            while (bits.readBit()) {
                position++;
                if (position >= groupCount) {
                    throw new IOException("Invalid BZip2 selector MTF value");
                }
            }
            if (index >= retainedSelectorCount) {
                continue;
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
        /// Initial owned staging capacity for buffer-driven decoding.
        private static final int INITIAL_STAGING_CAPACITY = 8192;

        /// The compressed channel source, or null for buffer-driven decoding.
        private final @Nullable ReadableByteChannel source;

        /// The channel compressed-input staging buffer, or null for buffer-driven decoding.
        private final @Nullable ByteBuffer inputBuffer;

        /// Owned bytes retained across incomplete buffer-driven block parses.
        private byte[] stagedBytes = new byte[INITIAL_STAGING_CAPACITY];

        /// The next owned staged byte to parse.
        private int stagedPosition;

        /// The position following the last owned staged byte.
        private int stagedLimit;

        /// The unread low-order bits from source bytes already consumed.
        private long buffer;

        /// The number of unread bits held in `buffer`.
        private int bitCount;

        /// The number of source bytes consumed.
        private long byteCount;

        /// The number of compressed bytes obtained from the source.
        private long sourceByteCount;

        /// Creates a bit reader over the given channel source.
        private BitInput(ReadableByteChannel source, int inputBufferSize) {
            this.source = Objects.requireNonNull(source, "source");
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize);
            inputBuffer.limit(0);
        }

        /// Creates a bit reader that accepts caller buffers transactionally.
        private BitInput() {
            source = null;
            inputBuffer = null;
        }

        /// Returns whether this reader accepts caller-owned source buffers.
        private boolean isBufferDriven() {
            return source == null;
        }

        /// Appends all remaining caller bytes into owned staging and returns their count.
        private int append(ByteBuffer input) {
            if (!isBufferDriven()) {
                throw new IllegalStateException("BZip2 bit input is attached to a source channel");
            }
            int length = input.remaining();
            ensureStagingCapacity(length);
            input.get(stagedBytes, stagedLimit, length);
            stagedLimit += length;
            return length;
        }

        /// Captures the transactional parse position while retaining appended bytes.
        private Snapshot snapshot() {
            return new Snapshot(stagedPosition, buffer, bitCount, byteCount);
        }

        /// Restores a transactional parse position after incomplete input.
        private void restore(Snapshot snapshot) {
            stagedPosition = snapshot.stagedPosition();
            buffer = snapshot.buffer();
            bitCount = snapshot.bitCount();
            byteCount = snapshot.byteCount();
        }

        /// Commits a successful parse and returns unread bytes to the current caller source.
        private void commit(ByteBuffer input, int appended) {
            int unread = stagedLimit - stagedPosition;
            if (unread < 0 || unread > appended) {
                throw new AssertionError("BZip2 parser retained bytes from an earlier input fragment");
            }
            input.position(input.position() - unread);
            stagedLimit -= unread;
            compactStaging();
        }

        /// Clears all buffer-driven input and bit state.
        private void resetBuffer() {
            if (!isBufferDriven()) {
                throw new IllegalStateException("BZip2 bit input is attached to a source channel");
            }
            stagedPosition = 0;
            stagedLimit = 0;
            buffer = 0L;
            bitCount = 0;
            byteCount = 0L;
            sourceByteCount = 0L;
        }

        /// Validates zero padding and aligns after one complete BZip2 stream.
        private void finishFrame() throws IOException {
            if (buffer != 0L) {
                throw new IOException("Invalid BZip2 stream padding");
            }
            bitCount = 0;
        }

        /// Reads one optional byte at a stream boundary.
        private int readOptionalByte() throws IOException {
            if (bitCount != 0) {
                throw new AssertionError("BZip2 stream boundary is not byte-aligned");
            }
            return readRawByte(true);
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
                int value = readRawByte(false);
                buffer = (buffer << 8) | value;
                bitCount += 8;
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

        /// Returns the number of compressed bytes obtained from the source.
        private long sourceByteCount() {
            return sourceByteCount;
        }

        /// Returns a read-only view of channel bytes not yet consumed.
        private @UnmodifiableView ByteBuffer unconsumedInput() {
            ByteBuffer channelBuffer = Objects.requireNonNull(inputBuffer, "inputBuffer");
            return channelBuffer.asReadOnlyBuffer();
        }

        /// Reads one raw byte from owned staging or the attached channel.
        private int readRawByte(boolean optional) throws IOException {
            if (isBufferDriven()) {
                if (stagedPosition >= stagedLimit) {
                    throw NeedInputException.INSTANCE;
                }
                byteCount++;
                return Byte.toUnsignedInt(stagedBytes[stagedPosition++]);
            }

            ByteBuffer channelBuffer = Objects.requireNonNull(inputBuffer, "inputBuffer");
            ReadableByteChannel channel = Objects.requireNonNull(source, "source");
            if (!channelBuffer.hasRemaining()) {
                channelBuffer.clear();
                int read = channel.read(channelBuffer);
                if (read < 0) {
                    channelBuffer.limit(0);
                    if (optional) {
                        return -1;
                    }
                    throw new EOFException("Truncated BZip2 stream");
                }
                if (read == 0) {
                    throw new IOException("BZip2 source channel made no progress");
                }
                sourceByteCount += read;
                channelBuffer.flip();
            }
            byteCount++;
            return Byte.toUnsignedInt(channelBuffer.get());
        }

        /// Ensures owned staging can accept another caller fragment.
        private void ensureStagingCapacity(int additionalLength) {
            int required = Math.addExact(stagedLimit, additionalLength);
            if (required <= stagedBytes.length) {
                return;
            }
            int capacity = stagedBytes.length;
            while (capacity < required) {
                capacity = Math.max(Math.addExact(capacity, capacity >>> 1), required);
            }
            stagedBytes = Arrays.copyOf(stagedBytes, capacity);
        }

        /// Removes raw bytes already incorporated into committed bit state.
        private void compactStaging() {
            int remaining = stagedLimit - stagedPosition;
            if (remaining > 0) {
                System.arraycopy(stagedBytes, stagedPosition, stagedBytes, 0, remaining);
            }
            stagedPosition = 0;
            stagedLimit = remaining;
        }

        /// Captures the mutable fields changed by a speculative block parse.
        ///
        /// @param stagedPosition next owned raw byte
        /// @param buffer unread bit value
        /// @param bitCount number of unread bits
        /// @param byteCount logical compressed bytes consumed
        @NotNullByDefault
        private record Snapshot(int stagedPosition, long buffer, int bitCount, long byteCount) {
        }
    }

    /// Signals that a speculative buffer-driven parse needs another compressed byte.
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
