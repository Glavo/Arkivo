// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.zstd.ZstdDictionary;
import org.glavo.arkivo.codec.zstd.ZstdDictionaryRequest;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.glavo.arkivo.codec.zstd.ZstdFrameInfo;
import org.glavo.arkivo.codec.zstd.ZstdSkippableFrameInfo;
import org.glavo.arkivo.codec.zstd.ZstdStandardFrameInfo;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

/// Incrementally decodes one Zstandard frame without retaining caller-owned buffers.
@NotNullByDefault
public final class ZstdDecoder
        implements CompressionDecoder.FramedDictionaryAware<ZstdDictionary, ZstdDictionaryRequest> {
    /// Empty decoded output.
    private static final ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Largest standard Zstandard frame header.
    private static final int MAXIMUM_HEADER_SIZE = 18;

    /// Initially configured dictionary, or null.
    private final @Nullable ZstdDictionary initialDictionary;

    /// Maximum permitted frame window, or the unknown-size sentinel.
    private final long maximumWindowSize;

    /// Whether standard frame magic is omitted.
    private final boolean magicless;

    /// Whether present checksums are verified.
    private final boolean verifyChecksums;

    /// Incrementally collected frame header.
    private final ByteBuffer header = ByteBuffer.allocate(MAXIMUM_HEADER_SIZE);

    /// Incrementally collected block header.
    private final ByteBuffer blockHeader = ByteBuffer.allocate(3);

    /// Incrementally collected frame checksum.
    private final ByteBuffer checksumBytes = ByteBuffer.allocate(Integer.BYTES);

    /// Dictionary used by the current decoding session.
    private ZstdDictionaryContext dictionary;

    /// Current standard-frame block decoder, or null outside a standard frame.
    private @Nullable ZstdBlockDecoder blockDecoder;

    /// Current standard-frame checksum, or null when absent or disabled.
    private @Nullable ZstdXXHash64 checksum;

    /// Current standard-frame metadata, or null outside a standard frame.
    private @Nullable ZstdStandardFrameInfo frameInfo;

    /// Compressed block payload being collected, or null outside payload collection.
    private byte @Nullable [] payload;

    /// Number of collected compressed block payload bytes.
    private int payloadSize;

    /// Current block type from zero through two.
    private int blockType;

    /// Current block-size field: encoded size for raw and compressed blocks, decoded size for run-length blocks.
    private int blockSize;

    /// Whether the current block is the final standard-frame block.
    private boolean lastBlock;

    /// Decoded block bytes waiting for caller output space.
    private ByteBuffer output = EMPTY_OUTPUT;

    /// Remaining bytes in the current skippable-frame payload.
    private long skippableBytes;

    /// Dictionary identifier requested by the current frame.
    private long requiredDictionaryId = ZstdDictionary.NO_DICTIONARY_ID;

    /// Current decoder lifecycle state.
    private State state = State.HEADER;

    /// Creates a pure Java Zstandard buffer decoder.
    ///
    /// @param dictionary configured dictionary, or null
    /// @param maximumWindowSize maximum permitted frame window, or the unknown-size sentinel
    /// @param magicless whether standard frame magic is omitted
    /// @param verifyChecksums whether present frame checksums are verified
    /// @throws IOException if the configured dictionary representation cannot initialize a decoding context
    public ZstdDecoder(
            @Nullable ZstdDictionary dictionary,
            long maximumWindowSize,
            boolean magicless,
            boolean verifyChecksums
    ) throws IOException {
        this.initialDictionary = dictionary;
        this.maximumWindowSize = maximumWindowSize;
        this.magicless = magicless;
        this.verifyChecksums = verifyChecksums;
        this.dictionary = ZstdDictionaryContext.parse(dictionary);
    }

    /// Decodes source bytes until input, output space, a dictionary, or the frame boundary stops progress.
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
        if (state == State.NEEDS_DICTIONARY) {
            return CodecOutcome.NEEDS_DICTIONARY;
        }

        while (true) {
            if (output.hasRemaining()) {
                copyOutput(target);
                if (output.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                output = EMPTY_OUTPUT;
                completeBlock();
                if (state == State.FINISHED) {
                    return CodecOutcome.FINISHED;
                }
                continue;
            }
            if (!target.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }

            switch (state) {
                case HEADER -> {
                    @Nullable CodecOutcome outcome = readHeader(source, endOfInput);
                    if (outcome != null) {
                        return outcome;
                    }
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
                case BLOCK_HEADER -> {
                    if (!copyInto(source, blockHeader)) {
                        return requireMoreInput(endOfInput);
                    }
                    beginBlock();
                }
                case BLOCK_PAYLOAD -> {
                    byte[] currentPayload = Objects.requireNonNull(payload);
                    int copied = Math.min(source.remaining(), currentPayload.length - payloadSize);
                    source.get(currentPayload, payloadSize, copied);
                    payloadSize += copied;
                    if (payloadSize != currentPayload.length) {
                        return requireMoreInput(endOfInput);
                    }
                    decodeBlock(currentPayload);
                    if (output.hasRemaining()) {
                        continue;
                    }
                    completeBlock();
                    if (state == State.FINISHED) {
                        return CodecOutcome.FINISHED;
                    }
                }
                case CHECKSUM -> {
                    if (!copyInto(source, checksumBytes)) {
                        return requireMoreInput(endOfInput);
                    }
                    verifyChecksum();
                    finishStandardFrame();
                    return CodecOutcome.FINISHED;
                }
                case NEEDS_DICTIONARY -> {
                    return CodecOutcome.NEEDS_DICTIONARY;
                }
                case FINISHED -> {
                    return CodecOutcome.FINISHED;
                }
                case CLOSED -> throw new AssertionError("Closed decoder passed requireOpen");
            }
        }
    }

    /// Returns the dictionary request from the current Zstandard frame.
    @Override
    public ZstdDictionaryRequest dictionaryRequest() {
        if (state != State.NEEDS_DICTIONARY) {
            throw new IllegalStateException("Zstandard decoder is not waiting for a dictionary");
        }
        return new ZstdDictionaryRequest(requiredDictionaryId);
    }

    /// Supplies the dictionary requested by the parsed frame header.
    @Override
    public void provideDictionary(ZstdDictionary dictionary) throws IOException {
        Objects.requireNonNull(dictionary, "dictionary");
        requireOpen();
        ZstdDictionaryRequest request = dictionaryRequest();
        if (!request.matches(dictionary)) {
            throw new IOException("Configured Zstandard dictionary does not satisfy " + request);
        }
        this.dictionary = ZstdDictionaryContext.parse(dictionary);
        beginStandardFrame(Objects.requireNonNull(frameInfo));
    }

    /// Abandons the current frame and restores the initial decoder configuration.
    @Override
    public void reset() {
        requireOpen();
        try {
            dictionary = ZstdDictionaryContext.parse(initialDictionary);
        } catch (IOException exception) {
            throw new IllegalStateException("Configured Zstandard dictionary became invalid", exception);
        }
        clearFrameState();
        state = State.HEADER;
    }

    /// Releases decoder state without consuming additional input.
    @Override
    public void close() {
        if (state != State.CLOSED) {
            clearFrameState();
            state = State.CLOSED;
        }
    }

    /// Collects and parses exactly one frame header.
    private @Nullable CodecOutcome readHeader(ByteBuffer source, boolean endOfInput) throws IOException {
        while (true) {
            ByteBuffer view = header.asReadOnlyBuffer();
            view.flip();
            try {
                ZstdFrameInfo parsed = ZstdFrameHeader.parse(view, magicless);
                header.clear();
                if (parsed instanceof ZstdSkippableFrameInfo skippable) {
                    skippableBytes = skippable.payloadSize();
                    if (skippableBytes == 0L) {
                        state = State.FINISHED;
                        return CodecOutcome.FINISHED;
                    }
                    state = State.SKIPPABLE_PAYLOAD;
                    return null;
                }
                ZstdStandardFrameInfo standard = (ZstdStandardFrameInfo) parsed;
                prepareStandardFrame(standard);
                return state == State.NEEDS_DICTIONARY ? CodecOutcome.NEEDS_DICTIONARY : null;
            } catch (EOFException exception) {
                if (!source.hasRemaining()) {
                    return requireMoreInput(endOfInput);
                }
                if (!header.hasRemaining()) {
                    throw new IOException("Invalid Zstandard frame header", exception);
                }
                header.put(source.get());
            } catch (IOException exception) {
                if ("Invalid Zstandard frame magic".equals(exception.getMessage())) {
                    throw new IOException("Invalid Zstandard frame", exception);
                }
                throw exception;
            }
        }
    }

    /// Validates standard-frame metadata and resolves its dictionary.
    private void prepareStandardFrame(ZstdStandardFrameInfo standard) throws IOException {
        if (standard.contentSize() == ZstdStandardFrameInfo.CONTENT_SIZE_OVERFLOW) {
            throw new IOException("Zstandard frame content size exceeds the Java long range");
        }
        CompressionDecoderSupport.requireWindowSize(maximumWindowSize, standard.windowSize());
        long dictionaryId = standard.dictionaryId();
        if (dictionaryId != ZstdDictionary.NO_DICTIONARY_ID && dictionary.id() != dictionaryId) {
            frameInfo = standard;
            requiredDictionaryId = dictionaryId;
            state = State.NEEDS_DICTIONARY;
            return;
        }
        beginStandardFrame(standard);
    }

    /// Initializes standard-frame block, history, and checksum state.
    private void beginStandardFrame(ZstdStandardFrameInfo standard) throws IOException {
        frameInfo = standard;
        requiredDictionaryId = ZstdDictionary.NO_DICTIONARY_ID;
        blockDecoder = new ZstdBlockDecoder(standard.windowSize(), dictionary);
        checksum = standard.checksum() && verifyChecksums ? new ZstdXXHash64() : null;
        blockHeader.clear();
        state = State.BLOCK_HEADER;
    }

    /// Parses a collected block header and allocates its physical payload.
    private void beginBlock() throws IOException {
        blockHeader.flip();
        int value = Byte.toUnsignedInt(blockHeader.get())
                | Byte.toUnsignedInt(blockHeader.get()) << 8
                | Byte.toUnsignedInt(blockHeader.get()) << 16;
        blockHeader.clear();
        lastBlock = (value & 1) != 0;
        blockType = (value >>> 1) & 3;
        blockSize = value >>> 3;
        if (blockType == 3) {
            throw new IOException("Reserved Zstandard block type");
        }
        int maximumBlockSize = (int) Math.min(
                ZstdBlockDecoder.MAX_BLOCK_SIZE,
                Objects.requireNonNull(frameInfo).windowSize()
        );
        if (blockSize > maximumBlockSize) {
            throw new IOException("Zstandard block exceeds the frame block-size limit");
        }
        int compressedSize = blockType == 1 ? 1 : blockSize;
        payload = new byte[compressedSize];
        payloadSize = 0;
        state = State.BLOCK_PAYLOAD;
    }

    /// Decodes one complete physical block payload.
    private void decodeBlock(byte[] compressed) throws IOException {
        ZstdBlockDecoder decoder = Objects.requireNonNull(blockDecoder);
        byte[] decoded = switch (blockType) {
            case 0 -> decoder.decodeRaw(compressed);
            case 1 -> decoder.decodeRle(Byte.toUnsignedInt(compressed[0]), blockSize);
            case 2 -> decoder.decodeCompressed(compressed);
            default -> throw new AssertionError(blockType);
        };
        if (decoded.length > ZstdBlockDecoder.MAX_BLOCK_SIZE) {
            throw new IOException("Decoded Zstandard block exceeds the frame block-size limit");
        }
        @Nullable ZstdXXHash64 selectedChecksum = checksum;
        if (selectedChecksum != null) {
            selectedChecksum.update(decoded, 0, decoded.length);
        }
        output = ByteBuffer.wrap(decoded).asReadOnlyBuffer();
        payload = null;
        payloadSize = 0;
    }

    /// Advances framing after one decoded block has been delivered.
    private void completeBlock() throws IOException {
        if (!lastBlock) {
            state = State.BLOCK_HEADER;
            return;
        }
        ZstdStandardFrameInfo standard = Objects.requireNonNull(frameInfo);
        long actualSize = Objects.requireNonNull(blockDecoder).frameSize();
        if (standard.contentSize() != CompressionCodec.UNKNOWN_SIZE
                && actualSize != standard.contentSize()) {
            throw new IOException("Zstandard frame content size mismatch");
        }
        if (standard.checksum()) {
            checksumBytes.clear();
            state = State.CHECKSUM;
        } else {
            finishStandardFrame();
        }
    }

    /// Verifies the collected standard-frame checksum when requested.
    private void verifyChecksum() throws IOException {
        checksumBytes.flip();
        long stored = Integer.toUnsignedLong(
                Byte.toUnsignedInt(checksumBytes.get())
                        | Byte.toUnsignedInt(checksumBytes.get()) << 8
                        | Byte.toUnsignedInt(checksumBytes.get()) << 16
                        | Byte.toUnsignedInt(checksumBytes.get()) << 24
        );
        if (verifyChecksums) {
            long actual = Objects.requireNonNull(checksum).digest() & 0xffff_ffffL;
            if (stored != actual) {
                throw new IOException("Zstandard frame checksum mismatch");
            }
        }
    }

    /// Marks the current standard frame complete.
    private void finishStandardFrame() {
        blockDecoder = null;
        checksum = null;
        frameInfo = null;
        state = State.FINISHED;
    }

    /// Copies pending decoded block bytes into the caller's target.
    private void copyOutput(ByteBuffer target) {
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

    /// Reports missing input or throws for a caller-declared final source.
    private static CodecOutcome requireMoreInput(boolean endOfInput) throws EOFException {
        if (endOfInput) {
            throw new EOFException("Unexpected end of Zstandard frame");
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Clears all mutable per-frame buffers and metadata.
    private void clearFrameState() {
        header.clear();
        blockHeader.clear();
        checksumBytes.clear();
        blockDecoder = null;
        checksum = null;
        frameInfo = null;
        payload = null;
        payloadSize = 0;
        output = EMPTY_OUTPUT;
        skippableBytes = 0L;
        requiredDictionaryId = ZstdDictionary.NO_DICTIONARY_ID;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Zstandard decoder is closed");
        }
    }

    /// Tracks incremental Zstandard frame parsing and lifecycle state.
    private enum State {
        /// A standard or skippable frame header is being collected.
        HEADER,

        /// A skippable-frame payload is being discarded.
        SKIPPABLE_PAYLOAD,

        /// Decoding is paused until the requested dictionary is supplied.
        NEEDS_DICTIONARY,

        /// A standard block header is being collected.
        BLOCK_HEADER,

        /// A standard block payload is being collected.
        BLOCK_PAYLOAD,

        /// A standard frame checksum is being collected.
        CHECKSUM,

        /// The single framed item completed.
        FINISHED,

        /// Decoder resources and state were released.
        CLOSED
    }
}
