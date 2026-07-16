// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.DecompressionWindowLimitException;
import org.glavo.arkivo.codec.bcj.BCJTransforms;
import org.glavo.arkivo.codec.delta.DeltaTransform;
import org.glavo.arkivo.codec.lzma.internal.LZMA2Decoder;
import org.glavo.arkivo.codec.spi.CompressionDecoderSupport;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.transform.ByteTransform.Direction;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

/// Incrementally decodes one XZ Stream without retaining caller-owned buffers.
///
/// Fixed-size metadata is collected transactionally, LZMA2 payloads are decoded directly from the current source, and
/// preprocessing filters retain only bounded lookahead. A Stream completes at its Footer; after reset, legal inter-Stream
/// padding is consumed before the next Stream Header without retaining caller input.
@NotNullByDefault
public final class XZDecoder implements CompressionDecoder.Framed {
    /// The largest XZ Block Header size.
    private static final int MAXIMUM_METADATA_SIZE = 1024;

    /// The bounded LZMA2-to-filter transfer size.
    private static final int FILTER_BUFFER_SIZE = 8192;

    /// A stateless pass-through transform used when a Block has no preprocessing filter.
    private static final ByteTransform IDENTITY_TRANSFORM = (buffer, offset, length) -> length;

    /// The maximum permitted LZMA2 dictionary size, or the shared unknown sentinel.
    private final long maximumWindowSize;

    /// Whether supported Block integrity checks are calculated and compared.
    private final boolean verifyChecksums;

    /// Completed Block records awaiting validation against the Index.
    private final List<BlockRecord> records = new ArrayList<>();

    /// Reusable storage for Stream headers, Block headers, checks, CRCs, and footers.
    private final byte[] metadata = new byte[MAXIMUM_METADATA_SIZE];

    /// Reusable LZMA2 decoded-byte transfer buffer.
    private final ByteBuffer decodedBuffer = ByteBuffer.allocate(FILTER_BUFFER_SIZE);

    /// Incremental Index CRC-32 state.
    private final CRC32 indexCrc = new CRC32();

    /// Current decoder phase.
    private Phase phase = Phase.STREAM_HEADER;

    /// Number of metadata bytes already collected.
    private int metadataSize;

    /// Number of metadata bytes required by the current phase.
    private int metadataRequired = 12;

    /// Current Stream integrity-check type.
    private int checkType;

    /// Active Block payload state, or null outside a Block.
    private @Nullable BlockContext block;

    /// Remaining zero padding bytes after the active Block payload.
    private int blockPaddingRemaining;

    /// Index byte count excluding its stored CRC-32.
    private long indexByteCount;

    /// Complete encoded Index size including its stored CRC-32.
    private long indexEncodedSize;

    /// Current Block record selected during Index validation.
    private int indexRecord;

    /// Value accumulated for the active Index variable-length integer.
    private long vliValue;

    /// Number of bytes consumed for the active Index variable-length integer.
    private int vliBytes;

    /// Most recently completed Index variable-length integer.
    private long completedVli;

    /// Number of zero Stream Padding bytes following the Footer.
    private long streamPadding;

    /// Creates an XZ decoder with explicit resource and checksum limits.
    ///
    /// @param maximumWindowSize maximum permitted LZMA2 dictionary size, or the shared unknown sentinel
    /// @param verifyChecksums whether supported Block integrity checks are verified
    public XZDecoder(long maximumWindowSize, boolean verifyChecksums) {
        this.maximumWindowSize = maximumWindowSize;
        this.verifyChecksums = verifyChecksums;
    }

    /// Decodes one XZ Stream until input, output space, or its Footer boundary stops progress.
    @Override
    public CodecOutcome decode(ByteBuffer source, ByteBuffer target, boolean endOfInput) throws IOException {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        requireOpen();
        if (phase == Phase.FINISHED) {
            return CodecOutcome.FINISHED;
        }
        if (!target.hasRemaining()) {
            return CodecOutcome.NEEDS_OUTPUT;
        }

        while (true) {
            switch (phase) {
                case STREAM_PREFIX_PADDING -> {
                    while (source.hasRemaining() && source.get(source.position()) == 0) {
                        source.get();
                        if (streamPadding == Long.MAX_VALUE) {
                            throw new IOException("XZ Stream Padding size overflow");
                        }
                        streamPadding++;
                    }
                    if (!source.hasRemaining()) {
                        if (!endOfInput) {
                            return CodecOutcome.NEEDS_INPUT;
                        }
                        requireAlignedStreamPadding();
                        phase = Phase.FINISHED;
                        return CodecOutcome.FINISHED;
                    }
                    requireAlignedStreamPadding();
                    beginMetadata(12);
                    phase = Phase.STREAM_HEADER;
                }
                case STREAM_HEADER -> {
                    if (!collectMetadata(source)) {
                        return inputOutcome(endOfInput);
                    }
                    parseStreamHeader();
                }
                case BLOCK_INDICATOR -> {
                    if (!source.hasRemaining()) {
                        return inputOutcome(endOfInput);
                    }
                    int indicator = Byte.toUnsignedInt(source.get());
                    if (indicator == 0) {
                        beginIndex();
                    } else {
                        int headerSize = 4 * (indicator + 1);
                        beginMetadata(headerSize);
                        metadata[0] = (byte) indicator;
                        metadataSize = 1;
                        phase = Phase.BLOCK_HEADER;
                    }
                }
                case BLOCK_HEADER -> {
                    if (!collectMetadata(source)) {
                        return inputOutcome(endOfInput);
                    }
                    block = parseBlockHeader();
                    phase = Phase.BLOCK_DATA;
                }
                case BLOCK_DATA -> {
                    BlockContext current = Objects.requireNonNull(block, "block");
                    @Nullable CodecOutcome outcome = current.decode(source, target, endOfInput);
                    if (outcome != null) {
                        return outcome;
                    }
                    blockPaddingRemaining = (int) (-current.compressedSize() & 3L);
                    phase = Phase.BLOCK_PADDING;
                }
                case BLOCK_PADDING -> {
                    while (blockPaddingRemaining > 0 && source.hasRemaining()) {
                        if (source.get() != 0) {
                            throw new IOException("Nonzero XZ Block padding");
                        }
                        blockPaddingRemaining--;
                    }
                    if (blockPaddingRemaining > 0) {
                        return inputOutcome(endOfInput);
                    }
                    int checkSize = Objects.requireNonNull(block, "block").checkSize();
                    if (checkSize == 0) {
                        completeBlock();
                    } else {
                        beginMetadata(checkSize);
                        phase = Phase.BLOCK_CHECK;
                    }
                }
                case BLOCK_CHECK -> {
                    if (!collectMetadata(source)) {
                        return inputOutcome(endOfInput);
                    }
                    Objects.requireNonNull(block, "block").verifyCheck(metadata, metadataRequired);
                    completeBlock();
                }
                case INDEX_COUNT -> {
                    if (!readIndexVli(source)) {
                        return inputOutcome(endOfInput);
                    }
                    if (completedVli != records.size()) {
                        throw new IOException("XZ Index record count does not match decoded Blocks");
                    }
                    indexRecord = 0;
                    phase = records.isEmpty() ? Phase.INDEX_PADDING : Phase.INDEX_UNPADDED;
                }
                case INDEX_UNPADDED -> {
                    if (!readIndexVli(source)) {
                        return inputOutcome(endOfInput);
                    }
                    if (completedVli != records.get(indexRecord).unpaddedSize()) {
                        throw new IOException("XZ Index record does not match its decoded Block");
                    }
                    phase = Phase.INDEX_UNCOMPRESSED;
                }
                case INDEX_UNCOMPRESSED -> {
                    if (!readIndexVli(source)) {
                        return inputOutcome(endOfInput);
                    }
                    if (completedVli != records.get(indexRecord).uncompressedSize()) {
                        throw new IOException("XZ Index record does not match its decoded Block");
                    }
                    indexRecord++;
                    phase = indexRecord == records.size() ? Phase.INDEX_PADDING : Phase.INDEX_UNPADDED;
                }
                case INDEX_PADDING -> {
                    while ((indexByteCount & 3L) != 0L && source.hasRemaining()) {
                        int value = Byte.toUnsignedInt(source.get());
                        if (value != 0) {
                            throw new IOException("Nonzero XZ Index padding");
                        }
                        trackIndexByte(value);
                    }
                    if ((indexByteCount & 3L) != 0L) {
                        return inputOutcome(endOfInput);
                    }
                    beginMetadata(Integer.BYTES);
                    phase = Phase.INDEX_CRC;
                }
                case INDEX_CRC -> {
                    if (!collectMetadata(source)) {
                        return inputOutcome(endOfInput);
                    }
                    if (XZSupport.getLittleEndian(metadata, 0, Integer.BYTES) != indexCrc.getValue()) {
                        throw new IOException("XZ Index CRC-32 mismatch");
                    }
                    indexEncodedSize = Math.addExact(indexByteCount, Integer.BYTES);
                    beginMetadata(12);
                    phase = Phase.STREAM_FOOTER;
                }
                case STREAM_FOOTER -> {
                    if (!collectMetadata(source)) {
                        return inputOutcome(endOfInput);
                    }
                    parseStreamFooter();
                    phase = Phase.FINISHED;
                    return CodecOutcome.FINISHED;
                }
                case FINISHED -> {
                    return CodecOutcome.FINISHED;
                }
                case CLOSED -> throw new IllegalStateException("XZ decoder is closed");
            }
        }
    }

    /// Abandons the current Stream and restores the decoder's initial state.
    @Override
    public void reset() {
        requireOpen();
        @Nullable BlockContext current = block;
        if (current != null) {
            current.close();
        }
        records.clear();
        metadataSize = 0;
        metadataRequired = 12;
        checkType = 0;
        block = null;
        blockPaddingRemaining = 0;
        indexCrc.reset();
        indexByteCount = 0L;
        indexEncodedSize = 0L;
        indexRecord = 0;
        resetVli();
        completedVli = 0L;
        streamPadding = 0L;
        phase = Phase.STREAM_PREFIX_PADDING;
    }

    /// Releases decoder-owned state without consuming additional source bytes.
    @Override
    public void close() {
        @Nullable BlockContext current = block;
        if (current != null) {
            current.close();
        }
        block = null;
        records.clear();
        phase = Phase.CLOSED;
    }

    /// Collects the current fixed-size metadata field from available source bytes.
    private boolean collectMetadata(ByteBuffer source) {
        int copied = Math.min(source.remaining(), metadataRequired - metadataSize);
        source.get(metadata, metadataSize, copied);
        metadataSize += copied;
        return metadataSize == metadataRequired;
    }

    /// Starts collecting a fixed-size metadata field.
    private void beginMetadata(int required) {
        if (required < 0 || required > metadata.length) {
            throw new IllegalArgumentException("Invalid XZ metadata size: " + required);
        }
        metadataSize = 0;
        metadataRequired = required;
    }

    /// Validates a complete XZ Stream Header and starts Block parsing.
    private void parseStreamHeader() throws IOException {
        for (int index = 0; index < XZSupport.HEADER_MAGIC.length; index++) {
            if (metadata[index] != XZSupport.HEADER_MAGIC[index]) {
                throw new IOException("Invalid XZ Stream Header signature");
            }
        }
        if (XZSupport.crc32Mismatch(metadata, 6, 2, 8)) {
            throw new IOException("XZ Stream Header CRC-32 mismatch");
        }
        if (metadata[6] != 0 || Byte.toUnsignedInt(metadata[7]) >= 16) {
            throw new IOException("Unsupported XZ Stream Header flags");
        }
        checkType = Byte.toUnsignedInt(metadata[7]);
        XZCheck.sizeOf(checkType);
        if (verifyChecksums) {
            XZCheck.create(checkType);
        }
        records.clear();
        block = null;
        phase = Phase.BLOCK_INDICATOR;
    }

    /// Parses and validates a complete XZ Block Header.
    private BlockContext parseBlockHeader() throws IOException {
        int headerSize = metadataRequired;
        if (XZSupport.crc32Mismatch(metadata, 0, headerSize - Integer.BYTES, headerSize - Integer.BYTES)) {
            throw new IOException("XZ Block Header CRC-32 mismatch");
        }

        int flags = Byte.toUnsignedInt(metadata[1]);
        if ((flags & 0x3c) != 0) {
            throw new IOException("Unsupported XZ Block Header flags");
        }
        int filterCount = (flags & 3) + 1;
        ByteArrayInputStream fields = new ByteArrayInputStream(metadata, 2, headerSize - 6);
        try {
            long compressedSize = -1L;
            long uncompressedSize = -1L;
            if ((flags & 0x40) != 0) {
                compressedSize = XZSupport.readVli(fields);
                if (compressedSize == 0L) {
                    throw new IOException("XZ Block declares an empty compressed-data field");
                }
            }
            if ((flags & 0x80) != 0) {
                uncompressedSize = XZSupport.readVli(fields);
            }

            Filter[] filters = new Filter[filterCount];
            for (int index = 0; index < filters.length; index++) {
                long identifier = XZSupport.readVli(fields);
                long propertySize = XZSupport.readVli(fields);
                if (propertySize > fields.available()) {
                    throw new IOException("XZ filter properties exceed the Block Header");
                }
                byte[] properties = new byte[(int) propertySize];
                XZSupport.readFully(fields, properties, 0, properties.length);
                filters[index] = new Filter(identifier, properties);
            }
            while (fields.available() > 0) {
                if (fields.read() != 0) {
                    throw new IOException("Nonzero XZ Block Header padding");
                }
            }

            validateFilterChain(filters);
            Filter terminal = filters[filters.length - 1];
            if (terminal.properties().length != 1) {
                throw new IOException("XZ LZMA2 filter requires one property byte");
            }
            int dictionarySize = XZSupport.lzma2DictionarySize(
                    Byte.toUnsignedInt(terminal.properties()[0])
            );
            CompressionDecoderSupport.requireWindowSize(maximumWindowSize, dictionarySize);

            List<ByteTransform> transforms = new ArrayList<>(filters.length - 1);
            for (int index = filters.length - 2; index >= 0; index--) {
                transforms.add(createDecodingTransform(filters[index]));
            }
            return new BlockContext(
                    headerSize,
                    compressedSize,
                    uncompressedSize,
                    dictionarySize,
                    transforms,
                    XZCheck.sizeOf(checkType),
                    verifyChecksums ? XZCheck.create(checkType) : null
            );
        } catch (DecompressionWindowLimitException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IOException("Invalid XZ Block Header", exception);
        }
    }

    /// Validates the supported XZ filter ordering.
    private static void validateFilterChain(Filter[] filters) throws IOException {
        if (filters[filters.length - 1].identifier() != XZSupport.FILTER_LZMA2) {
            throw new IOException("XZ filter chain must end with LZMA2");
        }
        for (int index = 0; index < filters.length - 1; index++) {
            long identifier = filters[index].identifier();
            if (identifier != XZSupport.FILTER_DELTA
                    && (identifier < XZSupport.FILTER_BCJ_X86
                    || identifier > XZSupport.FILTER_BCJ_RISCV)) {
                throw new IOException("Unsupported nonterminal XZ filter: " + identifier);
            }
        }
    }

    /// Creates one reverse preprocessing transform from an XZ filter descriptor.
    private static ByteTransform createDecodingTransform(Filter filter) throws IOException {
        long identifier = filter.identifier();
        byte[] properties = filter.properties();
        if (identifier == XZSupport.FILTER_DELTA) {
            if (properties.length != 1) {
                throw new IOException("XZ Delta filter requires one property byte");
            }
            return new DeltaTransform(Direction.DECODE, Byte.toUnsignedInt(properties[0]) + 1);
        }
        if (identifier >= XZSupport.FILTER_BCJ_X86 && identifier <= XZSupport.FILTER_BCJ_RISCV) {
            long startOffset;
            if (properties.length == 0) {
                startOffset = 0;
            } else if (properties.length == Integer.BYTES) {
                startOffset = XZSupport.getLittleEndian(properties, 0, Integer.BYTES);
            } else {
                throw new IOException("XZ BCJ filter properties must contain zero or four bytes");
            }
            return bcjTransform(identifier, startOffset);
        }
        throw new IOException("Unsupported XZ filter identifier: " + identifier);
    }

    /// Creates one BCJ decoding transform.
    private static ByteTransform bcjTransform(long identifier, long startOffset) {
        return switch ((int) identifier) {
            case 0x04 -> BCJTransforms.x86(Direction.DECODE, startOffset);
            case 0x05 -> BCJTransforms.powerPC(Direction.DECODE, startOffset);
            case 0x06 -> BCJTransforms.ia64(Direction.DECODE, startOffset);
            case 0x07 -> BCJTransforms.arm(Direction.DECODE, startOffset);
            case 0x08 -> BCJTransforms.armThumb(Direction.DECODE, startOffset);
            case 0x09 -> BCJTransforms.sparc(Direction.DECODE, startOffset);
            case 0x0a -> BCJTransforms.arm64(Direction.DECODE, startOffset);
            case 0x0b -> BCJTransforms.riscV(Direction.DECODE, startOffset);
            default -> throw new AssertionError(identifier);
        };
    }

    /// Records and clears the completed Block after its check field has been consumed.
    private void completeBlock() throws IOException {
        BlockContext current = Objects.requireNonNull(block, "block");
        records.add(new BlockRecord(current.unpaddedSize(), current.uncompressedSize()));
        current.close();
        block = null;
        phase = Phase.BLOCK_INDICATOR;
    }

    /// Initializes incremental Index parsing after consuming its zero indicator.
    private void beginIndex() {
        indexCrc.reset();
        indexCrc.update(0);
        indexByteCount = 1L;
        indexEncodedSize = 0L;
        indexRecord = 0;
        resetVli();
        phase = Phase.INDEX_COUNT;
    }

    /// Reads one canonical Index variable-length integer while tracking CRC and encoded size.
    private boolean readIndexVli(ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            int current = Byte.toUnsignedInt(source.get());
            trackIndexByte(current);
            if (vliBytes == 8 && (current & 0x80) != 0) {
                throw new IOException("XZ Index variable-length integer is too large");
            }
            vliValue |= (long) (current & 0x7f) << (vliBytes * 7);
            vliBytes++;
            if ((current & 0x80) == 0) {
                if (vliBytes > 1 && (current & 0x7f) == 0) {
                    throw new IOException("Non-canonical XZ Index variable-length integer");
                }
                completedVli = vliValue;
                resetVli();
                return true;
            }
        }
        return false;
    }

    /// Updates the Index CRC and byte count with one body byte.
    private void trackIndexByte(int value) throws IOException {
        if (indexByteCount == Long.MAX_VALUE) {
            throw new IOException("XZ Index size overflow");
        }
        indexCrc.update(value);
        indexByteCount++;
    }

    /// Clears the partial Index variable-length integer state.
    private void resetVli() {
        vliValue = 0L;
        vliBytes = 0;
    }

    /// Validates the matching Stream Footer and its backward Index size.
    private void parseStreamFooter() throws IOException {
        if (metadata[10] != XZSupport.FOOTER_MAGIC[0]
                || metadata[11] != XZSupport.FOOTER_MAGIC[1]
                || XZSupport.crc32Mismatch(metadata, 4, 6, 0)) {
            throw new IOException("Invalid XZ Stream Footer");
        }
        if (metadata[8] != 0 || Byte.toUnsignedInt(metadata[9]) != checkType) {
            throw new IOException("XZ Stream Header and Footer flags differ");
        }
        long storedBackwardSize = XZSupport.getLittleEndian(metadata, 4, Integer.BYTES);
        long backwardSize;
        try {
            backwardSize = Math.multiplyExact(Math.addExact(storedBackwardSize, 1L), 4L);
        } catch (ArithmeticException exception) {
            throw new IOException("XZ Stream Footer backward size overflow", exception);
        }
        if (backwardSize != indexEncodedSize) {
            throw new IOException("XZ Stream Footer backward size does not match the Index");
        }
    }

    /// Requires the accumulated Stream Padding to be a multiple of four bytes.
    private void requireAlignedStreamPadding() throws IOException {
        if ((streamPadding & 3L) != 0L) {
            throw new IOException("XZ Stream Padding is not a multiple of four bytes");
        }
    }

    /// Returns an input request or reports truncation of a final source.
    private static CodecOutcome inputOutcome(boolean endOfInput) throws EOFException {
        if (endOfInput) {
            throw new EOFException("Truncated XZ Stream");
        }
        return CodecOutcome.NEEDS_INPUT;
    }

    /// Requires this decoder to remain open.
    private void requireOpen() {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("XZ decoder is closed");
        }
    }

    /// Describes one decoded Block for final Index validation.
    ///
    /// @param unpaddedSize Block Header, compressed data, and check size without padding
    /// @param uncompressedSize decoded Block size
    private record BlockRecord(long unpaddedSize, long uncompressedSize) {
    }

    /// Describes one filter and its raw properties.
    ///
    /// @param identifier XZ filter identifier
    /// @param properties filter property bytes
    private record Filter(long identifier, byte @Unmodifiable [] properties) {
    }

    /// Holds incremental payload, filter, checksum, and size state for one XZ Block.
    @NotNullByDefault
    private final class BlockContext {
        /// Serialized Block Header size.
        private final int headerSize;

        /// Compressed-data size declared by the Block Header, or negative when absent.
        private final long declaredCompressedSize;

        /// Uncompressed size declared by the Block Header, or negative when absent.
        private final long declaredUncompressedSize;

        /// Incremental raw LZMA2 decoder.
        private final LZMA2Decoder decoder;

        /// Bounded reverse preprocessing pipeline.
        private final FilterPipeline filters;

        /// Encoded Block check field size.
        private final int checkSize;

        /// Active Block check calculator, or null when verification is disabled.
        private final @Nullable XZCheck check;

        /// Number of compressed-data bytes consumed by LZMA2.
        private long compressedSize;

        /// Number of original bytes returned through the filter pipeline.
        private long uncompressedSize;

        /// Whether the LZMA2 end marker has been consumed.
        private boolean lzmaFinished;

        /// Creates a Block context from a validated Block Header.
        private BlockContext(
                int headerSize,
                long declaredCompressedSize,
                long declaredUncompressedSize,
                int dictionarySize,
                List<ByteTransform> transforms,
                int checkSize,
                @Nullable XZCheck check
        ) {
            this.headerSize = headerSize;
            this.declaredCompressedSize = declaredCompressedSize;
            this.declaredUncompressedSize = declaredUncompressedSize;
            this.decoder = new LZMA2Decoder(dictionarySize);
            this.filters = new FilterPipeline(transforms);
            this.checkSize = checkSize;
            this.check = check;
        }

        /// Decodes Block payload bytes and returns null after all filtered output has drained.
        private @Nullable CodecOutcome decode(
                ByteBuffer source,
                ByteBuffer target,
                boolean endOfInput
        ) throws IOException {
            while (true) {
                drainFilteredOutput(target);
                if (!target.hasRemaining()) {
                    return CodecOutcome.NEEDS_OUTPUT;
                }

                if (lzmaFinished) {
                    if (filters.finished()) {
                        if (declaredUncompressedSize >= 0L
                                && uncompressedSize != declaredUncompressedSize) {
                            throw new IOException("XZ Block uncompressed size mismatch");
                        }
                        return null;
                    }
                    if (!filters.pump()) {
                        throw new IOException("XZ filter pipeline made no progress while finishing");
                    }
                    continue;
                }

                int capacity = filters.inputCapacity();
                if (capacity == 0) {
                    if (!filters.pump()) {
                        throw new IOException("XZ filter pipeline made no progress");
                    }
                    continue;
                }

                decodedBuffer.clear();
                decodedBuffer.limit(Math.min(decodedBuffer.capacity(), capacity));
                ByteBuffer compressed = boundedCompressedSource(source);
                boolean compressedEnd = compressedEnd(compressed, source, endOfInput);
                int inputStart = compressed.position();
                CodecOutcome outcome = decoder.decode(compressed, decodedBuffer, compressedEnd);
                int consumed = compressed.position() - inputStart;
                source.position(source.position() + consumed);
                addCompressedBytes(consumed);

                decodedBuffer.flip();
                boolean outputProduced = decodedBuffer.hasRemaining();
                if (outputProduced) {
                    filters.accept(decodedBuffer);
                }

                if (outcome == CodecOutcome.FINISHED) {
                    if (declaredCompressedSize >= 0L && compressedSize != declaredCompressedSize) {
                        throw new IOException("XZ Block compressed size mismatch");
                    }
                    lzmaFinished = true;
                    filters.finishInput();
                    continue;
                }
                if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    if (decodedBuffer.hasRemaining()) {
                        throw new IOException("XZ filter pipeline rejected LZMA2 output");
                    }
                    continue;
                }
                if (outcome != CodecOutcome.NEEDS_INPUT) {
                    throw new IOException("Unexpected LZMA2 decoder outcome in XZ Block: " + outcome);
                }
                if (compressed.hasRemaining()) {
                    throw new IOException("LZMA2 decoder requested input before consuming its source view");
                }
                if (declaredCompressedSize >= 0L && compressedSize == declaredCompressedSize) {
                    throw new EOFException("Truncated LZMA2 payload at the XZ Block size boundary");
                }
                if (outputProduced) {
                    continue;
                }
                return inputOutcome(endOfInput);
            }
        }

        /// Returns a source view bounded by an optional declared compressed-data size.
        private ByteBuffer boundedCompressedSource(ByteBuffer source) throws IOException {
            long remaining = declaredCompressedSize >= 0L
                    ? declaredCompressedSize - compressedSize
                    : source.remaining();
            if (remaining < 0L) {
                throw new IOException("XZ Block exceeds its declared compressed size");
            }
            ByteBuffer result = source.slice();
            result.limit((int) Math.min(result.remaining(), remaining));
            return result;
        }

        /// Returns whether the current bounded source contains the final possible compressed-data byte.
        private boolean compressedEnd(
                ByteBuffer compressed,
                ByteBuffer source,
                boolean endOfInput
        ) {
            if (declaredCompressedSize >= 0L) {
                return compressed.remaining() == declaredCompressedSize - compressedSize;
            }
            return endOfInput && compressed.remaining() == source.remaining();
        }

        /// Adds consumed compressed bytes with overflow protection.
        private void addCompressedBytes(int count) throws IOException {
            if (compressedSize > Long.MAX_VALUE - count) {
                throw new IOException("XZ Block compressed size overflow");
            }
            compressedSize += count;
        }

        /// Drains original bytes from the final filter stage and updates size and checksum state.
        private void drainFilteredOutput(ByteBuffer target) throws IOException {
            while (target.hasRemaining()) {
                int ready = filters.outputReady();
                if (ready == 0) {
                    if (!filters.pump()) {
                        return;
                    }
                    ready = filters.outputReady();
                    if (ready == 0) {
                        continue;
                    }
                }

                long permitted = declaredUncompressedSize >= 0L
                        ? declaredUncompressedSize - uncompressedSize
                        : Long.MAX_VALUE;
                if (permitted < 0L || permitted == 0L) {
                    throw new IOException("XZ Block contains output beyond its declared size");
                }
                int count = (int) Math.min(
                        Math.min((long) ready, permitted),
                        target.remaining()
                );
                if (uncompressedSize > Long.MAX_VALUE - count) {
                    throw new IOException("XZ Block uncompressed size overflow");
                }
                filters.drainOutput(target, count, check);
                uncompressedSize += count;
            }
        }

        /// Returns the number of compressed-data bytes consumed by this Block.
        private long compressedSize() {
            return compressedSize;
        }

        /// Returns the number of decoded original bytes produced by this Block.
        private long uncompressedSize() {
            return uncompressedSize;
        }

        /// Returns the encoded Block check field size.
        private int checkSize() {
            return checkSize;
        }

        /// Returns the Block's Index unpadded size with overflow validation.
        private long unpaddedSize() throws IOException {
            try {
                return Math.addExact(Math.addExact(headerSize, compressedSize), checkSize);
            } catch (ArithmeticException exception) {
                throw new IOException("XZ Block unpadded size overflow", exception);
            }
        }

        /// Verifies the consumed Block check field when checksum verification is enabled.
        private void verifyCheck(byte[] stored, int length) throws IOException {
            @Nullable XZCheck active = check;
            if (active != null && !Arrays.equals(
                    Arrays.copyOf(stored, length),
                    active.finish()
            )) {
                throw new IOException("XZ Block integrity check mismatch");
            }
        }

        /// Releases the nested LZMA2 decoder.
        private void close() {
            decoder.close();
        }
    }

    /// Applies zero or more reverse preprocessing transforms with bounded per-stage lookahead.
    @NotNullByDefault
    private static final class FilterPipeline {
        /// Ordered transform stages from LZMA2 output to original bytes.
        private final Stage[] stages;

        /// Creates a pipeline, adding an identity stage when no preprocessing filter exists.
        private FilterPipeline(List<ByteTransform> transforms) {
            if (transforms.isEmpty()) {
                stages = new Stage[]{new Stage(IDENTITY_TRANSFORM)};
            } else {
                stages = new Stage[transforms.size()];
                for (int index = 0; index < stages.length; index++) {
                    stages[index] = new Stage(transforms.get(index));
                }
            }
        }

        /// Returns the number of LZMA2 output bytes that can currently be accepted.
        private int inputCapacity() {
            return stages[0].inputCapacity();
        }

        /// Accepts every remaining LZMA2 output byte.
        private void accept(ByteBuffer source) throws IOException {
            int expected = source.remaining();
            int accepted = stages[0].accept(source, expected);
            if (accepted != expected) {
                throw new IOException("XZ filter pipeline input capacity changed unexpectedly");
            }
        }

        /// Signals that no more LZMA2 output will enter this pipeline.
        private void finishInput() {
            stages[0].finishInput();
        }

        /// Moves ready bytes and end signals toward the final stage.
        private boolean pump() throws IOException {
            boolean progressed = false;
            for (int index = stages.length - 2; index >= 0; index--) {
                Stage upstream = stages[index];
                Stage downstream = stages[index + 1];
                if (upstream.outputReady() > 0 && downstream.inputCapacity() > 0) {
                    int count = Math.min(upstream.outputReady(), downstream.inputCapacity());
                    downstream.accept(upstream, count);
                    progressed = true;
                }
                if (upstream.outputFinished() && !downstream.inputFinished()) {
                    downstream.finishInput();
                    progressed = true;
                }
            }
            return progressed;
        }

        /// Returns the number of original bytes ready in the final stage.
        private int outputReady() {
            return stages[stages.length - 1].outputReady();
        }

        /// Copies an exact number of original bytes to the caller and updates the optional check.
        private void drainOutput(ByteBuffer target, int length, @Nullable XZCheck check) {
            Stage output = stages[stages.length - 1];
            if (check != null) {
                check.update(output.bytes(), output.position(), length);
            }
            target.put(output.bytes(), output.position(), length);
            output.consume(length);
        }

        /// Returns whether the final stage has drained after receiving its end signal.
        private boolean finished() {
            return stages[stages.length - 1].outputFinished();
        }
    }

    /// Retains ready output and incomplete transform lookahead for one preprocessing stage.
    @NotNullByDefault
    private static final class Stage {
        /// Stateful in-place transform.
        private final ByteTransform transform;

        /// Bounded ready and pending byte storage.
        private final byte[] bytes = new byte[FILTER_BUFFER_SIZE];

        /// First ready byte.
        private int position;

        /// Number of transformed bytes ready for downstream consumption.
        private int ready;

        /// Number of trailing bytes awaiting more input.
        private int pending;

        /// Whether no additional input will be supplied.
        private boolean inputFinished;

        /// Whether pending tail bytes have been released unchanged.
        private boolean tailReleased;

        /// Creates a stage for one transform.
        private Stage(ByteTransform transform) {
            this.transform = Objects.requireNonNull(transform, "transform");
        }

        /// Returns this stage's owned storage.
        private byte[] bytes() {
            return bytes;
        }

        /// Returns the first ready-byte position.
        private int position() {
            return position;
        }

        /// Returns the number of transformed bytes ready for downstream consumption.
        private int outputReady() {
            return ready;
        }

        /// Returns how many upstream bytes can currently be accepted.
        private int inputCapacity() {
            return !inputFinished && ready == 0 ? bytes.length - pending : 0;
        }

        /// Returns whether this stage has received its final input signal.
        private boolean inputFinished() {
            return inputFinished;
        }

        /// Returns whether this stage has no more output now or in the future.
        private boolean outputFinished() {
            return inputFinished && tailReleased && ready == 0;
        }

        /// Accepts bytes from an LZMA2 transfer buffer.
        private int accept(ByteBuffer source, int requested) throws IOException {
            int count = Math.min(Math.min(requested, source.remaining()), inputCapacity());
            if (count == 0) {
                return 0;
            }
            compactPending();
            source.get(bytes, pending, count);
            transformPending(count);
            return count;
        }

        /// Accepts bytes from a preceding filter stage.
        private void accept(Stage source, int count) throws IOException {
            if (count <= 0 || count > source.ready || count > inputCapacity()) {
                throw new IllegalArgumentException("Invalid XZ filter transfer length: " + count);
            }
            compactPending();
            System.arraycopy(source.bytes, source.position, bytes, pending, count);
            source.consume(count);
            transformPending(count);
        }

        /// Applies the transform to retained lookahead plus newly accepted bytes.
        private void transformPending(int added) throws IOException {
            int total = pending + added;
            int transformed = transform.transform(bytes, 0, total);
            if (transformed < 0 || transformed > total) {
                throw new IOException("XZ byte filter returned an invalid transformed byte count");
            }
            position = 0;
            ready = transformed;
            pending = total - transformed;
            if (ready == 0 && pending == bytes.length) {
                throw new IOException("XZ byte filter made no progress with a full buffer");
            }
        }

        /// Consumes ready bytes and releases a final incomplete tail when appropriate.
        private void consume(int count) {
            if (count < 0 || count > ready) {
                throw new IllegalArgumentException("Invalid XZ filter consumption length: " + count);
            }
            position += count;
            ready -= count;
            if (ready == 0 && inputFinished && !tailReleased) {
                releaseTail();
            }
        }

        /// Signals that no more input will be accepted by this stage.
        private void finishInput() {
            if (inputFinished) {
                return;
            }
            inputFinished = true;
            if (ready == 0) {
                releaseTail();
            }
        }

        /// Moves retained pending bytes to the beginning of the stage buffer.
        private void compactPending() {
            if (ready != 0) {
                throw new IllegalStateException("Cannot accept input while XZ filter output remains ready");
            }
            if (pending > 0 && position != 0) {
                System.arraycopy(bytes, position, bytes, 0, pending);
            }
            position = 0;
        }

        /// Releases a final incomplete transform tail unchanged, as required by XZ BCJ filters.
        private void releaseTail() {
            compactPending();
            ready = pending;
            pending = 0;
            tailReleased = true;
        }
    }

    /// Enumerates the incremental XZ decoding phases.
    @NotNullByDefault
    private enum Phase {
        /// Inter-Stream zero padding is being consumed after decoder reset.
        STREAM_PREFIX_PADDING,

        /// The fixed Stream Header is being collected.
        STREAM_HEADER,

        /// The next Block Header size indicator or Index indicator is required.
        BLOCK_INDICATOR,

        /// A complete Block Header is being collected.
        BLOCK_HEADER,

        /// The active Block's LZMA2 payload and reverse filters are producing original bytes.
        BLOCK_DATA,

        /// Zero padding after compressed Block data is being consumed.
        BLOCK_PADDING,

        /// The active Block's integrity-check field is being collected.
        BLOCK_CHECK,

        /// The Index record-count VLI is being read.
        INDEX_COUNT,

        /// One Index record's unpadded-size VLI is being read.
        INDEX_UNPADDED,

        /// One Index record's uncompressed-size VLI is being read.
        INDEX_UNCOMPRESSED,

        /// The Index body is being padded to a four-byte boundary.
        INDEX_PADDING,

        /// The stored Index CRC-32 is being collected.
        INDEX_CRC,

        /// The fixed Stream Footer is being collected.
        STREAM_FOOTER,

        /// One padded XZ Stream has completed.
        FINISHED,

        /// The decoder has been closed.
        CLOSED
    }
}
