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

    /// The little-endian transactional bit reader.
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

            // A shorter distance requires newly generated bytes to become history before the next copy.
            copied = Math.min(copied, matchDistance);
            copied = Math.min(copied, window.length - windowPosition);
            copied = Math.min(copied, window.length - sourcePosition);

            System.arraycopy(window, sourcePosition, window, windowPosition, copied);
            target.put(window, windowPosition, copied);
            windowPosition = (windowPosition + copied) & windowMask;
            matchRemaining -= copied;
            availableHistory = Math.min(window.length, availableHistory + copied);
        }
    }

    /// Decodes one byte or returns the end-of-stream sentinel.
    private int readDecodedByte(ByteBuffer source, boolean endOfInput) throws IOException {
        while (true) {
            if (matchRemaining > 0) {
                int sourcePosition = (windowPosition - matchDistance) & windowMask;
                int value = Byte.toUnsignedInt(window[sourcePosition]);
                matchRemaining--;
                recordDecodedByte(value);
                return value;
            }

            if (blockState == BLOCK_NONE) {
                if (endReached) {
                    return -1;
                }
                openBlock(source, endOfInput);
                continue;
            }

            if (blockState == BLOCK_STORED) {
                if (storedRemaining == 0) {
                    finishBlock();
                    continue;
                }
                int value = bits.readBits(8, source, endOfInput, format);
                storedRemaining--;
                recordDecodedByte(value);
                return value;
            }

            bits.beginTransaction();
            try {
                HuffmanTree literals = Objects.requireNonNull(literalLengthTree, "literalLengthTree");
                int symbol = literals.decode(bits, source, endOfInput, format);
                if (symbol < END_OF_BLOCK_SYMBOL) {
                    bits.commitTransaction();
                    recordDecodedByte(symbol);
                    return symbol;
                }
                if (symbol == END_OF_BLOCK_SYMBOL) {
                    bits.commitTransaction();
                    finishBlock();
                    continue;
                }
                if (symbol > LAST_LENGTH_SYMBOL) {
                    throw malformed("literal/length symbol " + symbol + " is invalid");
                }

                int lengthIndex = symbol - FIRST_LENGTH_SYMBOL;
                int length;
                if (symbol == LAST_LENGTH_SYMBOL && format == Format.DEFLATE64) {
                    length = 3 + bits.readBits(16, source, endOfInput, format);
                } else {
                    length = LENGTH_BASES[lengthIndex]
                            + bits.readBits(LENGTH_EXTRA_BITS[lengthIndex], source, endOfInput, format);
                }
                HuffmanTree distances = Objects.requireNonNull(distanceTree, "distanceTree");
                int distanceSymbol = distances.decode(bits, source, endOfInput, format);
                if (distanceSymbol < 0 || distanceSymbol > format.maximumDistanceSymbol()) {
                    throw malformed("distance symbol " + distanceSymbol + " is invalid");
                }
                int distanceExtraBits = distanceSymbol < 4 ? 0 : (distanceSymbol >>> 1) - 1;
                int distanceBase = distanceSymbol < 4
                        ? distanceSymbol + 1
                        : ((2 + (distanceSymbol & 1)) << distanceExtraBits) + 1;
                int distance = distanceBase + bits.readBits(distanceExtraBits, source, endOfInput, format);
                if (distance <= 0 || distance > availableHistory) {
                    throw malformed(
                            "match distance " + distance + " exceeds available history " + availableHistory
                    );
                }
                bits.commitTransaction();
                matchDistance = distance;
                matchRemaining = length;
            } catch (NeedsInputException exception) {
                bits.rollbackTransaction();
                throw exception;
            } catch (IOException | RuntimeException | Error exception) {
                bits.abortTransaction();
                throw exception;
            }
        }
    }

    /// Reads and initializes the next complete block header transactionally.
    private void openBlock(ByteBuffer source, boolean endOfInput) throws IOException {
        bits.beginTransaction();
        try {
            boolean finalBlock = bits.readBits(1, source, endOfInput, format) != 0;
            int type = bits.readBits(2, source, endOfInput, format);
            switch (type) {
                case 0 -> {
                    bits.alignToByte();
                    int length = bits.readBits(16, source, endOfInput, format);
                    int complement = bits.readBits(16, source, endOfInput, format);
                    if ((length ^ 0xffff) != complement) {
                        throw malformed("stored block length complement does not match");
                    }
                    storedRemaining = length;
                    literalLengthTree = null;
                    distanceTree = null;
                    blockState = BLOCK_STORED;
                }
                case 1 -> {
                    literalLengthTree = FIXED_LITERAL_LENGTH_TREE;
                    distanceTree = FIXED_DISTANCE_TREE;
                    blockState = BLOCK_HUFFMAN;
                }
                case 2 -> {
                    HuffmanTrees trees = readDynamicTrees(source, endOfInput);
                    literalLengthTree = trees.literalLengthTree();
                    distanceTree = trees.distanceTree();
                    blockState = BLOCK_HUFFMAN;
                }
                default -> throw malformed("reserved block type");
            }
            currentBlockFinal = finalBlock;
            bits.commitTransaction();
        } catch (NeedsInputException exception) {
            bits.rollbackTransaction();
            throw exception;
        } catch (IOException | RuntimeException | Error exception) {
            bits.abortTransaction();
            throw exception;
        }
    }

    /// Reads and validates both canonical trees from one dynamic block header.
    private HuffmanTrees readDynamicTrees(ByteBuffer source, boolean endOfInput) throws IOException {
        int literalLengthCount = bits.readBits(5, source, endOfInput, format) + 257;
        int distanceCount = bits.readBits(5, source, endOfInput, format) + 1;
        int codeLengthCount = bits.readBits(4, source, endOfInput, format) + 4;

        int[] codeLengthLengths = new int[19];
        for (int index = 0; index < codeLengthCount; index++) {
            codeLengthLengths[CODE_LENGTH_ORDER[index]] = bits.readBits(3, source, endOfInput, format);
        }
        HuffmanTree codeLengthTree = HuffmanTree.create(
                codeLengthLengths,
                false,
                format.displayName() + " code-length"
        );

        int total = literalLengthCount + distanceCount;
        int[] lengths = new int[total];
        int position = 0;
        int previousLength = 0;
        while (position < total) {
            int symbol = codeLengthTree.decode(bits, source, endOfInput, format);
            if (symbol <= 15) {
                lengths[position++] = symbol;
                previousLength = symbol;
            } else if (symbol == 16) {
                if (position == 0) {
                    throw malformed("repeat code 16 has no previous length");
                }
                int repeat = bits.readBits(2, source, endOfInput, format) + 3;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, previousLength);
                position += repeat;
            } else if (symbol == 17) {
                int repeat = bits.readBits(3, source, endOfInput, format) + 3;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, 0);
                position += repeat;
                previousLength = 0;
            } else if (symbol == 18) {
                int repeat = bits.readBits(7, source, endOfInput, format) + 11;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, 0);
                position += repeat;
                previousLength = 0;
            } else {
                throw malformed("code-length symbol " + symbol + " is invalid");
            }
        }

        int[] literalLengths = new int[288];
        System.arraycopy(lengths, 0, literalLengths, 0, literalLengthCount);
        if (literalLengths[END_OF_BLOCK_SYMBOL] == 0) {
            throw malformed("dynamic block has no end-of-block symbol");
        }
        int[] distanceLengths = new int[32];
        System.arraycopy(lengths, literalLengthCount, distanceLengths, 0, distanceCount);
        return new HuffmanTrees(
                HuffmanTree.create(literalLengths, false, format.displayName() + " literal/length"),
                HuffmanTree.create(distanceLengths, true, format.displayName() + " distance")
        );
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

    /// Holds both trees parsed from a dynamic block header.
    ///
    /// @param literalLengthTree literal and length tree
    /// @param distanceTree distance tree
    @NotNullByDefault
    private record HuffmanTrees(HuffmanTree literalLengthTree, HuffmanTree distanceTree) {
    }

    /// Reads least-significant-bit-first fields with replayable input transactions.
    @NotNullByDefault
    private static final class BitInput {
        /// Buffered packed bits, with the next bit in bit zero.
        private long buffer;

        /// The number of available buffered bits.
        private int bitCount;

        /// Owned bytes consumed during an incomplete transaction.
        private byte[] replay = new byte[32];

        /// Number of valid bytes in the replay array.
        private int replaySize;

        /// Next replay byte used by the active transaction.
        private int replayPosition;

        /// Whether a parser transaction is active.
        private boolean transactionActive;

        /// Packed-bit value at the active transaction boundary.
        private long transactionBuffer;

        /// Available-bit count at the active transaction boundary.
        private int transactionBitCount;

        /// Begins or retries one parser transaction.
        private void beginTransaction() {
            if (transactionActive) {
                throw new IllegalStateException("Nested Deflate bit transactions are not supported");
            }
            transactionActive = true;
            transactionBuffer = buffer;
            transactionBitCount = bitCount;
            replayPosition = 0;
        }

        /// Commits all bits and owned source bytes consumed by the active transaction.
        private void commitTransaction() {
            requireTransaction();
            transactionActive = false;
            replaySize = 0;
            replayPosition = 0;
        }

        /// Restores packed bits while retaining copied source bytes for the next call.
        private void rollbackTransaction() {
            requireTransaction();
            buffer = transactionBuffer;
            bitCount = transactionBitCount;
            replayPosition = 0;
            transactionActive = false;
        }

        /// Abandons a malformed transaction and discards its replay storage.
        private void abortTransaction() {
            if (transactionActive) {
                buffer = transactionBuffer;
                bitCount = transactionBitCount;
                transactionActive = false;
            }
            replaySize = 0;
            replayPosition = 0;
        }

        /// Reads an unsigned field of up to sixteen bits.
        private int readBits(
                int count,
                ByteBuffer source,
                boolean endOfInput,
                Format format
        ) throws IOException {
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("Deflate bit count must be between 0 and 16");
            }
            while (bitCount < count) {
                int value = readByte(source, endOfInput, format);
                buffer |= (long) value << bitCount;
                bitCount += 8;
            }
            int result = count == 0 ? 0 : (int) (buffer & ((1L << count) - 1L));
            buffer >>>= count;
            bitCount -= count;
            return result;
        }

        /// Returns the number of bits already buffered without reading caller input.
        private int availableBitCount() {
            return bitCount;
        }

        /// Returns the requested low-order buffered bits without consuming them.
        private int peekBits(int count) {
            if (count < 0 || count > bitCount || count > 16) {
                throw new IllegalArgumentException("Unavailable Deflate peek bit count: " + count);
            }
            return count == 0 ? 0 : (int) (buffer & ((1L << count) - 1L));
        }

        /// Discards bits already proven to belong to one decoded value.
        private void discardBits(int count) {
            if (count < 0 || count > bitCount) {
                throw new IllegalArgumentException("Unavailable Deflate discard bit count: " + count);
            }
            buffer >>>= count;
            bitCount -= count;
        }

        /// Appends one required source byte after the current bits were proven to be an incomplete prefix.
        private void appendRequiredByte(ByteBuffer source, boolean endOfInput, Format format) throws IOException {
            int value = readByte(source, endOfInput, format);
            buffer |= (long) value << bitCount;
            bitCount += 8;
        }

        /// Reads one byte from transaction replay or the current caller-owned source.
        private int readByte(ByteBuffer source, boolean endOfInput, Format format) throws IOException {
            if (transactionActive && replayPosition < replaySize) {
                return Byte.toUnsignedInt(replay[replayPosition++]);
            }
            if (!source.hasRemaining()) {
                if (endOfInput) {
                    throw new EOFException(format.truncatedMessage());
                }
                throw NeedsInputException.INSTANCE;
            }
            int value = Byte.toUnsignedInt(source.get());
            if (transactionActive) {
                ensureReplayCapacity(replaySize + 1);
                replay[replaySize++] = (byte) value;
                replayPosition++;
            }
            return value;
        }

        /// Discards padding through the next byte boundary.
        private void alignToByte() {
            int discard = bitCount & 7;
            buffer >>>= discard;
            bitCount -= discard;
        }

        /// Restores an empty bit-reader session.
        private void reset() {
            buffer = 0L;
            bitCount = 0;
            replaySize = 0;
            replayPosition = 0;
            transactionActive = false;
            transactionBuffer = 0L;
            transactionBitCount = 0;
        }

        /// Expands owned transaction replay storage when necessary.
        private void ensureReplayCapacity(int requiredCapacity) {
            if (requiredCapacity > replay.length) {
                replay = Arrays.copyOf(replay, Math.max(requiredCapacity, replay.length << 1));
            }
        }

        /// Requires an active parser transaction.
        private void requireTransaction() {
            if (!transactionActive) {
                throw new IllegalStateException("No Deflate bit transaction is active");
            }
        }
    }

    /// Decodes one canonical Huffman alphabet.
    @NotNullByDefault
    private static final class HuffmanTree {
        /// The number of root bits decoded by one fast lookup.
        private static final int FAST_LOOKUP_BITS = 8;

        /// The mask used to unpack a fast lookup symbol length.
        private static final int FAST_LENGTH_MASK = (1 << 4) - 1;

        /// The lookup sentinel for an invalid bit prefix.
        private static final int INVALID_LOOKUP = Integer.MIN_VALUE;

        /// The zero-bit child index for every node.
        private final int @Unmodifiable [] zeroChildren;

        /// The one-bit child index for every node.
        private final int @Unmodifiable [] oneChildren;

        /// The symbol stored at every leaf, or `-1` for internal nodes.
        private final int @Unmodifiable [] symbols;

        /// Packed root symbols and lengths, continuation nodes, or invalid-prefix sentinels.
        private final int @Unmodifiable [] fastLookup;

        /// The maximum code length.
        private final int maximumLength;

        /// Description used in malformed-stream errors.
        private final String description;

        /// Creates one immutable Huffman tree.
        private HuffmanTree(
                int @Unmodifiable [] zeroChildren,
                int @Unmodifiable [] oneChildren,
                int @Unmodifiable [] symbols,
                int maximumLength,
                String description
        ) {
            this.zeroChildren = zeroChildren;
            this.oneChildren = oneChildren;
            this.symbols = symbols;
            this.fastLookup = createFastLookup(zeroChildren, oneChildren, symbols);
            this.maximumLength = maximumLength;
            this.description = description;
        }

        /// Builds the eight-bit root lookup without changing the canonical tree representation.
        private static int @Unmodifiable [] createFastLookup(
                int @Unmodifiable [] zeroChildren,
                int @Unmodifiable [] oneChildren,
                int @Unmodifiable [] symbols
        ) {
            int[] lookup = new int[1 << FAST_LOOKUP_BITS];
            for (int prefix = 0; prefix < lookup.length; prefix++) {
                int node = 0;
                int entry = INVALID_LOOKUP;
                for (int depth = 0; depth < FAST_LOOKUP_BITS; depth++) {
                    node = (prefix >>> depth & 1) == 0 ? zeroChildren[node] : oneChildren[node];
                    if (node < 0) {
                        break;
                    }
                    int symbol = symbols[node];
                    if (symbol >= 0) {
                        entry = symbol << 4 | depth + 1;
                        break;
                    }
                }
                if (entry == INVALID_LOOKUP && node >= 0) {
                    entry = -node - 1;
                }
                lookup[prefix] = entry;
            }
            return lookup;
        }

        /// Builds and validates a canonical Huffman tree.
        private static HuffmanTree create(int[] lengths, boolean allowEmpty, String description) throws IOException {
            int[] counts = new int[16];
            int nonZeroCount = 0;
            int maximumLength = 0;
            for (int length : lengths) {
                if (length < 0 || length > 15) {
                    throw new IOException(description + " code length is out of range");
                }
                if (length != 0) {
                    counts[length]++;
                    nonZeroCount++;
                    maximumLength = Math.max(maximumLength, length);
                }
            }
            if (nonZeroCount == 0) {
                if (!allowEmpty) {
                    throw new IOException(description + " tree is empty");
                }
                return empty(description);
            }

            int remainingCodes = 1;
            for (int length = 1; length <= 15; length++) {
                remainingCodes = (remainingCodes << 1) - counts[length];
                if (remainingCodes < 0) {
                    throw new IOException(description + " tree is oversubscribed");
                }
            }
            if (remainingCodes != 0 && !(nonZeroCount == 1 && maximumLength == 1)) {
                throw new IOException(description + " tree is incomplete");
            }

            int[] nextCodes = new int[16];
            int code = 0;
            for (int length = 1; length <= 15; length++) {
                code = (code + counts[length - 1]) << 1;
                nextCodes[length] = code;
            }

            int capacity = 1 + nonZeroCount * maximumLength;
            int[] zeroChildren = new int[capacity];
            int[] oneChildren = new int[capacity];
            int[] symbols = new int[capacity];
            Arrays.fill(zeroChildren, -1);
            Arrays.fill(oneChildren, -1);
            Arrays.fill(symbols, -1);
            int nodeCount = 1;

            for (int symbol = 0; symbol < lengths.length; symbol++) {
                int length = lengths[symbol];
                if (length == 0) {
                    continue;
                }
                int symbolCode = nextCodes[length]++;
                int node = 0;
                for (int depth = 0; depth < length; depth++) {
                    if (symbols[node] >= 0) {
                        throw new IOException(description + " tree has a prefix collision");
                    }
                    int bit = symbolCode >>> (length - depth - 1) & 1;
                    int child = bit == 0 ? zeroChildren[node] : oneChildren[node];
                    if (child < 0) {
                        child = nodeCount++;
                        if (bit == 0) {
                            zeroChildren[node] = child;
                        } else {
                            oneChildren[node] = child;
                        }
                    }
                    node = child;
                }
                if (symbols[node] >= 0 || zeroChildren[node] >= 0 || oneChildren[node] >= 0) {
                    throw new IOException(description + " tree has a duplicate code");
                }
                symbols[node] = symbol;
            }
            return new HuffmanTree(
                    Arrays.copyOf(zeroChildren, nodeCount),
                    Arrays.copyOf(oneChildren, nodeCount),
                    Arrays.copyOf(symbols, nodeCount),
                    maximumLength,
                    description
            );
        }

        /// Creates an empty tree that rejects every attempted symbol.
        private static HuffmanTree empty(String description) {
            return new HuffmanTree(new int[]{-1}, new int[]{-1}, new int[]{-1}, 0, description);
        }

        /// Decodes one symbol from the transactional bit input.
        private int decode(
                BitInput input,
                ByteBuffer source,
                boolean endOfInput,
                Format format
        ) throws IOException {
            int available = input.availableBitCount();
            if (available < FAST_LOOKUP_BITS) {
                int prefix = input.peekBits(available);
                int node = 0;
                for (int depth = 0; depth < available; depth++) {
                    node = (prefix >>> depth & 1) == 0 ? zeroChildren[node] : oneChildren[node];
                    if (node < 0) {
                        throw new IOException("Invalid " + description + " Huffman code");
                    }
                    int symbol = symbols[node];
                    if (symbol >= 0) {
                        input.discardBits(depth + 1);
                        return symbol;
                    }
                }
                if (available >= maximumLength) {
                    throw new IOException("Invalid " + description + " Huffman code");
                }

                // The complete buffered prefix is an internal node, so at least one more source bit is required.
                input.appendRequiredByte(source, endOfInput, format);
            }

            int entry = fastLookup[input.peekBits(FAST_LOOKUP_BITS)];
            if (entry == INVALID_LOOKUP) {
                throw new IOException("Invalid " + description + " Huffman code");
            }
            if (entry >= 0) {
                int length = entry & FAST_LENGTH_MASK;
                input.discardBits(length);
                return entry >>> 4;
            }

            int node = -entry - 1;
            input.discardBits(FAST_LOOKUP_BITS);
            for (int depth = FAST_LOOKUP_BITS; depth < maximumLength; depth++) {
                int bit = input.readBits(1, source, endOfInput, format);
                node = bit == 0 ? zeroChildren[node] : oneChildren[node];
                if (node < 0) {
                    throw new IOException("Invalid " + description + " Huffman code");
                }
                int symbol = symbols[node];
                if (symbol >= 0) {
                    return symbol;
                }
            }
            throw new IOException("Invalid " + description + " Huffman code");
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
