// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.ChannelOwnership;
import org.glavo.arkivo.codec.CodecResult;
import org.glavo.arkivo.codec.CodecStatus;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.DecompressingReadableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.DecodeDirective;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.spi.StandardCodecOptionSupport;
import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.codec.lzma.internal.LZMA2ChannelDecoder;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.transform.TransformingReadableByteChannel;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/// Decodes XZ streams and their filter chains directly from a channel.
@NotNullByDefault
public final class XzChannelDecoder implements DecompressingReadableByteChannel {
    /// The compressed-data source.
    private final ReadableByteChannel source;

    /// Tracks closure of the owned compressed-data source.
    private final OwnedChannelCloser sourceCloser;

    /// The buffered XZ source.
    private final XzChannelInput input;

    /// Whether stream padding may be followed by another XZ stream.
    private final boolean concatenated;

    /// The maximum permitted LZMA2 dictionary size, or the unknown sentinel.
    private final long maximumWindowSize;

    /// Whether supported block integrity checks are calculated and compared.
    private final boolean verifyChecksums;

    /// Completed block records awaiting validation against the Index.
    private final List<BlockRecord> records = new ArrayList<>();

    /// The current stream's integrity-check type.
    private int checkType;

    /// The active block decoder.
    private @Nullable BlockDecoder currentBlock;

    /// The number of uncompressed bytes returned to callers.
    private long outputBytes;

    /// Whether a validated stream boundary is awaiting concatenation processing.
    private boolean streamBoundaryPending;

    /// Whether the last decode operation completed one stream.
    private boolean lastStreamFinished;

    /// Whether no further concatenated stream remains.
    private boolean endReached;

    /// Whether this decoder remains open.
    private boolean open = true;

    /// Creates a decoder accepting concatenated XZ streams and stream padding.
    public XzChannelDecoder(ReadableByteChannel source, ChannelOwnership ownership) throws IOException {
        this(source, ownership, true, CompressionCodec.UNKNOWN_SIZE, true);
    }

    /// Creates a decoder with explicit concatenated-stream behavior.
    public XzChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            boolean concatenated
    ) throws IOException {
        this(source, ownership, concatenated, CompressionCodec.UNKNOWN_SIZE, true);
    }

    /// Creates a decoder with explicit concatenation and maximum-window behavior.
    public XzChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            boolean concatenated,
            long maximumWindowSize
    ) throws IOException {
        this(source, ownership, concatenated, maximumWindowSize, true);
    }

    /// Creates a decoder with explicit concatenation, window, and block-check behavior.
    public XzChannelDecoder(
            ReadableByteChannel source,
            ChannelOwnership ownership,
            boolean concatenated,
            long maximumWindowSize,
            boolean verifyChecksums
    ) throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.sourceCloser = new OwnedChannelCloser(source, ownership);
        this.concatenated = concatenated;
        this.maximumWindowSize = maximumWindowSize;
        this.verifyChecksums = verifyChecksums;
        input = new XzChannelInput(source);
        try {
            startStream();
        } catch (IOException | RuntimeException | Error exception) {
            sourceCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Reads uncompressed bytes across block and stream boundaries.
    @Override
    public int read(ByteBuffer target) throws IOException {
        return readDecoded(target, false);
    }

    /// Decodes one increment while optionally stopping after the current XZ stream.
    @Override
    public CodecResult decode(ByteBuffer target, DecodeDirective directive) throws IOException {
        Objects.requireNonNull(directive, "directive");
        long inputBefore = input.byteCount();
        long outputBefore = outputBytes;
        boolean stopAtFrame = directive == DecodeDirective.STOP_AT_FRAME;
        int read = readDecoded(target, stopAtFrame);
        CodecStatus status = stopAtFrame && lastStreamFinished
                ? CodecStatus.FRAME_FINISHED
                : read < 0 ? CodecStatus.END_OF_INPUT : CodecStatus.ACTIVE;
        return new CodecResult(input.byteCount() - inputBefore, outputBytes - outputBefore, status);
    }

    /// Performs one decoded read with explicit stream-boundary behavior.
    private int readDecoded(ByteBuffer target, boolean stopAtFrame) throws IOException {
        Objects.requireNonNull(target, "target");
        ensureOpen();
        lastStreamFinished = false;
        if (!target.hasRemaining()) {
            return 0;
        }

        int start = target.position();
        while (target.hasRemaining()) {
            if (streamBoundaryPending) {
                advanceAfterFooter();
            }
            if (endReached) {
                break;
            }
            BlockDecoder block = currentBlock;
            if (block == null) {
                int indicator = readRequiredByte();
                if (indicator == 0) {
                    readIndexAndFooter();
                    lastStreamFinished = true;
                    if (stopAtFrame) {
                        break;
                    }
                    continue;
                }
                block = new BlockDecoder(indicator);
                currentBlock = block;
            }

            int count = block.read(target);
            if (count < 0) {
                records.add(new BlockRecord(block.unpaddedSize(), block.uncompressedSize()));
                currentBlock = null;
            }
        }
        int count = target.position() - start;
        outputBytes += count;
        if (stopAtFrame && lastStreamFinished) {
            return count;
        }
        return count == 0 && endReached ? -1 : count;
    }

    /// Returns the number of logical XZ source bytes consumed.
    @Override
    public long inputBytes() {
        return input.byteCount();
    }

    /// Returns the number of bytes obtained from the source.
    @Override
    public long sourceBytes() {
        return input.sourceByteCount();
    }

    /// Returns a read-only view of bytes obtained but not consumed.
    @Override
    public @UnmodifiableView ByteBuffer unconsumedInput() {
        return input.unconsumedInput();
    }

    /// Returns the number of uncompressed bytes returned to callers.
    @Override
    public long outputBytes() {
        return outputBytes;
    }

    /// Returns whether this decoder remains open.
    @Override
    public boolean isOpen() {
        return open;
    }

    /// Closes this decoder and an owned source channel.
    @Override
    public void close() throws IOException {
        open = false;
        sourceCloser.close();
    }

    /// Reads and validates one Stream Header.
    private void startStream() throws IOException {
        byte[] header = readFully(12);
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
        XzCheck.sizeOf(checkType);
        if (verifyChecksums) {
            XzCheck.create(checkType);
        }
        records.clear();
        currentBlock = null;
    }

    /// Reads the Index and matching Stream Footer.
    private void readIndexAndFooter() throws IOException {
        long indexSize = readIndex();
        byte[] footer = readFully(12);
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
        streamBoundaryPending = true;
    }

    /// Reads and validates the complete Index.
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

    /// Stops after one stream or starts another stream after legal padding.
    private void advanceAfterFooter() throws IOException {
        streamBoundaryPending = false;
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

    /// Reads one required XZ byte.
    private int readRequiredByte() throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new IOException("Truncated XZ stream");
        }
        return value;
    }

    /// Reads an exact XZ byte range.
    private byte[] readFully(int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) {
                throw new IOException("Truncated XZ stream");
            }
            offset += count;
        }
        return bytes;
    }

    /// Requires this decoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Describes one decoded block for Index validation.
    ///
    /// @param unpaddedSize block header, compressed data, and check size without padding
    /// @param uncompressedSize decoded block size
    private record BlockRecord(long unpaddedSize, long uncompressedSize) {
    }

    /// Describes one filter and its raw properties.
    ///
    /// @param identifier XZ filter identifier
    /// @param properties filter property bytes
    private record Filter(long identifier, byte @Unmodifiable [] properties) {
    }

    /// Decodes and validates one XZ block.
    @NotNullByDefault
    private final class BlockDecoder {
        /// The decoded Block Header size.
        private final int headerSize;

        /// The compressed size declared by the Block Header.
        private final long declaredCompressedSize;

        /// The uncompressed size declared by the Block Header.
        private final long declaredUncompressedSize;

        /// The compressed-data counter and optional size boundary.
        private final CountingChannel compressedInput;

        /// The complete reverse decoding filter chain.
        private final ReadableByteChannel filterChain;

        /// The block integrity-check calculator, or `null` when verification is disabled.
        private final @Nullable XzCheck check;

        /// The encoded integrity-check field size.
        private final int checkSize;

        /// The reusable decoded-byte buffer.
        private final byte[] decodedBuffer = new byte[8192];

        /// The block's decoded byte count.
        private long uncompressedSize;

        /// Whether padding and the integrity check have been validated.
        private boolean validated;

        /// Creates a block decoder after its nonzero size indicator.
        private BlockDecoder(int headerIndicator) throws IOException {
            headerSize = 4 * (headerIndicator + 1);
            byte[] header = new byte[headerSize];
            header[0] = (byte) headerIndicator;
            byte[] remaining = readFully(header.length - 1);
            System.arraycopy(remaining, 0, header, 1, remaining.length);
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
                    int count = fields.read(properties);
                    if (count != properties.length) {
                        throw new IOException("Truncated XZ filter properties");
                    }
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
                checkSize = XzCheck.sizeOf(checkType);
                check = verifyChecksums ? XzCheck.create(checkType) : null;
                compressedInput = new CountingChannel(
                        compressedSize >= 0L ? compressedSize : Long.MAX_VALUE
                );
                ReadableByteChannel chain = compressedInput;
                for (int index = filters.length - 1; index >= 0; index--) {
                    chain = openFilter(chain, filters[index]);
                }
                filterChain = chain;
            } catch (DecompressionWindowLimitException exception) {
                throw exception;
            } catch (IOException exception) {
                throw new IOException("Invalid XZ Block Header", exception);
            }
        }

        /// Reads decoded block bytes into the target.
        private int read(ByteBuffer target) throws IOException {
            if (validated) {
                return -1;
            }
            int requested = Math.min(target.remaining(), decodedBuffer.length);
            ByteBuffer decoded = ByteBuffer.wrap(decodedBuffer, 0, requested);
            int count = filterChain.read(decoded);
            if (count < 0) {
                validateEnd();
                return -1;
            }
            if (count == 0) {
                throw new IOException("XZ filter chain made no progress");
            }
            @Nullable XzCheck activeCheck = check;
            if (activeCheck != null) {
                activeCheck.update(decodedBuffer, 0, count);
            }
            if (uncompressedSize > Long.MAX_VALUE - count) {
                throw new IOException("XZ block uncompressed size overflow");
            }
            uncompressedSize += count;
            if (declaredUncompressedSize >= 0L && uncompressedSize > declaredUncompressedSize) {
                throw new IOException("XZ block exceeds its declared uncompressed size");
            }
            target.put(decodedBuffer, 0, count);
            if (declaredUncompressedSize >= 0L && uncompressedSize == declaredUncompressedSize) {
                if (filterChain.read(ByteBuffer.allocate(1)) >= 0) {
                    throw new IOException("XZ block contains output beyond its declared size");
                }
                validateEnd();
            }
            return count;
        }

        /// Returns this block's unpadded size.
        private long unpaddedSize() {
            return headerSize + compressedInput.count() + checkSize;
        }

        /// Returns this block's decoded size.
        private long uncompressedSize() {
            return uncompressedSize;
        }

        /// Validates sizes, block padding, and the integrity check.
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
                if (readRequiredByte() != 0) {
                    throw new IOException("Nonzero XZ block padding");
                }
            }
            byte[] storedCheck = readFully(checkSize);
            @Nullable XzCheck activeCheck = check;
            if (activeCheck != null && !Arrays.equals(storedCheck, activeCheck.finish())) {
                throw new IOException("XZ block integrity check mismatch");
            }
            long size = headerSize + compressedSize + checkSize;
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

        /// Opens one reverse filter over its downstream representation.
        private ReadableByteChannel openFilter(ReadableByteChannel downstream, Filter filter) throws IOException {
            long identifier = filter.identifier();
            byte[] properties = filter.properties();
            if (identifier == XzSupport.FILTER_LZMA2) {
                if (properties.length != 1) {
                    throw new IOException("XZ LZMA2 filter requires one property byte");
                }
                int dictionarySize = XzSupport.lzma2DictionarySize(Byte.toUnsignedInt(properties[0]));
                StandardCodecOptionSupport.requireWindowSize(maximumWindowSize, dictionarySize);
                return new LZMA2ChannelDecoder(
                        downstream,
                        ChannelOwnership.RETAIN,
                        dictionarySize,
                        1
                );
            }
            if (identifier == XzSupport.FILTER_DELTA) {
                if (properties.length != 1) {
                    throw new IOException("XZ Delta filter requires one property byte");
                }
                return new TransformingReadableByteChannel(
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
                return new TransformingReadableByteChannel(downstream, bcjTransform(identifier, startOffset));
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

    /// Counts compressed block bytes and enforces an optional size boundary.
    @NotNullByDefault
    private final class CountingChannel implements ReadableByteChannel {
        /// The maximum number of bytes visible to this block.
        private final long limit;

        /// The number of bytes consumed by this block.
        private long count;

        /// Whether this wrapper remains open.
        private boolean channelOpen = true;

        /// Creates a bounded counting source.
        private CountingChannel(long limit) {
            this.limit = limit;
        }

        /// Reads compressed bytes without exceeding the boundary.
        @Override
        public int read(ByteBuffer target) throws IOException {
            Objects.requireNonNull(target, "target");
            if (!channelOpen) {
                throw new ClosedChannelException();
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            long remaining = limit - count;
            if (remaining == 0L) {
                return -1;
            }
            int requested = (int) Math.min(target.remaining(), remaining);
            ByteBuffer slice = target.slice();
            slice.limit(requested);
            int read = input.read(slice);
            if (read > 0) {
                target.position(target.position() + read);
                count += read;
            }
            return read;
        }

        /// Returns whether this wrapper remains open.
        @Override
        public boolean isOpen() {
            return channelOpen;
        }

        /// Closes this wrapper without closing the XZ source.
        @Override
        public void close() {
            channelOpen = false;
        }

        /// Returns the consumed compressed byte count.
        private long count() {
            return count;
        }
    }

    /// Reads the Index while maintaining encoded size and CRC-32.
    @NotNullByDefault
    private final class IndexReader {
        /// The Index CRC-32.
        private final CRC32 crc32 = new CRC32();

        /// The Index byte count excluding its final CRC-32.
        private long byteCount = 1L;

        /// Creates an Index reader after its zero indicator.
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
            byte[] stored = readFully(Integer.BYTES);
            if (XzSupport.getLittleEndian(stored, 0, stored.length) != crc32.getValue()) {
                throw new IOException("XZ Index CRC-32 mismatch");
            }
        }

        /// Returns the complete encoded Index size.
        private long encodedSize() {
            return byteCount + Integer.BYTES;
        }

        /// Reads one Index byte and updates its CRC and size.
        private int readTrackedByte() throws IOException {
            int value = readRequiredByte();
            crc32.update(value);
            byteCount++;
            return value;
        }
    }
}
