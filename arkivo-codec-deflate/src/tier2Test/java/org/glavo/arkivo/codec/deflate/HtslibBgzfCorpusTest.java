// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.deflate;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.DecompressionOutputLimitException;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Checks gzip decoding against HTSlib's BGZF files without interpreting their BAM payloads.
@NotNullByDefault
final class HtslibBgzfCorpusTest {
    /// Supplies original files with different divisions of plaintext into gzip members.
    private static Stream<String> fixtures() {
        return Stream.of("bgziptest.txt.gz", "bgzf_boundaries1.bam", "bgzf_boundaries2.bam",
                "bgzf_boundaries3.bam");
    }

    /// Combines original files with one-byte, short, and complete source reads.
    private static Stream<Arguments> sourceConfigurations() {
        return fixtures().flatMap(name -> Stream.of(1, 7, 8192).map(chunk -> Arguments.of(name, chunk)));
    }

    /// Adds heap, direct, and read-only input layouts to the source fragmentation matrix.
    private static Stream<Arguments> bufferConfigurations() {
        return fixtures().flatMap(name -> Stream.of(1, 7, 8192).flatMap(chunk ->
                Stream.of(0, 1, 2).map(shape -> Arguments.of(name, chunk, shape))));
    }

    /// Verifies the retained upstream scenarios and maps the independent index onto physical member boundaries.
    @Test
    void verifiesIndexAndProvenance() throws IOException {
        assertTrue(Files.readString(root().resolve("LICENSE")).contains("Genome Research Ltd."));
        String source = Files.readString(root().resolve("test_bgzf.c"));
        assertTrue(source.contains("test_embed_eof"));
        assertTrue(source.contains("test_bgzf_getline_on_truncated_file"));
        assertTrue(Files.readString(root().resolve("UPSTREAM.properties")).contains("version=1.22"));
        List<Block> blocks = blocks(read("bgziptest.txt.gz"));
        byte[] index = read("bgziptest.txt.gz.gzi");
        long count = ByteArrayAccess.readLongLittleEndian(index, 0);
        assertEquals(5, count);
        assertEquals(8 + count * 16, index.length);
        assertEquals(count + 1, blocks.size());
        for (int entry = 0; entry < count; entry++) {
            Block block = blocks.get(entry + 1);
            assertEquals(block.offset(), ByteArrayAccess.readLongLittleEndian(index, 8 + entry * 16));
            assertEquals(block.plainOffset(), ByteArrayAccess.readLongLittleEndian(index, 16 + entry * 16));
        }
    }

    /// Checks every engine stop against the producer's block sizes, including the final empty member.
    @ParameterizedTest(name = "{0}, chunk={1}, shape={2}")
    @MethodSource("bufferConfigurations")
    void preservesMemberBoundaries(String name, int chunk, int shape) throws IOException {
        byte[] encoded = read(name);
        byte[] expected = reference(name, encoded);
        List<Block> blocks = blocks(encoded);
        assertEquals(name.equals("bgziptest.txt.gz") ? 6 : name.equals("bgzf_boundaries3.bam") ? 19 : 4,
                blocks.size());
        ByteBuffer storage = shape == 1 ? ByteBuffer.allocateDirect(encoded.length + 4)
                : ByteBuffer.allocate(encoded.length + 4);
        storage.put((byte) 0x55).put(encoded).put(new byte[]{0x12, 0x34, 0x56});
        storage.position(1).limit(1 + Math.min(chunk, encoded.length + 3));
        ByteBuffer input = shape == 2 ? storage.asReadOnlyBuffer() : storage;
        ByteBuffer output = shape == 1 ? ByteBuffer.allocateDirect(5) : ByteBuffer.allocate(5);
        var plain = new ByteArrayOutputStream();
        try (var decoder = GzipCodec.DEFAULT.newDecoder()) {
            int member = 0;
            for (int step = 0; step < 10000; step++) {
                // Periodic empty targets exercise suspension without assuming that headers cannot be consumed.
                output.clear().put((byte) 0x66);
                output.put(4, (byte) 0x77);
                output.limit(step % 4 == 0 ? 1 : 4);
                CodecOutcome outcome = decoder.decode(input, output);
                int produced = output.position() - 1;
                output.limit(output.capacity());
                assertEquals((byte) 0x66, output.get(0));
                assertEquals((byte) 0x77, output.get(4));
                for (int index = 1; index <= produced; index++) plain.write(output.get(index));
                assertTrue(plain.size() <= expected.length, "output exceeds independently verified size");
                if (outcome == CodecOutcome.FINISHED) {
                    Block block = blocks.get(member++);
                    assertEquals(1 + block.offset() + block.length(), input.position());
                    assertEquals(block.plainOffset() + block.plainSize(), plain.size());
                    decoder.reset();
                    if (member == blocks.size()) {
                        input.limit(input.capacity());
                        assertEquals(3, input.remaining());
                        assertEquals(0x12, input.get());
                        assertEquals(0x34, input.get());
                        assertEquals(0x56, input.get());
                        assertEquals((byte) 0x55, input.get(0));
                        assertArrayEquals(expected, plain.toByteArray());
                        return;
                    }
                } else if (outcome == CodecOutcome.NEEDS_INPUT) {
                    assertFalse(input.hasRemaining());
                    assertTrue(input.limit() < input.capacity(), "decoder consumed the suffix as gzip data");
                    input.limit(Math.min(input.capacity(), input.limit() + chunk));
                } else {
                    assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                }
            }
            fail("Decoder did not finish within the bounded number of calls");
        }
    }

    /// Reads all members through both adapters, even when the source reports no immediately available bytes.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("sourceConfigurations")
    void readsStreamsAndChannels(String name, int chunk) throws IOException {
        byte[] encoded = read(name);
        byte[] expected = reference(name, encoded);
        assertArrayEquals(expected, decode(encoded, chunk, expected.length));
        try (var source = Channels.newChannel(new FragmentedInput(encoded, chunk));
             var channel = GzipCodec.DEFAULT.withMaximumOutputSize(expected.length).newReadableByteChannel(source)) {
            var result = new ByteArrayOutputStream();
            ByteBuffer target = ByteBuffer.allocateDirect(3);
            for (int step = 0; step <= expected.length + 1; step++) {
                int count = channel.read(target.clear());
                if (count < 0) {
                    assertArrayEquals(expected, result.toByteArray());
                    assertEquals(-1, channel.read(target.clear()));
                    return;
                }
                assertTrue(count > 0);
                for (int index = 0; index < count; index++) result.write(target.get(index));
            }
            fail("Channel did not reach EOF within the output bound");
        }
    }

    /// Accepts complete member prefixes but rejects every prefix ending inside a member.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("sourceConfigurations")
    void distinguishesTruncationFromMemberEof(String name, int chunk) throws IOException {
        byte[] encoded = read(name);
        byte[] expected = reference(name, encoded);
        List<Block> blocks = blocks(encoded);
        int member = 0;
        for (int length = 0; length <= encoded.length; length++) {
            byte[] prefix = Arrays.copyOf(encoded, length);
            Block block = blocks.get(member);
            if (length == 0 || length == block.offset() + block.length()) {
                int plainSize = length == 0 ? 0 : block.plainOffset() + block.plainSize();
                assertArrayEquals(Arrays.copyOf(expected, plainSize), decode(prefix, chunk, expected.length));
                if (length != 0 && member + 1 < blocks.size()) member++;
            } else {
                IOException failure = assertThrows(IOException.class, () -> decode(prefix, chunk, expected.length),
                        name + " truncated at " + length);
                assertFalse(failure instanceof DecompressionOutputLimitException,
                        "valid prefix must fail for truncation, not for exceeding the known output size");
            }
        }
    }

    /// Checks independent signatures, reserved flags, checksums, and lengths in every physical member.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("sourceConfigurations")
    void rejectsCorruptMembers(String name, int chunk) throws IOException {
        byte[] encoded = read(name);
        int expectedSize = reference(name, encoded).length;
        for (Block block : blocks(encoded)) {
            for (int offset : new int[]{block.offset(), block.offset() + 3,
                    block.offset() + block.length() - 8, block.offset() + block.length() - 4}) {
                byte[] damaged = encoded.clone();
                damaged[offset] ^= 0x40;
                IOException failure = assertThrows(IOException.class, () -> decode(damaged, chunk, expectedSize),
                        name + " corrupted at " + offset);
                assertFalse(failure instanceof DecompressionOutputLimitException);
            }
        }
    }

    /// Treats embedded BGZF EOF markers as empty gzip members and retains one budget across appended data.
    @ParameterizedTest(name = "{0}, chunk={1}")
    @MethodSource("sourceConfigurations")
    void readsPastEmbeddedEof(String name, int chunk) throws IOException {
        byte[] encoded = read(name);
        byte[] expected = reference(name, encoded);
        List<Block> blocks = blocks(encoded);
        Block last = blocks.get(blocks.size() - 1);
        assertEquals(0, last.plainSize());
        assertEquals(28, last.length());
        byte[] marker = Arrays.copyOfRange(encoded, last.offset(), encoded.length);
        var appended = new ByteArrayOutputStream();
        appended.writeBytes(marker);
        appended.writeBytes(encoded);
        appended.writeBytes(marker);
        appended.writeBytes(encoded);
        byte[] combined = appended.toByteArray();
        byte[] doubled = new byte[expected.length * 2];
        System.arraycopy(expected, 0, doubled, 0, expected.length);
        System.arraycopy(expected, 0, doubled, expected.length, expected.length);
        assertArrayEquals(doubled, decode(combined, chunk, doubled.length));
        assertArrayEquals(new byte[0], decode(marker, chunk, 0));
        for (int limit : new int[]{0, expected.length - 1, expected.length, doubled.length - 1}) {
            assertThrows(DecompressionOutputLimitException.class, () -> decode(combined, chunk, limit));
        }
    }

    /// Leaves BGZF-specific block-size semantics to BGZF readers rather than imposing them on generic gzip.
    @ParameterizedTest
    @MethodSource("fixtures")
    void ignoresBgzfSizeHint(String name) throws IOException {
        byte[] encoded = read(name);
        byte[] expected = reference(name, encoded);
        for (Block block : blocks(encoded)) {
            ByteArrayAccess.writeShortLittleEndian(encoded, block.offset() + 16, (short) 0xffff);
        }
        assertArrayEquals(expected, decode(encoded, 1, expected.length));
    }

    /// Decodes with the JDK and checks plaintext against a digest independently obtained with .NET GZipStream.
    private static byte[] reference(String name, byte @Unmodifiable [] encoded) throws IOException {
        byte[] plain;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            plain = gzip.readNBytes(312);
            assertEquals(-1, gzip.read());
        }
        boolean text = name.equals("bgziptest.txt.gz");
        assertEquals(text ? 15 : 311, plain.length);
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(plain));
            assertEquals(text ? "4891f856480a90e2f1132555cf4617fd07d1faec1c3b37df0b09c8b3b5ccb1ae"
                    : "9322a55ec1e7795ae3c6a4f0758a8135e9bc0beafd0c8595a28d20e1dc1fc2d4", digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        if (text) assertArrayEquals(read("bgziptest.txt"), plain);
        return plain;
    }

    /// Parses only the fixed BGZF header layout present in the pinned originals.
    private static @Unmodifiable List<Block> blocks(byte @Unmodifiable [] encoded) {
        List<Block> result = new ArrayList<>();
        int plainOffset = 0;
        for (int offset = 0; offset < encoded.length;) {
            assertTrue(encoded.length - offset >= 28);
            assertEquals(0x1f, Byte.toUnsignedInt(encoded[offset]));
            assertEquals(0x8b, Byte.toUnsignedInt(encoded[offset + 1]));
            assertEquals(8, encoded[offset + 2]);
            assertEquals(4, encoded[offset + 3]);
            assertEquals(6, ByteArrayAccess.readShortLittleEndian(encoded, offset + 10));
            assertEquals('B', encoded[offset + 12]);
            assertEquals('C', encoded[offset + 13]);
            assertEquals(2, ByteArrayAccess.readShortLittleEndian(encoded, offset + 14));
            int length = Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(encoded, offset + 16)) + 1;
            assertTrue(length >= 28 && length <= encoded.length - offset);
            int size = ByteArrayAccess.readIntLittleEndian(encoded, offset + length - 4);
            assertTrue(size >= 0 && size <= 311);
            result.add(new Block(offset, length, size, plainOffset));
            plainOffset += size;
            offset += length;
        }
        return List.copyOf(result);
    }

    /// Drains concatenated members with an explicit cumulative output budget.
    private static byte[] decode(byte @Unmodifiable [] encoded, int chunk, int limit) throws IOException {
        try (var input = GzipCodec.DEFAULT.withMaximumOutputSize(limit)
                .newInputStream(new FragmentedInput(encoded, chunk))) {
            return input.readAllBytes();
        }
    }

    /// Reads one verified upstream resource.
    private static byte[] read(String name) throws IOException {
        return Files.readAllBytes(root().resolve(name));
    }

    /// Returns the corpus directory prepared by Gradle.
    private static Path root() {
        return Path.of(Objects.requireNonNull(System.getProperty("arkivo.htslib.testDataDirectory")));
    }

    /// Describes one original BGZF member without retaining its input array.
    ///
    /// @param offset the member's compressed offset
    /// @param length the member's compressed length including its header and trailer
    /// @param plainSize the trailer's uncompressed size
    /// @param plainOffset the sum of preceding uncompressed sizes
    @NotNullByDefault
    private record Block(int offset, int length, int plainSize, int plainOffset) {
    }

    /// Limits compressed reads and deliberately makes no promise of immediately available bytes.
    @NotNullByDefault
    private static final class FragmentedInput extends ByteArrayInputStream {
        /// The largest bulk read returned to the adapter.
        private final int chunk;

        /// Creates a source with the requested read bound.
        private FragmentedInput(byte @Unmodifiable [] encoded, int chunk) {
            super(encoded);
            this.chunk = chunk;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) {
            return super.read(target, offset, Math.min(length, chunk));
        }

        @Override
        public synchronized int available() {
            return 0;
        }
    }
}
