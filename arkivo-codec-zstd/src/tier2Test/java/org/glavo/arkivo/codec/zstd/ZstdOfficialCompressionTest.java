// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdException;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Replays Zstandard 1.5.7 simple_compress.c's parameter layout and bounded-output compression.
///
/// Java and native compression may need different capacities. Only native destination-too-small errors and
/// Java buffer overflow are accepted; every completed frame is decoded independently. Java incremental
/// encoding additionally resumes after output backpressure and repeats the same input after reset.
@NotNullByDefault
@Timeout(180)
final class ZstdOfficialCompressionTest {
    /// Checks heap, direct and read-only input with fresh and retained native compression contexts.
    @ParameterizedTest(name = "buffers={0}, reuse={1}")
    @CsvSource({"0,false", "1,false", "2,false", "0,true", "1,true", "2,true"})
    void simpleCompression(int kind, boolean reuse) throws IOException {
        int javaOverflows = 0;
        int nativeOverflows = 0;
        int completed = 0;
        try (var retained = new ZstdCompressCtx()) {
            for (byte[] seed : seeds()) {
                Input parameters = parameters(seed);
                byte[] plain = Arrays.copyOf(seed, parameters.size());
                boolean nativeCompleted;
                if (reuse) {
                    nativeCompleted = nativeCompression(retained, plain, parameters);
                } else {
                    try (var fresh = new ZstdCompressCtx()) {
                        nativeCompleted = nativeCompression(fresh, plain, parameters);
                    }
                }
                if (!nativeCompleted) nativeOverflows++;
                ZstdCodec codec = ZstdCodec.DEFAULT.withCompressionLevel(parameters.level());
                ByteBuffer full = codec.compress(ByteBuffer.wrap(plain));
                byte[] expected = new byte[full.remaining()];
                full.get(expected);
                assertArrayEquals(plain, Zstd.decompress(expected, plain.length));
                ByteBuffer source = source(plain, kind);
                ByteBuffer target = target(parameters.capacity(), kind);
                boolean accepted;
                try {
                    codec.compress(source, target);
                    accepted = true;
                } catch (BufferOverflowException insufficientOutput) {
                    accepted = false;
                    javaOverflows++;
                }
                assertEquals(parameters.capacity() >= expected.length, accepted);
                checkSource(source, plain);
                checkTarget(target, parameters.capacity());
                byte[] produced = written(target);
                assertArrayEquals(Arrays.copyOf(expected, produced.length), produced);
                if (accepted) {
                    completed++;
                    assertFalse(source.hasRemaining());
                    assertArrayEquals(expected, produced);
                    assertArrayEquals(plain, Zstd.decompress(produced, plain.length));
                }
                resumeAndReset(codec, plain, expected, parameters.capacity(), kind);
            }
        }
        assertTrue(javaOverflows > 100 && nativeOverflows > 100 && completed > 100);
    }

    /// Accepts only the upstream destination-too-small error and verifies every successful native frame.
    private static boolean nativeCompression(ZstdCompressCtx context, byte[] plain, Input input) {
        byte[] output = new byte[input.capacity() + 6];
        Arrays.fill(output, (byte) 0x5a);
        byte[] source = plain.clone();
        boolean completed;
        int size = 0;
        context.setLevel(input.level());
        try {
            size = context.compressByteArray(output, 3, input.capacity(), source, 0, source.length);
            completed = true;
        } catch (ZstdException insufficientOutput) {
            assertEquals(Zstd.errDstSizeTooSmall(), insufficientOutput.getErrorCode());
            completed = false;
        }
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, output[index]);
            assertEquals((byte) 0x5a, output[output.length - 1 - index]);
        }
        assertArrayEquals(plain, source);
        if (completed) {
            assertTrue(size >= 0 && size <= input.capacity());
            assertArrayEquals(plain, Zstd.decompress(Arrays.copyOfRange(output, 3, 3 + size), plain.length));
        }
        return completed;
    }

    /// Drains the same bounded first output, completes pending input/output, and checks a reset encoder independently.
    private static void resumeAndReset(ZstdCodec codec, byte[] plain, byte[] expected, int capacity, int kind)
            throws IOException {
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(plain.length))) {
            ByteBuffer source = source(plain, kind);
            ByteBuffer target = target(capacity, kind);
            CodecOutcome outcome = encoder.encode(source, target);
            assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
            if (outcome == CodecOutcome.NEEDS_INPUT) {
                assertFalse(source.hasRemaining());
                outcome = encoder.finish(target);
            }
            checkTarget(target, capacity);
            ByteArrayOutputStream compressed = new ByteArrayOutputStream();
            compressed.writeBytes(written(target));
            int attempts = 0;
            while (outcome != CodecOutcome.FINISHED) {
                assertEquals(CodecOutcome.NEEDS_OUTPUT, outcome);
                assertTrue(++attempts <= plain.length + expected.length + 32, "Encoder made no progress");
                ByteBuffer next = target(4093, kind);
                if (source.hasRemaining()) {
                    outcome = encoder.encode(source, next);
                    if (outcome == CodecOutcome.NEEDS_INPUT) outcome = encoder.finish(next);
                } else {
                    outcome = encoder.finish(next);
                }
                checkTarget(next, 4093);
                compressed.writeBytes(written(next));
                assertTrue(compressed.size() <= expected.length);
            }
            checkSource(source, plain);
            assertFalse(source.hasRemaining());
            byte[] frame = compressed.toByteArray();
            assertArrayEquals(expected, frame);
            assertArrayEquals(plain, Zstd.decompress(frame, plain.length));
            encoder.reset();
            source = source(plain, kind);
            target = target(expected.length, kind);
            assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(source, target));
            assertEquals(CodecOutcome.FINISHED, encoder.finish(target));
            assertFalse(source.hasRemaining());
            checkSource(source, plain);
            checkTarget(target, expected.length);
            assertArrayEquals(expected, written(target));
        }
    }

    /// Places plaintext between three-byte guards without exposing either guard to the encoder.
    private static ByteBuffer source(byte[] plain, int kind) {
        ByteBuffer source = target(plain.length, kind);
        source.put(plain).position(3);
        return kind == 2 ? source.asReadOnlyBuffer() : source;
    }

    /// Allocates a writable guarded region, using direct output for the direct-input case.
    private static ByteBuffer target(int capacity, int kind) {
        ByteBuffer target = kind == 1 ? ByteBuffer.allocateDirect(capacity + 6) : ByteBuffer.allocate(capacity + 6);
        while (target.hasRemaining()) target.put((byte) 0x5a);
        return target.position(3).limit(3 + capacity);
    }

    /// Verifies source position bounds, unchanged limit and unchanged plaintext including both guards.
    private static void checkSource(ByteBuffer source, byte[] plain) {
        checkTarget(source, plain.length);
        ByteBuffer view = source.duplicate().position(3);
        byte[] actual = new byte[plain.length];
        view.get(actual);
        assertArrayEquals(plain, actual);
    }

    /// Verifies an operation did not change the advertised limit or write outside its caller-owned region.
    private static void checkTarget(ByteBuffer target, int capacity) {
        assertEquals(capacity + 3, target.limit());
        assertTrue(target.position() >= 3 && target.position() <= target.limit());
        ByteBuffer view = target.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, view.get(index));
            assertEquals((byte) 0x5a, view.get(view.capacity() - 1 - index));
        }
    }

    /// Copies only bytes produced after the target's nonzero starting position.
    private static byte[] written(ByteBuffer target) {
        ByteBuffer view = target.duplicate().limit(target.position()).position(3);
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    /// Preserves reserveDataPrefix, native compressBound, selected capacity and signed -3..19 level order.
    private static Input parameters(byte[] seed) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
        int size = producer.reservePrefix();
        int capacity = Math.toIntExact(producer.range(0, Zstd.compressBound(size)));
        int level = (int) producer.range(0, 22) - 3;
        return new Input(size, capacity, level);
    }

    /// Covers all original levels, empty/tiny payloads, block boundaries and exhausted/random parameter tails.
    private static @Unmodifiable List<byte[]> seeds() {
        List<byte[]> seeds = new ArrayList<>();
        for (int level = -3; level <= 19; level++) {
            for (int size : new int[]{0, 1, 8, 256, 4096}) addSeeds(seeds, size, level, false);
        }
        for (int level : new int[]{-3, 3, 19}) {
            for (int size : new int[]{65536, 131071, 131072, 131073}) addSeeds(seeds, size, level, true);
        }
        Random random = new Random(0x51c0_ffeeL);
        for (int size = 0; size <= 256; size++) {
            byte[] seed = new byte[size];
            random.nextBytes(seed);
            seeds.add(seed);
        }
        return List.copyOf(seeds);
    }

    /// Adds incompressible and match-rich payloads at zero, small and sufficient capacity boundaries.
    private static void addSeeds(List<byte[]> seeds, int size, int level, boolean large) {
        int bound = Math.toIntExact(Zstd.compressBound(size));
        int[] capacities = large ? new int[]{0, bound / 2, bound} : new int[]{0, 1, bound - 1, bound};
        for (int shape = 0; shape < 2; shape++) {
            byte[] plain = new byte[size];
            new Random(0x51c0L + size).nextBytes(plain);
            if (shape == 1) {
                for (int index = 64; index < size; index++) plain[index] = plain[index % 64];
            }
            for (int capacity : capacities) {
                int parameterBytes = width(bound) + 1;
                int prefixWidth = 1;
                while (prefixWidth != width(size + parameterBytes + prefixWidth)) {
                    prefixWidth = width(size + parameterBytes + prefixWidth);
                }
                byte[] seed = Arrays.copyOf(plain, size + parameterBytes + prefixWidth);
                int cursor = tail(seed, seed.length, parameterBytes, prefixWidth);
                cursor = tail(seed, cursor, capacity, width(bound));
                cursor = tail(seed, cursor, level + 3, 1);
                assertEquals(size, cursor);
                assertEquals(new Input(size, capacity, level), parameters(seed));
                seeds.add(seed);
            }
        }
    }

    /// Writes an integer in the reverse of the upstream producer's tail-first read order.
    private static int tail(byte[] target, int end, int value, int width) {
        for (int index = 0; index < width; index++) target[end - width + index] = (byte) (value >>> (8 * index));
        return end - width;
    }

    /// Returns bytes consumed by an inclusive unsigned range starting at zero.
    private static int width(int value) {
        int width = 0;
        for (; value != 0; value >>>= 8) width++;
        return width;
    }

    /// Stores the parameters derived from one complete official fuzz input.
    ///
    /// @param size the reserved plaintext prefix length
    /// @param capacity the native-bound-derived output capacity
    /// @param level the requested signed compression level
    @NotNullByDefault
    private record Input(int size, int capacity, int level) {
    }
}
