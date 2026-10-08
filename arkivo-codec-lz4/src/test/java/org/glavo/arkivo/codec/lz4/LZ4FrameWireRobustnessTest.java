// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests strict parsing and size accounting at LZ4 frame wire-format boundaries.
@NotNullByDefault
public final class LZ4FrameWireRobustnessTest {
    /// Standard descriptor flags for independent blocks without optional fields.
    private static final int BASIC_FLAGS = 0x60;

    /// Resumes an empty frame after output exhaustion without emitting bytes or consuming trailing input.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void resumesEmptyRawBlocksAfterOutputExhaustion(int flags) throws IOException {
        LZ4Codec codec = new LZ4Codec().withIndependentBlocks((flags & 4) != 0)
                .withBlockChecksum((flags & 1) != 0).withContentChecksum((flags & 2) != 0);
        byte[] empty = bytes(codec.compress(ByteBuffer.allocate(0)));
        int headerSize = (empty[4] & 8) != 0 ? 15 : 7;
        byte[] block = new byte[(flags & 1) != 0 ? 8 : 4];
        ByteArrayAccess.writeIntLittleEndian(block, 0, Integer.MIN_VALUE);
        if ((flags & 1) != 0) {
            ByteArrayAccess.writeIntLittleEndian(block, 4, XXHash32.DEFAULT.computeInt(new byte[0]));
        }
        ByteBuffer assembled = ByteBuffer.allocateDirect(empty.length + block.length + 1);
        assembled.put(empty, 0, headerSize).put(block)
                .put(empty, headerSize, empty.length - headerSize).put((byte) 0x5a).flip();
        @UnmodifiableView ByteBuffer source = assembled.asReadOnlyBuffer();
        try (var decoder = codec.newDecoder()) {
            CodecOutcome outcome = decoder.finish(source, ByteBuffer.allocate(0));
            if (outcome != CodecOutcome.FINISHED) {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                ByteBuffer target = ByteBuffer.allocateDirect(1);
                assertEquals(CodecOutcome.FINISHED, decoder.finish(source, target));
                assertEquals(0, target.position());
            }
            assertEquals(1, source.remaining());
            assertEquals((byte) 0x5a, source.get());
        }
    }

    /// Accepts empty raw blocks before and after payload while still requiring the distinct all-zero EndMark.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
    void distinguishesEmptyRawBlocksFromEndMark(int flags) throws IOException {
        byte[] expected = {1, 2, 3};
        LZ4Codec codec = new LZ4Codec().withIndependentBlocks((flags & 4) != 0)
                .withBlockChecksum((flags & 1) != 0).withContentChecksum((flags & 2) != 0);
        byte[] frame = bytes(codec.compress(ByteBuffer.wrap(expected)));
        int headerSize = (frame[4] & 8) != 0 ? 15 : 7;
        int footerSize = (flags & 2) != 0 ? 8 : 4;
        byte[] emptyBlock = new byte[(flags & 1) != 0 ? 8 : 4];
        ByteArrayAccess.writeIntLittleEndian(emptyBlock, 0, Integer.MIN_VALUE);
        if ((flags & 1) != 0) {
            ByteArrayAccess.writeIntLittleEndian(emptyBlock, 4, XXHash32.DEFAULT.computeInt(new byte[0]));
        }
        ByteBuffer assembled = ByteBuffer.allocate(frame.length + 2 * emptyBlock.length);
        assembled.put(frame, 0, headerSize).put(emptyBlock)
                .put(frame, headerSize, frame.length - headerSize - footerSize).put(emptyBlock)
                .put(frame, frame.length - footerSize, footerSize);
        byte[] withEmptyBlocks = assembled.array();
        assertArrayEquals(expected, decompress(codec, withEmptyBlocks));
        byte[] missingEndMark = Arrays.copyOf(withEmptyBlocks, withEmptyBlocks.length - footerSize);
        assertThrows(IOException.class, () -> decompress(codec, missingEndMark));
    }

    /// Verifies every nonempty proper prefix of standard and skippable frames is rejected as truncated.
    @Test
    public void rejectsEveryTruncatedFramePrefix() throws IOException {
        byte[] content = randomBytes(257, 0x4c5a_3402L);
        LZ4Codec codec = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .blockChecksum(true)
                .contentChecksum(true)
                .build();
        byte[] standardFrame = bytes(codec.compress(ByteBuffer.wrap(content)));
        assertArrayEquals(content, decompress(codec, standardFrame));
        assertEveryNonemptyProperPrefixRejected(codec, standardFrame);

        byte[] skippableFrame = skippableFrame(randomBytes(23, 0x5a1f_0001L));
        assertArrayEquals(new byte[0], decompress(codec, skippableFrame));
        assertEveryNonemptyProperPrefixRejected(codec, skippableFrame);
    }

    /// Verifies all unsupported FLG and BD byte values are rejected before payload processing.
    @Test
    public void rejectsEveryInvalidDescriptorByteValue() {
        for (int flags = 0; flags <= 0xff; flags++) {
            if ((flags & 0xc0) == 0x40 && (flags & 0x02) == 0) {
                continue;
            }
            byte[] prefix = descriptorPrefix(flags, 0x40);
            int testedFlags = flags;
            assertThrows(
                    IOException.class,
                    () -> decompress(new LZ4Codec(), prefix),
                    () -> "FLG 0x" + Integer.toHexString(testedFlags)
            );
        }

        for (int blockDescriptor = 0; blockDescriptor <= 0xff; blockDescriptor++) {
            if (blockDescriptor == 0x40
                    || blockDescriptor == 0x50
                    || blockDescriptor == 0x60
                    || blockDescriptor == 0x70) {
                continue;
            }
            byte[] prefix = descriptorPrefix(BASIC_FLAGS, blockDescriptor);
            int testedDescriptor = blockDescriptor;
            assertThrows(
                    IOException.class,
                    () -> decompress(new LZ4Codec(), prefix),
                    () -> "BD 0x" + Integer.toHexString(testedDescriptor)
            );
        }

        byte[] badHeaderChecksum = emptyStandardFrame(BASIC_FLAGS, 0x40, 0L);
        badHeaderChecksum[6] ^= 1;
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), badHeaderChecksum));
    }

    /// Verifies declared content sizes, block maxima, and unsigned skippable lengths are enforced.
    @Test
    public void enforcesDeclaredWireSizes() throws IOException {
        byte[] emptyWithExactSize = emptyStandardFrame(BASIC_FLAGS | 0x08, 0x40, 0L);
        assertArrayEquals(new byte[0], decompress(new LZ4Codec(), emptyWithExactSize));

        byte[] emptyWithWrongSize = emptyStandardFrame(BASIC_FLAGS | 0x08, 0x40, 1L);
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), emptyWithWrongSize));

        byte[] outputExceedsDeclaredSize = uncompressedStandardFrame(1L, new byte[]{1, 2});
        IOException excessiveOutput = assertThrows(
                IOException.class,
                () -> decompress(new LZ4Codec(), outputExceedsDeclaredSize)
        );
        assertEquals("LZ4 frame content size mismatch", excessiveOutput.getMessage());

        byte[] unrepresentableContentSize = emptyStandardFrame(
                BASIC_FLAGS | 0x08,
                0x40,
                Long.MIN_VALUE
        );
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), unrepresentableContentSize));

        for (LZ4BlockSize blockSize : LZ4BlockSize.values()) {
            byte[] header = standardHeader(BASIC_FLAGS, blockSize.descriptorCode() << 4, 0L);
            byte[] oversizedBlock = Arrays.copyOf(header, header.length + Integer.BYTES);
            ByteArrayAccess.writeIntLittleEndian(
                    oversizedBlock,
                    header.length,
                    blockSize.byteSize() + 1
            );
            assertThrows(
                    IOException.class,
                    () -> decompress(new LZ4Codec(), oversizedBlock),
                    blockSize.name()
            );
        }

        byte[] hugeSkippableFrame = new byte[2 * Integer.BYTES];
        ByteArrayAccess.writeIntLittleEndian(hugeSkippableFrame, 0, 0x184d_2a50);
        ByteArrayAccess.writeIntLittleEndian(hugeSkippableFrame, Integer.BYTES, -1);
        assertThrows(IOException.class, () -> decompress(new LZ4Codec(), hugeSkippableFrame));
    }

    /// Verifies the frame bound covers required metadata and physical block boundaries for every option family.
    @Test
    public void compressedSizeBoundCoversFrameOptionsAndBlockEdges() throws IOException {
        byte[] dictionaryBytes = randomBytes(257, 0xd1c7_10a4L);
        for (LZ4BlockSize blockSize : LZ4BlockSize.values()) {
            for (boolean blockChecksum : new boolean[]{false, true}) {
                for (boolean contentChecksum : new boolean[]{false, true}) {
                    for (boolean identifiedDictionary : new boolean[]{false, true}) {
                        LZ4Codec.Builder builder = LZ4Codec.builder()
                                .blockSize(blockSize)
                                .blockChecksum(blockChecksum)
                                .contentChecksum(contentChecksum);
                        if (identifiedDictionary) {
                            builder.dictionary(LZ4Dictionary.identified(0xfedc_ba98L, dictionaryBytes));
                        }
                        LZ4Codec codec = builder.build();
                        byte[] compressed = bytes(codec.compress(ByteBuffer.allocate(0)));
                        assertTrue(
                                compressed.length <= codec.maxCompressedSize(0L),
                                blockSize + ", block checksum " + blockChecksum
                                        + ", content checksum " + contentChecksum
                                        + ", dictionary " + identifiedDictionary
                        );
                    }
                }
            }
        }

        LZ4Codec boundaryCodec = LZ4Codec.builder()
                .blockSize(LZ4BlockSize.KIB_64)
                .blockChecksum(true)
                .contentChecksum(true)
                .dictionary(LZ4Dictionary.identified(7L, dictionaryBytes))
                .build();
        int[] lengths = {1, 65_535, 65_536, 65_537, 131_073};
        for (int length : lengths) {
            byte[] input = randomBytes(length, 0xb10c_0000L + length);
            byte[] compressed = bytes(boundaryCodec.compress(ByteBuffer.wrap(input)));
            assertTrue(compressed.length <= boundaryCodec.maxCompressedSize(length), "length " + length);
        }

        assertEquals(Long.MAX_VALUE, boundaryCodec.maxCompressedSize(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> boundaryCodec.maxCompressedSize(-1L));
    }

    /// Requires each nonempty proper prefix of one complete frame to fail at physical end of input.
    private static void assertEveryNonemptyProperPrefixRejected(LZ4Codec codec, byte[] frame) {
        for (int length = 1; length < frame.length; length++) {
            byte[] prefix = Arrays.copyOf(frame, length);
            int prefixLength = length;
            assertThrows(
                    IOException.class,
                    () -> decompress(codec, prefix),
                    () -> "prefix length " + prefixLength + " of " + frame.length
            );
        }
    }

    /// Creates the fixed six-byte prefix used to validate descriptor bytes before optional fields are collected.
    private static byte[] descriptorPrefix(int flags, int blockDescriptor) {
        byte[] prefix = new byte[Integer.BYTES + 2];
        ByteArrayAccess.writeIntLittleEndian(prefix, 0, (int) LZ4Format.FRAME_MAGIC);
        prefix[Integer.BYTES] = (byte) flags;
        prefix[Integer.BYTES + 1] = (byte) blockDescriptor;
        return prefix;
    }

    /// Creates a complete empty standard frame with an optional declared content size.
    private static byte[] emptyStandardFrame(int flags, int blockDescriptor, long contentSize) {
        byte[] header = standardHeader(flags, blockDescriptor, contentSize);
        int contentChecksumLength = (flags & 0x04) != 0 ? Integer.BYTES : 0;
        byte[] frame = Arrays.copyOf(header, header.length + Integer.BYTES + contentChecksumLength);
        if (contentChecksumLength != 0) {
            ByteArrayAccess.writeIntLittleEndian(
                    frame,
                    header.length + Integer.BYTES,
                    XXHash32.DEFAULT.computeInt(new byte[0])
            );
        }
        return frame;
    }

    /// Creates a standard frame containing one independently encoded uncompressed block.
    private static byte[] uncompressedStandardFrame(long declaredContentSize, byte[] content) {
        byte[] header = standardHeader(BASIC_FLAGS | 0x08, 0x40, declaredContentSize);
        byte[] frame = Arrays.copyOf(
                header,
                header.length + Integer.BYTES + content.length + Integer.BYTES
        );
        ByteArrayAccess.writeIntLittleEndian(frame, header.length, Integer.MIN_VALUE | content.length);
        System.arraycopy(content, 0, frame, header.length + Integer.BYTES, content.length);
        return frame;
    }

    /// Creates a standard frame header and a valid checksum for its descriptor fields.
    private static byte[] standardHeader(int flags, int blockDescriptor, long contentSize) {
        int optionalLength = (flags & 0x08) != 0 ? Long.BYTES : 0;
        if ((flags & 0x01) != 0) {
            optionalLength += Integer.BYTES;
        }
        byte[] descriptor = new byte[2 + optionalLength];
        descriptor[0] = (byte) flags;
        descriptor[1] = (byte) blockDescriptor;
        int position = 2;
        if ((flags & 0x08) != 0) {
            ByteArrayAccess.writeLongLittleEndian(descriptor, position, contentSize);
            position += Long.BYTES;
        }
        if ((flags & 0x01) != 0) {
            ByteArrayAccess.writeIntLittleEndian(descriptor, position, 0);
        }

        byte[] header = new byte[Integer.BYTES + descriptor.length + 1];
        ByteArrayAccess.writeIntLittleEndian(header, 0, (int) LZ4Format.FRAME_MAGIC);
        System.arraycopy(descriptor, 0, header, Integer.BYTES, descriptor.length);
        header[header.length - 1] = (byte) (XXHash32.DEFAULT.computeInt(descriptor) >>> 8);
        return header;
    }

    /// Creates one complete skippable frame around the supplied payload.
    private static byte[] skippableFrame(byte[] payload) {
        byte[] frame = new byte[2 * Integer.BYTES + payload.length];
        ByteArrayAccess.writeIntLittleEndian(frame, 0, 0x184d_2a5f);
        ByteArrayAccess.writeIntLittleEndian(frame, Integer.BYTES, payload.length);
        System.arraycopy(payload, 0, frame, 2 * Integer.BYTES, payload.length);
        return frame;
    }

    /// Decompresses all frames through the streaming adapter.
    private static byte[] decompress(LZ4Codec codec, byte[] input) throws IOException {
        try (InputStream decoder = codec.newInputStream(new ByteArrayInputStream(input))) {
            return decoder.readAllBytes();
        }
    }

    /// Copies one readable buffer into an owned byte array.
    private static byte[] bytes(ByteBuffer buffer) {
        ByteBuffer source = buffer.slice();
        byte[] bytes = new byte[source.remaining()];
        source.get(bytes);
        return bytes;
    }

    /// Returns deterministic pseudo-random bytes.
    private static byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }
}
