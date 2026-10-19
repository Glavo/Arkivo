// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.lz4;

import net.jpountz.xxhash.XXHashFactory;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.UnmodifiableView;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Independently exercises the changing-buffer scenarios in LZ4 1.10.0 frametest.c's test_lz4f_decompression.
/// Fixtures and scheduling are generated here without copying the upstream C implementation.
@NotNullByDefault
final class LZ4IncrementalLayoutTest {
    /// Crosses output layouts, buffer storage, block dependencies, dictionaries, and integrity checks.
    private static Stream<Arguments> configurations() {
        var result = Stream.<Arguments>builder();
        for (Layout layout : Layout.values()) {
            for (boolean direct : new boolean[]{false, true}) {
                for (boolean independent : new boolean[]{false, true}) {
                    for (boolean dictionary : new boolean[]{false, true}) {
                        for (int checksums = 0; checksums < 4; checksums++) {
                            result.add(Arguments.of(layout, direct, independent, dictionary, checksums));
                        }
                    }
                }
            }
        }
        return result.build();
    }

    /// Makes dictionary and linked-history references after caller storage has been poisoned or replaced.
    @ParameterizedTest(name = "{0}, direct={1}, independent={2}, dictionary={3}, checksums={4}")
    @MethodSource("configurations")
    void decodesWithChangingBuffers(Layout layout, boolean direct, boolean independent,
                                   boolean dictionary, int checksums) throws IOException {
        Frame frame = frame(independent, dictionary, checksums, 65_536);
        assertDecoded(frame.codec(), frame.encoded(), frame.content(), layout, direct, false);
    }

    /// Supplies individual bytes through every frame field and drains each output byte separately.
    @ParameterizedTest(name = "{0}, direct={1}, independent={2}, dictionary={3}, checksums={4}")
    @MethodSource("configurations")
    void decodesOneByteAtATime(Layout layout, boolean direct, boolean independent,
                              boolean dictionary, int checksums) throws IOException {
        Frame frame = frame(independent, dictionary, checksums, 23);
        assertDecoded(frame.codec(), frame.encoded(), frame.content(), layout, direct, true);
    }

    /// Applies all caller-storage layouts to independently produced native frames.
    static void assertNativeFrame(LZ4Codec codec, byte[] encoded, byte[] content) throws IOException {
        for (Layout layout : Layout.values()) {
            assertDecoded(codec, encoded, content, layout, layout != Layout.CONTIGUOUS, false);
        }
    }

    /// Replaces input on every call, preserves unconsumed bytes, and checks exact output and guarded buffer windows.
    private static void assertDecoded(LZ4Codec codec, byte[] encoded, byte[] content,
                                      Layout layout, boolean direct, boolean singleByte) throws IOException {
        byte[] input = Arrays.copyOf(encoded, encoded.length + 3);
        Arrays.fill(input, encoded.length, input.length, (byte) 0x6b);
        int maximumChunk = singleByte ? 1 : 4096;
        int destinationSize = switch (layout) {
            case CONTIGUOUS -> content.length + maximumChunk + 8;
            case GAPPED -> 2 * (encoded.length + content.length) + maximumChunk + 256;
            case OVERWRITE -> maximumChunk + 8;
        };
        ByteBuffer destination = allocate(destinationSize, direct);
        byte[] actual = new byte[content.length];
        var random = new Random(0x4c5a_3446L);
        try (var decoder = codec.newDecoder()) {
            // Reusing the engine also checks that a completed history cannot leak into another frame.
            for (int pass = 0; pass < 2; pass++) {
                decoder.reset();
                int inputPosition = 0;
                int available = 0;
                int outputPosition = 0;
                int destinationPosition = 3;
                CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
                for (int step = 0; outcome != CodecOutcome.FINISHED; step++) {
                    assertTrue(step < (encoded.length + content.length + 32) * 4, "Decoder stalled");
                    if (outcome == CodecOutcome.NEEDS_INPUT) {
                        assertTrue(available < input.length, "Decoder requested input beyond the frame and sentinel");
                        available = Math.min(input.length, available + (singleByte ? 1 : 1 + random.nextInt(maximumChunk)));
                        // Present the next frame's bytes alongside the end of this frame, even with one-byte input.
                        if (available >= encoded.length) available = input.length;
                    }
                    int count = available - inputPosition;
                    ByteBuffer storage = allocate(count + 7, direct);
                    storage.put(0, (byte) 0x31).put(1, (byte) 0x32);
                    storage.position(2).put(input, inputPosition, count).put((byte) 0x33);
                    @UnmodifiableView ByteBuffer source = storage.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
                    source.position(2).limit(2 + count).mark();
                    // Exercise output backpressure independently of input availability.
                    int capacity = step % 11 == 0 ? 0 : singleByte ? 1 : 1 + random.nextInt(maximumChunk);
                    int start = layout == Layout.OVERWRITE ? 3 : destinationPosition;
                    capacity = Math.min(capacity, destination.capacity() - start - 2);
                    ByteBuffer target = destination.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                    target.position(start).limit(start + capacity).mark();
                    byte preceding = destination.get(start - 1);
                    destination.put(start + capacity, (byte) 0x67);
                    // Alternate ordinary decoding and explicit end-of-input on the last input segment.
                    outcome = pass == 1 && available == input.length
                            ? decoder.finish(source, target) : decoder.decode(source, target);
                    int consumed = source.position() - 2;
                    int produced = target.position() - start;
                    assertEquals(2 + count, source.limit());
                    assertEquals(start + capacity, target.limit());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, source.order());
                    assertEquals(ByteOrder.LITTLE_ENDIAN, target.order());
                    assertEquals(preceding, destination.get(start - 1));
                    assertEquals((byte) 0x67, destination.get(start + capacity));
                    assertEquals((byte) 0x31, storage.get(0));
                    assertEquals((byte) 0x32, storage.get(1));
                    assertEquals((byte) 0x33, storage.get(2 + count));
                    assertTrue(outputPosition + produced <= content.length, "Unexpected decoded bytes");
                    target.get(start, actual, outputPosition, produced);
                    source.reset();
                    target.reset();
                    assertEquals(2, source.position());
                    assertEquals(start, target.position());
                    assertEquals(ByteBuffer.wrap(input, inputPosition, count),
                            storage.duplicate().position(2).limit(2 + count));
                    // The caller owns these bytes again as soon as decode returns.
                    for (int i = 0; i < storage.capacity(); i++) storage.put(i, (byte) 0x5a);
                    if (layout == Layout.OVERWRITE) {
                        for (int i = start; i < start + produced; i++) destination.put(i, (byte) 0xa5);
                    }
                    inputPosition += consumed;
                    outputPosition += produced;
                    assertTrue(inputPosition <= encoded.length, "Decoder consumed the following frame");
                    if (layout != Layout.OVERWRITE) destinationPosition += produced + (layout == Layout.GAPPED ? 1 : 0);
                    switch (outcome) {
                        case NEEDS_OUTPUT -> assertEquals(capacity, produced);
                        case NEEDS_INPUT -> assertTrue(available < input.length);
                        case FINISHED -> assertEquals(encoded.length, inputPosition);
                        default -> throw new AssertionError("Unexpected outcome: " + outcome);
                    }
                }
                assertEquals(content.length, outputPosition);
                assertArrayEquals(content, actual);
                if (layout == Layout.CONTIGUOUS) {
                    assertEquals(ByteBuffer.wrap(content), destination.duplicate().position(3).limit(3 + content.length));
                }
                ByteBuffer tail = ByteBuffer.wrap(input, encoded.length, 3);
                assertEquals(CodecOutcome.FINISHED, decoder.decode(tail, ByteBuffer.allocate(0)));
                assertEquals(encoded.length, tail.position());
            }
        }
    }

    /// Allocates the requested heap or direct scratch buffer.
    private static ByteBuffer allocate(int size, boolean direct) {
        return direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }

    /// Creates raw and matched blocks independently of the Arkivo encoder, including distance 65535 references.
    static Frame frame(boolean independent, boolean useDictionary, int checksums, int blockSize) {
        byte[] dictionary = new byte[65_536];
        new Random(0x4449_4354L).nextBytes(dictionary);
        byte[] raw = new byte[blockSize];
        new Random(0x5354_4f52L).nextBytes(raw);
        var body = new ByteArrayOutputStream();
        var content = new ByteArrayOutputStream();
        if (useDictionary) appendMatch(body, content, dictionary, blockSize, checksums);
        else appendRaw(body, content, raw, checksums);
        appendRaw(body, content, raw, checksums);
        appendMatch(body, content, independent ? (useDictionary ? dictionary : new byte[0]) : raw, blockSize, checksums);
        appendRaw(body, content, new byte[]{1, 3, 5, 7, 9}, checksums);
        appendInt(body, 0);
        byte[] expected = content.toByteArray();
        if ((checksums & 2) != 0) appendInt(body, hash(expected));
        byte[] descriptor = new byte[10];
        descriptor[0] = (byte) (0x48 | (independent ? 0x20 : 0) | ((checksums & 1) != 0 ? 0x10 : 0)
                | ((checksums & 2) != 0 ? 4 : 0));
        descriptor[1] = 0x40;
        ByteArrayAccess.writeLongLittleEndian(descriptor, 2, expected.length);
        var encoded = new ByteArrayOutputStream();
        appendInt(encoded, 0x184d2204);
        encoded.writeBytes(descriptor);
        encoded.write(hash(descriptor) >>> 8);
        encoded.writeBytes(body.toByteArray());
        LZ4Codec codec = new LZ4Codec().withMaximumOutputSize(expected.length).withMaximumMemorySize(1 << 20);
        if (useDictionary) codec = codec.withDictionary(LZ4Dictionary.rawContent(dictionary));
        return new Frame(codec, encoded.toByteArray(), expected);
    }

    /// Appends a raw block and its independently known plaintext.
    private static void appendRaw(ByteArrayOutputStream body, ByteArrayOutputStream content, byte[] raw, int checksums) {
        appendInt(body, raw.length | 0x8000_0000);
        body.writeBytes(raw);
        if ((checksums & 1) != 0) appendInt(body, hash(raw));
        content.writeBytes(raw);
    }

    /// Encodes one match followed by five literals, using either external history or a single repeated literal.
    private static void appendMatch(ByteArrayOutputStream body, ByteArrayOutputStream content,
                                    byte[] history, int size, int checksums) {
        int literals = history.length == 0 ? 1 : 0;
        int distance = history.length == 0 ? 1 : Math.min(65_535, history.length);
        int matchLength = size - literals - 5;
        var block = new ByteArrayOutputStream();
        block.write(literals << 4 | Math.min(15, matchLength - 4));
        if (literals != 0) block.write('x');
        block.write(distance);
        block.write(distance >>> 8);
        if (matchLength >= 19) {
            int remaining = matchLength - 19;
            while (remaining >= 255) {
                block.write(255);
                remaining -= 255;
            }
            block.write(remaining);
        }
        block.write(0x50);
        block.writeBytes(new byte[]{11, 13, 17, 19, 23});
        byte[] payload = block.toByteArray();
        appendInt(body, payload.length);
        body.writeBytes(payload);
        if ((checksums & 1) != 0) appendInt(body, hash(payload));
        if (literals != 0) content.write('x');
        for (int i = 0; i < matchLength; i++) content.write(history.length == 0 ? 'x' : history[history.length - distance + i]);
        content.writeBytes(new byte[]{11, 13, 17, 19, 23});
    }

    /// Computes a frame checksum with the independent lz4-java implementation.
    private static int hash(byte[] bytes) {
        return XXHashFactory.safeInstance().hash32().hash(bytes, 0, bytes.length, 0);
    }

    /// Appends an unsigned little-endian word.
    private static void appendInt(ByteArrayOutputStream output, int value) {
        byte[] bytes = new byte[4];
        ByteArrayAccess.writeIntLittleEndian(bytes, 0, value);
        output.writeBytes(bytes);
    }

    /// Holds freshly generated test data with no shared mutable state.
    /// @param codec the decoding configuration
    /// @param encoded the independent frame bytes
    /// @param content the expected plaintext
    @NotNullByDefault
    record Frame(LZ4Codec codec, byte[] encoded, byte[] content) {
    }

    /// Selects the caller's destination-storage lifetime.
    @NotNullByDefault
    private enum Layout {
        /// Appends each result immediately after the preceding output.
        CONTIGUOUS,
        /// Leaves one unused byte between successive output windows.
        GAPPED,
        /// Overwrites all returned bytes before invoking the decoder again.
        OVERWRITE
    }
}
