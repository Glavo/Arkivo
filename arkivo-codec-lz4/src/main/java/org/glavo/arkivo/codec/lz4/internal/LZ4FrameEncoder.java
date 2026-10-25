// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4.internal;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.EncodingOptions;
import org.glavo.arkivo.codec.lz4.LZ4BlockSize;
import org.glavo.arkivo.codec.lz4.LZ4Dictionary;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/// Incrementally encodes standard LZ4 frames without retaining caller-owned buffers.
@NotNullByDefault
public final class LZ4FrameEncoder implements CompressionEncoder.FlushableFramed {
    /// Standard LZ4 frame magic bytes.
    private static final byte[] FRAME_MAGIC = {0x04, 0x22, 0x4d, 0x18};

    /// Empty pending output.
    private static final ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Maximum match distance retained between linked blocks.
    private static final int MAXIMUM_HISTORY_SIZE = 65_535;

    /// Empty storage used before history is available and after owned buffers are released.
    private static final byte @Unmodifiable [] EMPTY_BYTES = new byte[0];

    /// Configured standard maximum decoded block size.
    private final LZ4BlockSize blockSize;

    /// Whether each block starts with an empty history window.
    private final boolean independentBlocks;

    /// Whether every physical block carries an xxHash-32 checksum.
    private final boolean blockChecksum;

    /// Whether every frame carries a decoded-content xxHash-32 checksum.
    private final boolean contentChecksum;

    /// Owned effective external dictionary history, or empty after closure.
    private byte @Unmodifiable [] dictionaryHistory;

    /// Unsigned dictionary identifier, or the no-identifier sentinel.
    private final long dictionaryId;

    /// Owned decoded bytes collected for the current block, or empty after closure.
    private byte[] block;

    /// Block compressor whose match table is reused within this encoder.
    private final LZ4BlockCompression blockCompressor = new LZ4BlockCompression();

    /// Encoded block workspace, retained across frames and reset but released on close.
    private byte[] encodedBlock = EMPTY_BYTES;

    /// Exact source size restored for the initial frame by reset, or the unknown-size sentinel.
    private final long initialSourceSize;

    /// Streaming decoded-content checksum state.
    private final ChecksumAccumulator.Width32 contentHash = XXHash32.newAccumulator();

    /// Prefix history retained for the next linked block.
    private byte[] history = new byte[0];

    /// Encoded bytes awaiting caller-owned target space.
    private ByteBuffer output = EMPTY_OUTPUT;

    /// Number of decoded bytes collected in the current block.
    private int blockLength;

    /// Exact source size declared for the active frame, or the unknown-size sentinel.
    private long frameSourceSize;

    /// Number of source bytes accepted for the active frame.
    private long frameInputSize;

    /// Whether the current frame header has been emitted.
    private boolean frameStarted;

    /// Whether the current frame trailer has been queued.
    private boolean trailerQueued;

    /// Current frame encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates a standard LZ4 frame encoder from immutable codec settings.
    ///
    /// @param blockSize the maximum decoded size of each physical block
    /// @param independentBlocks whether each block starts with empty frame history
    /// @param blockChecksum whether to append xxHash-32 to each physical block
    /// @param contentChecksum whether to append decoded-content xxHash-32 to each frame
    /// @param dictionary the external prefix dictionary, or `null`
    /// @param sourceSize exact decoded size of the initial frame, or the unknown-size sentinel
    public LZ4FrameEncoder(
            LZ4BlockSize blockSize,
            boolean independentBlocks,
            boolean blockChecksum,
            boolean contentChecksum,
            @Nullable LZ4Dictionary dictionary,
            long sourceSize
    ) {
        this.blockSize = Objects.requireNonNull(blockSize, "blockSize");
        this.independentBlocks = independentBlocks;
        this.blockChecksum = blockChecksum;
        this.contentChecksum = contentChecksum;
        dictionaryHistory = dictionary == null ? EMPTY_BYTES : dictionary.bytes();
        dictionaryId = dictionary == null ? LZ4Dictionary.NO_DICTIONARY_ID : dictionary.dictionaryId();
        block = new byte[blockSize.byteSize()];
        if (sourceSize < CompressionCodec.UNKNOWN_SIZE) {
            throw new IllegalArgumentException("sourceSize must be non-negative or UNKNOWN_SIZE");
        }
        initialSourceSize = sourceSize;
        frameSourceSize = sourceSize;
    }

    /// Explicitly starts another frame with independent decoded-size metadata.
    @Override
    public void startFrame(EncodingOptions options) {
        Objects.requireNonNull(options, "options");
        requireOpen();
        if (state != State.BETWEEN_FRAMES) {
            throw new IllegalStateException("Cannot start an LZ4 frame while encoder state is " + state);
        }
        frameSourceSize = options.sourceSize();
        frameInputSize = 0L;
        state = State.ACTIVE;
    }

    /// Encodes source bytes until source exhaustion or encoded-output backpressure.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            state = State.ACTIVE;
        }
        requireState(State.ACTIVE, "encode");
        if (frameSourceSize >= 0L && source.remaining() > frameSourceSize - frameInputSize) {
            throw new IOException("LZ4 frame input exceeds the declared source size");
        }

        while (true) {
            drain(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (!source.hasRemaining()) {
                return CodecOutcome.NEEDS_INPUT;
            }
            if (!frameStarted) {
                emitHeader();
                continue;
            }

            int copied = Math.min(source.remaining(), block.length - blockLength);
            int blockOffset = blockLength;
            source.get(block, blockLength, copied);
            blockLength += copied;
            frameInputSize += copied;
            if (contentChecksum) {
                contentHash.update(block, blockOffset, copied);
            }
            if (blockLength == block.length) {
                emitBlock();
            }
        }
    }

    /// Emits accepted content through a complete LZ4 data-block boundary.
    @Override
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.FLUSHED;
        }
        if (state == State.ACTIVE) {
            state = State.FLUSHING;
        } else if (state != State.FLUSHING) {
            throw new IllegalStateException("Cannot flush while LZ4 frame encoder state is " + state);
        }

        while (true) {
            drain(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (blockLength != 0) {
                emitBlock();
                continue;
            }
            state = State.ACTIVE;
            return CodecOutcome.FLUSHED;
        }
    }

    /// Finishes the current frame and retains the encoding session for another frame.
    @Override
    public CodecOutcome finishFrame(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.BETWEEN_FRAMES) {
            return CodecOutcome.BOUNDARY_REACHED;
        }
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active LZ4 flush before finishing its frame");
        }
        if (state == State.FINISHING || state == State.FINISHED) {
            throw new IllegalStateException("Cannot finish an LZ4 frame after terminal finalization started");
        }
        if (state == State.ACTIVE) {
            state = State.FINISHING_FRAME;
        }
        requireState(State.FINISHING_FRAME, "finish a frame");
        return finishCurrentFrame(target, false);
    }

    /// Finishes the complete LZ4 encoding session without adding an empty frame between frames.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.BETWEEN_FRAMES) {
            state = State.FINISHED;
            return CodecOutcome.FINISHED;
        }
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active LZ4 flush before finishing the encoding");
        }
        if (state == State.FINISHING_FRAME) {
            throw new IllegalStateException("Complete the active LZ4 frame boundary before terminal finalization");
        }
        if (state == State.ACTIVE) {
            state = State.FINISHING;
        }
        requireState(State.FINISHING, "finish");
        return finishCurrentFrame(target, true);
    }

    /// Abandons the current frame sequence and restores the configured initial state.
    @Override
    public void reset() {
        requireOpen();
        output = EMPTY_OUTPUT;
        clearFrameState();
        frameSourceSize = initialSourceSize;
        state = State.ACTIVE;
    }

    /// Releases encoder-owned frame state without implicitly finishing pending content.
    @Override
    public void close() {
        output = EMPTY_OUTPUT;
        clearFrameState();
        block = EMPTY_BYTES;
        encodedBlock = EMPTY_BYTES;
        blockCompressor.releaseWorkspace();
        dictionaryHistory = EMPTY_BYTES;
        state = State.CLOSED;
    }

    /// Drives current-frame finalization through header, final block, and trailer output.
    private CodecOutcome finishCurrentFrame(ByteBuffer target, boolean terminal) throws IOException {
        if (frameSourceSize >= 0L && frameInputSize != frameSourceSize) {
            throw new IOException(
                    "LZ4 frame source size " + frameInputSize
                            + " does not match declared size " + frameSourceSize
            );
        }
        while (true) {
            drain(target);
            if (output.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            if (!frameStarted) {
                emitHeader();
                continue;
            }
            if (blockLength != 0) {
                emitBlock();
                continue;
            }
            if (!trailerQueued) {
                emitTrailer();
                trailerQueued = true;
                continue;
            }

            clearFrameState();
            state = terminal ? State.FINISHED : State.BETWEEN_FRAMES;
            return terminal ? CodecOutcome.FINISHED : CodecOutcome.BOUNDARY_REACHED;
        }
    }

    /// Emits the standard magic and configured frame descriptor.
    private void emitHeader() {
        int flags = 0x40;
        if (independentBlocks) {
            flags |= 0x20;
        }
        if (blockChecksum) {
            flags |= 0x10;
        }
        if (contentChecksum) {
            flags |= 0x04;
        }
        if (frameSourceSize >= 0L) {
            flags |= 0x08;
        }
        if (dictionaryId != LZ4Dictionary.NO_DICTIONARY_ID) {
            flags |= 0x01;
        }
        int blockDescriptor = blockSize.descriptorCode() << 4;
        byte[] descriptor = new byte[
                2
                        + (frameSourceSize >= 0L ? Long.BYTES : 0)
                        + (dictionaryId != LZ4Dictionary.NO_DICTIONARY_ID ? Integer.BYTES : 0)
        ];
        descriptor[0] = (byte) flags;
        descriptor[1] = (byte) blockDescriptor;
        int offset = 2;
        if (frameSourceSize >= 0L) {
            ByteArrayAccess.writeLongLittleEndian(descriptor, offset, frameSourceSize);
            offset += Long.BYTES;
        }
        if (dictionaryId != LZ4Dictionary.NO_DICTIONARY_ID) {
            ByteArrayAccess.writeIntLittleEndian(descriptor, offset, (int) dictionaryId);
        }
        byte[] header = new byte[FRAME_MAGIC.length + descriptor.length + 1];
        System.arraycopy(FRAME_MAGIC, 0, header, 0, FRAME_MAGIC.length);
        System.arraycopy(descriptor, 0, header, FRAME_MAGIC.length, descriptor.length);
        header[header.length - 1] = (byte) (XXHash32.computeInt(descriptor) >>> 8);
        queue(header);
        contentHash.reset();
        history = dictionaryHistory;
        frameStarted = true;
    }

    /// Compresses and queues one complete physical frame block.
    private void emitBlock() throws IOException {
        if (output.hasRemaining()) {
            throw new AssertionError("LZ4 block output must be drained before workspace reuse");
        }
        int overhead = Integer.BYTES + (blockChecksum ? Integer.BYTES : 0);
        int capacity = Math.toIntExact(LZ4BlockCompression.maxCompressedLength(blockLength)) + overhead;
        if (encodedBlock.length < capacity) {
            encodedBlock = new byte[capacity];
        }
        byte[] encoded = encodedBlock;
        int payloadLength = blockCompressor.compressInto(block, blockLength,
                independentBlocks ? dictionaryHistory : history, encoded, Integer.BYTES);
        boolean uncompressed = payloadLength >= blockLength;
        if (uncompressed) {
            payloadLength = blockLength;
            System.arraycopy(block, 0, encoded, Integer.BYTES, blockLength);
        }
        int blockHeader = payloadLength | (uncompressed ? 0x8000_0000 : 0);
        ByteArrayAccess.writeIntLittleEndian(encoded, 0, blockHeader);
        if (blockChecksum) {
            ByteArrayAccess.writeIntLittleEndian(
                    encoded,
                    Integer.BYTES + payloadLength,
                    XXHash32.computeInt(encoded, Integer.BYTES, payloadLength)
            );
        }
        queue(encoded, payloadLength + overhead);
        if (!independentBlocks) {
            appendHistory();
        }
        blockLength = 0;
    }

    /// Queues the EndMark and optional decoded-content checksum.
    private void emitTrailer() {
        byte[] trailer = new byte[Integer.BYTES + (contentChecksum ? Integer.BYTES : 0)];
        if (contentChecksum) {
            ByteArrayAccess.writeIntLittleEndian(trailer, Integer.BYTES, contentHash.finishInt());
        }
        queue(trailer);
    }

    /// Appends the occupied prefix of the current block to the retained linked-block history window.
    private void appendHistory() {
        if (blockLength >= MAXIMUM_HISTORY_SIZE) {
            history = Arrays.copyOfRange(
                    block,
                    blockLength - MAXIMUM_HISTORY_SIZE,
                    blockLength
            );
            return;
        }
        int retained = Math.min(history.length, MAXIMUM_HISTORY_SIZE - blockLength);
        byte[] updated = new byte[retained + blockLength];
        System.arraycopy(history, history.length - retained, updated, 0, retained);
        System.arraycopy(block, 0, updated, retained, blockLength);
        history = updated;
    }

    /// Installs one owned encoded range after verifying the previous range was drained.
    private void queue(byte[] encoded) {
        queue(encoded, encoded.length);
    }

    /// Installs an occupied prefix without copying or exposing unused output capacity.
    private void queue(byte[] encoded, int length) {
        if (output.hasRemaining()) {
            throw new AssertionError("LZ4 output queue already contains pending bytes");
        }
        output = ByteBuffer.wrap(encoded, 0, length).asReadOnlyBuffer();
    }

    /// Copies pending encoded bytes into a caller-owned target.
    private void drain(ByteBuffer target) {
        int length = Math.min(output.remaining(), target.remaining());
        int originalLimit = output.limit();
        output.limit(output.position() + length);
        try {
            target.put(output);
        } finally {
            output.limit(originalLimit);
        }
        if (!output.hasRemaining()) {
            output = EMPTY_OUTPUT;
        }
    }

    /// Clears mutable state belonging to one frame.
    private void clearFrameState() {
        blockLength = 0;
        frameStarted = false;
        trailerQueued = false;
        history = EMPTY_BYTES;
        contentHash.reset();
        frameSourceSize = CompressionCodec.UNKNOWN_SIZE;
        frameInputSize = 0L;
    }

    /// Requires the exact encoder lifecycle state.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while LZ4 frame encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("LZ4 frame encoder is closed");
        }
    }

    /// Tracks incremental LZ4 frame output and lifecycle state.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted for the current frame.
        ACTIVE,

        /// A flush boundary must be drained.
        FLUSHING,

        /// A nonterminal frame trailer must be drained.
        FINISHING_FRAME,

        /// A frame completed and the following frame has not accepted source bytes.
        BETWEEN_FRAMES,

        /// The terminal frame trailer must be drained.
        FINISHING,

        /// The complete encoding session has finished.
        FINISHED,

        /// Encoder-owned state has been released.
        CLOSED
    }
}
