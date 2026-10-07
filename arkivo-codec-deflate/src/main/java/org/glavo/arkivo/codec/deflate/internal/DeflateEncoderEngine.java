// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0
// Portions adapted from zlib 1.3.1 and zlib-ng; see NOTICE and LICENSES/Zlib.txt.
// This Java implementation differs from the original C sources.

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.deflate.DeflateStrategy;
import org.glavo.arkivo.codec.internal.CodecBuffers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.CODE_LENGTH_ORDER;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.END_OF_BLOCK_SYMBOL;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.FIRST_LENGTH_SYMBOL;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.LAST_LENGTH_SYMBOL;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.LENGTH_BASES;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.LENGTH_SYMBOLS;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.MAXIMUM_CODE_LENGTH;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.MAXIMUM_DATA_CODE_LENGTH;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.MINIMUM_MATCH_LENGTH;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.distanceExtraBits;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.distanceExtraValue;
import static org.glavo.arkivo.codec.deflate.internal.DeflateFormatConstants.distanceSymbol;

/// Incrementally encodes RFC 1951 streams using bounded sliding-window and symbol workspaces.
///
/// Match selection and block construction follow zlib's level and strategy rules. Caller buffers are never retained.
@NotNullByDefault
public final class DeflateEncoderEngine implements CompressionEncoder.Flushable {
    /// The input capacity of the canonical level-zero reference schedule.
    private static final int BLOCK_SIZE = 1 << 16;

    /// The hash-table size used by the bounded match finder.
    private static final int HASH_SIZE = 1 << 15;

    /// The number of bits contributed by each byte to the rolling hash.
    private static final int HASH_SHIFT = 5;

    /// Shared empty output marker.
    private static final @Unmodifiable ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0);

    /// History capacity and mask for hash-chain links.
    private static final int WINDOW_SIZE = 32768;
    /// Minimum lookahead required before deciding a match without a flush.
    private static final int MINIMUM_LOOKAHEAD = 262;
    /// Maximum literal or match tokens in one block at memLevel eight.
    private static final int MAXIMUM_TOKENS = 16383;
    /// Search tuning indexed by compression level: good, lazy, nice, and chain lengths.
    private static final int @Unmodifiable [] @Unmodifiable [] CONFIGURATION = {
            {0, 0, 0, 0}, {4, 4, 8, 4}, {4, 5, 16, 8}, {4, 6, 32, 32},
            {4, 4, 16, 16}, {8, 16, 32, 32}, {8, 16, 128, 128},
            {8, 32, 128, 256}, {32, 128, 258, 1024}, {32, 258, 258, 4096}
    };
    /// Immutable compression level.
    private final int compressionLevel;
    /// Immutable token-selection strategy.
    private final DeflateStrategy strategy;
    /// Initial history, or null when no dictionary is configured.
    private final byte @Nullable @Unmodifiable [] dictionary;
    /// Sliding history and unprocessed lookahead, including safe comparison guards.
    private final byte[] window = new byte[2 * WINDOW_SIZE + 258];
    /// Most recent window position for each three-byte hash.
    private final int[] hashHeads = new int[HASH_SIZE];
    /// Previous positions in each hash chain.
    private final int[] previous = new int[WINDOW_SIZE];
    /// Literal bytes or match lengths in the active block.
    private final int[] tokenValues = new int[MAXIMUM_TOKENS];
    /// Match distances, or zero for literals.
    private final int[] tokenDistances = new int[MAXIMUM_TOKENS];
    /// Literal/length frequencies, including end of block.
    private final int[] literalLengthFrequencies = new int[286];
    /// Distance frequencies.
    private final int[] distanceFrequencies = new int[30];
    /// Primitive workspace for frequency/depth-ordered tree construction.
    private final HuffmanWorkspace huffmanWorkspace = new HuffmanWorkspace();
    /// Literal/length codes for the active block.
    private final HuffmanCode literalLengthCode = new HuffmanCode(286);
    /// Distance codes for the active block.
    private final HuffmanCode distanceCode = new HuffmanCode(30);
    /// Codes describing the two data trees.
    private final HuffmanCode codeLengthCode = new HuffmanCode(19);
    /// Reusable encoded tree lengths.
    private final RunLengthEncoding runLengths = new RunLengthEncoding(318);
    /// Number of literal/length codes transmitted in the active dynamic header.
    private int dynamicLiteralCount;
    /// Number of distance codes transmitted in the active dynamic header.
    private int dynamicDistanceCount;
    /// Number of code-length codes transmitted in the active dynamic header.
    private int dynamicCodeLengthCount;
    /// Reusable bit writer.
    private final DeflateBitOutput bits = new DeflateBitOutput();
    /// Completed bytes awaiting caller target space.
    private ByteBuffer pendingOutput = EMPTY_OUTPUT;
    /// Current lifecycle.
    private State state = State.ACTIVE;
    /// Position of the next unprocessed input byte in the window.
    private int strstart;
    /// Number of available unprocessed bytes.
    private int lookahead;
    /// First raw byte of the active block; negative after it slides out of the window.
    private int blockStart;
    /// Number of input positions awaiting hash insertion after a boundary.
    private int insert;
    /// Length of the pending lazy match.
    private int matchLength = 2;
    /// Start position of the pending lazy match.
    private int matchStart;
    /// Whether the preceding byte is waiting for a lazy decision.
    private boolean matchAvailable;
    /// Number of active literal or match tokens.
    private int tokenCount;
    /// Match extra-bit cost for the active block.
    private long tokenExtraBitCost;
    /// Whether a nonterminal boundary was emitted without subsequent input.
    private boolean alreadyFlushed;
    /// Whether the current boundary's final bytes were prepared.
    private boolean boundaryPrepared;
    /// Canonical level-zero input staging and retained un-emitted bytes.
    private final byte[] stored;
    /// Number of bytes staged for level zero.
    private int storedSize;
    /// Bytes accepted in the current canonical level-zero input chunk.
    private int storedInputCount;

    /// Wrapper bytes occupying the first canonical output buffer.
    private final int wrapperHeaderSize;
    /// Whether no stored block has yet been emitted.
    private boolean firstStoredBlock = true;

    /// Creates a raw Deflate encoder with immutable level, dictionary, and strategy.
    ///
    /// @param compressionLevel compression level from zero through nine
    /// @param dictionary initial history, or null
    /// @param strategy match-selection strategy
    public DeflateEncoderEngine(int compressionLevel, byte @Nullable [] dictionary, DeflateStrategy strategy) {
        this(compressionLevel, dictionary, strategy, 0);
    }

    /// Creates an engine whose first canonical output buffer includes a wrapper header.
    ///
    /// @param compressionLevel compression level from zero through nine
    /// @param dictionary initial history, or null
    /// @param strategy match-selection strategy
    /// @param wrapperHeaderSize bytes preceding the raw payload in the first reference output buffer
    DeflateEncoderEngine(int compressionLevel, byte @Nullable [] dictionary, DeflateStrategy strategy,
                         int wrapperHeaderSize) {
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("Deflate compression level must be between 0 and 9");
        }
        this.compressionLevel = compressionLevel;
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.dictionary = dictionary == null ? null : dictionary.clone();
        this.wrapperHeaderSize = wrapperHeaderSize;
        this.stored = new byte[compressionLevel == 0 ? 2 * BLOCK_SIZE : 0];
        restoreDictionary();
    }

    /// Consumes input without retaining caller buffers or creating implicit flush boundaries.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");
        while (true) {
            CodecBuffers.transfer(pendingOutput, target);
            if (pendingOutput.hasRemaining()) return CodecOutcome.NEEDS_OUTPUT;
            pendingOutput = EMPTY_OUTPUT;
            if (compressionLevel == 0) {
                if (!source.hasRemaining()) return CodecOutcome.NEEDS_INPUT;
                int count = Math.min(source.remaining(), BLOCK_SIZE - storedInputCount);
                source.get(stored, storedSize, count);
                storedSize += count;
                storedInputCount += count;
                alreadyFlushed = false;
                if (storedInputCount == BLOCK_SIZE) {
                    storedInputCount = 0;
                    writeStoredPrefix(Math.min(storedSize, BLOCK_SIZE - 5
                            - (firstStoredBlock ? wrapperHeaderSize : 0)), false);
                    // A full retained window becomes a pending block even when the reference output is full.
                    if (storedSize >= WINDOW_SIZE) {
                        writeStoredPrefix(Math.min(storedSize, BLOCK_SIZE - 5), false);
                    }
                    pendingOutput = bits.takeOutput();
                }
            } else if (strategy == DeflateStrategy.HUFFMAN_ONLY) {
                if (!source.hasRemaining()) return CodecOutcome.NEEDS_INPUT;
                int count = Math.min(source.remaining(), MAXIMUM_TOKENS - tokenCount);
                source.get(window, strstart, count);
                for (int end = strstart + count; strstart < end; strstart++) {
                    addLiteral(Byte.toUnsignedInt(window[strstart]));
                }
                alreadyFlushed = false;
                if (tokenCount == MAXIMUM_TOKENS) {
                    writeBlock(false);
                    strstart = blockStart = 0;
                    pendingOutput = bits.takeOutput();
                }
            } else {
                if (lookahead < MINIMUM_LOOKAHEAD) fillWindow(source);
                if (lookahead < MINIMUM_LOOKAHEAD) return CodecOutcome.NEEDS_INPUT;
                processToken();
            }
        }
    }

    /// Emits a sync boundary once; repeated flushes without new input produce no additional marker.
    @Override
    public CodecOutcome flush(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished Deflate stream");
        }
        if (state == State.ACTIVE) {
            if (alreadyFlushed) return CodecOutcome.FLUSHED;
            state = State.FLUSHING;
            boundaryPrepared = false;
        }
        return finishBoundary(target, false);
    }

    /// Emits the final block, leaving finalization resumable when target space is exhausted.
    @Override
    public CodecOutcome finish(ByteBuffer target) {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) throw new IllegalStateException("Complete the active flush before finishing");
        if (state == State.FINISHED) return CodecOutcome.FINISHED;
        if (state == State.ACTIVE) {
            state = State.FINISHING;
            boundaryPrepared = false;
        }
        return finishBoundary(target, true);
    }

    /// Completes pending token decisions and then emits one logical boundary.
    private CodecOutcome finishBoundary(ByteBuffer target, boolean terminal) {
        while (true) {
            CodecBuffers.transfer(pendingOutput, target);
            if (pendingOutput.hasRemaining()) return CodecOutcome.NEEDS_OUTPUT;
            pendingOutput = EMPTY_OUTPUT;
            if (boundaryPrepared) {
                state = terminal ? State.FINISHED : State.ACTIVE;
                alreadyFlushed = !terminal;
                return terminal ? CodecOutcome.FINISHED : CodecOutcome.FLUSHED;
            }
            if (compressionLevel == 0) {
                // The final canonical input chunk is processed before the empty-input finish operation.
                if (storedInputCount > 0 && storedSize >= WINDOW_SIZE) {
                    storedInputCount = 0;
                    writeStoredPrefix(Math.min(storedSize, BLOCK_SIZE - 5
                            - (firstStoredBlock ? wrapperHeaderSize : 0)), false);
                    if (storedSize >= WINDOW_SIZE) {
                        writeStoredPrefix(Math.min(storedSize, BLOCK_SIZE - 5), false);
                    }
                    pendingOutput = bits.takeOutput();
                    continue;
                }
                if (terminal || storedSize > 0) writeStoredPrefix(storedSize, terminal);
                storedInputCount = 0;
            } else {
                if (lookahead > 0) {
                    processToken();
                    continue;
                }
                if (matchAvailable) {
                    addLiteral(Byte.toUnsignedInt(window[strstart - 1]));
                    matchAvailable = false;
                }
                insert = Math.min(strstart, 2);
                if (terminal || tokenCount > 0) writeBlock(terminal);
                if (strategy == DeflateStrategy.HUFFMAN_ONLY) {
                    strstart = blockStart = insert = 0;
                }
            }
            if (terminal) bits.finish();
            else writeSyncFlushMarker();
            firstStoredBlock = false;
            pendingOutput = bits.takeOutput();
            boundaryPrepared = true;
        }
    }

    /// Restores the immutable configuration and abandons every unfinished block.
    @Override
    public void reset() {
        requireOpen();
        bits.reset();
        pendingOutput = EMPTY_OUTPUT;
        strstart = lookahead = blockStart = insert = matchStart = tokenCount = storedSize = storedInputCount = 0;
        matchLength = 2;
        matchAvailable = alreadyFlushed = boundaryPrepared = false;
        firstStoredBlock = true;
        state = State.ACTIVE;
        restoreDictionary();
    }

    /// Releases pending output without generating a trailer.
    @Override
    public void close() {
        state = State.CLOSED;
        pendingOutput = EMPTY_OUTPUT;
        bits.reset();
    }

    /// Fills lookahead and slides retained history using the same window positions as zlib.
    private void fillWindow(ByteBuffer source) {
        if (strstart >= 2 * WINDOW_SIZE - MINIMUM_LOOKAHEAD) {
            System.arraycopy(window, WINDOW_SIZE, window, 0, WINDOW_SIZE);
            strstart -= WINDOW_SIZE;
            blockStart -= WINDOW_SIZE;
            matchStart -= WINDOW_SIZE;
            for (int i = 0; i < HASH_SIZE; i++) hashHeads[i] = Math.max(0, hashHeads[i] - WINDOW_SIZE);
            for (int i = 0; i < WINDOW_SIZE; i++) previous[i] = Math.max(0, previous[i] - WINDOW_SIZE);
            insert = Math.min(insert, strstart);
        }
        int count = Math.min(source.remaining(), 2 * WINDOW_SIZE - strstart - lookahead);
        if (count == 0) return;
        source.get(window, strstart + lookahead, count);
        lookahead += count;
        alreadyFlushed = false;
        while (insert > 0 && lookahead + insert >= 3) {
            insertPosition(strstart - insert);
            insert--;
        }
    }

    /// Inserts one position and returns the preceding chain head; window position zero is the sentinel.
    private int insertPosition(int position) {
        int hash = ((Byte.toUnsignedInt(window[position]) << (2 * HASH_SHIFT))
                ^ (Byte.toUnsignedInt(window[position + 1]) << HASH_SHIFT)
                ^ Byte.toUnsignedInt(window[position + 2])) & (HASH_SIZE - 1);
        int head = hashHeads[hash];
        previous[position & (WINDOW_SIZE - 1)] = head;
        hashHeads[hash] = position;
        return head;
    }

    /// Searches the chain in descending position order, retaining the first longest match.
    private int longestMatch(int candidate, int previousLength) {
        int[] configuration = CONFIGURATION[compressionLevel];
        int chain = configuration[3];
        if (previousLength >= configuration[0]) chain >>>= 2;
        int best = previousLength;
        int maximum = Math.min(258, lookahead);
        int nice = Math.min(configuration[2], lookahead);
        int limit = Math.max(0, strstart - (WINDOW_SIZE - MINIMUM_LOOKAHEAD));
        do {
            if (best < maximum && window[candidate + best] == window[strstart + best]
                    && window[candidate + best - 1] == window[strstart + best - 1]
                    && window[candidate] == window[strstart] && window[candidate + 1] == window[strstart + 1]) {
                int mismatch = Arrays.mismatch(window, candidate + 2, candidate + maximum,
                        window, strstart + 2, strstart + maximum);
                int length = mismatch < 0 ? maximum : mismatch + 2;
                if (length > best) {
                    matchStart = candidate;
                    best = length;
                    if (length >= nice) break;
                }
            }
            candidate = previous[candidate & (WINDOW_SIZE - 1)];
        } while (candidate > limit && --chain != 0);
        return Math.min(best, lookahead);
    }

    /// Advances one greedy or lazy decision, emitting a block when its symbol workspace fills.
    private void processToken() {
        int head = lookahead >= 3 ? insertPosition(strstart) : 0;
        if (compressionLevel <= 3) {
            matchLength = 2;
            if (head != 0 && strstart - head <= WINDOW_SIZE - MINIMUM_LOOKAHEAD) {
                matchLength = longestMatch(head, 2);
            }
            if (matchLength >= 3) {
                int length = matchLength;
                addMatch(length, strstart - matchStart);
                lookahead -= length;
                if (length <= CONFIGURATION[compressionLevel][1] && lookahead >= 3) {
                    for (int end = strstart + length; ++strstart < end;) insertPosition(strstart);
                } else strstart += length;
                matchLength = 2;
            } else {
                addLiteral(Byte.toUnsignedInt(window[strstart++]));
                lookahead--;
            }
            if (tokenCount == MAXIMUM_TOKENS) publishBlock();
            return;
        }
        int previousLength = matchLength;
        int previousMatch = matchStart;
        matchLength = 2;
        if (head != 0 && previousLength < CONFIGURATION[compressionLevel][1]
                && strstart - head <= WINDOW_SIZE - MINIMUM_LOOKAHEAD) {
            matchLength = longestMatch(head, previousLength);
            if (matchLength <= 5 && (strategy == DeflateStrategy.FILTERED
                    || matchLength == 3 && strstart - matchStart > 4096)) matchLength = 2;
        }
        if (previousLength >= 3 && matchLength <= previousLength) {
            addMatch(previousLength, strstart - 1 - previousMatch);
            int maximumInsert = strstart + lookahead - 3;
            lookahead -= previousLength - 1;
            for (int remaining = previousLength - 2; remaining > 0; remaining--) {
                if (++strstart <= maximumInsert) insertPosition(strstart);
            }
            strstart++;
            matchAvailable = false;
            matchLength = 2;
            if (tokenCount == MAXIMUM_TOKENS) publishBlock();
        } else if (matchAvailable) {
            addLiteral(Byte.toUnsignedInt(window[strstart - 1]));
            // This byte still awaits the next lazy decision and belongs to the following raw block.
            if (tokenCount == MAXIMUM_TOKENS) publishBlock();
            strstart++;
            lookahead--;
        } else {
            matchAvailable = true;
            strstart++;
            lookahead--;
        }
    }

    /// Writes a full nonfinal symbol block and stages its complete bytes.
    private void publishBlock() {
        writeBlock(false);
        pendingOutput = bits.takeOutput();
    }

    /// Selects a block using zlib's rounded byte costs and tie rules.
    private void writeBlock(boolean terminal) {
        long fixed = (tokenBitCost(FixedCode.INSTANCE, FixedDistanceCode.INSTANCE) + 3 + 7) >>> 3;
        long dynamic = (prepareDynamicBlock() + 7) >>> 3;
        int rawLength = strstart - blockStart;
        if (blockStart >= 0 && rawLength <= 65535 && rawLength + 4L <= Math.min(fixed, dynamic)) {
            bits.writeBits(3, terminal ? 1 : 0);
            bits.alignToByte();
            bits.writeBits(16, rawLength);
            bits.writeBits(16, rawLength ^ 0xffff);
            bits.writeBytes(window, blockStart, rawLength);
        } else if (fixed <= dynamic) writeFixedBlock(terminal);
        else writeDynamicBlock(terminal);
        blockStart = strstart;
        tokenCount = 0;
        tokenExtraBitCost = 0;
        Arrays.fill(literalLengthFrequencies, 0);
        Arrays.fill(distanceFrequencies, 0);
        literalLengthFrequencies[END_OF_BLOCK_SYMBOL] = 1;
    }

    /// Emits a level-zero prefix and retains the un-emitted bytes in canonical input staging.
    private void writeStoredPrefix(int length, boolean terminal) {
        firstStoredBlock = false;
        bits.writeBits(3, terminal ? 1 : 0);
        bits.alignToByte();
        bits.writeBits(16, length);
        bits.writeBits(16, length ^ 0xffff);
        bits.writeBytes(stored, 0, length);
        storedSize -= length;
        System.arraycopy(stored, length, stored, 0, storedSize);
    }

    /// Writes one fixed-Huffman block from the current token stream.
    private void writeFixedBlock(boolean finalBlock) {
        bits.writeBits(1, finalBlock ? 1 : 0);
        bits.writeBits(2, 1);
        writeTokens(FixedCode.INSTANCE, FixedDistanceCode.INSTANCE);
    }

    /// Writes one dynamic-Huffman block and its canonical tree description.
    private void writeDynamicBlock(boolean finalBlock) {
        bits.writeBits(1, finalBlock ? 1 : 0);
        bits.writeBits(2, 2);
        bits.writeBits(5, dynamicLiteralCount - 257);
        bits.writeBits(5, dynamicDistanceCount - 1);
        bits.writeBits(4, dynamicCodeLengthCount - 4);
        for (int index = 0; index < dynamicCodeLengthCount; index++) {
            bits.writeBits(3, codeLengthCode.length(CODE_LENGTH_ORDER[index]));
        }
        for (int index = 0; index < runLengths.count(); index++) {
            int symbol = runLengths.symbols()[index];
            codeLengthCode.writeSymbol(bits, symbol);
            bits.writeBits(runLengths.extraBits()[index], runLengths.extraValues()[index]);
        }
        writeTokens(literalLengthCode, distanceCode);
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

    /// Creates the dynamic trees and run-length encoded tree description for the current token stream.
    private long prepareDynamicBlock() {
        huffmanWorkspace.build(literalLengthFrequencies, MAXIMUM_DATA_CODE_LENGTH, literalLengthCode);
        huffmanWorkspace.build(distanceFrequencies, MAXIMUM_DATA_CODE_LENGTH, distanceCode);
        int literalLengthCount = Math.max(257, lastNonZero(literalLengthCode.lengths()) + 1);
        int distanceCount = Math.max(1, lastNonZero(distanceCode.lengths()) + 1);
        runLengths.clear();
        runLengths.encode(literalLengthCode.lengths(), literalLengthCount);
        runLengths.encode(distanceCode.lengths(), distanceCount);
        huffmanWorkspace.build(runLengths.frequencies(), MAXIMUM_CODE_LENGTH, codeLengthCode);
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
        dynamicLiteralCount = literalLengthCount;
        dynamicDistanceCount = distanceCount;
        dynamicCodeLengthCount = codeLengthCount;
        return bitCost;
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
        if (length <= 258) return LENGTH_SYMBOLS[length];
        throw new AssertionError(length);
    }

    /// Returns the extra-bit count for one Deflate length symbol.
    private int lengthExtraBits(int symbol) {
        return DeflateFormatConstants.lengthExtraBits(symbol - FIRST_LENGTH_SYMBOL);
    }

    /// Returns the extra-bit value for one encoded match length.
    private int lengthExtraValue(int length, int symbol) {
        if (symbol == LAST_LENGTH_SYMBOL) {
            return 0;
        }
        return length - LENGTH_BASES[symbol - FIRST_LENGTH_SYMBOL];
    }

    /// Writes the empty stored block used as a synchronization boundary.
    private void writeSyncFlushMarker() {
        bits.writeBits(1, 0);
        bits.writeBits(2, 0);
        bits.alignToByte();
        bits.writeBits(16, 0);
        bits.writeBits(16, 0xffff);
    }

    /// Initializes hash chains from the dictionary's trailing window.
    private void restoreDictionary() {
        Arrays.fill(hashHeads, 0);
        // Clearing the heads makes old links unreachable; insertion overwrites each link before publishing its head.
        Arrays.fill(literalLengthFrequencies, 0);
        Arrays.fill(distanceFrequencies, 0);
        literalLengthFrequencies[END_OF_BLOCK_SYMBOL] = 1;
        tokenExtraBitCost = 0;
        if (compressionLevel == 0 || dictionary == null || dictionary.length == 0) return;
        int size = Math.min(WINDOW_SIZE, dictionary.length);
        System.arraycopy(dictionary, dictionary.length - size, window, 0, size);
        for (int position = 0; position + 2 < size; position++) insertPosition(position);
        strstart = blockStart = size;
        insert = Math.min(size, 2);
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

    /// Requires the exact encoder state for an operation.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException(
                    "Cannot " + operation + " while Deflate encoder state is " + state
            );
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Deflate encoder is closed");
        }
    }

    /// Provides symbol lengths and emits corresponding canonical codes.
    @NotNullByDefault
    private interface SymbolCode {
        /// Returns the encoded bit length of one symbol.
        int length(int symbol);

        /// Writes one symbol to the supplied bit output.
        void writeSymbol(DeflateBitOutput output, int symbol);
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
        public void writeSymbol(DeflateBitOutput output, int symbol) {
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
        public void writeSymbol(DeflateBitOutput output, int symbol) {
            output.writeBits(5, reverseBits(symbol, 5));
        }
    }

    /// Builds canonical trees with zlib's frequency/depth heap ordering and overflow repair.
    @NotNullByDefault
    static final class HuffmanWorkspace {
        /// Weights of leaves and combined nodes.
        private final long[] weights = new long[572];
        /// Depths used to resolve equal weights.
        private final int[] depths = new int[572];
        /// Parent indices of combined nodes.
        private final int[] parents = new int[572];
        /// Provisional depths after walking the completed tree.
        private final int[] nodeLengths = new int[572];
        /// One-based heap and reverse-ordered removed nodes.
        private final int[] heap = new int[573];
        /// Code counts indexed by length.
        private final int[] lengthCounts = new int[MAXIMUM_DATA_CODE_LENGTH + 1];
        /// Next unreversed canonical code indexed by length.
        private final int[] nextCodes = new int[MAXIMUM_DATA_CODE_LENGTH + 1];
        /// Number of active heap nodes.
        private int heapSize;

        /// Builds a canonical tree without mutating the input frequencies.
        void build(int[] frequencies, int maximumLength, HuffmanCode result) {
            int symbolCount = frequencies.length;
            Arrays.fill(weights, 0, symbolCount * 2, 0);
            Arrays.fill(depths, 0, symbolCount * 2, 0);
            Arrays.fill(result.lengths, 0);
            Arrays.fill(result.codes, 0);
            Arrays.fill(lengthCounts, 0);

            int maximumSymbol = initializeHeap(frequencies);
            int rootIndex = mergeNodes(symbolCount);
            int overflow = assignLengths(rootIndex, maximumSymbol, maximumLength, result);
            if (overflow > 0) {
                repairOverflow(overflow, maximumSymbol, maximumLength, result);
            }
            assignCanonicalCodes(maximumSymbol, maximumLength, result);
        }

        /// Builds the leaf heap and returns the highest included symbol, adding dummy leaves when needed.
        private int initializeHeap(int[] frequencies) {
            heapSize = 0;
            int maximumSymbol = -1;
            for (int symbol = 0; symbol < frequencies.length; symbol++) {
                weights[symbol] = frequencies[symbol];
                if (frequencies[symbol] != 0) {
                    heap[++heapSize] = symbol;
                    maximumSymbol = symbol;
                }
            }
            // Two leaves are required even for empty and single-symbol alphabets.
            while (heapSize < 2) {
                int symbol = maximumSymbol < 2 ? ++maximumSymbol : 0;
                heap[++heapSize] = symbol;
                weights[symbol] = 1;
            }
            for (int index = heapSize / 2; index >= 1; index--) {
                siftDown(index);
            }
            return maximumSymbol;
        }

        /// Merges the two lightest nodes until one root remains and returns its index in the heap array.
        ///
        /// Removed nodes fill the array from the end. The resulting suffix orders every parent before
        /// its children, allowing code lengths to be assigned in one forward pass.
        private int mergeNodes(int symbolCount) {
            int nextNode = symbolCount;
            int sortedStart = heap.length;
            do {
                int left = heap[1];
                heap[1] = heap[heapSize--];
                siftDown(1);
                int right = heap[1];
                heap[--sortedStart] = left;
                heap[--sortedStart] = right;
                weights[nextNode] = weights[left] + weights[right];
                depths[nextNode] = Math.max(depths[left], depths[right]) + 1;
                parents[left] = nextNode;
                parents[right] = nextNode;
                heap[1] = nextNode++;
                siftDown(1);
            } while (heapSize >= 2);
            heap[--sortedStart] = heap[1];
            return sortedStart;
        }

        /// Assigns bounded provisional lengths and returns the number of nodes exceeding the length limit.
        private int assignLengths(int rootIndex, int maximumSymbol, int maximumLength, HuffmanCode result) {
            nodeLengths[heap[rootIndex]] = 0;
            int overflow = 0;
            for (int index = rootIndex + 1; index < heap.length; index++) {
                int node = heap[index];
                int length = nodeLengths[parents[node]] + 1;
                if (length > maximumLength) {
                    length = maximumLength;
                    overflow++;
                }
                nodeLengths[node] = length;
                if (node > maximumSymbol) {
                    continue;
                }
                result.lengths[node] = length;
                lengthCounts[length]++;
            }
            return overflow;
        }

        /// Redistributes overlong codes and reassigns leaf lengths in reverse heap order.
        private void repairOverflow(int overflow, int maximumSymbol, int maximumLength, HuffmanCode result) {
            do {
                int length = maximumLength - 1;
                while (lengthCounts[length] == 0) {
                    length--;
                }
                lengthCounts[length]--;
                lengthCounts[length + 1] += 2;
                lengthCounts[maximumLength]--;
                overflow -= 2;
            } while (overflow > 0);

            int index = heap.length;
            for (int length = maximumLength; length > 0; length--) {
                for (int count = lengthCounts[length]; count > 0;) {
                    int node = heap[--index];
                    if (node > maximumSymbol) {
                        continue;
                    }
                    result.lengths[node] = length;
                    count--;
                }
            }
        }

        /// Assigns canonical codes by symbol order and reverses them for least-significant-bit-first output.
        private void assignCanonicalCodes(int maximumSymbol, int maximumLength, HuffmanCode result) {
            int code = 0;
            for (int length = 1; length <= maximumLength; length++) {
                code = (code + lengthCounts[length - 1]) << 1;
                nextCodes[length] = code;
            }
            for (int symbol = 0; symbol <= maximumSymbol; symbol++) {
                int length = result.lengths[symbol];
                if (length != 0) {
                    result.codes[symbol] = reverseBits(nextCodes[length]++, length);
                }
            }
        }

        /// Compares weights, then depths, retaining zlib's non-strict tie rule.
        private boolean comesFirst(int left, int right) {
            return weights[left] < weights[right] || weights[left] == weights[right] && depths[left] <= depths[right];
        }

        /// Restores the heap after replacing one node.
        private void siftDown(int index) {
            int value = heap[index];
            int child = index * 2;
            while (child <= heapSize) {
                if (child < heapSize && comesFirst(heap[child + 1], heap[child])) {
                    child++;
                }
                if (comesFirst(value, heap[child])) {
                    break;
                }
                heap[index] = heap[child];
                index = child;
                child *= 2;
            }
            heap[index] = value;
        }
    }

    /// Stores one generated canonical Huffman tree.
    @NotNullByDefault
    static final class HuffmanCode implements SymbolCode {
        /// Bit lengths indexed by symbol.
        private final int[] lengths;

        /// Reversed canonical codes indexed by symbol.
        private final int[] codes;

        /// Allocates a reusable code table for one alphabet.
        HuffmanCode(int symbolCount) {
            this.lengths = new int[symbolCount];
            this.codes = new int[symbolCount];
        }

        /// Returns the encoded bit length of one symbol.
        @Override
        public int length(int symbol) {
            return lengths[symbol];
        }

        /// Writes one generated canonical symbol.
        @Override
        public void writeSymbol(DeflateBitOutput output, int symbol) {
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
        private int count;

        /// Allocates the reusable arrays for a combined data-tree alphabet.
        private RunLengthEncoding(int capacity) {
            symbols = new int[capacity];
            extraValues = new int[capacity];
            extraBits = new int[capacity];
            frequencies = new int[19];
        }

        /// Clears frequencies before scanning both data trees separately.
        private void clear() {
            Arrays.fill(frequencies, 0);
            count = 0;
        }

        /// Appends one tree's length description using zlib's adaptive repeat thresholds.
        private void encode(int[] lengths, int lengthCount) {
            int previousLength = -1;
            int nextLength = lengths[0];
            int repeated = 0;
            int maximum = nextLength == 0 ? 138 : 7;
            int minimum = nextLength == 0 ? 3 : 4;
            for (int n = 0; n < lengthCount; n++) {
                int current = nextLength;
                nextLength = n + 1 < lengthCount ? lengths[n + 1] : 65535;
                if (++repeated < maximum && current == nextLength) continue;
                if (repeated < minimum) {
                    do {
                        add(current, 0, 0);
                    } while (--repeated != 0);
                } else if (current != 0) {
                    if (current != previousLength) {
                        add(current, 0, 0);
                        repeated--;
                    }
                    add(16, repeated - 3, 2);
                } else if (repeated <= 10) {
                    add(17, repeated - 3, 3);
                } else {
                    add(18, repeated - 11, 7);
                }
                repeated = 0;
                previousLength = current;
                if (nextLength == 0) {
                    maximum = 138;
                    minimum = 3;
                } else if (current == nextLength) {
                    maximum = 6;
                    minimum = 3;
                } else {
                    maximum = 7;
                    minimum = 4;
                }
            }
        }

        /// Appends one code-length symbol and updates its frequency.
        private void add(int symbol, int extraValue, int extraBitCount) {
            symbols[count] = symbol;
            extraValues[count] = extraValue;
            extraBits[count] = extraBitCount;
            frequencies[symbol]++;
            count++;
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
