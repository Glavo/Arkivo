// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.gzip.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/// Encodes gzip members directly between ByteBuffers and a target channel.
@NotNullByDefault
public final class GzipChannelEncoder implements CompressionEncoder {
    /// The owned input staging-buffer size.
    private static final int INPUT_BUFFER_SIZE = 8192;

    /// The compressed output staging-buffer size.
    private static final int OUTPUT_BUFFER_SIZE = 8192;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Whether this context owns the target channel.
    private final ChannelOwnership ownership;

    /// The raw deflate context used for the member body.
    private final Deflater deflater;

    /// The member-content checksum.
    private final CRC32 checksum = new CRC32();

    /// The direct owned input staging buffer.
    private final ByteBuffer inputBuffer = ByteBuffer.allocateDirect(INPUT_BUFFER_SIZE);

    /// The direct compressed output staging buffer.
    private final ByteBuffer outputBuffer = ByteBuffer.allocateDirect(OUTPUT_BUFFER_SIZE);

    /// The number of uncompressed bytes consumed.
    private long inputBytes;

    /// The number of compressed bytes written, including the header and trailer.
    private long outputBytes;

    /// The member input size modulo 2^32.
    private long memberSize;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates a gzip encoder and writes its fixed member header.
    ///
    /// @param target compressed-data target
    /// @param ownership whether this context closes the target
    /// @param compressionLevel JDK deflate compression level
    public GzipChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int compressionLevel
    ) throws IOException {
        this.target = Objects.requireNonNull(target, "target");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.deflater = new Deflater(compressionLevel, true);
        try {
            writeHeader(compressionLevel);
        } catch (IOException | RuntimeException | Error exception) {
            deflater.end();
            throw exception;
        }
    }

    /// Consumes uncompressed bytes and updates the member checksum and size.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }

        int start = source.position();
        try {
            while (source.hasRemaining()) {
                stageInput(source);
                ByteBuffer checksumInput = inputBuffer.asReadOnlyBuffer();
                checksum.update(checksumInput);
                memberSize = (memberSize + inputBuffer.remaining()) & 0xffff_ffffL;

                deflater.setInput(inputBuffer);
                do {
                    int inputPosition = inputBuffer.position();
                    int produced = deflate(Deflater.NO_FLUSH);
                    if (produced == 0 && inputBuffer.position() == inputPosition) {
                        throw new IOException("Gzip encoder made no progress");
                    }
                } while (inputBuffer.hasRemaining());
            }
            return source.position() - start;
        } finally {
            inputBytes += source.position() - start;
        }
    }

    /// Flushes pending member data without ending the member.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        while (deflate(Deflater.SYNC_FLUSH) == outputBuffer.capacity()) {
            // Continue until the deflater no longer fills the complete staging buffer.
        }
    }

    /// Finishes the member, writes its trailer, and releases native resources.
    @Override
    public void finish() throws IOException {
        if (!open) {
            return;
        }

        @Nullable Throwable failure = null;
        try {
            deflater.finish();
            while (!deflater.finished()) {
                if (deflate(Deflater.NO_FLUSH) == 0 && !deflater.finished()) {
                    throw new IOException("Gzip encoder could not finish the member");
                }
            }
            writeTrailer();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            open = false;
            deflater.end();
            closeOwnedTarget(failure);
        }
    }

    /// Returns the consumed uncompressed byte count.
    @Override
    public long inputBytes() {
        return inputBytes;
    }

    /// Returns the emitted gzip byte count.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes the encoder.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Writes the standard fixed gzip member header.
    private void writeHeader(int compressionLevel) throws IOException {
        int extraFlags = compressionLevel == Deflater.BEST_COMPRESSION
                ? 2
                : compressionLevel == Deflater.BEST_SPEED ? 4 : 0;
        ByteBuffer header = ByteBuffer.wrap(new byte[]{
                0x1f, (byte) 0x8b, 8, 0,
                0, 0, 0, 0,
                (byte) extraFlags, (byte) 0xff
        });
        writeFully(header);
    }

    /// Writes the checksum and uncompressed-size trailer in little-endian order.
    private void writeTrailer() throws IOException {
        ByteBuffer trailer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        trailer.putInt((int) checksum.getValue());
        trailer.putInt((int) memberSize);
        trailer.flip();
        writeFully(trailer);
    }

    /// Deflates into the output staging buffer and drains it to the target.
    private int deflate(int flushMode) throws IOException {
        outputBuffer.clear();
        int produced = deflater.deflate(outputBuffer, flushMode);
        outputBuffer.flip();
        writeFully(outputBuffer);
        return produced;
    }

    /// Copies one bounded source range into the owned direct input buffer.
    private void stageInput(ByteBuffer source) {
        inputBuffer.clear();
        int count = Math.min(source.remaining(), inputBuffer.capacity());
        ByteBuffer chunk = source.slice();
        chunk.limit(count);
        inputBuffer.put(chunk);
        source.position(source.position() + count);
        inputBuffer.flip();
    }

    /// Writes all remaining bytes and updates the compressed-output counter.
    private void writeFully(ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            int written = target.write(source);
            if (written == 0) {
                throw new IOException("Gzip target channel made no progress");
            }
            outputBytes += written;
        }
    }

    /// Closes the target when this context owns it without hiding an earlier failure.
    private void closeOwnedTarget(@Nullable Throwable failure) throws IOException {
        if (ownership != ChannelOwnership.CLOSE) {
            return;
        }
        try {
            target.close();
        } catch (IOException | RuntimeException | Error exception) {
            if (failure != null) {
                failure.addSuppressed(exception);
                return;
            }
            throw exception;
        }
    }

    /// Requires the encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }
}
