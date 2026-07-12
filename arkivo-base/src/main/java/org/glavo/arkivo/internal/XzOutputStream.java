// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;

/// Writes a standards-compliant XZ stream containing one streaming LZMA2 block.
@NotNullByDefault
public final class XzOutputStream extends OutputStream {
    /// The default LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The destination owned by this stream.
    private final OutputStream output;

    /// The LZMA2 dictionary size.
    private final int dictionarySize;

    /// The stream integrity-check type.
    private final int checkType;

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// The active block check after the first write.
    private @Nullable XzCheck blockCheck;

    /// The compressed-data byte counter after the first write.
    private @Nullable CountingOutputStream compressedCounter;

    /// The active LZMA2 block encoder after the first write.
    private @Nullable Lzma2OutputStream blockEncoder;

    /// The block's uncompressed size.
    private long uncompressedSize;

    /// The block's unpadded size after it has finished.
    private long unpaddedSize;

    /// Whether the stream footer has been written.
    private boolean finished;

    /// Whether the destination has closed.
    private boolean closed;

    /// Creates an XZ stream with the default dictionary and CRC-64 integrity check.
    public XzOutputStream(OutputStream output) throws IOException {
        this(output, DEFAULT_DICTIONARY_SIZE, XzSupport.CHECK_CRC64);
    }

    /// Creates an XZ stream with explicit dictionary and integrity-check settings.
    public XzOutputStream(OutputStream output, int dictionarySize, int checkType) throws IOException {
        this.output = Objects.requireNonNull(output, "output");
        XzSupport.lzma2DictionaryProperty(dictionarySize);
        XzCheck.create(checkType);
        this.dictionarySize = dictionarySize;
        this.checkType = checkType;
        writeStreamHeader();
    }

    /// Writes one uncompressed byte.
    @Override
    public void write(int value) throws IOException {
        singleByte[0] = (byte) value;
        write(singleByte, 0, 1);
    }

    /// Writes uncompressed bytes to the single streaming block.
    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureWritable();
        if (length == 0) {
            return;
        }
        startBlock();
        if (uncompressedSize > Long.MAX_VALUE - length) {
            throw new IOException("XZ block uncompressed size overflow");
        }
        java.util.Objects.requireNonNull(blockCheck).update(bytes, offset, length);
        java.util.Objects.requireNonNull(blockEncoder).write(bytes, offset, length);
        uncompressedSize += length;
    }

    /// Flushes bytes already emitted by the active LZMA2 encoder.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        Lzma2OutputStream encoder = blockEncoder;
        if (encoder != null) {
            encoder.flush();
        }
        output.flush();
    }

    /// Finishes the block, Index, and Stream Footer without closing the destination.
    public void finish() throws IOException {
        ensureOpen();
        if (finished) {
            return;
        }
        finishBlock();
        byte[] index = createIndex();
        output.write(index);
        writeStreamFooter(index.length);
        output.flush();
        finished = true;
    }

    /// Finishes this XZ stream and closes the destination.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        @Nullable Throwable failure = null;
        try {
            finish();
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        closed = true;
        try {
            output.close();
        } catch (IOException | RuntimeException | Error exception) {
            failure = appendFailure(failure, exception);
        }
        rethrow(failure);
    }

    /// Writes the fixed stream header and its CRC-32.
    private void writeStreamHeader() throws IOException {
        byte[] header = new byte[12];
        System.arraycopy(XzSupport.HEADER_MAGIC, 0, header, 0, XzSupport.HEADER_MAGIC.length);
        header[6] = 0;
        header[7] = (byte) checkType;
        XzSupport.putLittleEndian(header, 8, XzSupport.crc32(header, 6, 2), Integer.BYTES);
        output.write(header);
    }

    /// Lazily writes the block header and opens its LZMA2 encoder.
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

        XzCheck check = XzCheck.create(checkType);
        CountingOutputStream counter = new CountingOutputStream(output);
        blockCheck = check;
        compressedCounter = counter;
        blockEncoder = new Lzma2OutputStream(new NonClosingOutputStream(counter), dictionarySize);
    }

    /// Finishes the active block, padding, and integrity check.
    private void finishBlock() throws IOException {
        Lzma2OutputStream encoder = blockEncoder;
        if (encoder == null) {
            return;
        }
        encoder.close();
        long compressedSize = java.util.Objects.requireNonNull(compressedCounter).count();
        for (long padded = compressedSize; (padded & 3L) != 0L; padded++) {
            output.write(0);
        }
        byte[] check = java.util.Objects.requireNonNull(blockCheck).finish();
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

    /// Writes the stream footer matching the Index and header flags.
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

    /// Requires this stream to remain open and unfinished.
    private void ensureWritable() throws IOException {
        ensureOpen();
        if (finished) {
            throw new IOException("XZ stream has already finished");
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Appends a close-time failure.
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

    /// Counts bytes without owning the wrapped destination.
    @NotNullByDefault
    private static final class CountingOutputStream extends OutputStream {
        /// The wrapped destination.
        private final OutputStream output;

        /// The number of forwarded bytes.
        private long count;

        /// Creates a counting wrapper.
        private CountingOutputStream(OutputStream output) {
            this.output = output;
        }

        /// Writes one byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
            count++;
        }

        /// Writes bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
            count += length;
        }

        /// Flushes the wrapped destination.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Returns the forwarded byte count.
        private long count() {
            return count;
        }
    }

    /// Prevents a nested block encoder from closing the XZ destination.
    @NotNullByDefault
    private static final class NonClosingOutputStream extends OutputStream {
        /// The wrapped destination.
        private final OutputStream output;

        /// Creates a non-closing wrapper.
        private NonClosingOutputStream(OutputStream output) {
            this.output = output;
        }

        /// Writes one byte.
        @Override
        public void write(int value) throws IOException {
            output.write(value);
        }

        /// Writes bytes.
        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            output.write(bytes, offset, length);
        }

        /// Flushes the wrapped destination.
        @Override
        public void flush() throws IOException {
            output.flush();
        }

        /// Leaves the wrapped destination open.
        @Override
        public void close() {
        }
    }
}
