// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.internal.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDictionary;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.CompressionStrategy;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;
import java.util.PriorityQueue;

/// Incrementally encodes the shared Deflate bitstream grammar without retaining caller-owned buffers.
///
/// Format parameters select RFC 1951 Deflate or Deflate64 window, length, and distance semantics. The encoder chooses
/// stored, fixed-Huffman, or dynamic-Huffman blocks from their exact encoded bit costs and retains only bounded format
/// history plus the current block.
@NotNullByDefault
public final class DeflateEncoderEngine implements CompressionEncoder {
    /// The maximum uncompressed bytes buffered before a block decision.
    private static final int BLOCK_SIZE = 1 << 16;

    /// The hash-table size used by the bounded match finder.
    private static final int HASH_SIZE = 1 << 16;

    /// The minimum match length represented by either format.
    private static final int MINIMUM_MATCH_LENGTH = 3;

    /// The end-of-block literal/length symbol.
    private static final int END_OF_BLOCK_SYMBOL = 256;

    /// The first length symbol.
    private static final int FIRST_LENGTH_SYMBOL = 257;

    /// The final length symbol.
    private static final int LAST_LENGTH_SYMBOL = 285;

    /// The maximum literal/length Huffman code length.
    private static final int MAXIMUM_DATA_CODE_LENGTH = 15;

    /// The maximum code-length Huffman code length.
    private static final int MAXIMUM_CODE_LENGTH = 7;

    /// Minimum dynamic-block header cost before encoded code-length symbols.
    private static final int MINIMUM_DYNAMIC_HEADER_BIT_COST = 29;

    /// Shared empty output marker.
    private static final @Unmodifiable ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0);

    /// The base lengths for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17,
            19, 23, 27, 31,
            35, 43, 51, 59,
            67, 83, 99, 115,
            131, 163, 195, 227,
            258
    };

    /// The extra-bit counts for symbols 257 through 285 in RFC 1951 Deflate.
    private static final int @Unmodifiable [] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4,
            5, 5, 5, 5,
            0
    };

    /// The ordered code-length alphabet used by dynamic block headers.
    private static final int @Unmodifiable [] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    /// Selected Deflate-family format.
    private final Format format;

    /// Configured compression level restored by reset.
    private final int compressionLevel;

    /// Configured compression strategy restored by reset.
    private final CompressionStrategy strategy;

    /// Configured preset dictionary bytes, or null.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// The maximum hash-chain candidates examined for each position.
    private final int searchLimit;

    /// The little-endian bit writer.
    private final BitOutput bits = new BitOutput();

    /// The current uncompressed block.
    private final byte[] block = new byte[BLOCK_SIZE];

    /// History retained from the dictionary and preceding blocks.
    private final byte[] history;

    /// Contiguous retained history and current block used by the match finder.
    private final byte[] matchBytes;

    /// Hash-chain heads for the combined history and current block.
    private final int[] hashHeads = new int[HASH_SIZE];

    /// Previous positions in the combined bounded search domain.
    private final int[] previous;

    /// Literal values or match lengths for the current token stream.
    private final int[] tokenValues = new int[BLOCK_SIZE];

    /// Match distances, or zero for literal tokens.
    private final int[] tokenDistances = new int[BLOCK_SIZE];

    /// Literal/length symbol frequencies for the current block.
    private final int[] literalLengthFrequencies = new int[286];

    /// Distance symbol frequencies for the current block.
    private final int[] distanceFrequencies = new int[32];

    /// Sorted nonzero weights used to calculate an allocation-free Huffman cost lower bound.
    private final long[] huffmanCostLeaves = new long[286];

    /// Ordered merged weights used to calculate an allocation-free Huffman cost lower bound.
    private final long[] huffmanCostMerged = new long[286];

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Complete compressed bytes waiting for caller-owned target space.
    private ByteBuffer pendingOutput = EMPTY_OUTPUT;

    /// The number of bytes in the current block.
    private int blockSize;

    /// The number of retained history bytes.
    private int historySize;

    /// The number of generated literal or match tokens.
    private int tokenCount;

    /// Extra bits contributed by the current block's match tokens.
    private long tokenExtraBitCost;

    /// Whether the current flush has already encoded its synchronization boundary.
    private boolean flushPrepared;

    /// Creates an encoder for one Deflate-family format and immutable stream configuration.
    ///
    /// @param format selected bitstream semantics
    /// @param compressionLevel bounded match-search level from zero through nine
    /// @param dictionary initial history content, or null
    /// @param strategy match-selection strategy
    public DeflateEncoderEngine(
            Format format,
            int compressionLevel,
            @Nullable CompressionDictionary dictionary,
            CompressionStrategy strategy
    ) {
        this.format = Objects.requireNonNull(format, "format");
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException(format.displayName() + " compression level must be between 0 and 9");
        }
        if (format == Format.DEFLATE64 && dictionary != null) {
            throw new IllegalArgumentException("Deflate64 does not support preset dictionaries");
        }
        this.compressionLevel = compressionLevel;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.dictionary = dictionary != null ? dictionary.bytes() : null;
        this.searchLimit = searchLimit(compressionLevel, strategy);
        this.history = new byte[format.windowSize()];
        this.matchBytes = new byte[format.windowSize() + BLOCK_SIZE];
        this.previous = new int[format.windowSize() + BLOCK_SIZE];
        restoreDictionary();
    }

    /// Encodes source bytes until the source or target is exhausted.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");

        while (true) {
            copyPendingOutput(target);
            if (pendingOutput.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            pendingOutput = EMPTY_OUTPUT;

            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            int copied = Math.min(source.remaining(), block.length - blockSize);
            source.get(block, blockSize, copied);
            blockSize += copied;
            if (blockSize == block.length) {
                writeBlock(false);
                pendingOutput = bits.takeOutput();
            }
        }
    }

    /// Ends the current block with a byte-aligned empty stored-block synchronization marker.
    @Override
    public CodecOutcome flush(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished " + format.displayName() + " stream");
        }
        if (state == State.ACTIVE) {
            state = State.FLUSHING;
            flushPrepared = false;
        }

        copyPendingOutput(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingOutput = EMPTY_OUTPUT;

        if (!flushPrepared) {
            if (blockSize > 0) {
                writeBlock(false);
            }
            writeSyncFlushMarker();
            pendingOutput = bits.takeOutput();
            flushPrepared = true;
        }
        copyPendingOutput(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        pendingOutput = EMPTY_OUTPUT;
        flushPrepared = false;
        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the raw stream without releasing encoder-owned state.
    @Override
    public CodecOutcome finish(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException(
                    "Complete the active flush before finishing the " + format.displayName() + " stream"
            );
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            copyPendingOutput(target);
            if (pendingOutput.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            pendingOutput = EMPTY_OUTPUT;

            writeBlock(true);
            bits.finish();
            pendingOutput = bits.takeOutput();
            state = State.FINISHING;
        }

        copyPendingOutput(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingOutput = EMPTY_OUTPUT;
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current stream and restores its configured initial history.
    @Override
    public void reset() {
        requireOpen();
        blockSize = 0;
        tokenCount = 0;
        flushPrepared = false;
        pendingOutput = EMPTY_OUTPUT;
        bits.reset();
        restoreDictionary();
        state = State.ACTIVE;
    }

    /// Releases encoder-owned stream state without finishing pending data.
    @Override
    public void close() {
        state = State.CLOSED;
        pendingOutput = EMPTY_OUTPUT;
        blockSize = 0;
        historySize = 0;
        tokenCount = 0;
        bits.reset();
    }

    /// Selects and writes one block, then retains its trailing history.
    private void writeBlock(boolean finalBlock) {
        if (compressionLevel == 0) {
            writeStoredBlock(finalBlock);
        } else {
            generateTokens();
            long fixedCost = fixedBlockBitCost();
            long storedCost = storedBlockBitCost();
            if (storedCost <= fixedCost && dynamicBlockBitCostLowerBound() >= storedCost) {
                writeStoredBlock(finalBlock);
            } else {
                DynamicPlan dynamicPlan = createDynamicPlan();
                if (storedCost <= fixedCost && storedCost <= dynamicPlan.bitCost()) {
                    writeStoredBlock(finalBlock);
                } else if (dynamicPlan.bitCost() < fixedCost) {
                    writeDynamicBlock(finalBlock, dynamicPlan);
                } else {
                    writeFixedBlock(finalBlock);
                }
            }
        }
        retainHistory();
        blockSize = 0;
        tokenCount = 0;
    }

    /// Writes one or more byte-aligned stored blocks containing the current block bytes.
    private void writeStoredBlock(boolean finalBlock) {
        int offset = 0;
        int remaining = blockSize;
        do {
            int length = Math.min(remaining, 0xffff);
            boolean lastStoredBlock = remaining <= 0xffff;
            bits.writeBits(1, finalBlock && lastStoredBlock ? 1 : 0);
            bits.writeBits(2, 0);
            bits.alignToByte();
            bits.writeBits(16, length);
            bits.writeBits(16, length ^ 0xffff);
            bits.writeBytes(block, offset, length);
            offset += length;
            remaining -= length;
        } while (remaining > 0);
    }

    /// Writes one fixed-Huffman block from the current token stream.
    private void writeFixedBlock(boolean finalBlock) {
        bits.writeBits(1, finalBlock ? 1 : 0);
        bits.writeBits(2, 1);
        writeTokens(FixedCode.INSTANCE, FixedDistanceCode.INSTANCE);
    }

    /// Writes one dynamic-Huffman block and its canonical tree description.
    private void writeDynamicBlock(boolean finalBlock, DynamicPlan plan) {
        bits.writeBits(1, finalBlock ? 1 : 0);
        bits.writeBits(2, 2);
        bits.writeBits(5, plan.literalLengthCount() - 257);
        bits.writeBits(5, plan.distanceCount() - 1);
        bits.writeBits(4, plan.codeLengthCount() - 4);
        for (int index = 0; index < plan.codeLengthCount(); index++) {
            bits.writeBits(3, plan.codeLengthCode().length(CODE_LENGTH_ORDER[index]));
        }
        for (int index = 0; index < plan.runLengthCount(); index++) {
            int symbol = plan.runLengthSymbols()[index];
            plan.codeLengthCode().writeSymbol(bits, symbol);
            bits.writeBits(plan.runLengthExtraBits()[index], plan.runLengthExtraValues()[index]);
        }
        writeTokens(plan.literalLengthCode(), plan.distanceCode());
    }

    /// Writes all literal and match tokens followed by the end-of-block symbol.
    private void writeTokens(SymbolCode literalLengthCode, SymbolCode distanceCode) {
        for (int index = 0; index < tokenCount; index++) {
            int distance = tokenDistances[index];
            int value = tokenValues[index];
            if (distance == 0) {
                literalLengthCode.writeSymbol(bits, value);
            } else {
                int lengthSymbol = lengthSymbol(value);
                literalLengthCode.writeSymbol(bits, lengthSymbol);
                bits.writeBits(lengthExtraBits(lengthSymbol), lengthExtraValue(value, lengthSymbol));
                int distanceSymbol = distanceSymbol(distance);
                distanceCode.writeSymbol(bits, distanceSymbol);
                bits.writeBits(distanceExtraBits(distanceSymbol), distanceExtraValue(distance, distanceSymbol));
            }
        }
        literalLengthCode.writeSymbol(bits, END_OF_BLOCK_SYMBOL);
    }

    /// Generates literal and match tokens and their data-tree frequencies.
    private void generateTokens() {
        Arrays.fill(literalLengthFrequencies, 0);
        Arrays.fill(distanceFrequencies, 0);
        tokenCount = 0;
        tokenExtraBitCost = 0L;

        if (strategy == CompressionStrategy.HUFFMAN_ONLY) {
            for (int position = 0; position < blockSize; position++) {
                addLiteral(Byte.toUnsignedInt(block[position]));
            }
        } else {
            initializeMatchFinder();
            int position = 0;
            while (position < blockSize) {
                int logicalPosition = historySize + position;
                Match match = findAndInsertMatch(logicalPosition);
                if (strategy == CompressionStrategy.FILTERED && match.length() <= 5) {
                    match = Match.NONE;
                }
                if (match.length() >= MINIMUM_MATCH_LENGTH) {
                    addMatch(match.length(), match.distance());
                    int end = position + match.length();
                    position++;
                    while (position < end) {
                        insertPosition(historySize + position);
                        position++;
                    }
                } else {
                    addLiteral(Byte.toUnsignedInt(block[position]));
                    position++;
                }
            }
        }
        literalLengthFrequencies[END_OF_BLOCK_SYMBOL]++;
    }

    /// Adds one literal token and updates its frequency.
    private void addLiteral(int value) {
        tokenValues[tokenCount] = value;
        tokenDistances[tokenCount] = 0;
        tokenCount++;
        literalLengthFrequencies[value]++;
    }

    /// Adds one match token and updates both symbol frequencies.
    private void addMatch(int length, int distance) {
        tokenValues[tokenCount] = length;
        tokenDistances[tokenCount] = distance;
        tokenCount++;
        int lengthSymbol = lengthSymbol(length);
        int distanceSymbol = distanceSymbol(distance);
        literalLengthFrequencies[lengthSymbol]++;
        distanceFrequencies[distanceSymbol]++;
        tokenExtraBitCost += lengthExtraBits(lengthSymbol) + distanceExtraBits(distanceSymbol);
    }

    /// Initializes hash chains with the retained dictionary or preceding-block history.
    private void initializeMatchFinder() {
        System.arraycopy(history, 0, matchBytes, 0, historySize);
        System.arraycopy(block, 0, matchBytes, historySize, blockSize);
        Arrays.fill(hashHeads, -1);
        for (int position = 0; position + 2 < historySize; position++) {
            insertPosition(position);
        }
    }

    /// Finds the longest bounded match and inserts the current combined-domain position.
    private Match findAndInsertMatch(int position) {
        int remaining = Math.min(historySize + blockSize - position, format.maximumMatchLength());
        if (searchLimit == 0 || remaining < MINIMUM_MATCH_LENGTH) {
            return Match.NONE;
        }
        int hash = hash(position);
        int candidate = hashHeads[hash];
        previous[position] = candidate;
        hashHeads[hash] = position;
        int bestLength = 0;
        int bestDistance = 0;
        int searched = 0;
        while (candidate >= 0 && searched++ < searchLimit) {
            int distance = position - candidate;
            if (distance > format.windowSize()) {
                break;
            }
            // A candidate differing at the current best endpoint cannot extend beyond that match.
            if ((bestLength == 0 || matchBytes[candidate + bestLength] == matchBytes[position + bestLength])
                    && matchBytes[candidate] == matchBytes[position]
                    && matchBytes[candidate + 1] == matchBytes[position + 1]
                    && matchBytes[candidate + 2] == matchBytes[position + 2]) {
                int mismatch = Arrays.mismatch(
                        matchBytes,
                        candidate + MINIMUM_MATCH_LENGTH,
                        candidate + remaining,
                        matchBytes,
                        position + MINIMUM_MATCH_LENGTH,
                        position + remaining
                );
                int length = mismatch < 0 ? remaining : MINIMUM_MATCH_LENGTH + mismatch;
                if (length > bestLength) {
                    bestLength = length;
                    bestDistance = distance;
                    if (length == remaining) {
                        break;
                    }
                }
            }
            candidate = previous[candidate];
        }
        return bestLength >= MINIMUM_MATCH_LENGTH
                ? new Match(bestLength, bestDistance)
                : Match.NONE;
    }

    /// Inserts one position into its three-byte hash chain.
    private void insertPosition(int position) {
        if (position + 2 >= historySize + blockSize) {
            return;
        }
        int hash = hash(position);
        previous[position] = hashHeads[hash];
        hashHeads[hash] = position;
    }

    /// Returns the hash of three bytes at one combined-domain position.
    private int hash(int position) {
        int value = Byte.toUnsignedInt(matchBytes[position]);
        value = value * 251 + Byte.toUnsignedInt(matchBytes[position + 1]);
        value = value * 251 + Byte.toUnsignedInt(matchBytes[position + 2]);
        return value & (HASH_SIZE - 1);
    }

    /// Creates the dynamic trees and run-length encoded tree description for the current token stream.
    private DynamicPlan createDynamicPlan() {
        HuffmanCode literalLengthCode = HuffmanCode.create(
                literalLengthFrequencies,
                MAXIMUM_DATA_CODE_LENGTH
        );
        HuffmanCode distanceCode = HuffmanCode.create(distanceFrequencies, MAXIMUM_DATA_CODE_LENGTH);
        int literalLengthCount = Math.max(257, lastNonZero(literalLengthCode.lengths()) + 1);
        int distanceCount = Math.max(1, lastNonZero(distanceCode.lengths()) + 1);
        int[] combinedLengths = new int[literalLengthCount + distanceCount];
        System.arraycopy(literalLengthCode.lengths(), 0, combinedLengths, 0, literalLengthCount);
        System.arraycopy(distanceCode.lengths(), 0, combinedLengths, literalLengthCount, distanceCount);

        RunLengthEncoding runLengths = RunLengthEncoding.create(combinedLengths);
        HuffmanCode codeLengthCode = HuffmanCode.create(runLengths.frequencies(), MAXIMUM_CODE_LENGTH);
        int codeLengthCount = 4;
        for (int index = CODE_LENGTH_ORDER.length - 1; index >= 4; index--) {
            if (codeLengthCode.length(CODE_LENGTH_ORDER[index]) != 0) {
                codeLengthCount = index + 1;
                break;
            }
        }

        long bitCost = 3L + 5L + 5L + 4L + 3L * codeLengthCount;
        for (int index = 0; index < runLengths.count(); index++) {
            bitCost += codeLengthCode.length(runLengths.symbols()[index]);
            bitCost += runLengths.extraBits()[index];
        }
        bitCost += tokenBitCost(literalLengthCode, distanceCode);
        return new DynamicPlan(
                literalLengthCode,
                distanceCode,
                codeLengthCode,
                runLengths.symbols(),
                runLengths.extraValues(),
                runLengths.extraBits(),
                runLengths.count(),
                literalLengthCount,
                distanceCount,
                codeLengthCount,
                bitCost
        );
    }

    /// Returns a lower bound for the complete dynamic-Huffman block cost.
    private long dynamicBlockBitCostLowerBound() {
        return MINIMUM_DYNAMIC_HEADER_BIT_COST
                + tokenExtraBitCost
                + minimumHuffmanBitCost(literalLengthFrequencies)
                + minimumHuffmanBitCost(distanceFrequencies);
    }

    /// Returns the unconstrained optimal Huffman data cost for one frequency alphabet.
    private long minimumHuffmanBitCost(int[] frequencies) {
        int leafCount = 0;
        for (int frequency : frequencies) {
            if (frequency > 0) {
                huffmanCostLeaves[leafCount++] = frequency;
            }
        }
        if (leafCount == 0) {
            return 0L;
        }
        if (leafCount == 1) {
            return huffmanCostLeaves[0];
        }

        Arrays.sort(huffmanCostLeaves, 0, leafCount);
        int leafPosition = 0;
        int mergedPosition = 0;
        int mergedCount = 0;
        long cost = 0L;
        for (int merge = 1; merge < leafCount; merge++) {
            long first;
            if (leafPosition < leafCount
                    && (mergedPosition >= mergedCount
                    || huffmanCostLeaves[leafPosition] <= huffmanCostMerged[mergedPosition])) {
                first = huffmanCostLeaves[leafPosition++];
            } else {
                first = huffmanCostMerged[mergedPosition++];
            }

            long second;
            if (leafPosition < leafCount
                    && (mergedPosition >= mergedCount
                    || huffmanCostLeaves[leafPosition] <= huffmanCostMerged[mergedPosition])) {
                second = huffmanCostLeaves[leafPosition++];
            } else {
                second = huffmanCostMerged[mergedPosition++];
            }

            long combined = first + second;
            huffmanCostMerged[mergedCount++] = combined;
            cost += combined;
        }
        return cost;
    }

    /// Returns the encoded cost of the fixed-Huffman block including its header.
    private long fixedBlockBitCost() {
        return 3L + tokenBitCost(FixedCode.INSTANCE, FixedDistanceCode.INSTANCE);
    }

    /// Returns the encoded cost of stored blocks at the current bit alignment.
    private long storedBlockBitCost() {
        int remaining = blockSize;
        int bitOffset = bits.bitCount();
        long cost = 0L;
        do {
            int length = Math.min(remaining, 0xffff);
            int padding = -(bitOffset + 3) & 7;
            cost += 3L + padding + 32L + 8L * length;
            bitOffset = 0;
            remaining -= length;
        } while (remaining > 0);
        return cost;
    }

    /// Returns the encoded token and end-of-block cost for two symbol trees.
    private long tokenBitCost(SymbolCode literalLengthCode, SymbolCode distanceCode) {
        long cost = tokenExtraBitCost;
        for (int symbol = 0; symbol < literalLengthFrequencies.length; symbol++) {
            cost += (long) literalLengthFrequencies[symbol] * literalLengthCode.length(symbol);
        }
        for (int symbol = 0; symbol < distanceFrequencies.length; symbol++) {
            cost += (long) distanceFrequencies[symbol] * distanceCode.length(symbol);
        }
        return cost;
    }

    /// Resolves the literal/length symbol for one match length.
    private int lengthSymbol(int length) {
        for (int index = 0; index < LENGTH_BASES.length - 1; index++) {
            int maximum = index == LENGTH_BASES.length - 2
                    ? 257
                    : LENGTH_BASES[index] + (1 << LENGTH_EXTRA_BITS[index]) - 1;
            if (length <= maximum) {
                return FIRST_LENGTH_SYMBOL + index;
            }
        }
        if (length <= format.maximumMatchLength()) {
            return LAST_LENGTH_SYMBOL;
        }
        throw new AssertionError(length);
    }

    /// Returns the extra-bit count for one length symbol in the selected format.
    private int lengthExtraBits(int symbol) {
        if (symbol == LAST_LENGTH_SYMBOL && format == Format.DEFLATE64) {
            return 16;
        }
        return LENGTH_EXTRA_BITS[symbol - FIRST_LENGTH_SYMBOL];
    }

    /// Returns the extra-bit value for one encoded match length.
    private int lengthExtraValue(int length, int symbol) {
        if (symbol == LAST_LENGTH_SYMBOL) {
            return format == Format.DEFLATE64 ? length - MINIMUM_MATCH_LENGTH : 0;
        }
        return length - LENGTH_BASES[symbol - FIRST_LENGTH_SYMBOL];
    }

    /// Resolves the distance symbol for one backward distance.
    private int distanceSymbol(int distance) {
        for (int symbol = 0; symbol <= format.maximumDistanceSymbol(); symbol++) {
            int maximum = distanceBase(symbol) + (1 << distanceExtraBits(symbol)) - 1;
            if (distance <= maximum) {
                return symbol;
            }
        }
        throw new AssertionError(distance);
    }

    /// Returns the number of extra bits for one distance symbol.
    private static int distanceExtraBits(int symbol) {
        return symbol < 4 ? 0 : (symbol >>> 1) - 1;
    }

    /// Returns the base distance for one distance symbol.
    private static int distanceBase(int symbol) {
        int extraBits = distanceExtraBits(symbol);
        return symbol < 4 ? symbol + 1 : ((2 + (symbol & 1)) << extraBits) + 1;
    }

    /// Returns the extra-bit value for one encoded distance.
    private static int distanceExtraValue(int distance, int symbol) {
        return distance - distanceBase(symbol);
    }

    /// Writes the empty stored block used as a synchronization boundary.
    private void writeSyncFlushMarker() {
        bits.writeBits(1, 0);
        bits.writeBits(2, 0);
        bits.alignToByte();
        bits.writeBits(16, 0);
        bits.writeBits(16, 0xffff);
    }

    /// Retains the final format-window bytes of combined history and the current block.
    private void retainHistory() {
        int combinedSize = historySize + blockSize;
        int retained = Math.min(format.windowSize(), combinedSize);
        int start = combinedSize - retained;
        if (start < historySize) {
            int fromHistory = historySize - start;
            System.arraycopy(history, start, history, 0, fromHistory);
            System.arraycopy(block, 0, history, fromHistory, retained - fromHistory);
        } else {
            System.arraycopy(block, start - historySize, history, 0, retained);
        }
        historySize = retained;
    }

    /// Restores history bytes from the immutable configured dictionary.
    private void restoreDictionary() {
        byte @Nullable [] selectedDictionary = dictionary;
        if (selectedDictionary == null || selectedDictionary.length == 0) {
            historySize = 0;
            return;
        }
        historySize = Math.min(selectedDictionary.length, history.length);
        System.arraycopy(
                selectedDictionary,
                selectedDictionary.length - historySize,
                history,
                0,
                historySize
        );
    }

    /// Copies as many staged compressed bytes as fit in the caller-owned target.
    private void copyPendingOutput(ByteBuffer target) {
        int count = Math.min(pendingOutput.remaining(), target.remaining());
        if (count == 0) {
            return;
        }
        int originalLimit = pendingOutput.limit();
        pendingOutput.limit(pendingOutput.position() + count);
        try {
            target.put(pendingOutput);
        } finally {
            pendingOutput.limit(originalLimit);
        }
    }

    /// Returns the final nonzero array position, or zero when every value is zero.
    private static int lastNonZero(int[] values) {
        for (int index = values.length - 1; index > 0; index--) {
            if (values[index] != 0) {
                return index;
            }
        }
        return 0;
    }

    /// Resolves the bounded hash-chain search count for one compression configuration.
    private static int searchLimit(int compressionLevel, CompressionStrategy strategy) {
        if (compressionLevel == 0 || strategy == CompressionStrategy.HUFFMAN_ONLY) {
            return 0;
        }
        return 1 << Math.min(compressionLevel + 1, 10);
    }

    /// Requires the exact encoder state for an operation.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException(
                    "Cannot " + operation + " while " + format.displayName() + " encoder state is " + state
            );
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException(format.displayName() + " encoder is closed");
        }
    }

    /// Selects one Deflate-family bitstream profile.
    @NotNullByDefault
    public enum Format {
        /// RFC 1951 Deflate with a 32 KiB window and 258-byte maximum match.
        DEFLATE("raw Deflate", 1 << 15, 29, 258),

        /// Deflate64 with a 64 KiB window, extended distances, and extended symbol 285.
        DEFLATE64("Deflate64", 1 << 16, 31, 3 + 0xffff);

        /// Human-readable format name used in errors.
        private final String displayName;

        /// History-window size.
        private final int windowSize;

        /// Largest valid distance symbol.
        private final int maximumDistanceSymbol;

        /// Largest representable match length.
        private final int maximumMatchLength;

        /// Creates one immutable encoder profile.
        Format(String displayName, int windowSize, int maximumDistanceSymbol, int maximumMatchLength) {
            this.displayName = displayName;
            this.windowSize = windowSize;
            this.maximumDistanceSymbol = maximumDistanceSymbol;
            this.maximumMatchLength = maximumMatchLength;
        }

        /// Returns the human-readable stream name.
        private String displayName() {
            return displayName;
        }

        /// Returns the history-window size.
        private int windowSize() {
            return windowSize;
        }

        /// Returns the largest valid distance symbol.
        private int maximumDistanceSymbol() {
            return maximumDistanceSymbol;
        }

        /// Returns the largest representable match length.
        private int maximumMatchLength() {
            return maximumMatchLength;
        }
    }

    /// Describes one selected LZ77 match.
    ///
    /// @param length match length
    /// @param distance backward match distance
    @NotNullByDefault
    private record Match(int length, int distance) {
        /// The absence of a usable match.
        private static final Match NONE = new Match(0, 0);
    }

    /// Provides symbol lengths and emits corresponding canonical codes.
    @NotNullByDefault
    private interface SymbolCode {
        /// Returns the encoded bit length of one symbol.
        int length(int symbol);

        /// Writes one symbol to the supplied bit output.
        void writeSymbol(BitOutput output, int symbol);
    }

    /// Provides the RFC 1951 fixed literal/length tree.
    @NotNullByDefault
    private enum FixedCode implements SymbolCode {
        /// Shared stateless fixed-tree implementation.
        INSTANCE;

        /// Returns the fixed-tree bit length of one literal/length symbol.
        @Override
        public int length(int symbol) {
            if (symbol <= 143) {
                return 8;
            }
            if (symbol <= 255) {
                return 9;
            }
            if (symbol <= 279) {
                return 7;
            }
            if (symbol <= 287) {
                return 8;
            }
            throw new AssertionError(symbol);
        }

        /// Writes one fixed literal/length symbol.
        @Override
        public void writeSymbol(BitOutput output, int symbol) {
            int code;
            if (symbol <= 143) {
                code = 0x30 + symbol;
            } else if (symbol <= 255) {
                code = 0x190 + symbol - 144;
            } else if (symbol <= 279) {
                code = symbol - 256;
            } else if (symbol <= 287) {
                code = 0xc0 + symbol - 280;
            } else {
                throw new AssertionError(symbol);
            }
            int length = length(symbol);
            output.writeBits(length, reverseBits(code, length));
        }
    }

    /// Provides the fixed five-bit distance tree.
    @NotNullByDefault
    private enum FixedDistanceCode implements SymbolCode {
        /// Shared stateless fixed-distance implementation.
        INSTANCE;

        /// Returns the fixed five-bit distance-code length.
        @Override
        public int length(int symbol) {
            return 5;
        }

        /// Writes one fixed distance symbol.
        @Override
        public void writeSymbol(BitOutput output, int symbol) {
            output.writeBits(5, reverseBits(symbol, 5));
        }
    }

    /// Stores one generated canonical Huffman tree.
    @NotNullByDefault
    private static final class HuffmanCode implements SymbolCode {
        /// Bit lengths indexed by symbol.
        private final int[] lengths;

        /// Reversed canonical codes indexed by symbol.
        private final int[] codes;

        /// Creates one immutable code table from generated arrays.
        private HuffmanCode(int[] lengths, int[] codes) {
            this.lengths = lengths;
            this.codes = codes;
        }

        /// Builds a length-limited canonical tree from symbol frequencies.
        private static HuffmanCode create(int[] frequencies, int maximumLength) {
            int symbolCount = frequencies.length;
            long[] weights = new long[symbolCount * 2];
            int[] minimumSymbols = new int[symbolCount * 2];
            int[] parents = new int[symbolCount * 2];
            Arrays.fill(parents, -1);
            int activeSymbols = 0;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (frequencies[symbol] > 0) {
                    weights[symbol] = frequencies[symbol];
                    minimumSymbols[symbol] = symbol;
                    activeSymbols++;
                }
            }
            for (int symbol = 0; activeSymbols < 2 && symbol < symbolCount; symbol++) {
                if (weights[symbol] == 0L) {
                    weights[symbol] = 1L;
                    minimumSymbols[symbol] = symbol;
                    activeSymbols++;
                }
            }

            Comparator<Integer> order = Comparator
                    .comparingLong((Integer node) -> weights[node])
                    .thenComparingInt(node -> minimumSymbols[node]);
            PriorityQueue<Integer> queue = new PriorityQueue<>(order);
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (weights[symbol] != 0L) {
                    queue.add(symbol);
                }
            }
            int nextNode = symbolCount;
            while (queue.size() > 1) {
                int left = queue.remove();
                int right = queue.remove();
                int parent = nextNode++;
                weights[parent] = weights[left] + weights[right];
                minimumSymbols[parent] = Math.min(minimumSymbols[left], minimumSymbols[right]);
                parents[left] = parent;
                parents[right] = parent;
                queue.add(parent);
            }

            int[] lengthCounts = new int[maximumLength + 1];
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (weights[symbol] == 0L) {
                    continue;
                }
                int length = 0;
                for (int node = symbol; parents[node] >= 0; node = parents[node]) {
                    length++;
                }
                lengthCounts[Math.min(length, maximumLength)]++;
            }

            int remainingCodes = remainingCodeSlots(lengthCounts, maximumLength);
            while (remainingCodes < 0) {
                int length = maximumLength - 1;
                while (length > 0 && lengthCounts[length] == 0) {
                    length--;
                }
                if (length == 0 || lengthCounts[maximumLength] == 0) {
                    throw new AssertionError("Unable to limit Huffman code lengths");
                }
                lengthCounts[length]--;
                lengthCounts[length + 1] += 2;
                lengthCounts[maximumLength]--;
                remainingCodes++;
            }
            if (remainingCodes != 0) {
                throw new AssertionError("Length-limited Huffman tree is incomplete");
            }

            Integer[] orderedSymbols = new Integer[activeSymbols];
            int orderedIndex = 0;
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                if (weights[symbol] != 0L) {
                    orderedSymbols[orderedIndex++] = symbol;
                }
            }
            Arrays.sort(orderedSymbols, Comparator
                    .comparingLong((Integer symbol) -> weights[symbol])
                    .thenComparingInt(Integer::intValue));
            int[] lengths = new int[symbolCount];
            orderedIndex = 0;
            for (int length = maximumLength; length >= 1; length--) {
                for (int count = lengthCounts[length]; count > 0; count--) {
                    lengths[orderedSymbols[orderedIndex++]] = length;
                }
            }
            if (orderedIndex != orderedSymbols.length) {
                throw new AssertionError("Huffman length assignment is incomplete");
            }

            int[] nextCodes = new int[maximumLength + 1];
            int code = 0;
            for (int length = 1; length <= maximumLength; length++) {
                code = (code + lengthCounts[length - 1]) << 1;
                nextCodes[length] = code;
            }
            int[] codes = new int[symbolCount];
            for (int symbol = 0; symbol < symbolCount; symbol++) {
                int length = lengths[symbol];
                if (length != 0) {
                    codes[symbol] = reverseBits(nextCodes[length]++, length);
                }
            }
            return new HuffmanCode(lengths, codes);
        }

        /// Returns unused code slots at the maximum depth, or a negative oversubscription count.
        private static int remainingCodeSlots(int[] lengthCounts, int maximumLength) {
            int remaining = 1;
            for (int length = 1; length <= maximumLength; length++) {
                remaining = (remaining << 1) - lengthCounts[length];
            }
            return remaining;
        }

        /// Returns the encoded bit length of one symbol.
        @Override
        public int length(int symbol) {
            return lengths[symbol];
        }

        /// Writes one generated canonical symbol.
        @Override
        public void writeSymbol(BitOutput output, int symbol) {
            int length = lengths[symbol];
            if (length == 0) {
                throw new AssertionError("Huffman symbol has no code: " + symbol);
            }
            output.writeBits(length, codes[symbol]);
        }

        /// Returns the generated length table for internal header construction.
        private int[] lengths() {
            return lengths;
        }
    }

    /// Stores the run-length encoded data-tree lengths used by a dynamic header.
    @NotNullByDefault
    private static final class RunLengthEncoding {
        /// Encoded code-length symbols.
        private final int[] symbols;

        /// Extra values accompanying repeat symbols.
        private final int[] extraValues;

        /// Extra-bit counts accompanying repeat symbols.
        private final int[] extraBits;

        /// Code-length symbol frequencies.
        private final int[] frequencies;

        /// Number of populated encoded entries.
        private final int count;

        /// Creates one run-length encoding from populated arrays.
        private RunLengthEncoding(
                int[] symbols,
                int[] extraValues,
                int[] extraBits,
                int[] frequencies,
                int count
        ) {
            this.symbols = symbols;
            this.extraValues = extraValues;
            this.extraBits = extraBits;
            this.frequencies = frequencies;
            this.count = count;
        }

        /// Encodes a concatenated literal/length and distance length table.
        private static RunLengthEncoding create(int[] lengths) {
            int[] symbols = new int[lengths.length];
            int[] extraValues = new int[lengths.length];
            int[] extraBits = new int[lengths.length];
            int[] frequencies = new int[19];
            int count = 0;
            int position = 0;
            while (position < lengths.length) {
                int value = lengths[position];
                int runEnd = position + 1;
                while (runEnd < lengths.length && lengths[runEnd] == value) {
                    runEnd++;
                }
                int runLength = runEnd - position;
                if (value == 0) {
                    while (runLength >= 11) {
                        int repeated = Math.min(runLength, 138);
                        count = add(symbols, extraValues, extraBits, frequencies, count, 18, repeated - 11, 7);
                        runLength -= repeated;
                    }
                    if (runLength >= 3) {
                        int repeated = Math.min(runLength, 10);
                        count = add(symbols, extraValues, extraBits, frequencies, count, 17, repeated - 3, 3);
                        runLength -= repeated;
                    }
                    while (runLength-- > 0) {
                        count = add(symbols, extraValues, extraBits, frequencies, count, 0, 0, 0);
                    }
                } else {
                    count = add(symbols, extraValues, extraBits, frequencies, count, value, 0, 0);
                    runLength--;
                    while (runLength >= 3) {
                        int repeated = Math.min(runLength, 6);
                        count = add(symbols, extraValues, extraBits, frequencies, count, 16, repeated - 3, 2);
                        runLength -= repeated;
                    }
                    while (runLength-- > 0) {
                        count = add(symbols, extraValues, extraBits, frequencies, count, value, 0, 0);
                    }
                }
                position = runEnd;
            }
            return new RunLengthEncoding(symbols, extraValues, extraBits, frequencies, count);
        }

        /// Adds one encoded length entry and returns the next insertion position.
        private static int add(
                int[] symbols,
                int[] extraValues,
                int[] extraBits,
                int[] frequencies,
                int position,
                int symbol,
                int extraValue,
                int extraBitCount
        ) {
            symbols[position] = symbol;
            extraValues[position] = extraValue;
            extraBits[position] = extraBitCount;
            frequencies[symbol]++;
            return position + 1;
        }

        /// Returns the encoded symbols.
        private int[] symbols() {
            return symbols;
        }

        /// Returns the encoded extra values.
        private int[] extraValues() {
            return extraValues;
        }

        /// Returns the encoded extra-bit counts.
        private int[] extraBits() {
            return extraBits;
        }

        /// Returns the code-length symbol frequencies.
        private int[] frequencies() {
            return frequencies;
        }

        /// Returns the number of encoded entries.
        private int count() {
            return count;
        }
    }

    /// Stores a complete dynamic-Huffman block plan.
    ///
    /// @param literalLengthCode literal/length code table
    /// @param distanceCode distance code table
    /// @param codeLengthCode code-length code table
    /// @param runLengthSymbols encoded data-tree length symbols
    /// @param runLengthExtraValues encoded repeat values
    /// @param runLengthExtraBits encoded repeat bit counts
    /// @param runLengthCount number of encoded length entries
    /// @param literalLengthCount number of transmitted literal/length code lengths
    /// @param distanceCount number of transmitted distance code lengths
    /// @param codeLengthCount number of transmitted code-length code lengths
    /// @param bitCost complete dynamic block cost
    @NotNullByDefault
    private record DynamicPlan(
            HuffmanCode literalLengthCode,
            HuffmanCode distanceCode,
            HuffmanCode codeLengthCode,
            int @Unmodifiable [] runLengthSymbols,
            int @Unmodifiable [] runLengthExtraValues,
            int @Unmodifiable [] runLengthExtraBits,
            int runLengthCount,
            int literalLengthCount,
            int distanceCount,
            int codeLengthCount,
            long bitCost
    ) {
    }

    /// Collects little-endian packed fields into reusable engine-owned complete bytes.
    @NotNullByDefault
    private static final class BitOutput {
        /// Complete compressed-byte storage sized for one uncompressed block and its headers.
        private byte[] output = new byte[BLOCK_SIZE + 64];

        /// Reusable view of complete compressed bytes awaiting transfer.
        private ByteBuffer outputView = ByteBuffer.wrap(output);

        /// Number of complete compressed bytes currently stored.
        private int outputSize;

        /// Packed pending bits, with the next output bit in bit zero.
        private long buffer;

        /// The number of pending bits.
        private int bitCount;

        /// Writes up to sixteen low-order bits.
        private void writeBits(int count, int value) {
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("Deflate bit count must be between 0 and 16");
            }
            long mask = count == 0 ? 0L : (1L << count) - 1L;
            buffer |= ((long) value & mask) << bitCount;
            bitCount += count;
            while (bitCount >= 8) {
                writeByte((int) buffer);
                buffer >>>= 8;
                bitCount -= 8;
            }
        }

        /// Writes aligned bytes directly to the completed output.
        private void writeBytes(byte[] values, int offset, int length) {
            if (bitCount != 0) {
                throw new IllegalStateException("Deflate byte output is not aligned");
            }
            ensureCapacity(length);
            System.arraycopy(values, offset, output, outputSize, length);
            outputSize += length;
        }

        /// Pads pending bits through the next byte boundary.
        private void alignToByte() {
            int padding = -bitCount & 7;
            writeBits(padding, 0);
        }

        /// Pads the final partial byte into the complete-byte output.
        private void finish() {
            if (bitCount > 0) {
                writeByte((int) buffer);
                buffer = 0L;
                bitCount = 0;
            }
        }

        /// Transfers all complete bytes through a view that remains stable until the next write.
        private ByteBuffer takeOutput() {
            if (outputSize == 0) {
                return EMPTY_OUTPUT;
            }
            outputView.clear().limit(outputSize);
            outputSize = 0;
            return outputView;
        }

        /// Returns the number of bits pending below the next byte boundary.
        private int bitCount() {
            return bitCount;
        }

        /// Restores an empty bitstream session.
        private void reset() {
            outputSize = 0;
            outputView.clear();
            buffer = 0L;
            bitCount = 0;
        }

        /// Appends one completed byte to reusable storage.
        private void writeByte(int value) {
            ensureCapacity(1);
            output[outputSize++] = (byte) value;
        }

        /// Ensures reusable storage can append the requested byte count.
        private void ensureCapacity(int additional) {
            int required = outputSize + additional;
            if (required <= output.length) {
                return;
            }
            output = Arrays.copyOf(output, Math.max(required, output.length << 1));
            outputView = ByteBuffer.wrap(output);
        }
    }

    /// Reverses the low-order `length` bits of a value.
    private static int reverseBits(int value, int length) {
        return Integer.reverse(value) >>> (Integer.SIZE - length);
    }

    /// Tracks the explicit encoder lifecycle.
    @NotNullByDefault
    private enum State {
        /// The encoder accepts source bytes.
        ACTIVE,

        /// A flush must complete before source bytes can be accepted again.
        FLUSHING,

        /// Final compressed bytes must be drained.
        FINISHING,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Encoder-owned state was released.
        CLOSED
    }
}
