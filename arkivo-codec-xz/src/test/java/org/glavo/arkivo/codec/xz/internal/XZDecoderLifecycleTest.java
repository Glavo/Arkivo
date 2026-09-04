// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.xz.internal;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.xz.XZCheckType;
import org.glavo.arkivo.codec.xz.XZCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Verifies XZ container parsing, buffer progress, malformed metadata, and decoder lifecycle behavior.
@NotNullByDefault
final class XZDecoderLifecycleTest {
    /// Header offset of the first XZ Block.
    private static final int FIRST_BLOCK_OFFSET = 12;

    /// Size of an XZ Stream Footer.
    private static final int STREAM_FOOTER_SIZE = 12;

    /// LZMA2 dictionary property used by synthetic Block Headers.
    private static final int DICTIONARY_PROPERTY = XZSupport.lzma2DictionaryProperty(1 << 20);

    /// Verifies output backpressure, terminal idempotence, Stream Padding, reset, and closed-state behavior.
    @Test
    @SuppressWarnings("DataFlowIssue")
    void enforcesIncrementalLifecycleAndPadding() throws IOException {
        byte[] emptyStream = encode(XZCodec.DEFAULT, new byte[0]);
        XZDecoder decoder = newDecoder();
        ByteBuffer source = ByteBuffer.wrap(emptyStream);

        assertEquals(CodecOutcome.NEEDS_OUTPUT, decoder.finish(source, ByteBuffer.allocate(0)));
        assertEquals(0, source.position());
        assertEquals(
                CodecOutcome.NEEDS_INPUT,
                decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertEquals(CodecOutcome.FINISHED, decoder.finish(source, ByteBuffer.allocateDirect(1)));
        assertEquals(emptyStream.length, source.position());

        ByteBuffer trailing = ByteBuffer.wrap(new byte[]{9, 8, 7});
        assertEquals(CodecOutcome.FINISHED, decoder.decode(trailing, ByteBuffer.allocate(1)));
        assertEquals(0, trailing.position());
        assertEquals(CodecOutcome.FINISHED, decoder.finish(trailing, ByteBuffer.allocate(1)));
        assertEquals(0, trailing.position());

        decoder.reset();
        ByteBuffer padding = ByteBuffer.wrap(new byte[4]);
        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(padding, ByteBuffer.allocate(1)));
        assertEquals(padding.limit(), padding.position());
        assertEquals(
                CodecOutcome.FINISHED,
                decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );

        decoder.reset();
        IOException misalignedPadding = assertThrows(
                IOException.class,
                () -> decoder.finish(ByteBuffer.wrap(new byte[3]), ByteBuffer.allocate(1))
        );
        assertEquals(
                "XZ Stream Padding is not a multiple of four bytes",
                misalignedPadding.getMessage()
        );

        byte[] nonemptyStream = encode(XZCodec.DEFAULT, new byte[]{1, 2, 3, 4, 5});
        int blockDataOffset = FIRST_BLOCK_OFFSET + blockHeaderSize(nonemptyStream);
        decoder.reset();
        ByteBuffer partialBlock = ByteBuffer.wrap(Arrays.copyOf(nonemptyStream, blockDataOffset + 1));
        assertEquals(CodecOutcome.NEEDS_INPUT, decoder.decode(partialBlock, ByteBuffer.allocate(32)));
        assertEquals(partialBlock.limit(), partialBlock.position());
        decoder.reset();
        assertArrayEquals(new byte[0], decode(decoder, emptyStream));

        assertThrows(NullPointerException.class, () -> decoder.decode(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.decode(ByteBuffer.allocate(0), null));
        assertThrows(NullPointerException.class, () -> decoder.finish(null, ByteBuffer.allocate(1)));
        assertThrows(NullPointerException.class, () -> decoder.finish(ByteBuffer.allocate(0), null));

        decoder.close();
        decoder.close();
        assertThrows(IllegalStateException.class, decoder::reset);
        assertThrows(
                IllegalStateException.class,
                () -> decoder.decode(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
        assertThrows(
                IllegalStateException.class,
                () -> decoder.finish(ByteBuffer.allocate(0), ByteBuffer.allocate(1))
        );
    }

    /// Verifies every proper prefix of an empty Stream is reported as truncated at end of input.
    @Test
    void rejectsEveryTruncatedEmptyStreamPrefix() throws IOException {
        byte[] valid = encode(XZCodec.DEFAULT, new byte[0]);

        for (int length = 0; length < valid.length; length++) {
            IOException failure = decodeFailure(Arrays.copyOf(valid, length));
            assertInstanceOf(EOFException.class, failure, "prefix length " + length);
            assertEquals("Truncated XZ Stream", failure.getMessage(), "prefix length " + length);
        }
    }

    /// Verifies Stream Header and Footer signatures, CRCs, flags, and backward sizes are enforced independently.
    @Test
    void validatesStreamHeaderAndFooter() throws IOException {
        byte[] valid = encode(XZCodec.DEFAULT, new byte[0]);

        byte[] headerSignature = valid.clone();
        headerSignature[0] ^= 1;
        assertFailureMessage(headerSignature, "Invalid XZ Stream Header signature");

        byte[] headerCrc = valid.clone();
        headerCrc[8] ^= 1;
        assertFailureMessage(headerCrc, "XZ Stream Header CRC-32 mismatch");

        byte[] reservedHeaderFlag = valid.clone();
        reservedHeaderFlag[6] = 1;
        rewriteCrc32(reservedHeaderFlag, 6, 2, 8);
        assertFailureMessage(reservedHeaderFlag, "Unsupported XZ Stream Header flags");

        byte[] outOfRangeCheck = valid.clone();
        outOfRangeCheck[7] = 16;
        rewriteCrc32(outOfRangeCheck, 6, 2, 8);
        assertFailureMessage(outOfRangeCheck, "Unsupported XZ Stream Header flags");

        int footerOffset = valid.length - STREAM_FOOTER_SIZE;
        byte[] footerSignature = valid.clone();
        footerSignature[footerOffset + 10] ^= 1;
        assertFailureMessage(footerSignature, "Invalid XZ Stream Footer");

        byte[] footerCrc = valid.clone();
        footerCrc[footerOffset] ^= 1;
        assertFailureMessage(footerCrc, "Invalid XZ Stream Footer");

        byte[] footerFlags = valid.clone();
        footerFlags[footerOffset + 8] = 1;
        rewriteCrc32(footerFlags, footerOffset + 4, 6, footerOffset);
        assertFailureMessage(footerFlags, "XZ Stream Header and Footer flags differ");

        byte[] backwardSize = valid.clone();
        long storedBackwardSize = XZSupport.getLittleEndian(
                backwardSize,
                footerOffset + 4,
                Integer.BYTES
        );
        XZSupport.putLittleEndian(
                backwardSize,
                footerOffset + 4,
                storedBackwardSize + 1L,
                Integer.BYTES
        );
        rewriteCrc32(backwardSize, footerOffset + 4, 6, footerOffset);
        assertFailureMessage(backwardSize, "XZ Stream Footer backward size does not match the Index");
    }

    /// Verifies Block Headers reject corrupt CRCs, unsupported flags, malformed fields, and invalid filter chains.
    @Test
    void validatesBlockHeadersAndFilterDescriptors() throws IOException {
        byte[] validHeader = blockHeader(0, XZSupport.FILTER_LZMA2, 1, DICTIONARY_PROPERTY);
        byte[] corruptCrc = validHeader.clone();
        corruptCrc[corruptCrc.length - 1] ^= 1;
        assertFailureMessage(
                streamWithBlockHeader(corruptCrc),
                "XZ Block Header CRC-32 mismatch"
        );

        assertFailureMessage(
                streamWithBlockHeader(blockHeader(
                        0x04,
                        XZSupport.FILTER_LZMA2,
                        1,
                        DICTIONARY_PROPERTY
                )),
                "Unsupported XZ Block Header flags"
        );

        assertInvalidBlockHeaderCause(
                blockHeader(0x40, 0, XZSupport.FILTER_LZMA2, 1, DICTIONARY_PROPERTY),
                "XZ Block declares an empty compressed-data field"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, XZSupport.FILTER_LZMA2, 127),
                "XZ filter properties exceed the Block Header"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, XZSupport.FILTER_LZMA2, 1, DICTIONARY_PROPERTY, 1),
                "Nonzero XZ Block Header padding"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, XZSupport.FILTER_DELTA, 1, 0),
                "XZ filter chain must end with LZMA2"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(1, 2, 0, XZSupport.FILTER_LZMA2, 1, DICTIONARY_PROPERTY),
                "Unsupported nonterminal XZ filter: 2"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(
                        1,
                        XZSupport.FILTER_DELTA,
                        0,
                        XZSupport.FILTER_LZMA2,
                        1,
                        DICTIONARY_PROPERTY
                ),
                "XZ Delta filter requires one property byte"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(
                        1,
                        XZSupport.FILTER_BCJ_X86,
                        1,
                        0,
                        XZSupport.FILTER_LZMA2,
                        1,
                        DICTIONARY_PROPERTY
                ),
                "XZ BCJ filter properties must contain zero or four bytes"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, XZSupport.FILTER_LZMA2, 0),
                "XZ LZMA2 filter requires one property byte"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, XZSupport.FILTER_LZMA2, 1, 38),
                "Unsupported XZ LZMA2 dictionary property: 38"
        );
        assertInvalidBlockHeaderCause(
                blockHeader(0, 0xa1, 0, 1, DICTIONARY_PROPERTY),
                "Non-canonical XZ variable-length integer"
        );
    }

    /// Verifies Index record values, canonical VLIs, padding, and CRC state are validated separately.
    @Test
    void validatesIndexRecordsAndEncoding() throws IOException {
        byte[] empty = encode(XZCodec.DEFAULT, new byte[0]);
        int emptyIndexOffset = indexOffset(empty);

        byte[] emptyCountMismatch = empty.clone();
        emptyCountMismatch[emptyIndexOffset + 1] = 1;
        assertFailureMessage(
                emptyCountMismatch,
                "XZ Index record count does not match decoded Blocks"
        );

        byte[] nonCanonicalCount = empty.clone();
        nonCanonicalCount[emptyIndexOffset + 1] = (byte) 0x80;
        nonCanonicalCount[emptyIndexOffset + 2] = 0;
        assertFailureMessage(
                nonCanonicalCount,
                "Non-canonical XZ Index variable-length integer"
        );

        byte[] oversizedCount = Arrays.copyOf(empty, FIRST_BLOCK_OFFSET + 10);
        oversizedCount[FIRST_BLOCK_OFFSET] = 0;
        Arrays.fill(
                oversizedCount,
                FIRST_BLOCK_OFFSET + 1,
                oversizedCount.length,
                (byte) 0x80
        );
        assertFailureMessage(
                oversizedCount,
                "XZ Index variable-length integer is too large"
        );

        byte[] nonzeroPadding = empty.clone();
        nonzeroPadding[emptyIndexOffset + 2] = 1;
        assertFailureMessage(nonzeroPadding, "Nonzero XZ Index padding");

        byte[] indexCrc = empty.clone();
        int emptyFooterOffset = empty.length - STREAM_FOOTER_SIZE;
        indexCrc[emptyFooterOffset - 1] ^= 1;
        assertFailureMessage(indexCrc, "XZ Index CRC-32 mismatch");

        byte[] oneBlock = encode(XZCodec.DEFAULT.withCheckType(XZCheckType.NONE), patternedBytes(67));
        SingleBlockLayout layout = singleBlockLayout(oneBlock);

        byte[] countMismatch = oneBlock.clone();
        countMismatch[layout.indexOffset() + 1] = 0;
        assertFailureMessage(
                countMismatch,
                "XZ Index record count does not match decoded Blocks"
        );

        byte[] unpaddedMismatch = oneBlock.clone();
        unpaddedMismatch[layout.unpaddedFieldOffset()] ^= 1;
        assertFailureMessage(
                unpaddedMismatch,
                "XZ Index record does not match its decoded Block"
        );

        byte[] uncompressedMismatch = oneBlock.clone();
        uncompressedMismatch[layout.uncompressedFieldOffset()] ^= 1;
        assertFailureMessage(
                uncompressedMismatch,
                "XZ Index record does not match its decoded Block"
        );
    }

    /// Verifies nonzero Block Padding is rejected after a complete LZMA2 payload.
    @Test
    void rejectsNonzeroBlockPadding() throws IOException {
        PaddedBlock paddedBlock = paddedBlockStream();
        byte[] encoded = paddedBlock.encoded();
        SingleBlockLayout layout = paddedBlock.layout();

        encoded[layout.blockPaddingOffset()] = 1;
        assertFailureMessage(encoded, "Nonzero XZ Block padding");
    }

    /// Verifies optional compressed and uncompressed Block sizes constrain payload consumption and output.
    @Test
    void enforcesDeclaredBlockSizes() throws IOException {
        byte[] expected = {3, 1, 4, 1, 5, 9};
        byte[] original = encode(XZCodec.DEFAULT.withCheckType(XZCheckType.NONE), expected);
        SingleBlockLayout layout = singleBlockLayout(original);
        assertTrue(layout.compressedSize() > 1 && layout.compressedSize() < 127);
        assertTrue(expected.length < 127);

        byte[] declared = addSingleByteBlockSizes(
                original,
                layout.compressedSize(),
                expected.length
        );
        assertArrayEquals(expected, decode(newDecoder(), declared));

        byte[] shortCompressed = addSingleByteBlockSizes(
                original,
                layout.compressedSize() - 1,
                expected.length
        );
        assertInstanceOf(EOFException.class, decodeFailure(shortCompressed));

        byte[] longCompressed = addSingleByteBlockSizes(
                original,
                layout.compressedSize() + 1,
                expected.length
        );
        assertFailureMessage(longCompressed, "XZ Block compressed size mismatch");

        byte[] shortUncompressed = addSingleByteBlockSizes(
                original,
                layout.compressedSize(),
                expected.length - 1
        );
        assertFailureMessage(
                shortUncompressed,
                "XZ Block contains output beyond its declared size"
        );

        byte[] longUncompressed = addSingleByteBlockSizes(
                original,
                layout.compressedSize(),
                expected.length + 1
        );
        assertFailureMessage(longUncompressed, "XZ Block uncompressed size mismatch");
    }

    /// Creates a decoder with no history-window limit and enabled integrity checks.
    private static XZDecoder newDecoder() {
        return new XZDecoder(CompressionCodec.UNKNOWN_SIZE, true);
    }

    /// Encodes one complete XZ Stream with the selected immutable codec.
    private static byte[] encode(XZCodec codec, byte[] source) throws IOException {
        ByteBuffer encoded = codec.compress(ByteBuffer.wrap(source));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        return bytes;
    }

    /// Decodes one complete Stream through bounded direct target buffers.
    private static byte[] decode(XZDecoder decoder, byte[] encoded) throws IOException {
        ByteBuffer source = ByteBuffer.wrap(encoded);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CodecOutcome outcome;
        do {
            ByteBuffer target = ByteBuffer.allocateDirect(3);
            outcome = decoder.finish(source, target);
            drain(target, output);
        } while (outcome == CodecOutcome.NEEDS_OUTPUT);
        assertEquals(CodecOutcome.FINISHED, outcome);
        assertEquals(encoded.length, source.position());
        return output.toByteArray();
    }

    /// Returns the decoding failure produced while draining bounded target buffers.
    private static IOException decodeFailure(byte[] encoded) {
        return assertThrows(IOException.class, () -> {
            ByteBuffer source = ByteBuffer.wrap(encoded);
            try (XZDecoder decoder = newDecoder()) {
                while (true) {
                    CodecOutcome outcome = decoder.finish(source, ByteBuffer.allocateDirect(17));
                    if (outcome != CodecOutcome.NEEDS_OUTPUT) {
                        throw new AssertionError("Malformed XZ stream unexpectedly finished: " + outcome);
                    }
                }
            }
        });
    }

    /// Asserts decoding fails with the exact stable diagnostic message.
    private static void assertFailureMessage(byte[] encoded, String expectedMessage) {
        assertEquals(expectedMessage, decodeFailure(encoded).getMessage());
    }

    /// Asserts a synthetic Block Header is rejected with the expected wrapped cause.
    private static void assertInvalidBlockHeaderCause(byte[] header, String expectedCause) throws IOException {
        IOException failure = decodeFailure(streamWithBlockHeader(header));
        assertEquals("Invalid XZ Block Header", failure.getMessage());
        IOException cause = assertInstanceOf(IOException.class, failure.getCause());
        assertEquals(expectedCause, cause.getMessage());
    }

    /// Returns a valid Stream Header followed by the supplied Block Header.
    private static byte[] streamWithBlockHeader(byte[] blockHeader) throws IOException {
        byte[] emptyStream = encode(XZCodec.DEFAULT, new byte[0]);
        byte[] result = Arrays.copyOf(emptyStream, FIRST_BLOCK_OFFSET + blockHeader.length);
        System.arraycopy(blockHeader, 0, result, FIRST_BLOCK_OFFSET, blockHeader.length);
        return result;
    }

    /// Creates a CRC-protected Block Header from one flags byte and encoded field bytes.
    private static byte[] blockHeader(int flags, long... fields) {
        int headerSize = (fields.length + 9) & ~3;
        byte[] header = new byte[headerSize];
        header[0] = (byte) (headerSize / 4 - 1);
        header[1] = (byte) flags;
        for (int index = 0; index < fields.length; index++) {
            long field = fields[index];
            if (field < 0L || field > 0xffL) {
                throw new IllegalArgumentException("Block Header test field is not a byte: " + field);
            }
            header[index + 2] = (byte) field;
        }
        rewriteCrc32(header, 0, header.length - Integer.BYTES, header.length - Integer.BYTES);
        return header;
    }

    /// Adds one-byte compressed and uncompressed sizes to a generated single-Block Header.
    private static byte[] addSingleByteBlockSizes(
            byte[] original,
            int compressedSize,
            int uncompressedSize
    ) {
        byte[] encoded = original.clone();
        int headerSize = blockHeaderSize(encoded);
        assertEquals(12, headerSize);
        assertEquals(0, Byte.toUnsignedInt(encoded[FIRST_BLOCK_OFFSET + 1]));
        assertEquals(XZSupport.FILTER_LZMA2, Byte.toUnsignedLong(encoded[FIRST_BLOCK_OFFSET + 2]));
        assertEquals(1, Byte.toUnsignedInt(encoded[FIRST_BLOCK_OFFSET + 3]));
        int property = Byte.toUnsignedInt(encoded[FIRST_BLOCK_OFFSET + 4]);

        encoded[FIRST_BLOCK_OFFSET + 1] = (byte) 0xc0;
        encoded[FIRST_BLOCK_OFFSET + 2] = (byte) compressedSize;
        encoded[FIRST_BLOCK_OFFSET + 3] = (byte) uncompressedSize;
        encoded[FIRST_BLOCK_OFFSET + 4] = (byte) XZSupport.FILTER_LZMA2;
        encoded[FIRST_BLOCK_OFFSET + 5] = 1;
        encoded[FIRST_BLOCK_OFFSET + 6] = (byte) property;
        encoded[FIRST_BLOCK_OFFSET + 7] = 0;
        rewriteCrc32(
                encoded,
                FIRST_BLOCK_OFFSET,
                headerSize - Integer.BYTES,
                FIRST_BLOCK_OFFSET + headerSize - Integer.BYTES
        );
        return encoded;
    }

    /// Returns layout information for a generated Stream containing exactly one Block.
    private static SingleBlockLayout singleBlockLayout(byte[] encoded) throws IOException {
        int indexOffset = indexOffset(encoded);
        int[] cursor = {indexOffset};
        assertEquals(0, Byte.toUnsignedInt(encoded[cursor[0]++]));
        assertEquals(1L, readVli(encoded, cursor));
        int unpaddedFieldOffset = cursor[0];
        long unpaddedSize = readVli(encoded, cursor);
        int uncompressedFieldOffset = cursor[0];
        readVli(encoded, cursor);

        int headerSize = blockHeaderSize(encoded);
        int checkSize = XZCheck.sizeOf(Byte.toUnsignedInt(encoded[7]));
        int compressedSize = Math.toIntExact(unpaddedSize - headerSize - checkSize);
        int blockPaddingSize = -compressedSize & 3;
        int blockPaddingOffset = FIRST_BLOCK_OFFSET + headerSize + compressedSize;
        assertEquals(indexOffset, blockPaddingOffset + blockPaddingSize + checkSize);
        return new SingleBlockLayout(
                indexOffset,
                unpaddedFieldOffset,
                uncompressedFieldOffset,
                compressedSize,
                blockPaddingOffset,
                blockPaddingSize
        );
    }

    /// Returns a generated single-Block Stream containing at least one Block Padding byte.
    private static PaddedBlock paddedBlockStream() throws IOException {
        for (int size = 1; size <= 64; size++) {
            byte[] encoded = encode(
                    XZCodec.DEFAULT.withCheckType(XZCheckType.NONE),
                    patternedBytes(size)
            );
            SingleBlockLayout layout = singleBlockLayout(encoded);
            if (layout.blockPaddingSize() > 0) {
                return new PaddedBlock(encoded, layout);
            }
        }
        throw new AssertionError("Expected an encoded XZ Block requiring padding");
    }

    /// Returns the first Block Header size encoded by a Stream.
    private static int blockHeaderSize(byte[] encoded) {
        return 4 * (Byte.toUnsignedInt(encoded[FIRST_BLOCK_OFFSET]) + 1);
    }

    /// Returns the Index offset recorded by a Stream Footer.
    private static int indexOffset(byte[] encoded) {
        int footerOffset = encoded.length - STREAM_FOOTER_SIZE;
        long storedBackwardSize = XZSupport.getLittleEndian(
                encoded,
                footerOffset + 4,
                Integer.BYTES
        );
        int indexSize = Math.toIntExact((storedBackwardSize + 1L) * 4L);
        return footerOffset - indexSize;
    }

    /// Reads one canonical test VLI and advances its mutable cursor.
    private static long readVli(byte[] encoded, int[] cursor) {
        long value = 0L;
        for (int index = 0; index < 9; index++) {
            int current = Byte.toUnsignedInt(encoded[cursor[0]++]);
            value |= (long) (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                return value;
            }
        }
        throw new AssertionError("Invalid VLI in generated XZ Stream");
    }

    /// Rewrites one little-endian CRC-32 for the selected byte range.
    private static void rewriteCrc32(byte[] bytes, int offset, int length, int storedOffset) {
        XZSupport.putLittleEndian(
                bytes,
                storedOffset,
                XZSupport.crc32(bytes, offset, length),
                Integer.BYTES
        );
    }

    /// Copies produced target bytes into the decoded stream.
    private static void drain(ByteBuffer target, ByteArrayOutputStream output) {
        target.flip();
        byte[] bytes = new byte[target.remaining()];
        target.get(bytes);
        output.writeBytes(bytes);
    }

    /// Creates deterministic source bytes from a requested size.
    private static byte[] patternedBytes(int size) {
        byte[] bytes = new byte[size];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = (byte) (index * 31 + index / 7);
        }
        return bytes;
    }

    /// Describes offsets and sizes in a generated single-Block Stream.
    ///
    /// @param indexOffset offset of the Index indicator
    /// @param unpaddedFieldOffset offset of the first record's unpadded-size VLI
    /// @param uncompressedFieldOffset offset of the first record's uncompressed-size VLI
    /// @param compressedSize encoded LZMA2 payload size
    /// @param blockPaddingOffset offset immediately following the compressed payload
    /// @param blockPaddingSize number of zero padding bytes before the Block Check
    private record SingleBlockLayout(
            int indexOffset,
            int unpaddedFieldOffset,
            int uncompressedFieldOffset,
            int compressedSize,
            int blockPaddingOffset,
            int blockPaddingSize
    ) {
    }

    /// Holds a mutable encoded Stream and its precomputed single-Block layout.
    ///
    /// @param encoded encoded Stream bytes owned by the test
    /// @param layout parsed single-Block layout
    private record PaddedBlock(byte[] encoded, SingleBlockLayout layout) {
    }
}
