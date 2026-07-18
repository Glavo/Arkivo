// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionMemoryLimitException;
import org.glavo.arkivo.codec.lz4.LZ4BlockSize;
import org.glavo.arkivo.codec.lz4.LZ4Format;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally decodes one standard or skippable LZ4 frame without retaining caller-owned buffers.
@NotNullByDefault
public final class LZ4FrameDecoder implements CompressionDecoder.Framed {
    /// Legacy LZ4 frame magic, which this decoder deliberately does not interpret as standard framing.
    private static final long LEGACY_FRAME_MAGIC = 0x184c_2102L;

    /// Maximum match distance retained between linked blocks.
    private static final int MAXIMUM_HISTORY_SIZE = 65_535;

    /// Maximum standard descriptor size including its checksum byte.
    private static final int MAXIMUM_DESCRIPTOR_SIZE = 15;

    /// Empty decoded output.
    private static final ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Empty prefix history.
    private static final byte[] EMPTY_HISTORY = new byte[0];

    /// Maximum permitted match distance, or the unlimited sentinel.
    private final long maximumWindowSize;

    /// Maximum permitted working memory, or the unlimited sentinel.
    private final long maximumMemorySize;

    /// Whether block and content checksums are verified.
    private final boolean verifyChecksums;

    /// Incrementally collected frame magic.
    private final ByteBuffer magic = ByteBuffer.allocate(Integer.BYTES);

    /// Incrementally collected frame descriptor.
    private final ByteBuffer descriptor = ByteBuffer.allocate(MAXIMUM_DESCRIPTOR_SIZE);

    /// Incrementally collected skippable-frame payload size.
    private final ByteBuffer skippableSize = ByteBuffer.allocate(Integer.BYTES);

    /// Incrementally collected physical block header.
    private final ByteBuffer blockHeader = ByteBuffer.allocate(Integer.BYTES);

    /// Incrementally collected block or content checksum.
    private final ByteBuffer checksum = ByteBuffer.allocate(Integer.BYTES);

    /// Streaming decoded-content checksum state.
    private final XXHash32 contentHash = new XXHash32();

    /// Maximum decoded size of one block from the current descriptor.
    private int maximumBlockSize;

    /// Whether every block starts with an empty history window.
    private boolean independentBlocks;

    /// Whether every physical block is followed by a checksum.
    private boolean blockChecksum;

    /// Whether the frame trailer contains a decoded-content checksum.
    private boolean contentChecksum;

    /// Expected decoded frame size, or the unknown-size sentinel.
    private long expectedContentSize = CompressionCodec.UNKNOWN_SIZE;

    /// Number of decoded bytes in the current standard frame.
    private long decodedContentSize;

    /// Bytes remaining in the current skippable-frame payload.
    private long skippableBytes;

    /// Current physical block payload, or null outside payload processing.
    private byte @Nullable [] payload;

    /// Number of collected physical block payload bytes.
    private int payloadSize;

    /// Whether the current physical block is stored without compression.
    private boolean uncompressedBlock;

    /// Prefix history retained for the next linked block.
    private byte[] history = EMPTY_HISTORY;

    /// Decoded bytes awaiting caller-owned target space.
    private ByteBuffer output = EMPTY_OUTPUT;

    /// Current frame decoder lifecycle state.
    private State state = State.MAGIC;

    /// Creates an LZ4 frame decoder with operation-scoped safety settings.
    public LZ4FrameDecoder(
            long maximumWindowSize,
            long maximumMemorySize,
            boolean verifyChecksums
    ) {
        this.maximumWindowSize = maximumWindowSize;
        this.maximumMemorySize = maximumMemorySize;
        this.verifyChecksums = verifyChecksums;
        prepareDescriptor();
    }

    /// Decodes bytes until input, output space, or the current frame boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, false);
    }

    /// Finishes decoding after every compressed source byte has been supplied.
    @Override
    public CodecOutcome finish(ByteBuffer source, ByteBuffer target) throws IOException {
        return decodeInternal(source, target, true);
    }

    /// Implements incremental decoding with the selected source-completion state.
    private CodecOutcome decodeInternal(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }

        while (true) {
            if (output.hasRemaining()) {
                drain(target);
                if (output.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                output = EMPTY_OUTPUT;
                continue;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }

            switch (state) {
                case MAGIC -> {
                    if (!copyInto(source, magic)) {
                        return requireMoreInput(endOfInput);
                    }
                    parseMagic();
                }
                case SKIPPABLE_SIZE -> {
                    if (!copyInto(source, skippableSize)) {
                        return requireMoreInput(endOfInput);
                    }
                    skippableSize.flip();
                    skippableBytes = readUnsignedInt(skippableSize, 0);
                    skippableSize.clear();
                    state = State.SKIPPABLE_PAYLOAD;
                }
                case SKIPPABLE_PAYLOAD -> {
                    int skipped = (int) Math.min(skippableBytes, source.remaining());
                    source.position(source.position() + skipped);
                    skippableBytes -= skipped;
                    if (skippableBytes == 0L) {
                        state = State.FINISHED;
                        return CodecOutcome.FINISHED;
                    }
                    return requireMoreInput(endOfInput);
                }
                case DESCRIPTOR -> {
                    if (!copyInto(source, descriptor)) {
                        return requireMoreInput(endOfInput);
                    }
                    if (descriptor.limit() == 2) {
                        extendDescriptor();
                    } else {
                        parseDescriptor();
                    }
                }
                case BLOCK_HEADER -> {
                    if (!copyInto(source, blockHeader)) {
                        return requireMoreInput(endOfInput);
                    }
                    if (beginBlock()) {
                        return CodecOutcome.FINISHED;
                    }
                }
                case BLOCK_PAYLOAD -> {
                    byte[] currentPayload = Objects.requireNonNull(payload);
                    int copied = Math.min(source.remaining(), currentPayload.length - payloadSize);
                    source.get(currentPayload, payloadSize, copied);
                    payloadSize += copied;
                    if (payloadSize != currentPayload.length) {
                        return requireMoreInput(endOfInput);
                    }
                    if (blockChecksum) {
                        checksum.clear();
                        state = State.BLOCK_CHECKSUM;
                    } else {
                        decodeBlock();
                    }
                }
                case BLOCK_CHECKSUM -> {
                    if (!copyInto(source, checksum)) {
                        return requireMoreInput(endOfInput);
                    }
                    verifyBlockChecksum();
                    decodeBlock();
                }
                case CONTENT_CHECKSUM -> {
                    if (!copyInto(source, checksum)) {
                        return requireMoreInput(endOfInput);
                    }
                    verifyContentChecksum();
                    finishStandardFrame();
                    return CodecOutcome.FINISHED;
                }
                case FINISHED -> {
                    return CodecOutcome.FINISHED;
                }
                case CLOSED -> throw new AssertionError("Closed LZ4 decoder passed requireOpen");
            }
        }
    }

    /// Abandons the current frame and restores the initial decoder state.
    @Override
    public void reset() {
        requireOpen();
        clearFrameState();
        state = State.MAGIC;
    }

    /// Releases decoder-owned frame state without consuming additional input.
    @Override
    public void close() {
        clearFrameState();
        state = State.CLOSED;
    }

    /// Interprets a collected standard, skippable, or unsupported legacy magic value.
    private void parseMagic() throws IOException {
        magic.flip();
        long value = readUnsignedInt(magic, 0);
        magic.clear();
        if (value == LZ4Format.FRAME_MAGIC) {
            prepareDescriptor();
            state = State.DESCRIPTOR;
        } else if (LZ4Format.isSkippableFrameMagic(value)) {
            skippableSize.clear();
            state = State.SKIPPABLE_SIZE;
        } else if (value == LEGACY_FRAME_MAGIC) {
            throw new IOException("Legacy LZ4 frames are not supported");
        } else {
            throw new IOException("Invalid LZ4 frame magic");
        }
    }

    /// Validates fixed descriptor flags and extends collection over optional fields and checksum.
    private void extendDescriptor() throws IOException {
        byte[] bytes = descriptor.array();
        int flags = Byte.toUnsignedInt(bytes[0]);
        int blockDescriptor = Byte.toUnsignedInt(bytes[1]);
        if ((flags & 0xc0) != 0x40) {
            throw new IOException("Unsupported LZ4 frame descriptor version");
        }
        if ((flags & 0x02) != 0) {
            throw new IOException("Reserved LZ4 frame flag is set");
        }
        if ((blockDescriptor & 0x8f) != 0) {
            throw new IOException("Reserved LZ4 block-descriptor bits are set");
        }
        try {
            LZ4BlockSize.fromDescriptorCode((blockDescriptor >>> 4) & 7);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Unsupported LZ4 frame block size", exception);
        }
        int optionalLength = ((flags & 0x08) != 0 ? Long.BYTES : 0)
                + ((flags & 0x01) != 0 ? Integer.BYTES : 0);
        descriptor.limit(2 + optionalLength + 1);
    }

    /// Validates and installs a complete standard frame descriptor.
    private void parseDescriptor() throws IOException {
        byte[] bytes = descriptor.array();
        int descriptorLength = descriptor.limit();
        byte[] hashed = Arrays.copyOf(bytes, descriptorLength - 1);
        int storedHeaderChecksum = Byte.toUnsignedInt(bytes[descriptorLength - 1]);
        int actualHeaderChecksum = (int) (XXHash32.hash(hashed) >>> 8) & 0xff;
        if (storedHeaderChecksum != actualHeaderChecksum) {
            throw new IOException("LZ4 frame header checksum mismatch");
        }

        int flags = Byte.toUnsignedInt(bytes[0]);
        int blockDescriptor = Byte.toUnsignedInt(bytes[1]);
        independentBlocks = (flags & 0x20) != 0;
        blockChecksum = (flags & 0x10) != 0;
        contentChecksum = (flags & 0x04) != 0;
        maximumBlockSize = LZ4BlockSize.fromDescriptorCode(
                (blockDescriptor >>> 4) & 7
        ).byteSize();
        CompressionDecoderSupport.requireWindowSize(maximumWindowSize, MAXIMUM_HISTORY_SIZE);

        int position = 2;
        if ((flags & 0x08) != 0) {
            expectedContentSize = readLong(bytes, position);
            if (expectedContentSize < 0L) {
                throw new IOException("LZ4 frame content size exceeds the Java long range");
            }
            position += Long.BYTES;
        } else {
            expectedContentSize = CompressionCodec.UNKNOWN_SIZE;
        }
        if ((flags & 0x01) != 0) {
            long dictionaryId = readUnsignedInt(bytes, position);
            throw new IOException("LZ4 frame dictionaries are not supported (dictionary ID " + dictionaryId + ")");
        }

        descriptor.clear();
        decodedContentSize = 0L;
        contentHash.reset();
        history = EMPTY_HISTORY;
        blockHeader.clear();
        state = State.BLOCK_HEADER;
    }

    /// Parses a physical block header and allocates its bounded payload.
    private boolean beginBlock() throws IOException {
        blockHeader.flip();
        int value = readInt(blockHeader, 0);
        blockHeader.clear();
        if (value == 0) {
            validateContentSize();
            if (contentChecksum) {
                checksum.clear();
                state = State.CONTENT_CHECKSUM;
                return false;
            }
            finishStandardFrame();
            return true;
        }

        uncompressedBlock = value < 0;
        int size = value & 0x7fff_ffff;
        if (size > maximumBlockSize) {
            throw new IOException("LZ4 physical block exceeds its descriptor maximum");
        }
        requireMemory((long) size + history.length);
        payload = new byte[size];
        payloadSize = 0;
        state = State.BLOCK_PAYLOAD;
        return false;
    }

    /// Verifies the checksum over the physical compressed or raw block payload.
    private void verifyBlockChecksum() throws IOException {
        checksum.flip();
        int stored = readInt(checksum, 0);
        checksum.clear();
        if (verifyChecksums && stored != (int) XXHash32.hash(Objects.requireNonNull(payload))) {
            throw new IOException("LZ4 block checksum mismatch");
        }
    }

    /// Decodes one collected physical block and prepares its output.
    private void decodeBlock() throws IOException {
        byte[] currentPayload = Objects.requireNonNull(payload);
        byte[] decoded;
        int decodedLength;
        if (uncompressedBlock) {
            decoded = currentPayload;
            decodedLength = currentPayload.length;
        } else {
            LZ4BlockDecompression.Result result = LZ4BlockDecompression.decompress(
                    currentPayload,
                    independentBlocks ? EMPTY_HISTORY : history,
                    maximumBlockSize,
                    maximumWindowSize,
                    maximumMemorySize
            );
            decoded = result.bytes();
            decodedLength = result.length();
        }

        try {
            decodedContentSize = Math.addExact(decodedContentSize, decodedLength);
        } catch (ArithmeticException exception) {
            throw new IOException("LZ4 frame decoded size exceeds the Java long range", exception);
        }
        if (expectedContentSize != CompressionCodec.UNKNOWN_SIZE
                && decodedContentSize > expectedContentSize) {
            throw new IOException("LZ4 frame content size mismatch");
        }
        if (contentChecksum && verifyChecksums) {
            contentHash.update(decoded, 0, decodedLength);
        }
        if (!independentBlocks) {
            int retainedHistorySize = Math.min(MAXIMUM_HISTORY_SIZE, decodedLength + history.length);
            long decodedStorageSize = decoded.length;
            long payloadStorageSize = decoded == currentPayload ? 0L : currentPayload.length;
            requireMemory(
                    payloadStorageSize
                            + decodedStorageSize
                            + history.length
                            + retainedHistorySize
            );
            appendHistory(decoded, decodedLength);
        }
        output = ByteBuffer.wrap(decoded, 0, decodedLength).slice().asReadOnlyBuffer();
        payload = null;
        payloadSize = 0;
        blockHeader.clear();
        state = State.BLOCK_HEADER;
    }

    /// Verifies the decoded-content checksum from the frame trailer.
    private void verifyContentChecksum() throws IOException {
        checksum.flip();
        int stored = readInt(checksum, 0);
        checksum.clear();
        if (verifyChecksums && stored != (int) contentHash.value()) {
            throw new IOException("LZ4 content checksum mismatch");
        }
    }

    /// Validates optional exact decoded-size metadata.
    private void validateContentSize() throws IOException {
        if (expectedContentSize != CompressionCodec.UNKNOWN_SIZE
                && decodedContentSize != expectedContentSize) {
            throw new IOException("LZ4 frame content size mismatch");
        }
    }

    /// Marks the current standard frame complete.
    private void finishStandardFrame() throws IOException {
        validateContentSize();
        payload = null;
        history = EMPTY_HISTORY;
        state = State.FINISHED;
    }

    /// Appends decoded bytes to the retained linked-block history window.
    private void appendHistory(byte[] decoded, int decodedLength) {
        if (decodedLength >= MAXIMUM_HISTORY_SIZE) {
            history = Arrays.copyOfRange(
                    decoded,
                    decodedLength - MAXIMUM_HISTORY_SIZE,
                    decodedLength
            );
            return;
        }
        int retained = Math.min(history.length, MAXIMUM_HISTORY_SIZE - decodedLength);
        byte[] updated = new byte[retained + decodedLength];
        System.arraycopy(history, history.length - retained, updated, 0, retained);
        System.arraycopy(decoded, 0, updated, retained, decodedLength);
        history = updated;
    }

    /// Copies pending decoded bytes into a caller-owned target.
    private void drain(ByteBuffer target) {
        int length = Math.min(output.remaining(), target.remaining());
        int originalLimit = output.limit();
        output.limit(output.position() + length);
        try {
            target.put(output);
        } finally {
            output.limit(originalLimit);
        }
    }

    /// Copies source bytes until one fixed-size framing buffer is full.
    private static boolean copyInto(ByteBuffer source, ByteBuffer destination) {
        int length = Math.min(source.remaining(), destination.remaining());
        int originalLimit = source.limit();
        source.limit(source.position() + length);
        try {
            destination.put(source);
        } finally {
            source.limit(originalLimit);
        }
        return !destination.hasRemaining();
    }

    /// Prepares the descriptor buffer to collect its fixed FLG and BD bytes.
    private void prepareDescriptor() {
        descriptor.clear();
        descriptor.limit(2);
    }

    /// Enforces one concrete decoder working-memory requirement.
    private void requireMemory(long requiredMemorySize) throws DecompressionMemoryLimitException {
        if (maximumMemorySize >= 0L && requiredMemorySize > maximumMemorySize) {
            throw new DecompressionMemoryLimitException(maximumMemorySize, requiredMemorySize);
        }
    }

    /// Reports missing input or throws for a caller-declared final source.
    private static CodecOutcome requireMoreInput(boolean endOfInput) throws EOFException {
        if (endOfInput) {
            throw new EOFException("Unexpected end of LZ4 frame");
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Reads one signed little-endian 32-bit integer from a buffer.
    private static int readInt(ByteBuffer buffer, int offset) {
        return Byte.toUnsignedInt(buffer.get(offset))
                | Byte.toUnsignedInt(buffer.get(offset + 1)) << 8
                | Byte.toUnsignedInt(buffer.get(offset + 2)) << 16
                | Byte.toUnsignedInt(buffer.get(offset + 3)) << 24;
    }

    /// Reads one unsigned little-endian 32-bit integer from a buffer.
    private static long readUnsignedInt(ByteBuffer buffer, int offset) {
        return Integer.toUnsignedLong(readInt(buffer, offset));
    }

    /// Reads one unsigned little-endian 32-bit integer from an array.
    private static long readUnsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(bytes[offset])
                        | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                        | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                        | Byte.toUnsignedInt(bytes[offset + 3]) << 24
        );
    }

    /// Reads one little-endian 64-bit integer from an array.
    private static long readLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int index = Long.BYTES - 1; index >= 0; index--) {
            value = value << 8 | Byte.toUnsignedLong(bytes[offset + index]);
        }
        return value;
    }

    /// Clears all mutable per-frame buffers and metadata.
    private void clearFrameState() {
        magic.clear();
        prepareDescriptor();
        skippableSize.clear();
        blockHeader.clear();
        checksum.clear();
        contentHash.reset();
        maximumBlockSize = 0;
        independentBlocks = false;
        blockChecksum = false;
        contentChecksum = false;
        expectedContentSize = CompressionCodec.UNKNOWN_SIZE;
        decodedContentSize = 0L;
        skippableBytes = 0L;
        payload = null;
        payloadSize = 0;
        uncompressedBlock = false;
        history = EMPTY_HISTORY;
        output = EMPTY_OUTPUT;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("LZ4 frame decoder is closed");
        }
    }

    /// Tracks incremental LZ4 frame parsing and lifecycle state.
    @NotNullByDefault
    private enum State {
        /// A standard or skippable frame magic is being collected.
        MAGIC,

        /// A skippable-frame payload size is being collected.
        SKIPPABLE_SIZE,

        /// A skippable-frame payload is being discarded.
        SKIPPABLE_PAYLOAD,

        /// A standard frame descriptor is being collected.
        DESCRIPTOR,

        /// A physical data-block header or EndMark is being collected.
        BLOCK_HEADER,

        /// A physical compressed or raw block payload is being collected.
        BLOCK_PAYLOAD,

        /// A physical block checksum is being collected.
        BLOCK_CHECKSUM,

        /// A decoded-content checksum is being collected.
        CONTENT_CHECKSUM,

        /// The current standard or skippable frame completed.
        FINISHED,

        /// Decoder-owned state has been released.
        CLOSED
    }
}
