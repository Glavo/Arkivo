// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally decodes the shared Deflate bitstream grammar without retaining caller-owned buffers.
///
/// Format parameters select either RFC 1951 Deflate or Deflate64 window, length, and distance semantics. A configured
/// raw Deflate dictionary initializes history directly because the raw format carries no dictionary identifier.
@NotNullByDefault
public final class DeflateDecoderEngine implements CompressionDecoder {
    /// The absence-of-current-block state.
    private static final int BLOCK_NONE = 0;

    /// The stored-block state.
    private static final int BLOCK_STORED = 1;

    /// The Huffman-compressed block state.
    private static final int BLOCK_HUFFMAN = 2;

    /// The end-of-block literal/length symbol.
    private static final int END_OF_BLOCK_SYMBOL = 256;

    /// The first length symbol.
    private static final int FIRST_LENGTH_SYMBOL = 257;

    /// The final defined literal/length symbol.
    private static final int LAST_LENGTH_SYMBOL = 285;

    /// The ordered code-length alphabet used by dynamic block headers.
    private static final int @Unmodifiable [] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    /// The RFC 1951 base lengths for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17,
            19, 23, 27, 31,
            35, 43, 51, 59,
            67, 83, 99, 115,
            131, 163, 195, 227,
            258
    };

    /// The RFC 1951 extra-bit counts for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4,
            5, 5, 5, 5,
            0
    };

    /// The fixed literal/length tree shared by both formats.
    private static final HuffmanTree FIXED_LITERAL_LENGTH_TREE = fixedLiteralLengthTree();

    /// The fixed 32-symbol distance tree shared by both formats.
    private static final HuffmanTree FIXED_DISTANCE_TREE = fixedDistanceTree();

    /// Selected Deflate-family format.
    private final Format format;

    /// Configured dictionary bytes restored by reset, or null.
    private final byte @Nullable @Unmodifiable [] dictionary;

    /// The little-endian incremental bit reader.
    private final BitInput bits = new BitInput();

    /// The configured decoded history window.
    private final byte[] window;

    /// Mask used to wrap history-window positions.
    private final int windowMask;

    /// Current decoder lifecycle state.
    private State state = State.ACTIVE;

    /// The next history-window position to write.
    private int windowPosition;

    /// The number of history bytes currently available to matches.
    private int availableHistory;

    /// The current block state.
    private int blockState = BLOCK_NONE;

    /// Whether the current block carries the final-block flag.
    private boolean currentBlockFinal;

    /// The remaining byte count in a stored block.
    private int storedRemaining;

    /// The current literal/length tree, or null outside a Huffman block.
    private @Nullable HuffmanTree literalLengthTree;

    /// The current distance tree, or null outside a Huffman block.
    private @Nullable HuffmanTree distanceTree;

    /// The number of pending bytes in the current history match.
    private int matchRemaining;

    /// The backward distance of the current history match.
    private int matchDistance;

    /// Whether the final block has ended.
    private boolean endReached;

    /// The next field or symbol to parse.
    private ParseState parseState = ParseState.BLOCK_HEADER;

    /// The literal/length alphabet size declared by the current dynamic header.
    private int literalCount;

    /// The distance alphabet size declared by the current dynamic header.
    private int distanceCount;

    /// The number of declared code-length alphabet entries.
    private int codeLengthCount;

    /// The next code-length alphabet entry to read.
    private int codeLengthPosition;

    /// The next combined data-tree code length to expand.
    private int lengthPosition;

    /// The previously expanded code length.
    private int previousLength;

    /// A code-length repeat waiting for its extra bits.
    private int repeatSymbol;

    /// A length symbol waiting for its extra bits.
    private int lengthIndex;

    /// The complete length waiting for its distance.
    private int pendingLength;

    /// The base of a distance waiting for its extra bits.
    private int distanceBase;

    /// The number of extra bits in the pending distance.
    private int distanceExtraBits;

    /// Reused code-length alphabet lengths.
    private final int[] codeLengths = new int[19];

    /// Reused combined literal/length and distance lengths.
    private final int[] combinedLengths = new int[320];

    /// Reused literal/length lengths, including reserved symbols.
    private final int[] literalLengths = new int[288];

    /// Reused distance lengths, including Deflate64 symbols.
    private final int[] distanceLengths = new int[32];

    /// Reused dynamic code-length tree.
    private final HuffmanTree dynamicCodeLengths;

    /// Reused dynamic literal/length tree.
    private final HuffmanTree dynamicLiterals;

    /// Reused dynamic distance tree.
    private final HuffmanTree dynamicDistances;

    /// Resumable grammar fields; each transition occurs only after its required bits are available.
    @NotNullByDefault
    private enum ParseState {
        /// Final flag and block type.
        BLOCK_HEADER,
        /// Byte-aligned stored-block length.
        STORED_LENGTH,
        /// Stored-block length complement.
        STORED_COMPLEMENT,
        /// Stored-block payload.
        STORED_DATA,
        /// Dynamic alphabet sizes.
        DYNAMIC_COUNTS,
        /// Code-length alphabet entries.
        DYNAMIC_CODE_LENGTHS,
        /// Combined data-tree lengths.
        DYNAMIC_SYMBOL,
        /// Extra bits for a code-length repeat.
        DYNAMIC_REPEAT,
        /// A literal, end-of-block, or length symbol.
        HUFFMAN_SYMBOL,
        /// Extra bits for the pending length.
        LENGTH_EXTRA,
        /// The distance symbol of a match.
        DISTANCE_SYMBOL,
        /// Extra bits for the pending distance.
        DISTANCE_EXTRA
    }

    /// Creates a decoder with the selected format's maximum history window.
    ///
    /// @param format selected bitstream semantics
    /// @param dictionary initial history content, or null
    public DeflateDecoderEngine(Format format, byte @Nullable [] dictionary) {
        this(format, dictionary, Objects.requireNonNull(format, "format").windowSize());
    }

    /// Creates a decoder with a power-of-two history window bounded by the selected format.
    ///
    /// This supports container formats such as zlib whose header may declare a smaller window than raw Deflate allows.
    ///
    /// @param format selected bitstream semantics
    /// @param dictionary initial history content, or null
    /// @param windowSize effective history-window size
    public DeflateDecoderEngine(
            Format format,
            byte @Nullable [] dictionary,
            int windowSize
    ) {
        this.format = Objects.requireNonNull(format, "format");
        if (windowSize <= 0
                || windowSize > format.windowSize()
                || (windowSize & (windowSize - 1)) != 0) {
            throw new IllegalArgumentException(
                    format.displayName() + " window size must be a positive power of two no larger than "
                            + format.windowSize()
            );
        }
        this.dictionary = dictionary != null ? dictionary.clone() : null;
        this.window = new byte[windowSize];
        this.windowMask = windowSize - 1;
        dynamicCodeLengths = new HuffmanTree(19, format.displayName() + " code-length");
        dynamicLiterals = new HuffmanTree(288, format.displayName() + " literal/length");
        dynamicDistances = new HuffmanTree(32, format.displayName() + " distance");
        restoreDictionary();
    }

    /// Decodes source bytes until input, output space, or the stream boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Finishes decoding after all source bytes have been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Implements decoding with the selected source-completion state.
    private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            if (matchRemaining > 0) {
                copyMatch(target);
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                continue;
            }
            if (blockState == BLOCK_STORED && storedRemaining > 0) {
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                if (!source.hasRemaining()) {
                    if (endOfInput) {
                        throw new EOFException(format.truncatedMessage());
                    }
                    return CodecOutcome.NEEDS_INPUT;
                }
                copyStored(source, target);
                continue;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (parseState == ParseState.HUFFMAN_SYMBOL) {
                // Consecutive literals do not need to re-enter the block-header and match grammar.
                HuffmanTree tree = Objects.requireNonNull(literalLengthTree);
                try {
                    while (target.hasRemaining()) {
                        int symbol = tree.decode(bits, source, endOfInput, format);
                        if (symbol >= END_OF_BLOCK_SYMBOL) {
                            acceptHuffmanBoundary(symbol);
                            break;
                        }
                        recordDecodedByte(symbol);
                        target.put((byte) symbol);
                    }
                } catch (NeedsInputException exception) {
                    return CodecOutcome.NEEDS_INPUT;
                }
                continue;
            }
            int value;
            try {
                value = readDecodedByte(source, endOfInput);
            } catch (NeedsInputException exception) {
                return CodecOutcome.NEEDS_INPUT;
            }
            if (value < 0) {
                state = State.FINISHED;
                return CodecOutcome.FINISHED;
            }
            target.put((byte) value);
        }
    }

    /// Abandons the current stream and restores its configured initial history.
    @Override
    public void reset() {
        requireOpen();
        bits.reset();
        blockState = BLOCK_NONE;
        currentBlockFinal = false;
        storedRemaining = 0;
        literalLengthTree = null;
        distanceTree = null;
        matchRemaining = 0;
        matchDistance = 0;
        endReached = false;
        parseState = ParseState.BLOCK_HEADER;
        restoreDictionary();
        state = State.ACTIVE;
    }

    /// Releases decoder-owned state without consuming additional input.
    @Override
    public void close() {
        bits.reset();
        literalLengthTree = null;
        distanceTree = null;
        state = State.CLOSED;
    }

    /// Copies byte-aligned stored-block content into the history window and caller target.
    private void copyStored(ByteBuffer source, ByteBuffer target) {
        int copied = Math.min(storedRemaining, Math.min(source.remaining(), target.remaining()));
        copied = Math.min(copied, window.length - windowPosition);
        source.get(window, windowPosition, copied);
        target.put(window, windowPosition, copied);
        windowPosition = (windowPosition + copied) & windowMask;
        storedRemaining -= copied;
        availableHistory = Math.min(window.length, availableHistory + copied);
    }

    /// Copies as much of the active LZ match as the caller target can accept.
    private void copyMatch(ByteBuffer target) {
        while (matchRemaining > 0 && target.hasRemaining()) {
            int sourcePosition = (windowPosition - matchDistance) & windowMask;
            int copied = Math.min(matchRemaining, target.remaining());
            copied = Math.min(copied, window.length - windowPosition);
            if (matchDistance == 1) {
                Arrays.fill(window, windowPosition, windowPosition + copied, window[sourcePosition]);
            } else {
                int seed = Math.min(copied, Math.min(matchDistance, window.length - sourcePosition));
                System.arraycopy(window, sourcePosition, window, windowPosition, seed);
                if (seed < matchDistance) {
                    copied = seed;
                } else {
                    // Every extension reads only bytes generated earlier in this contiguous destination range.
                    int populated = seed;
                    while (populated < copied) {
                        int extension = Math.min(populated, copied - populated);
                        System.arraycopy(window, windowPosition, window, windowPosition + populated, extension);
                        populated += extension;
                    }
                }
            }
            target.put(window, windowPosition, copied);
            windowPosition = (windowPosition + copied) & windowMask;
            matchRemaining -= copied;
            availableHistory = Math.min(window.length, availableHistory + copied);
        }
    }

    /// Resumes the current grammar field and returns one decoded byte or the stream-end sentinel.
    private int readDecodedByte(ByteBuffer source, boolean endOfInput) throws IOException {
        while (true) {
            if (matchRemaining > 0) {
                int value = Byte.toUnsignedInt(window[(windowPosition - matchDistance) & windowMask]);
                matchRemaining--;
                recordDecodedByte(value);
                return value;
            }
            switch (parseState) {
                case BLOCK_HEADER -> {
                    if (endReached) return -1;
                    int header = bits.readBits(3, source, endOfInput, format);
                    currentBlockFinal = (header & 1) != 0;
                    switch (header >>> 1) {
                        case 0 -> {
                            bits.alignToByte();
                            parseState = ParseState.STORED_LENGTH;
                        }
                        case 1 -> {
                            literalLengthTree = FIXED_LITERAL_LENGTH_TREE;
                            distanceTree = FIXED_DISTANCE_TREE;
                            blockState = BLOCK_HUFFMAN;
                            parseState = ParseState.HUFFMAN_SYMBOL;
                        }
                        case 2 -> parseState = ParseState.DYNAMIC_COUNTS;
                        default -> throw malformed("reserved block type");
                    }
                }
                case STORED_LENGTH -> {
                    storedRemaining = bits.readBits(16, source, endOfInput, format);
                    parseState = ParseState.STORED_COMPLEMENT;
                }
                case STORED_COMPLEMENT -> {
                    if ((storedRemaining ^ 0xffff) != bits.readBits(16, source, endOfInput, format)) {
                        throw malformed("stored block length complement does not match");
                    }
                    literalLengthTree = null;
                    distanceTree = null;
                    blockState = BLOCK_STORED;
                    parseState = ParseState.STORED_DATA;
                }
                case STORED_DATA -> {
                    if (storedRemaining == 0) {
                        finishBlock();
                    } else {
                        int value = bits.readBits(8, source, endOfInput, format);
                        storedRemaining--;
                        recordDecodedByte(value);
                        return value;
                    }
                }
                case DYNAMIC_COUNTS -> {
                    int counts = bits.readBits(14, source, endOfInput, format);
                    literalCount = (counts & 31) + 257;
                    distanceCount = (counts >>> 5 & 31) + 1;
                    codeLengthCount = (counts >>> 10) + 4;
                    codeLengthPosition = 0;
                    Arrays.fill(codeLengths, 0);
                    parseState = ParseState.DYNAMIC_CODE_LENGTHS;
                }
                case DYNAMIC_CODE_LENGTHS -> {
                    while (codeLengthPosition < codeLengthCount) {
                        int length = bits.readBits(3, source, endOfInput, format);
                        codeLengths[CODE_LENGTH_ORDER[codeLengthPosition++]] = length;
                    }
                    dynamicCodeLengths.rebuild(codeLengths, false);
                    lengthPosition = 0;
                    previousLength = 0;
                    parseState = ParseState.DYNAMIC_SYMBOL;
                }
                case DYNAMIC_SYMBOL -> {
                    int total = literalCount + distanceCount;
                    while (lengthPosition < total && parseState == ParseState.DYNAMIC_SYMBOL) {
                        int symbol = dynamicCodeLengths.decode(bits, source, endOfInput, format);
                        if (symbol <= 15) {
                            combinedLengths[lengthPosition++] = symbol;
                            previousLength = symbol;
                        } else if (symbol <= 18) {
                            if (symbol == 16 && lengthPosition == 0) {
                                throw malformed("repeat code 16 has no previous length");
                            }
                            repeatSymbol = symbol;
                            parseState = ParseState.DYNAMIC_REPEAT;
                        } else {
                            throw malformed("code-length symbol " + symbol + " is invalid");
                        }
                    }
                    if (lengthPosition == total) {
                        installDynamicTrees();
                        parseState = ParseState.HUFFMAN_SYMBOL;
                    }
                }
                case DYNAMIC_REPEAT -> {
                    int extra = repeatSymbol == 16 ? 2 : repeatSymbol == 17 ? 3 : 7;
                    int repeat = bits.readBits(extra, source, endOfInput, format)
                            + (repeatSymbol == 18 ? 11 : 3);
                    requireRepeatCapacity(lengthPosition, repeat, literalCount + distanceCount);
                    if (repeatSymbol != 16) previousLength = 0;
                    Arrays.fill(combinedLengths, lengthPosition, lengthPosition + repeat, previousLength);
                    lengthPosition += repeat;
                    parseState = ParseState.DYNAMIC_SYMBOL;
                }
                case HUFFMAN_SYMBOL -> {
                    int symbol = Objects.requireNonNull(literalLengthTree).decode(bits, source, endOfInput, format);
                    if (symbol < END_OF_BLOCK_SYMBOL) {
                        recordDecodedByte(symbol);
                        return symbol;
                    }
                    acceptHuffmanBoundary(symbol);
                }
                case LENGTH_EXTRA -> {
                    if (lengthIndex == LAST_LENGTH_SYMBOL - FIRST_LENGTH_SYMBOL && format == Format.DEFLATE64) {
                        pendingLength = 3 + bits.readBits(16, source, endOfInput, format);
                    } else {
                        pendingLength = LENGTH_BASES[lengthIndex]
                                + bits.readBits(LENGTH_EXTRA_BITS[lengthIndex], source, endOfInput, format);
                    }
                    parseState = ParseState.DISTANCE_SYMBOL;
                }
                case DISTANCE_SYMBOL -> {
                    int symbol = Objects.requireNonNull(distanceTree).decode(bits, source, endOfInput, format);
                    if (symbol > format.maximumDistanceSymbol()) {
                        throw malformed("distance symbol " + symbol + " is invalid");
                    }
                    distanceExtraBits = symbol < 4 ? 0 : (symbol >>> 1) - 1;
                    distanceBase = symbol < 4 ? symbol + 1 : ((2 + (symbol & 1)) << distanceExtraBits) + 1;
                    parseState = ParseState.DISTANCE_EXTRA;
                }
                case DISTANCE_EXTRA -> {
                    int distance = distanceBase + bits.readBits(distanceExtraBits, source, endOfInput, format);
                    if (distance <= 0 || distance > availableHistory) {
                        throw malformed("match distance " + distance + " exceeds available history " + availableHistory);
                    }
                    matchDistance = distance;
                    matchRemaining = pendingLength;
                    parseState = ParseState.HUFFMAN_SYMBOL;
                }
            }
        }
    }

    /// Advances the grammar after an end-of-block or validated length symbol.
    private void acceptHuffmanBoundary(int symbol) throws IOException {
        if (symbol == END_OF_BLOCK_SYMBOL) {
            finishBlock();
        } else {
            if (symbol > LAST_LENGTH_SYMBOL) {
                throw malformed("literal/length symbol " + symbol + " is invalid");
            }
            lengthIndex = symbol - FIRST_LENGTH_SYMBOL;
            parseState = ParseState.LENGTH_EXTRA;
        }
    }

    /// Installs validated data trees after the complete dynamic header has been expanded.
    private void installDynamicTrees() throws IOException {
        Arrays.fill(literalLengths, 0);
        Arrays.fill(distanceLengths, 0);
        System.arraycopy(combinedLengths, 0, literalLengths, 0, literalCount);
        System.arraycopy(combinedLengths, literalCount, distanceLengths, 0, distanceCount);
        if (literalLengths[END_OF_BLOCK_SYMBOL] == 0) {
            throw malformed("dynamic block has no end-of-block symbol");
        }
        dynamicLiterals.rebuild(literalLengths, false);
        dynamicDistances.rebuild(distanceLengths, true);
        literalLengthTree = dynamicLiterals;
        distanceTree = dynamicDistances;
        blockState = BLOCK_HUFFMAN;
    }

    /// Rejects a code-length repeat that extends beyond the target arrays.
    private void requireRepeatCapacity(int position, int repeat, int total) throws IOException {
        if (repeat > total - position) {
            throw malformed("code-length repeat exceeds the dynamic header");
        }
    }

    /// Completes the current block and records final-stream state.
    private void finishBlock() {
        blockState = BLOCK_NONE;
        literalLengthTree = null;
        distanceTree = null;
        storedRemaining = 0;
        parseState = ParseState.BLOCK_HEADER;
        if (currentBlockFinal) {
            endReached = true;
        }
    }

    /// Adds one decoded byte to the circular history window.
    private void recordDecodedByte(int value) {
        window[windowPosition] = (byte) value;
        windowPosition = (windowPosition + 1) & windowMask;
        if (availableHistory < window.length) {
            availableHistory++;
        }
    }

    /// Restores history bytes from the immutable configured dictionary.
    private void restoreDictionary() {
        byte @Nullable [] selectedDictionary = dictionary;
        if (selectedDictionary == null || selectedDictionary.length == 0) {
            windowPosition = 0;
            availableHistory = 0;
            return;
        }
        int retained = Math.min(selectedDictionary.length, window.length);
        System.arraycopy(selectedDictionary, selectedDictionary.length - retained, window, 0, retained);
        windowPosition = retained & windowMask;
        availableHistory = retained;
    }

    /// Creates a consistently prefixed malformed-stream exception.
    private IOException malformed(String detail) {
        return new IOException("Invalid " + format.displayName() + " stream: " + detail);
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException(format.displayName() + " decoder is closed");
        }
    }

    /// Creates the fixed Deflate literal/length tree.
    private static HuffmanTree fixedLiteralLengthTree() {
        int[] lengths = new int[288];
        Arrays.fill(lengths, 0, 144, 8);
        Arrays.fill(lengths, 144, 256, 9);
        Arrays.fill(lengths, 256, 280, 7);
        Arrays.fill(lengths, 280, 288, 8);
        try {
            return HuffmanTree.create(lengths, false, "fixed Deflate literal/length");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Creates the fixed 32-symbol distance tree.
    private static HuffmanTree fixedDistanceTree() {
        int[] lengths = new int[32];
        Arrays.fill(lengths, 5);
        try {
            return HuffmanTree.create(lengths, false, "fixed Deflate distance");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Selects one Deflate-family bitstream profile.
    @NotNullByDefault
    public enum Format {
        /// RFC 1951 Deflate with a 32 KiB window and reserved distance symbols 30 and 31.
        DEFLATE("raw Deflate", "Unexpected end of raw deflate stream", 1 << 15, 29),

        /// Deflate64 with a 64 KiB window, extended distances, and an extended symbol 285.
        DEFLATE64("Deflate64", "Truncated Deflate64 stream", 1 << 16, 31);

        /// Human-readable format name used in errors.
        private final String displayName;

        /// Message used when final caller input ends before the stream boundary.
        private final String truncatedMessage;

        /// History-window size.
        private final int windowSize;

        /// Largest valid distance symbol.
        private final int maximumDistanceSymbol;

        /// Creates one immutable decoder profile.
        Format(String displayName, String truncatedMessage, int windowSize, int maximumDistanceSymbol) {
            this.displayName = displayName;
            this.truncatedMessage = truncatedMessage;
            this.windowSize = windowSize;
            this.maximumDistanceSymbol = maximumDistanceSymbol;
        }

        /// Returns the human-readable format name.
        private String displayName() {
            return displayName;
        }

        /// Returns the format-compatible truncated-stream message.
        private String truncatedMessage() {
            return truncatedMessage;
        }

        /// Returns the history-window size.
        private int windowSize() {
            return windowSize;
        }

        /// Returns the largest valid distance symbol.
        private int maximumDistanceSymbol() {
            return maximumDistanceSymbol;
        }
    }

    /// Reads least-significant-bit-first fields without replaying caller input.
    @NotNullByDefault
    private static final class BitInput {
        /// Unread packed bits, with the next bit in bit zero.
        private long buffer;

        /// The number of unread packed bits.
        private int bitCount;

        /// The node of an incomplete canonical symbol, or zero before its first bit.
        private int symbolNode;

        /// Reads an unsigned field of at most sixteen bits, retaining an incomplete field.
        private int readBits(int count, ByteBuffer source, boolean endOfInput, Format format) throws IOException {
            while (bitCount < count) appendRequiredByte(source, endOfInput, format);
            int result = (int) (buffer & ((1L << count) - 1L));
            buffer >>>= count;
            bitCount -= count;
            return result;
        }

        /// Appends exactly one byte required by an incomplete field or symbol prefix.
        private void appendRequiredByte(ByteBuffer source, boolean endOfInput, Format format) throws IOException {
            if (!source.hasRemaining()) {
                if (endOfInput) throw new EOFException(format.truncatedMessage());
                throw NeedsInputException.INSTANCE;
            }
            buffer |= Byte.toUnsignedLong(source.get()) << bitCount;
            bitCount += Byte.SIZE;
        }

        /// Discards already validated low-order bits.
        private void discardBits(int count) {
            buffer >>>= count;
            bitCount -= count;
        }

        /// Discards padding through the next byte boundary.
        private void alignToByte() {
            discardBits(bitCount & 7);
        }

        /// Restores an empty bit and symbol reader.
        private void reset() {
            buffer = 0L;
            bitCount = 0;
            symbolNode = 0;
        }
    }

    /// Builds bounded canonical trees and decodes their low-order input prefixes.
    @NotNullByDefault
    private static final class HuffmanTree {
        /// Bits covered by the root table.
        private static final int FAST_LOOKUP_BITS = 8;

        /// Mask selecting the buffered root-table prefix.
        private static final int FAST_LOOKUP_MASK = (1 << FAST_LOOKUP_BITS) - 1;

        /// Mask for the packed symbol's code length.
        private static final int FAST_LENGTH_MASK = 15;

        /// A root-table entry that cannot identify a valid symbol.
        private static final int INVALID_LOOKUP = Integer.MIN_VALUE;

        /// Zero-bit child indices for codes extending beyond the root table.
        private final int[] zeroChildren;

        /// One-bit child indices for codes extending beyond the root table.
        private final int[] oneChildren;

        /// Leaf symbols, or negative values at internal nodes.
        private final int[] symbols;

        /// Packed symbols and lengths, continuation nodes, or invalid prefixes.
        private final int[] fastLookup = new int[1 << FAST_LOOKUP_BITS];

        /// Reused counts of codes at each depth.
        private final int[] counts = new int[16];

        /// Reused next canonical codes at each depth.
        private final int[] nextCodes = new int[16];

        /// Error description fixed for this alphabet.
        private final String description;

        /// The maximum valid code length, or zero for an empty tree.
        private int maximumLength;

        /// Reserves enough nodes for a complete binary alphabet or a single one-bit code.
        private HuffmanTree(int alphabetSize, String description) {
            int capacity = Math.max(2, alphabetSize * 2);
            zeroChildren = new int[capacity];
            oneChildren = new int[capacity];
            symbols = new int[capacity];
            this.description = description;
        }

        /// Builds an independently owned tree used by a shared fixed alphabet.
        private static HuffmanTree create(int[] lengths, boolean allowEmpty, String description) throws IOException {
            HuffmanTree tree = new HuffmanTree(lengths.length, description);
            tree.rebuild(lengths, allowEmpty);
            return tree;
        }

        /// Validates lengths and replaces the active nodes without allocating additional arrays.
        private void rebuild(int[] lengths, boolean allowEmpty) throws IOException {
            Arrays.fill(counts, 0);
            int nonZeroCount = 0;
            maximumLength = 0;
            for (int length : lengths) {
                if (length < 0 || length > 15) throw new IOException(description + " code length is out of range");
                if (length != 0) {
                    counts[length]++;
                    nonZeroCount++;
                    maximumLength = Math.max(maximumLength, length);
                }
            }
            Arrays.fill(fastLookup, INVALID_LOOKUP);
            if (nonZeroCount == 0) {
                if (!allowEmpty) throw new IOException(description + " tree is empty");
                return;
            }
            int remainingCodes = 1;
            for (int length = 1; length <= 15; length++) {
                remainingCodes = (remainingCodes << 1) - counts[length];
                if (remainingCodes < 0) throw new IOException(description + " tree is oversubscribed");
            }
            if (remainingCodes != 0 && !(nonZeroCount == 1 && maximumLength == 1)) {
                throw new IOException(description + " tree is incomplete");
            }
            int code = 0;
            for (int length = 1; length <= 15; length++) {
                code = (code + counts[length - 1]) << 1;
                nextCodes[length] = code;
            }
            int nodeCount = 1;
            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int length = lengths[symbol];
                if (length == 0) continue;
                int symbolCode = nextCodes[length]++;
                int reversedCode = Integer.reverse(symbolCode) >>> (32 - length);
                if (length <= FAST_LOOKUP_BITS) {
                    int entry = symbol << 4 | length;
                    for (int prefix = reversedCode; prefix < fastLookup.length; prefix += 1 << length) {
                        fastLookup[prefix] = entry;
                    }
                    continue;
                }
                int prefix = reversedCode & FAST_LOOKUP_MASK;
                int node;
                if (fastLookup[prefix] == INVALID_LOOKUP) {
                    node = nodeCount++;
                    initializeNode(node);
                    fastLookup[prefix] = -node - 1;
                } else {
                    node = -fastLookup[prefix] - 1;
                }
                for (int depth = FAST_LOOKUP_BITS; depth < length; depth++) {
                    int bit = symbolCode >>> (length - depth - 1) & 1;
                    int child = bit == 0 ? zeroChildren[node] : oneChildren[node];
                    if (child < 0) {
                        child = nodeCount++;
                        initializeNode(child);
                        if (bit == 0) zeroChildren[node] = child;
                        else oneChildren[node] = child;
                    }
                    node = child;
                }
                symbols[node] = symbol;
            }
        }

        /// Clears one node before it becomes reachable from the current root.
        private void initializeNode(int node) {
            zeroChildren[node] = -1;
            oneChildren[node] = -1;
            symbols[node] = -1;
        }

        /// Decodes one symbol while retaining an incomplete long-code traversal.
        private int decode(BitInput input, ByteBuffer source, boolean endOfInput, Format format) throws IOException {
            if (maximumLength == 0) throw invalidCode();
            int node = input.symbolNode;
            if (node == 0) {
                while (true) {
                    int entry = fastLookup[(int) input.buffer & FAST_LOOKUP_MASK];
                    if (entry >= 0 && (entry & FAST_LENGTH_MASK) <= input.bitCount) {
                        input.discardBits(entry & FAST_LENGTH_MASK);
                        return entry >>> 4;
                    }
                    if (input.bitCount >= FAST_LOOKUP_BITS) {
                        if (entry == INVALID_LOOKUP) throw invalidCode();
                        input.discardBits(FAST_LOOKUP_BITS);
                        node = -entry - 1;
                        break;
                    }
                    if (entry == INVALID_LOOKUP && input.bitCount != 0) {
                        // Only a single one-bit alphabet may be incomplete; its unused branch is already known.
                        throw invalidCode();
                    }
                    input.appendRequiredByte(source, endOfInput, format);
                }
            }
            try {
                while (symbols[node] < 0) {
                    node = input.readBits(1, source, endOfInput, format) == 0
                            ? zeroChildren[node] : oneChildren[node];
                    if (node < 0) throw invalidCode();
                }
            } catch (NeedsInputException exception) {
                input.symbolNode = node;
                throw exception;
            }
            input.symbolNode = 0;
            return symbols[node];
        }

        /// Creates the malformed-code diagnostic for this alphabet.
        private IOException invalidCode() {
            return new IOException("Invalid " + description + " Huffman code");
        }
    }

    /// Signals temporary caller-input exhaustion without exposing it as a data error.
    @NotNullByDefault
    private static final class NeedsInputException extends IOException {
        /// Shared exception instance because temporary input exhaustion is a normal control path.
        private static final NeedsInputException INSTANCE = new NeedsInputException();

        /// Creates the shared control-flow exception with an empty stack trace.
        private NeedsInputException() {
            super("Additional Deflate input is required");
            setStackTrace(new StackTraceElement[0]);
        }
    }

    /// Tracks the explicit decoder lifecycle.
    @NotNullByDefault
    private enum State {
        /// The decoder accepts source bytes.
        ACTIVE,

        /// The stream completed and may only be reset or closed.
        FINISHED,

        /// Decoder-owned state was released.
        CLOSED
    }
}
