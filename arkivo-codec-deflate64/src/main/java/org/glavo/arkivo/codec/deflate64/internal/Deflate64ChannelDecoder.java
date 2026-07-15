// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate64.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
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

/// Decodes a raw Deflate64 stream with stored, fixed-Huffman, and dynamic-Huffman blocks.
///
/// Deflate64 retains the Deflate bitstream grammar while extending the history window to 64 KiB, defining distance
/// symbols 30 and 31, and assigning sixteen extra bits to length symbol 285.
@NotNullByDefault
public final class Deflate64ChannelDecoder implements CompressionDecoder {
    /// The Deflate64 history-window size.
    private static final int WINDOW_SIZE = 1 << 16;

    /// The mask used to wrap history-window positions.
    private static final int WINDOW_MASK = WINDOW_SIZE - 1;

    /// The compressed-source input buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

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

    /// The final Deflate64 length symbol.
    private static final int LAST_LENGTH_SYMBOL = 285;

    /// The ordered code-length alphabet used by dynamic block headers.
    private static final int @Unmodifiable [] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    /// The base lengths for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_BASES = {
            3, 4, 5, 6, 7, 8, 9, 10,
            11, 13, 15, 17,
            19, 23, 27, 31,
            35, 43, 51, 59,
            67, 83, 99, 115,
            131, 163, 195, 227,
            3
    };

    /// The extra-bit counts for symbols 257 through 285.
    private static final int @Unmodifiable [] LENGTH_EXTRA_BITS = {
            0, 0, 0, 0, 0, 0, 0, 0,
            1, 1, 1, 1,
            2, 2, 2, 2,
            3, 3, 3, 3,
            4, 4, 4, 4,
            5, 5, 5, 5,
            16
    };

    /// The fixed literal/length tree.
    private static final HuffmanTree FIXED_LITERAL_LENGTH_TREE = fixedLiteralLengthTree();

    /// The fixed 32-symbol distance tree.
    private static final HuffmanTree FIXED_DISTANCE_TREE = fixedDistanceTree();

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The little-endian bit reader over the source.
    private final BitInput bits;

    /// The 64 KiB decoded history window.
    private final byte[] window = new byte[WINDOW_SIZE];

    /// The next history-window position to write.
    private int windowPosition;

    /// The total number of decoded bytes produced.
    private long produced;

    /// The current block state.
    private int blockState = BLOCK_NONE;

    /// Whether the current block carries the final-block flag.
    private boolean currentBlockFinal;

    /// The remaining byte count in a stored block.
    private int storedRemaining;

    /// The current literal/length tree, or `null` outside a Huffman block.
    private @Nullable HuffmanTree literalLengthTree;

    /// The current distance tree, or `null` outside a Huffman block.
    private @Nullable HuffmanTree distanceTree;

    /// The number of pending bytes in the current history match.
    private int matchRemaining;

    /// The backward distance of the current history match.
    private int matchDistance;

    /// Whether the final block has ended.
    private boolean endReached;

    /// Whether this decoder has closed.
    private boolean closed;

    /// The number of decoded bytes returned through channel reads.
    private long outputBytes;

    /// Creates a decoder over a raw Deflate64 channel with explicit ownership.
    public Deflate64ChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) {
        Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.bits = new BitInput(source, INPUT_BUFFER_SIZE);
    }

    /// Reads decoded bytes directly into the target buffer.
    @Override
    public int read(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        if (!target.hasRemaining()) {
            return 0;
        }
        int start = target.position();
        while (target.hasRemaining()) {
            int value = readDecodedByte();
            if (value < 0) {
                break;
            }
            target.put((byte) value);
        }
        int count = target.position() - start;
        outputBytes += count;
        return count == 0 ? -1 : count;
    }

    /// Returns the number of logical compressed bytes consumed.
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

    /// Returns the number of decoded bytes returned through read operations.
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
        sourceCloser.close();
    }

    /// Decodes one byte or returns end-of-stream.
    private int readDecodedByte() throws IOException {
        while (true) {
            if (matchRemaining > 0) {
                int sourcePosition = (windowPosition - matchDistance) & WINDOW_MASK;
                int value = Byte.toUnsignedInt(window[sourcePosition]);
                matchRemaining--;
                recordDecodedByte(value);
                return value;
            }

            if (blockState == BLOCK_NONE) {
                if (endReached) {
                    return -1;
                }
                openBlock();
                continue;
            }

            if (blockState == BLOCK_STORED) {
                if (storedRemaining == 0) {
                    finishBlock();
                    continue;
                }
                int value = bits.readBits(8);
                storedRemaining--;
                recordDecodedByte(value);
                return value;
            }

            HuffmanTree literals = Objects.requireNonNull(literalLengthTree, "literalLengthTree");
            int symbol = literals.decode(bits);
            if (symbol < END_OF_BLOCK_SYMBOL) {
                recordDecodedByte(symbol);
                return symbol;
            }
            if (symbol == END_OF_BLOCK_SYMBOL) {
                finishBlock();
                continue;
            }
            if (symbol > LAST_LENGTH_SYMBOL) {
                throw new IOException("Invalid Deflate64 literal/length symbol: " + symbol);
            }

            int lengthIndex = symbol - FIRST_LENGTH_SYMBOL;
            int length = LENGTH_BASES[lengthIndex] + bits.readBits(LENGTH_EXTRA_BITS[lengthIndex]);
            HuffmanTree distances = Objects.requireNonNull(distanceTree, "distanceTree");
            int distanceSymbol = distances.decode(bits);
            if (distanceSymbol < 0 || distanceSymbol > 31) {
                throw new IOException("Invalid Deflate64 distance symbol: " + distanceSymbol);
            }
            int distanceExtraBits = distanceSymbol < 4 ? 0 : (distanceSymbol >>> 1) - 1;
            int distanceBase = distanceSymbol < 4
                    ? distanceSymbol + 1
                    : ((2 + (distanceSymbol & 1)) << distanceExtraBits) + 1;
            int distance = distanceBase + bits.readBits(distanceExtraBits);
            long availableHistory = Math.min(produced, WINDOW_SIZE);
            if (distance <= 0 || distance > availableHistory) {
                throw new IOException(
                        "Deflate64 match distance " + distance + " exceeds available history " + availableHistory
                );
            }
            matchDistance = distance;
            matchRemaining = length;
        }
    }

    /// Reads and initializes the next block header.
    private void openBlock() throws IOException {
        currentBlockFinal = bits.readBits(1) != 0;
        int type = bits.readBits(2);
        switch (type) {
            case 0 -> openStoredBlock();
            case 1 -> {
                literalLengthTree = FIXED_LITERAL_LENGTH_TREE;
                distanceTree = FIXED_DISTANCE_TREE;
                blockState = BLOCK_HUFFMAN;
            }
            case 2 -> openDynamicBlock();
            default -> throw new IOException("Reserved Deflate64 block type");
        }
    }

    /// Reads one stored-block length header.
    private void openStoredBlock() throws IOException {
        bits.alignToByte();
        int length = bits.readBits(16);
        int complement = bits.readBits(16);
        if ((length ^ 0xffff) != complement) {
            throw new IOException("Deflate64 stored block length complement does not match");
        }
        storedRemaining = length;
        blockState = BLOCK_STORED;
    }

    /// Reads dynamic Huffman code lengths and initializes both data trees.
    private void openDynamicBlock() throws IOException {
        int literalLengthCount = bits.readBits(5) + 257;
        int distanceCount = bits.readBits(5) + 1;
        int codeLengthCount = bits.readBits(4) + 4;

        int[] codeLengthLengths = new int[19];
        for (int index = 0; index < codeLengthCount; index++) {
            codeLengthLengths[CODE_LENGTH_ORDER[index]] = bits.readBits(3);
        }
        HuffmanTree codeLengthTree = HuffmanTree.create(
                codeLengthLengths,
                false,
                "Deflate64 code-length"
        );

        int total = literalLengthCount + distanceCount;
        int[] lengths = new int[total];
        int position = 0;
        int previousLength = 0;
        while (position < total) {
            int symbol = codeLengthTree.decode(bits);
            if (symbol <= 15) {
                lengths[position++] = symbol;
                previousLength = symbol;
            } else if (symbol == 16) {
                if (position == 0) {
                    throw new IOException("Deflate64 repeat code 16 has no previous length");
                }
                int repeat = bits.readBits(2) + 3;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, previousLength);
                position += repeat;
            } else if (symbol == 17) {
                int repeat = bits.readBits(3) + 3;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, 0);
                position += repeat;
                previousLength = 0;
            } else if (symbol == 18) {
                int repeat = bits.readBits(7) + 11;
                requireRepeatCapacity(position, repeat, total);
                Arrays.fill(lengths, position, position + repeat, 0);
                position += repeat;
                previousLength = 0;
            } else {
                throw new IOException("Invalid Deflate64 code-length symbol: " + symbol);
            }
        }

        int[] literalLengths = new int[288];
        System.arraycopy(lengths, 0, literalLengths, 0, literalLengthCount);
        if (literalLengths[END_OF_BLOCK_SYMBOL] == 0) {
            throw new IOException("Deflate64 dynamic block has no end-of-block symbol");
        }
        int[] distanceLengths = new int[32];
        System.arraycopy(lengths, literalLengthCount, distanceLengths, 0, distanceCount);
        literalLengthTree = HuffmanTree.create(
                literalLengths,
                false,
                "Deflate64 literal/length"
        );
        distanceTree = HuffmanTree.create(
                distanceLengths,
                true,
                "Deflate64 distance"
        );
        blockState = BLOCK_HUFFMAN;
    }

    /// Rejects a code-length repeat that extends beyond the target arrays.
    private static void requireRepeatCapacity(int position, int repeat, int total) throws IOException {
        if (repeat > total - position) {
            throw new IOException("Deflate64 code-length repeat exceeds the dynamic header");
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
        windowPosition = (windowPosition + 1) & WINDOW_MASK;
        produced++;
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
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
            return HuffmanTree.create(lengths, false, "fixed Deflate64 literal/length");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Creates the fixed Deflate64 distance tree.
    private static HuffmanTree fixedDistanceTree() {
        int[] lengths = new int[32];
        Arrays.fill(lengths, 5);
        try {
            return HuffmanTree.create(lengths, false, "fixed Deflate64 distance");
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    /// Reads least-significant-bit-first packed fields from the compressed source.
    @NotNullByDefault
    private static final class BitInput {
        /// The compressed source.
        private final ReadableByteChannel source;

        /// The direct compressed-input staging buffer.
        private final ByteBuffer inputBuffer;

        /// The number of logical compressed bytes consumed.
        private long byteCount;

        /// The number of compressed bytes obtained from the source.
        private long sourceByteCount;

        /// Buffered packed bits, with the next bit in bit zero.
        private long buffer;

        /// The number of available buffered bits.
        private int bitCount;

        /// Creates a bit reader.
        private BitInput(ReadableByteChannel source, int inputBufferSize) {
            this.source = Objects.requireNonNull(source, "source");
            inputBuffer = ByteBuffer.allocateDirect(inputBufferSize);
            inputBuffer.limit(0);
        }

        /// Reads an unsigned field of up to sixteen bits.
        private int readBits(int count) throws IOException {
            if (count < 0 || count > 16) {
                throw new IllegalArgumentException("Deflate64 bit count must be between 0 and 16");
            }
            while (bitCount < count) {
                int value = readByte();
                buffer |= (long) value << bitCount;
                bitCount += 8;
            }
            int result = count == 0 ? 0 : (int) (buffer & ((1L << count) - 1L));
            buffer >>>= count;
            bitCount -= count;
            return result;
        }

        /// Reads one buffered compressed byte.
        private int readByte() throws IOException {
            if (!inputBuffer.hasRemaining()) {
                inputBuffer.clear();
                int read = source.read(inputBuffer);
                if (read < 0) {
                    throw new EOFException("Truncated Deflate64 stream");
                }
                if (read == 0) {
                    throw new IOException("Deflate64 source channel made no progress");
                }
                sourceByteCount += read;
                inputBuffer.flip();
            }
            byteCount++;
            return Byte.toUnsignedInt(inputBuffer.get());
        }

        /// Returns the number of logical compressed bytes consumed.
        private long byteCount() {
            return byteCount;
        }

        /// Returns the number of compressed bytes obtained from the source.
        private long sourceByteCount() {
            return sourceByteCount;
        }

        /// Returns a read-only view of compressed bytes not yet consumed.
        private @UnmodifiableView ByteBuffer unconsumedInput() {
            return inputBuffer.asReadOnlyBuffer();
        }

        /// Discards padding through the next byte boundary.
        private void alignToByte() {
            int discard = bitCount & 7;
            buffer >>>= discard;
            bitCount -= discard;
        }
    }

    /// Decodes one canonical Huffman alphabet.
    @NotNullByDefault
    private static final class HuffmanTree {
        /// The zero-bit child index for every node.
        private final int[] zeroChildren;

        /// The one-bit child index for every node.
        private final int[] oneChildren;

        /// The symbol stored at every leaf, or `-1` for internal nodes.
        private final int[] symbols;

        /// The maximum code length.
        private final int maximumLength;

        /// A description used in malformed-stream errors.
        private final String description;

        /// Creates one immutable Huffman tree.
        private HuffmanTree(
                int[] zeroChildren,
                int[] oneChildren,
                int[] symbols,
                int maximumLength,
                String description
        ) {
            this.zeroChildren = zeroChildren;
            this.oneChildren = oneChildren;
            this.symbols = symbols;
            this.maximumLength = maximumLength;
            this.description = description;
        }

        /// Builds and validates a canonical Huffman tree.
        private static HuffmanTree create(
                int[] lengths,
                boolean allowEmpty,
                String description
        ) throws IOException {
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
                    int bit = (symbolCode >>> (length - depth - 1)) & 1;
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

        /// Decodes one symbol.
        private int decode(BitInput input) throws IOException {
            int node = 0;
            for (int depth = 0; depth < maximumLength; depth++) {
                int bit = input.readBits(1);
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
}
