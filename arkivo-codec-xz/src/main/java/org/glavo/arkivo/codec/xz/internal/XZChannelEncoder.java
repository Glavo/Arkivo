// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.ResourceOwnership;
import org.glavo.arkivo.codec.CompressingWritableByteChannel;
import org.glavo.arkivo.codec.spi.OwnedChannelCloser;
import org.glavo.arkivo.codec.lzma.internal.LZMA2ChannelEncoder;
import org.glavo.arkivo.codec.lzma.LZMAProperties;
import org.glavo.arkivo.codec.transform.ByteTransform;
import org.glavo.arkivo.codec.transform.ByteTransform.Direction;
import org.glavo.arkivo.codec.transform.TransformingWritableByteChannel;
import org.glavo.arkivo.codec.xz.XZBCJFilter;
import org.glavo.arkivo.codec.xz.XZDeltaFilter;
import org.glavo.arkivo.codec.xz.XZFilter;
import org.glavo.arkivo.codec.xz.XZFilterChain;
import org.glavo.arkivo.codec.xz.internal.filter.BCJTransforms;
import org.glavo.arkivo.codec.xz.internal.filter.DeltaTransform;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.WritableByteChannel;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/// Encodes standards-compliant multi-Block and concatenated XZ streams directly to a channel.
@NotNullByDefault
public final class XZChannelEncoder implements CompressingWritableByteChannel.FlushableFramed {
    /// The default LZMA2 dictionary size.
    public static final int DEFAULT_DICTIONARY_SIZE = 8 * 1024 * 1024;

    /// The initial number of Block records retained for the final Index.
    private static final int INITIAL_INDEX_CAPACITY = 8;

    /// The compressed-data target.
    private final WritableByteChannel target;

    /// Tracks closure of the owned compressed-data target.
    private final OwnedChannelCloser targetCloser;

    /// The buffered XZ byte target.
    private final XZChannelOutput output;

    /// The LZMA2 model properties.
    private final LZMAProperties properties;

    /// The stream integrity-check type.
    private final int checkType;

    /// The ordered preprocessing filters placed before LZMA2.
    private final XZFilterChain filterChain;

    /// The maximum uncompressed bytes per Block, or zero for one unbounded Block.
    private final long maximumBlockSize;

    /// The reusable uncompressed transfer buffer.
    private final byte[] transferBuffer = new byte[8192];

    /// The active block check after the first write.
    private @Nullable XZCheck blockCheck;

    /// The compressed-data counter after the first write.
    private @Nullable CountingChannel compressedCounter;

    /// The active LZMA2 block encoder after the first write.
    private @Nullable LZMA2ChannelEncoder blockEncoder;

    /// The outermost preprocessing input, or the LZMA2 encoder when no preprocessing filter is configured.
    private @Nullable WritableByteChannel blockInput;

    /// The serialized Block Header size.
    private long blockHeaderSize;

    /// The total number of uncompressed bytes accepted.
    private long inputBytes;

    /// The active Block's uncompressed size.
    private long blockUncompressedSize;

    /// The unpadded size of every completed Block.
    private long[] blockUnpaddedSizes = new long[INITIAL_INDEX_CAPACITY];

    /// The uncompressed size of every completed Block.
    private long[] blockUncompressedSizes = new long[INITIAL_INDEX_CAPACITY];

    /// The number of completed Block records.
    private int blockCount;

    /// Whether an XZ Stream Header has been emitted and awaits a matching footer.
    private boolean streamActive = true;

    /// Whether this encoder remains open.
    private boolean open = true;

    /// Creates an XZ encoder with explicit dictionary and integrity-check settings.
    public XZChannelEncoder(
            WritableByteChannel target,
            ResourceOwnership ownership,
            int dictionarySize,
            int checkType
    ) throws IOException {
        this(
                target,
                ownership,
                LZMAProperties.defaults(dictionarySize),
                checkType,
                XZFilterChain.EMPTY
        );
    }

    /// Creates an XZ encoder with complete LZMA2 model and integrity-check settings.
    public XZChannelEncoder(
            WritableByteChannel target,
            ResourceOwnership ownership,
            LZMAProperties properties,
            int checkType
    ) throws IOException {
        this(target, ownership, properties, checkType, XZFilterChain.EMPTY);
    }

    /// Creates an XZ encoder with complete LZMA2, integrity-check, and preprocessing settings.
    public XZChannelEncoder(
            WritableByteChannel target,
            ResourceOwnership ownership,
            LZMAProperties properties,
            int checkType,
            XZFilterChain filterChain
    ) throws IOException {
        this(target, ownership, properties, checkType, filterChain, 0L);
    }

    /// Creates an XZ encoder with complete filter and Block-layout settings.
    public XZChannelEncoder(
            WritableByteChannel target,
            ResourceOwnership ownership,
            LZMAProperties properties,
            int checkType,
            XZFilterChain filterChain,
            long maximumBlockSize
    ) throws IOException {
        if (maximumBlockSize < 0L) {
            throw new IllegalArgumentException("XZ maximum Block size must not be negative");
        }
        this.target = Objects.requireNonNull(target, "target");
        this.targetCloser = new OwnedChannelCloser(target, ownership);
        this.properties = Objects.requireNonNull(properties, "properties");
        XZSupport.lzma2DictionaryProperty(properties.dictionarySize());
        XZCheck.create(checkType);
        this.checkType = checkType;
        this.filterChain = Objects.requireNonNull(filterChain, "filterChain");
        this.maximumBlockSize = maximumBlockSize;
        output = new XZChannelOutput(target);
        try {
            writeStreamHeader();
        } catch (IOException | RuntimeException | Error exception) {
            targetCloser.closeAfter(exception);
            throw exception;
        }
    }

    /// Consumes uncompressed bytes and rotates Blocks at the configured boundary.
    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        ensureOpen();
        if (!source.hasRemaining()) {
            return 0;
        }
        startStream();
        int start = source.position();
        while (source.hasRemaining()) {
            startBlock();
            long remainingInBlock = maximumBlockSize == 0L
                    ? Long.MAX_VALUE
                    : maximumBlockSize - blockUncompressedSize;
            int count = (int) Math.min(
                    Math.min(source.remaining(), transferBuffer.length),
                    remainingInBlock
            );
            if (inputBytes > Long.MAX_VALUE - count) {
                throw new IOException("XZ uncompressed input size overflow");
            }

            source.get(transferBuffer, 0, count);
            Objects.requireNonNull(blockCheck).update(transferBuffer, 0, count);
            Objects.requireNonNull(blockInput).write(ByteBuffer.wrap(transferBuffer, 0, count));
            inputBytes += count;
            blockUncompressedSize += count;

            if (maximumBlockSize != 0L && blockUncompressedSize == maximumBlockSize) {
                finishBlock();
            }
        }
        return source.position() - start;
    }

    /// Finishes the active Block so every accepted byte reaches a decodable Stream boundary.
    @Override
    public void flush() throws IOException {
        ensureOpen();
        finishBlock();
        output.flush();
    }

    /// Finishes the active XZ Stream while retaining the encoder for another stream.
    @Override
    public void finishFrame() throws IOException {
        ensureOpen();
        if (!streamActive) {
            return;
        }
        finishStream();
    }

    /// Finishes the active XZ Stream and releases the encoder context.
    @Override
    public void finish() throws IOException {
        if (!open) {
            targetCloser.close();
            return;
        }
        @Nullable Throwable failure = null;
        try {
            if (streamActive) {
                finishStream();
            }
        } catch (IOException | RuntimeException | Error exception) {
            failure = exception;
        }
        open = false;
        targetCloser.closeAfter(failure);
    }

    /// Returns the number of uncompressed bytes accepted.
    @Override
    public long inputBytes() {
        return inputBytes;
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

    /// Lazily starts another XZ Stream after an explicit frame boundary.
    private void startStream() throws IOException {
        if (streamActive) {
            return;
        }
        writeStreamHeader();
        streamActive = true;
    }

    /// Finishes one active Stream and resets its Block Index state.
    private void finishStream() throws IOException {
        finishBlock();
        byte[] index = createIndex();
        output.write(index);
        writeStreamFooter(index.length);
        output.flush();
        blockCount = 0;
        streamActive = false;
    }

    /// Writes the fixed Stream Header and its CRC-32.
    private void writeStreamHeader() throws IOException {
        byte[] header = new byte[12];
        System.arraycopy(XZSupport.HEADER_MAGIC, 0, header, 0, XZSupport.HEADER_MAGIC.length);
        header[6] = 0;
        header[7] = (byte) checkType;
        XZSupport.putLittleEndian(header, 8, XZSupport.crc32(header, 6, 2), Integer.BYTES);
        output.write(header);
    }

    /// Lazily writes the Block Header and opens its preprocessing and LZMA2 encoders.
    private void startBlock() throws IOException {
        if (blockEncoder != null) {
            return;
        }
        byte[] header = createBlockHeader();
        output.write(header);
        blockHeaderSize = header.length;

        CountingChannel counter = new CountingChannel(output);
        blockCheck = XZCheck.create(checkType);
        compressedCounter = counter;
        LZMA2ChannelEncoder encoder = new LZMA2ChannelEncoder(
                counter,
                ResourceOwnership.BORROWED,
                properties
        );
        blockEncoder = encoder;

        WritableByteChannel input = encoder;
        List<XZFilter> filters = filterChain.filters();
        for (int index = filters.size() - 1; index >= 0; index--) {
            input = new TransformingWritableByteChannel(
                    input,
                    createEncodingTransform(filters.get(index)),
                    ResourceOwnership.OWNED
            );
        }
        blockInput = input;
    }

    /// Finishes the active block, padding, and integrity check.
    private void finishBlock() throws IOException {
        LZMA2ChannelEncoder encoder = blockEncoder;
        if (encoder == null) {
            return;
        }
        WritableByteChannel input = Objects.requireNonNull(blockInput);
        if (input == encoder) {
            encoder.finish();
        } else {
            input.close();
        }
        long compressedSize = Objects.requireNonNull(compressedCounter).count();
        for (long padded = compressedSize; (padded & 3L) != 0L; padded++) {
            output.write(0);
        }
        byte[] check = Objects.requireNonNull(blockCheck).finish();
        output.write(check);
        if (compressedSize > Long.MAX_VALUE - blockHeaderSize - check.length) {
            throw new IOException("XZ Block unpadded size overflow");
        }
        addBlockRecord(
                blockHeaderSize + compressedSize + check.length,
                blockUncompressedSize
        );
        blockHeaderSize = 0L;
        blockUncompressedSize = 0L;
        blockCheck = null;
        compressedCounter = null;
        blockInput = null;
        blockEncoder = null;
    }

    /// Creates a Block Header describing every preprocessing filter and terminal LZMA2.
    private byte[] createBlockHeader() throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        body.write(filterChain.filters().size());
        for (XZFilter filter : filterChain.filters()) {
            writeFilterDescriptor(body, filter);
        }
        writeFilterDescriptor(
                body,
                XZSupport.FILTER_LZMA2,
                new byte[]{(byte) XZSupport.lzma2DictionaryProperty(properties.dictionarySize())}
        );
        while ((body.size() + Integer.BYTES & 3) != 0) {
            body.write(0);
        }

        byte[] bodyBytes = body.toByteArray();
        byte[] header = Arrays.copyOf(bodyBytes, bodyBytes.length + Integer.BYTES);
        header[0] = (byte) (header.length / 4 - 1);
        XZSupport.putLittleEndian(
                header,
                bodyBytes.length,
                XZSupport.crc32(header, 0, bodyBytes.length),
                Integer.BYTES
        );
        return header;
    }

    /// Writes one public preprocessing-filter descriptor.
    private static void writeFilterDescriptor(ByteArrayOutputStream output, XZFilter filter)
            throws IOException {
        if (filter instanceof XZDeltaFilter delta) {
            writeFilterDescriptor(
                    output,
                    XZSupport.FILTER_DELTA,
                    new byte[]{(byte) (delta.distance() - 1L)}
            );
            return;
        }
        if (filter instanceof XZBCJFilter bcj) {
            byte[] properties;
            if (bcj.startOffset() == 0L) {
                properties = new byte[0];
            } else {
                properties = new byte[Integer.BYTES];
                XZSupport.putLittleEndian(properties, 0, bcj.startOffset(), Integer.BYTES);
            }
            writeFilterDescriptor(output, bcj.architecture().identifier(), properties);
            return;
        }
        throw new AssertionError(filter);
    }

    /// Writes one raw filter identifier and property sequence.
    private static void writeFilterDescriptor(
            ByteArrayOutputStream output,
            long identifier,
            byte[] properties
    ) throws IOException {
        XZSupport.writeVli(output, identifier);
        XZSupport.writeVli(output, properties.length);
        output.write(properties);
    }

    /// Creates the stateful encoding transform for one public filter.
    private static ByteTransform createEncodingTransform(XZFilter filter) {
        if (filter instanceof XZDeltaFilter delta) {
            return new DeltaTransform(Direction.ENCODE, (int) delta.distance());
        }
        if (filter instanceof XZBCJFilter bcj) {
            long startOffset = bcj.startOffset();
            return switch (bcj.architecture()) {
                case X86 -> BCJTransforms.x86(Direction.ENCODE, startOffset);
                case POWERPC -> BCJTransforms.powerPC(Direction.ENCODE, startOffset);
                case IA64 -> BCJTransforms.ia64(Direction.ENCODE, startOffset);
                case ARM -> BCJTransforms.arm(Direction.ENCODE, startOffset);
                case ARM_THUMB -> BCJTransforms.armThumb(Direction.ENCODE, startOffset);
                case SPARC -> BCJTransforms.sparc(Direction.ENCODE, startOffset);
                case ARM64 -> BCJTransforms.arm64(Direction.ENCODE, startOffset);
                case RISCV -> BCJTransforms.riscV(Direction.ENCODE, startOffset);
            };
        }
        throw new AssertionError(filter);
    }

    /// Appends one completed Block record to compact primitive Index storage.
    private void addBlockRecord(long unpaddedSize, long uncompressedSize) throws IOException {
        if (blockCount == Integer.MAX_VALUE) {
            throw new IOException("XZ Block count exceeds the supported Index capacity");
        }
        if (blockCount == blockUnpaddedSizes.length) {
            int capacity = Math.min(
                    Integer.MAX_VALUE,
                    Math.max(blockCount + 1, blockCount + (blockCount >>> 1))
            );
            blockUnpaddedSizes = Arrays.copyOf(blockUnpaddedSizes, capacity);
            blockUncompressedSizes = Arrays.copyOf(blockUncompressedSizes, capacity);
        }
        blockUnpaddedSizes[blockCount] = unpaddedSize;
        blockUncompressedSizes[blockCount] = uncompressedSize;
        blockCount++;
    }

    /// Creates the complete Index field and its CRC-32.
    private byte[] createIndex() throws IOException {
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        index.write(0);
        XZSupport.writeVli(index, blockCount);
        for (int block = 0; block < blockCount; block++) {
            XZSupport.writeVli(index, blockUnpaddedSizes[block]);
            XZSupport.writeVli(index, blockUncompressedSizes[block]);
        }
        while ((index.size() & 3) != 0) {
            index.write(0);
        }
        byte[] body = index.toByteArray();
        XZSupport.writeCrc32(index, XZSupport.crc32(body, 0, body.length));
        return index.toByteArray();
    }

    /// Writes the Stream Footer matching the Index and header flags.
    private void writeStreamFooter(int indexSize) throws IOException {
        byte[] footer = new byte[12];
        XZSupport.putLittleEndian(footer, 4, indexSize / 4L - 1L, Integer.BYTES);
        footer[8] = 0;
        footer[9] = (byte) checkType;
        footer[10] = XZSupport.FOOTER_MAGIC[0];
        footer[11] = XZSupport.FOOTER_MAGIC[1];
        XZSupport.putLittleEndian(footer, 0, XZSupport.crc32(footer, 4, 6), Integer.BYTES);
        output.write(footer);
    }

    /// Requires this encoder to remain open.
    private void ensureOpen() throws ClosedChannelException {
        if (!open) {
            throw new ClosedChannelException();
        }
    }

    /// Counts compressed block bytes forwarded to the XZ output.
    @NotNullByDefault
    private static final class CountingChannel implements WritableByteChannel {
        /// The XZ output target.
        private final XZChannelOutput output;

        /// The number of forwarded bytes.
        private long count;

        /// Whether this counting channel remains open.
        private boolean open = true;

        /// Creates a counting wrapper.
        private CountingChannel(XZChannelOutput output) {
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
