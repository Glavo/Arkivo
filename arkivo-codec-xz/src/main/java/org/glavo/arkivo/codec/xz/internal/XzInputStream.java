// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.transform.TransformingInputStream;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.codec.lzma.internal.Lzma2InputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/// Decodes XZ streams with LZMA2, Delta, and every standardized BCJ filter.
@NotNullByDefault
public final class XzInputStream extends InputStream {
    /// The compressed source with one-byte concatenated-stream lookahead.
    private final PushbackInputStream input;

    /// Whether stream padding may be followed by another XZ stream.
    private final boolean concatenated;

    /// Completed block records awaiting validation against the Index.
    private final List<BlockRecord> records = new ArrayList<>();

    /// The reusable single-byte buffer.
    private final byte[] singleByte = new byte[1];

    /// The current stream's integrity-check type.
    private int checkType;

    /// The active block decoder, or `null` between blocks.
    private @Nullable BlockInput currentBlock;

    /// Whether no further concatenated stream remains.
    private boolean endReached;

    /// Whether this stream has closed.
    private boolean closed;

    /// Creates a decoder that accepts concatenated XZ streams and stream padding.
    public XzInputStream(InputStream input) throws IOException {
        this(input, true);
    }

    /// Creates a decoder that optionally stops exactly after one XZ Stream Footer.
    public XzInputStream(InputStream input, boolean concatenated) throws IOException {
        this.input = new PushbackInputStream(Objects.requireNonNull(input, "input"), 1);
        this.concatenated = concatenated;
        startStream();
    }

    /// Reads one uncompressed byte.
    @Override
    public int read() throws IOException {
        int count = read(singleByte, 0, 1);
        return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
    }

    /// Reads uncompressed bytes across block and optional concatenated-stream boundaries.
    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        ensureOpen();
        if (length == 0) {
            return 0;
        }

        int total = 0;
        while (total < length) {
            if (endReached) {
                return total == 0 ? -1 : total;
            }

            BlockInput block = currentBlock;
            if (block == null) {
                int indicator = XzSupport.readRequiredByte(input);
                if (indicator == 0) {
                    readIndexAndFooter();
                    continue;
                }
                block = new BlockInput(indicator);
                currentBlock = block;
            }

            int count = block.read(bytes, offset + total, length - total);
            if (count < 0) {
                records.add(new BlockRecord(block.unpaddedSize(), block.uncompressedSize()));
                currentBlock = null;
            } else {
                total += count;
            }
        }
        return total;
    }

    /// Returns bytes immediately available from the active block.
    @Override
    public int available() throws IOException {
        ensureOpen();
        BlockInput block = currentBlock;
        return block == null ? 0 : block.available();
    }

    /// Closes the compressed source.
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        input.close();
    }

    /// Reads and validates one Stream Header.
    private void startStream() throws IOException {
        byte[] header = new byte[12];
        XzSupport.readFully(input, header, 0, header.length);
        for (int index = 0; index < XzSupport.HEADER_MAGIC.length; index++) {
            if (header[index] != XzSupport.HEADER_MAGIC[index]) {
                throw new IOException("Invalid XZ stream header signature");
            }
        }
        if (XzSupport.crc32Mismatch(header, 6, 2, 8)) {
            throw new IOException("XZ Stream Header CRC-32 mismatch");
        }
        if (header[6] != 0 || Byte.toUnsignedInt(header[7]) >= 16) {
            throw new IOException("Unsupported XZ Stream Header flags");
        }
        checkType = Byte.toUnsignedInt(header[7]);
        XzCheck.create(checkType);
        records.clear();
        currentBlock = null;
    }

    /// Reads the Index and matching Stream Footer after its zero indicator.
    private void readIndexAndFooter() throws IOException {
        long indexSize = readIndex();

        byte[] footer = new byte[12];
        XzSupport.readFully(input, footer, 0, footer.length);
        if (footer[10] != XzSupport.FOOTER_MAGIC[0]
                || footer[11] != XzSupport.FOOTER_MAGIC[1]
                || XzSupport.crc32Mismatch(footer, 4, 6, 0)) {
            throw new IOException("Invalid XZ Stream Footer");
        }
        if (footer[8] != 0 || Byte.toUnsignedInt(footer[9]) != checkType) {
            throw new IOException("XZ Stream Header and Footer flags differ");
        }
        long backwardSize = (XzSupport.getLittleEndian(footer, 4, Integer.BYTES) + 1L) * 4L;
        if (backwardSize != indexSize) {
            throw new IOException("XZ Stream Footer backward size does not match the Index");
        }
        advanceAfterFooter();
    }

    /// Reads and validates the complete Index, returning its encoded size.
    private long readIndex() throws IOException {
        IndexReader index = new IndexReader();
        long recordCount = index.readVli();
        if (recordCount != records.size()) {
            throw new IOException("XZ Index record count does not match decoded blocks");
        }
        for (BlockRecord record : records) {
            if (index.readVli() != record.unpaddedSize()
                    || index.readVli() != record.uncompressedSize()) {
                throw new IOException("XZ Index record does not match its decoded block");
            }
        }
        index.readPadding();
        index.verifyCrc();
        return index.encodedSize();
    }

    /// Stops after one stream or consumes legal stream padding before a concatenated stream.
    private void advanceAfterFooter() throws IOException {
        if (!concatenated) {
            endReached = true;
            return;
        }

        int padding = 0;
        while (true) {
            int value = input.read();
            if (value < 0) {
                if ((padding & 3) != 0) {
                    throw new IOException("XZ stream padding is not a multiple of four bytes");
                }
                endReached = true;
                return;
            }
            if (value == 0) {
                padding++;
                continue;
            }
            if ((padding & 3) != 0) {
                throw new IOException("XZ stream padding is not a multiple of four bytes");
            }
            input.unread(value);
            startStream();
            return;
        }
    }

    /// Requires this stream to remain open.
    private void ensureOpen() throws IOException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    /// Describes one decoded block for Index validation.
    ///
    /// @param unpaddedSize     the block header, compressed data, and check size without block padding
    /// @param uncompressedSize the block's decoded byte count
    private record BlockRecord(long unpaddedSize, long uncompressedSize) {
    }

    /// Describes one filter and its raw properties from a Block Header.
    ///
    /// @param identifier the XZ filter identifier
    /// @param properties the filter property bytes
    private record Filter(long identifier, byte[] properties) {
    }

    /// Decodes and validates one XZ block.
    @NotNullByDefault
    private final class BlockInput extends InputStream {
        /// The decoded block header size.
        private final int headerSize;

        /// The compressed size declared by the Block Header, or `-1` when omitted.
        private final long declaredCompressedSize;

        /// The uncompressed size declared by the Block Header, or `-1` when omitted.
        private final long declaredUncompressedSize;

        /// The compressed-data counter and optional declared-size boundary.
        private final CountingInputStream compressedInput;

        /// The complete reverse decoding filter chain.
        private final InputStream filterChain;

        /// The block integrity-check calculator.
        private final XzCheck check;

        /// The block's decoded byte count.
        private long uncompressedSize;

        /// Whether block padding and the integrity check have been validated.
        private boolean validated;

        /// Creates a block decoder after its nonzero header-size indicator has been read.
        private BlockInput(int headerIndicator) throws IOException {
            headerSize = 4 * (headerIndicator + 1);
            byte[] header = new byte[headerSize];
            header[0] = (byte) headerIndicator;
            XzSupport.readFully(input, header, 1, header.length - 1);
            if (XzSupport.crc32Mismatch(header, 0, header.length - 4, header.length - 4)) {
                throw new IOException("XZ Block Header CRC-32 mismatch");
            }

            int flags = Byte.toUnsignedInt(header[1]);
            if ((flags & 0x3c) != 0) {
                throw new IOException("Unsupported XZ Block Header flags");
            }
            int filterCount = (flags & 3) + 1;
            ByteArrayInputStream fields = new ByteArrayInputStream(header, 2, header.length - 6);

            long compressedSize = -1L;
            long outputSize = -1L;
            try {
                if ((flags & 0x40) != 0) {
                    compressedSize = XzSupport.readVli(fields);
                    if (compressedSize == 0L) {
                        throw new IOException("XZ block declares an empty compressed-data field");
                    }
                }
                if ((flags & 0x80) != 0) {
                    outputSize = XzSupport.readVli(fields);
                }

                Filter[] filters = new Filter[filterCount];
                for (int index = 0; index < filters.length; index++) {
                    long identifier = XzSupport.readVli(fields);
                    long propertySize = XzSupport.readVli(fields);
                    if (propertySize > fields.available()) {
                        throw new IOException("XZ filter properties exceed the Block Header");
                    }
                    byte[] properties = new byte[(int) propertySize];
                    XzSupport.readFully(fields, properties, 0, properties.length);
                    filters[index] = new Filter(identifier, properties);
                }
                while (fields.available() > 0) {
                    if (fields.read() != 0) {
                        throw new IOException("Nonzero XZ Block Header padding");
                    }
                }

                validateFilterChain(filters);
                declaredCompressedSize = compressedSize;
                declaredUncompressedSize = outputSize;
                check = XzCheck.create(checkType);
                compressedInput = new CountingInputStream(
                        input,
                        compressedSize >= 0L ? compressedSize : Long.MAX_VALUE
                );
                InputStream chain = compressedInput;
                for (int index = filters.length - 1; index >= 0; index--) {
                    chain = openFilter(chain, filters[index]);
                }
                filterChain = chain;
            } catch (IOException exception) {
                throw new IOException("Invalid XZ Block Header", exception);
            }
        }

        /// Reads one decoded block byte.
        @Override
        public int read() throws IOException {
            int count = read(singleByte, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(singleByte[0]);
        }

        /// Reads decoded block bytes and updates size and integrity state.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            if (validated) {
                return -1;
            }

            int count = filterChain.read(bytes, offset, length);
            if (count == 0) {
                int value = filterChain.read();
                if (value < 0) {
                    validateEnd();
                    return -1;
                }
                bytes[offset] = (byte) value;
                count = 1;
            } else if (count < 0) {
                validateEnd();
                return -1;
            }

            check.update(bytes, offset, count);
            if (uncompressedSize > Long.MAX_VALUE - count) {
                throw new IOException("XZ block uncompressed size overflow");
            }
            uncompressedSize += count;
            if (declaredUncompressedSize >= 0L && uncompressedSize > declaredUncompressedSize) {
                throw new IOException("XZ block exceeds its declared uncompressed size");
            }
            if (declaredUncompressedSize >= 0L && uncompressedSize == declaredUncompressedSize) {
                if (filterChain.read() >= 0) {
                    throw new IOException("XZ block contains output beyond its declared size");
                }
                validateEnd();
            }
            return count;
        }

        /// Returns bytes immediately available through the filter chain.
        @Override
        public int available() throws IOException {
            return validated ? 0 : filterChain.available();
        }

        /// Returns this block's unpadded size after validation.
        private long unpaddedSize() {
            return headerSize + compressedInput.count() + check.size();
        }

        /// Returns this block's decoded size after validation.
        private long uncompressedSize() {
            return uncompressedSize;
        }

        /// Validates compressed sizes, block padding, and the integrity check.
        private void validateEnd() throws IOException {
            if (validated) {
                return;
            }
            long compressedSize = compressedInput.count();
            if (declaredCompressedSize >= 0L && declaredCompressedSize != compressedSize) {
                throw new IOException("XZ block compressed size mismatch");
            }
            if (declaredUncompressedSize >= 0L && declaredUncompressedSize != uncompressedSize) {
                throw new IOException("XZ block uncompressed size mismatch");
            }
            for (long padded = compressedSize; (padded & 3L) != 0L; padded++) {
                if (XzSupport.readRequiredByte(input) != 0) {
                    throw new IOException("Nonzero XZ block padding");
                }
            }
            byte[] storedCheck = new byte[check.size()];
            XzSupport.readFully(input, storedCheck, 0, storedCheck.length);
            if (!Arrays.equals(storedCheck, check.finish())) {
                throw new IOException("XZ block integrity check mismatch");
            }
            long size = headerSize + compressedSize + check.size();
            if (size < 0L) {
                throw new IOException("XZ block unpadded size overflow");
            }
            validated = true;
        }

        /// Validates the supported XZ filter ordering.
        private void validateFilterChain(Filter[] filters) throws IOException {
            if (filters[filters.length - 1].identifier() != XzSupport.FILTER_LZMA2) {
                throw new IOException("XZ filter chain must end with LZMA2");
            }
            for (int index = 0; index < filters.length - 1; index++) {
                long identifier = filters[index].identifier();
                if (identifier != XzSupport.FILTER_DELTA
                        && (identifier < XzSupport.FILTER_BCJ_X86
                        || identifier > XzSupport.FILTER_BCJ_RISCV)) {
                    throw new IOException("Unsupported nonterminal XZ filter: " + identifier);
                }
            }
        }

        /// Opens one reverse filter over its downstream compressed representation.
        private InputStream openFilter(InputStream downstream, Filter filter) throws IOException {
            long identifier = filter.identifier();
            byte[] properties = filter.properties();
            if (identifier == XzSupport.FILTER_LZMA2) {
                if (properties.length != 1) {
                    throw new IOException("XZ LZMA2 filter requires one property byte");
                }
                return new Lzma2InputStream(
                        downstream,
                        XzSupport.lzma2DictionarySize(Byte.toUnsignedInt(properties[0]))
                );
            }
            if (identifier == XzSupport.FILTER_DELTA) {
                if (properties.length != 1) {
                    throw new IOException("XZ Delta filter requires one property byte");
                }
                return new TransformingInputStream(
                        downstream,
                        new DeltaTransform(false, Byte.toUnsignedInt(properties[0]) + 1)
                );
            }
            if (identifier >= XzSupport.FILTER_BCJ_X86 && identifier <= XzSupport.FILTER_BCJ_RISCV) {
                int startOffset;
                if (properties.length == 0) {
                    startOffset = 0;
                } else if (properties.length == Integer.BYTES) {
                    startOffset = (int) XzSupport.getLittleEndian(properties, 0, Integer.BYTES);
                } else {
                    throw new IOException("XZ BCJ filter properties must contain zero or four bytes");
                }
                return new TransformingInputStream(downstream, bcjTransform(identifier, startOffset));
            }
            throw new IOException("Unsupported XZ filter identifier: " + identifier);
        }

        /// Creates one BCJ decoding transform.
        private ByteTransform bcjTransform(long identifier, int startOffset) {
            return switch ((int) identifier) {
                case 0x04 -> BCJTransforms.x86(false, startOffset);
                case 0x05 -> BCJTransforms.powerPc(false, startOffset);
                case 0x06 -> BCJTransforms.ia64(false, startOffset);
                case 0x07 -> BCJTransforms.arm(false, startOffset);
                case 0x08 -> BCJTransforms.armThumb(false, startOffset);
                case 0x09 -> BCJTransforms.sparc(false, startOffset);
                case 0x0a -> BCJTransforms.arm64(false, startOffset);
                case 0x0b -> BCJTransforms.riscV(false, startOffset);
                default -> throw new AssertionError(identifier);
            };
        }
    }

    /// Counts compressed block bytes and enforces an optional declared-size boundary.
    @NotNullByDefault
    private static final class CountingInputStream extends InputStream {
        /// The underlying XZ stream.
        private final InputStream input;

        /// The maximum number of bytes visible to this block.
        private final long limit;

        /// The number of bytes consumed by this block.
        private long count;

        /// Creates a bounded counting source.
        private CountingInputStream(InputStream input, long limit) {
            this.input = input;
            this.limit = limit;
        }

        /// Reads one compressed byte.
        @Override
        public int read() throws IOException {
            if (count == limit) {
                return -1;
            }
            int value = input.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        /// Reads compressed bytes without exceeding the declared limit.
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            long remaining = limit - count;
            if (remaining == 0L) {
                return -1;
            }
            int requested = (int) Math.min(length, remaining);
            int read = input.read(bytes, offset, requested);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        /// Returns the consumed compressed byte count.
        private long count() {
            return count;
        }
    }

    /// Reads the Index while maintaining its encoded size and CRC-32.
    @NotNullByDefault
    private final class IndexReader {
        /// The Index CRC-32 over its indicator, records, and padding.
        private final CRC32 crc32 = new CRC32();

        /// The Index byte count excluding its final CRC-32.
        private long byteCount = 1L;

        /// Creates an Index reader after the zero indicator has been consumed.
        private IndexReader() {
            crc32.update(0);
        }

        /// Reads one canonical tracked variable-length integer.
        private long readVli() throws IOException {
            long value = 0L;
            for (int index = 0; index < 9; index++) {
                int current = readTrackedByte();
                if (index == 8 && (current & 0x80) != 0) {
                    throw new IOException("XZ Index variable-length integer is too large");
                }
                value |= (long) (current & 0x7f) << (index * 7);
                if ((current & 0x80) == 0) {
                    if (index != 0 && (current & 0x7f) == 0) {
                        throw new IOException("Non-canonical XZ Index variable-length integer");
                    }
                    return value;
                }
            }
            throw new IOException("XZ Index variable-length integer is too large");
        }

        /// Reads zero padding until the Index body is four-byte aligned.
        private void readPadding() throws IOException {
            while ((byteCount & 3L) != 0L) {
                if (readTrackedByte() != 0) {
                    throw new IOException("Nonzero XZ Index padding");
                }
            }
        }

        /// Verifies the stored Index CRC-32.
        private void verifyCrc() throws IOException {
            byte[] stored = new byte[Integer.BYTES];
            XzSupport.readFully(input, stored, 0, stored.length);
            if (XzSupport.getLittleEndian(stored, 0, stored.length) != crc32.getValue()) {
                throw new IOException("XZ Index CRC-32 mismatch");
            }
        }

        /// Returns the complete encoded Index size including its CRC-32.
        private long encodedSize() {
            return byteCount + Integer.BYTES;
        }

        /// Reads one Index byte and updates its CRC and size.
        private int readTrackedByte() throws IOException {
            int value = XzSupport.readRequiredByte(input);
            crc32.update(value);
            byteCount++;
            return value;
        }
    }
}
