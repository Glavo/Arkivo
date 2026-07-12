// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.glavo.arkivo.codec.lzma.internal.Lzma2ChannelEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;

/// Encodes a standards-compliant single-block XZ stream directly to a channel.
@NotNullByDefault
public final class XzChannelEncoder implements CompressionEncoder {
    /// The default LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Whether this context owns the target.
    private final ChannelOwnership ownership;

    /// The buffered XZ byte target.
    private final XzChannelOutput output;

    /// The LZMA2 dictionary size.
    private final int dictionarySize;

    /// The stream integrity-check type.
    private final int checkType;

    /// The reusable uncompressed transfer buffer.
    private final byte[] transferBuffer = new byte[8192];

    /// The active block check after the first write.
    private @Nullable XzCheck blockCheck;

    /// The compressed-data counter after the first write.
    private @Nullable CountingChannel compressedCounter;

    /// The active LZMA2 block encoder after the first write.
    private @Nullable Lzma2ChannelEncoder blockEncoder;

    /// The block's uncompressed size.
    private long uncompressedSize;

    /// The block's unpadded size after it has finished.
    private long unpaddedSize;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an XZ encoder with explicit dictionary and integrity-check settings.
    public XzChannelEncoder(
            WritableByteChannel target,
            ChannelOwnership ownership,
            int dictionarySize,
            int checkType
    ) throws IOException {
        this.target = Objects.requireNonNull(target, "target");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        XzSupport.lzma2DictionaryProperty(dictionarySize);
        XzCheck.create(checkType);
        this.dictionarySize = dictionarySize;
        this.checkType = checkType;
        output = new XzChannelOutput(target);
        writeStreamHeader();
    }

    /// Consumes uncompressed bytes for the single XZ block.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }
        startBlock();
        int start = source.position();
        Lzma2ChannelEncoder encoder = Objects.requireNonNull(blockEncoder);
        XzCheck check = Objects.requireNonNull(blockCheck);
        while (source.hasRemaining()) {
            int count = Math.min(source.remaining(), transferBuffer.length);
            source.get(transferBuffer, 0, count);
            check.update(transferBuffer, 0, count);
            encoder.write(ByteBuffer.wrap(transferBuffer, 0, count));
        }
        int count = source.position() - start;
        if (uncompressedSize > Long.MAX_VALUE - count) {
            throw new IOException("XZ block uncompressed size overflow");
        }
        uncompressedSize += count;
        return count;
    }

    /// Flushes bytes already emitted by the active LZMA2 encoder.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        Lzma2ChannelEncoder encoder = blockEncoder;
        if (encoder != null) {
            encoder.flush();
        }
        output.flush();
    }

    /// Finishes the block, Index, Stream Footer, and encoder context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            finishBlock();
            byte[] index = createIndex();
            output.write(index);
            writeStreamFooter(index.length);
            output.flush();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        if (ownership == ChannelOwnership.CLOSE) {
            try {
                target.close();
            } catch (IOException | RuntimeException | Error exception) {
                failure = appendFailure(failure, exception);
            }
        }
        rethrow(failure);
    }

    /// Returns the number of uncompressed bytes accepted.
    @Override
    public long inputBytes() {
        return uncompressedSize;
    }

    /// Returns the number of XZ bytes written to the target.
    @Override
    public long outputBytes() {
        return output.byteCount();
    }

    /// Returns whether this encoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Finishes and closes this encoder context.
    @Override
    public void close() throws IOException {
        finish();
    }

    /// Writes the fixed Stream Header and its CRC-32.
    private void writeStreamHeader() throws IOException {
        byte[] header = new byte[12];
        System.arraycopy(XzSupport.HEADER_MAGIC, 0, header, 0, XzSupport.HEADER_MAGIC.length);
        header[6] = 0;
        header[7] = (byte) checkType;
        XzSupport.putLittleEndian(header, 8, XzSupport.crc32(header, 6, 2), Integer.BYTES);
        output.write(header);
    }

    /// Lazily writes the Block Header and opens its LZMA2 encoder.
    private void startBlock() throws IOException {
        if (blockEncoder != null) {
            return;
        }
        byte[] header = new byte[12];
        header[0] = 2;
        header[1] = 0;
        header[2] = (byte) XzSupport.FILTER_LZMA2;
        header[3] = 1;
        header[4] = (byte) XzSupport.lzma2DictionaryProperty(dictionarySize);
        XzSupport.putLittleEndian(header, 8, XzSupport.crc32(header, 0, 8), Integer.BYTES);
        output.write(header);

        CountingChannel counter = new CountingChannel(output);
        blockCheck = XzCheck.create(checkType);
        compressedCounter = counter;
        blockEncoder = new Lzma2ChannelEncoder(counter, ChannelOwnership.RETAIN, dictionarySize);
    }

    /// Finishes the active block, padding, and integrity check.
    private void finishBlock() throws IOException {
        Lzma2ChannelEncoder encoder = blockEncoder;
        if (encoder == null) {
            return;
        }
        encoder.finish();
        long compressedSize = Objects.requireNonNull(compressedCounter).count();
        for (long padded = compressedSize; (padded & 3L) != 0L; padded++) {
            output.write(0);
        }
        byte[] check = Objects.requireNonNull(blockCheck).finish();
        output.write(check);
        unpaddedSize = 12L + compressedSize + check.length;
        blockEncoder = null;
    }

    /// Creates the complete Index field and its CRC-32.
    private byte[] createIndex() throws IOException {
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        index.write(0);
        if (unpaddedSize == 0L) {
            XzSupport.writeVli(index, 0L);
        } else {
            XzSupport.writeVli(index, 1L);
            XzSupport.writeVli(index, unpaddedSize);
            XzSupport.writeVli(index, uncompressedSize);
        }
        while ((index.size() & 3) != 0) {
            index.write(0);
        }
        byte[] body = index.toByteArray();
        XzSupport.writeCrc32(index, XzSupport.crc32(body, 0, body.length));
        return index.toByteArray();
    }

    /// Writes the Stream Footer matching the Index and header flags.
    private void writeStreamFooter(int indexSize) throws IOException {
        byte[] footer = new byte[12];
        XzSupport.putLittleEndian(footer, 4, indexSize / 4L - 1L, Integer.BYTES);
        footer[8] = 0;
        footer[9] = (byte) checkType;
        footer[10] = XzSupport.FOOTER_MAGIC[0];
        footer[11] = XzSupport.FOOTER_MAGIC[1];
        XzSupport.putLittleEndian(footer, 0, XzSupport.crc32(footer, 4, 6), Integer.BYTES);
        output.write(footer);
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Appends one close-time failure.
    private static Throwable appendFailure(@Nullable Throwable failure, Throwable next) {
        if (failure == null) {
            return next;
        }
        failure.addSuppressed(next);
        return failure;
    }

    /// Rethrows a captured close-time failure with its original type.
    private static void rethrow(@Nullable Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException exception) {
            throw exception;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    /// Counts compressed block bytes forwarded to the XZ output.
    @NotNullByDefault
    private static final class CountingChannel implements WritableByteChannel {
        /// The XZ output target.
        private final XzChannelOutput output;

        /// The number of forwarded bytes.
        private long count;

        /// Whether this counting channel remains open.
        private boolean open = true;

        /// Creates a counting wrapper.
        private CountingChannel(XzChannelOutput output) {
            this.output = output;
        }

        /// Forwards and counts compressed bytes.
        @Override
        public int write(ByteBuffer source) throws IOException {
            int written = output.write(source);
            count += written;
            return written;
        }

        /// Returns whether this channel remains open.
        @Override
        public boolean isOpen() {
            return open;
        }

        /// Closes this wrapper without closing the XZ output.
        @Override
        public void close() {
            open = false;
        }

        /// Returns the forwarded byte count.
        private long count() {
            return count;
        }
    }
}
