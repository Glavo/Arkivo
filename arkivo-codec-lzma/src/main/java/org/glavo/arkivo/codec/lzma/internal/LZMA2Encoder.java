// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lzma.internal;

import org.glavo.arkivo.codec.lzma.LZMAProperties;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.util.Objects;

/// Incrementally encodes a raw LZMA2 stream without retaining caller-owned buffers.
///
/// Complete 64 KiB chunks are staged internally because an LZMA2 chunk header declares both sizes before its body.
/// Flushing emits the current partial chunk as an independently decodable LZMA2 boundary without ending the stream.
@NotNullByDefault
public final class LZMA2Encoder implements CompressionEncoder {
    /// The largest uncompressed chunk considered by this encoder.
    private static final int BLOCK_SIZE = 1 << 16;

    /// Shared empty output marker.
    private static final @Unmodifiable ByteBuffer EMPTY_OUTPUT = ByteBuffer.allocate(0);

    /// The immutable LZMA properties used for compressed chunks.
    private final LZMAProperties properties;

    /// The pending uncompressed chunk.
    private final byte[] block = new byte[BLOCK_SIZE];

    /// Complete encoded bytes waiting for caller-owned target space.
    private ByteBuffer pendingOutput = EMPTY_OUTPUT;

    /// The number of pending bytes in `block`.
    private int blockSize;

    /// Current encoder lifecycle state.
    private State state = State.ACTIVE;

    /// Creates an encoder with complete immutable model properties.
    ///
    /// @param properties LZMA model and dictionary configuration
    public LZMA2Encoder(LZMAProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /// Encodes source bytes until the source or target is exhausted.
    @Override
    public CodecOutcome encode(ByteBuffer source, ByteBuffer target) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireState(State.ACTIVE, "encode");

        while (true) {
            copyPending(target);
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
                pendingOutput = encodeBlock();
            }
        }
    }

    /// Emits the current partial chunk without ending the LZMA2 stream.
    public CodecOutcome flush(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FINISHING || state == State.TRAILER || state == State.FINISHED) {
            throw new IllegalStateException("Cannot flush a finishing or finished LZMA2 stream");
        }
        if (state == State.ACTIVE) {
            state = State.FLUSHING;
        }

        copyPending(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingOutput = EMPTY_OUTPUT;

        if (blockSize > 0) {
            pendingOutput = encodeBlock();
            copyPending(target);
            if (pendingOutput.hasRemaining()) {
                return CodecOutcome.NEEDS_OUTPUT;
            }
            pendingOutput = EMPTY_OUTPUT;
        }

        state = State.ACTIVE;
        return CodecOutcome.FLUSHED;
    }

    /// Finishes the LZMA2 stream and emits its end control byte.
    @Override
    public CodecOutcome finish(ByteBuffer target) throws IOException {
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (state == State.FLUSHING) {
            throw new IllegalStateException("Complete the active flush before finishing the LZMA2 stream");
        }
        if (state == State.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (state == State.ACTIVE) {
            state = State.FINISHING;
        }

        copyPending(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingOutput = EMPTY_OUTPUT;

        if (state == State.FINISHING) {
            if (blockSize > 0) {
                pendingOutput = encodeBlock();
                copyPending(target);
                if (pendingOutput.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }
                pendingOutput = EMPTY_OUTPUT;
            }
            pendingOutput = ByteBuffer.wrap(new byte[]{0});
            state = State.TRAILER;
        }

        copyPending(target);
        if (pendingOutput.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }
        pendingOutput = EMPTY_OUTPUT;
        state = State.FINISHED;
        return CodecOutcome.FINISHED;
    }

    /// Abandons the current stream and restores its initial empty state.
    @Override
    public void reset() {
        requireOpen();
        blockSize = 0;
        pendingOutput = EMPTY_OUTPUT;
        state = State.ACTIVE;
    }

    /// Releases encoder-owned stream state without implicitly finishing it.
    @Override
    public void close() {
        state = State.CLOSED;
        blockSize = 0;
        pendingOutput = EMPTY_OUTPUT;
    }

    /// Compresses and frames the pending block, or stores it when compression has no benefit.
    private ByteBuffer encodeBlock() throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(blockSize);
        int effectiveDictionarySize = Math.max(
                LZMAProperties.MINIMUM_DICTIONARY_SIZE,
                Math.min(properties.dictionarySize(), blockSize)
        );
        LZMAProperties blockProperties = new LZMAProperties(
                properties.literalContextBits(),
                properties.literalPositionBits(),
                properties.positionBits(),
                effectiveDictionarySize
        );
        LZMAChannelOutput compressedOutput = new LZMAChannelOutput(Channels.newChannel(compressed));
        LZMAEncoderEngine encoder = new LZMAEncoderEngine(compressedOutput, blockProperties);
        encoder.write(block, 0, blockSize);
        encoder.finish(false);
        compressedOutput.flush();

        byte[] encoded = compressed.toByteArray();
        ByteBuffer output;
        if (encoded.length <= 1 << 16 && encoded.length + 3 < blockSize) {
            int uncompressedMinusOne = blockSize - 1;
            output = ByteBuffer.allocate(encoded.length + 6);
            output.put((byte) (0xe0 | uncompressedMinusOne >>> 16));
            putUnsignedShort(output, uncompressedMinusOne);
            putUnsignedShort(output, encoded.length - 1);
            output.put((byte) properties.propertyByte());
            output.put(encoded);
        } else {
            output = ByteBuffer.allocate(blockSize + 3);
            output.put((byte) 0x01);
            putUnsignedShort(output, blockSize - 1);
            output.put(block, 0, blockSize);
        }
        blockSize = 0;
        return output.flip();
    }

    /// Writes an unsigned big-endian 16-bit value.
    private static void putUnsignedShort(ByteBuffer output, int value) {
        output.put((byte) (value >>> 8));
        output.put((byte) value);
    }

    /// Copies as many staged bytes as fit in the caller-owned target.
    private void copyPending(ByteBuffer target) {
        int length = Math.min(pendingOutput.remaining(), target.remaining());
        if (length == 0) {
            return;
        }
        int originalLimit = pendingOutput.limit();
        pendingOutput.limit(pendingOutput.position() + length);
        try {
            target.put(pendingOutput);
        } finally {
            pendingOutput.limit(originalLimit);
        }
    }

    /// Requires the exact active state for an operation that accepts source bytes.
    private void requireState(State required, String operation) {
        requireOpen();
        if (state != required) {
            throw new IllegalStateException("Cannot " + operation + " while LZMA2 encoder state is " + state);
        }
    }

    /// Requires this encoder to remain open.
    private void requireOpen() {
        if (state == State.CLOSED) {
            throw new IllegalStateException("LZMA2 encoder is closed");
        }
    }

    /// Enumerates the LZMA2 encoder lifecycle states.
    @NotNullByDefault
    private enum State {
        /// Source bytes may be accepted.
        ACTIVE,

        /// A flush boundary must be drained before encoding continues.
        FLUSHING,

        /// Final chunk bytes must be prepared and drained.
        FINISHING,

        /// The stream end control byte must be drained.
        TRAILER,

        /// The stream has completed.
        FINISHED,

        /// The encoder has been closed.
        CLOSED
    }
}
