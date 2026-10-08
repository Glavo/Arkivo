// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionCodec;
import org.glavo.arkivo.codec.EncodingOptions;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Replays simple_round_trip.c with its original native parameter selection and round-trip assertions.
///
/// The native adapter retains compression determinism, producer rollback and overlapping decompression.
/// Between reused inputs it clears only the native decoder's prior maximum-block restriction, which the
/// upstream target can otherwise leave incompatible with a later frame. A separate regression verifies this case.
/// Java uses separate source and target buffers, checks every native frame, and independently exercises
/// the shared encoder settings without requiring native compression-byte identity. The executable is built
/// by `buildZstdRoundTripReference` and selected with `ARKIVO_ZSTD_ROUND_TRIP_EXECUTABLE`;
/// `ARKIVO_REQUIRE_ZSTD_ROUND_TRIP=true` makes its absence a failure.
@NotNullByDefault
@Timeout(240)
final class ZstdOfficialRoundTripTest {
    /// Holds batch requests, frames and diagnostics outside the source tree.
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path directory;

    /// Checks finite parameter, payload and exhausted-input cases with fresh and retained native contexts.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialRoundTrips(boolean reuse) throws IOException, InterruptedException {
        List<byte[]> seeds = seeds();
        List<String> responses = run(executable(), reuse ? "--reuse" : null, seeds, "round-trips");
        assertEquals(seeds.size(), responses.size());
        Map<String, Set<Integer>> observed = new HashMap<>();
        int randomCases = 0;
        int levelCases = 0;
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            Input input = parameters(seed);
            NativeFrame frame = response(responses.get(index));
            assertEquals(input.size(), frame.value("size"), "Payload partition: " + index);
            assertEquals(input.capacity(), frame.value("capacity"), "Original allocation: " + index);
            assertEquals(input.random() ? 1 : 0, frame.value("random"));
            assertTrue(frame.bytes().length <= input.capacity());
            byte[] plain = Arrays.copyOf(seed, input.size());
            try {
                ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) ZstdCodec.DEFAULT.frameInfo(ByteBuffer.wrap(frame.bytes()));
                assertEquals(input.random() && frame.value("checksum") != 0, info.checksum());
                assertEquals(!input.random() || frame.value("contentSize") != 0
                        ? plain.length : CompressionCodec.UNKNOWN_SIZE, info.contentSize());
                for (int kind = 0; kind < 3; kind++) decode(frame.bytes(), plain, kind);
                ZstdCodec codec = codec(frame);
                byte[] encoded = encode(codec, plain, index % 3);
                checkEncodingReset(codec, plain, encoded, index % 3);
                assertArrayEquals(plain, Zstd.decompress(encoded, plain.length), "Native decoding of Java output");
                decode(encoded, plain, index % 3);
            } catch (IOException | RuntimeException | AssertionError failure) {
                throw new AssertionError("Seed " + index + ", reuse=" + reuse + ", parameters=" + frame.parameters(), failure);
            }
            if (input.random()) {
                randomCases++;
                for (String name : List.of("strategy", "checksum", "contentSize", "ldm", "workers",
                        "row", "literal", "splitter", "maxBlock", "targetBlock")) {
                    observed.computeIfAbsent(name, ignored -> new HashSet<>()).add(frame.value(name));
                }
            } else {
                levelCases++;
            }
        }
        assertTrue(randomCases > 200 && levelCases > 200);
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), observed.get("strategy"));
        for (String name : List.of("checksum", "contentSize")) assertEquals(Set.of(0, 1), observed.get(name));
        for (String name : List.of("workers", "ldm", "row", "literal")) assertEquals(Set.of(0, 1, 2), observed.get(name));
        assertTrue(observed.get("splitter").containsAll(Set.of(0, 6)));
        assertTrue(observed.get("maxBlock").containsAll(Set.of(1024, 131072)));
        assertTrue(observed.get("targetBlock").containsAll(Set.of(1340, 131072)));
    }

    /// Checks the native retained-limit failure and decodes alternating block sizes with one reset Java decoder.
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void retainedBlockLimit(int kind) throws IOException, InterruptedException {
        List<String> lines = run(executable(), "--retained-limit", List.of(), "retained-limit");
        assertEquals(3, lines.size());
        assertEquals("retained-limit-rejected-and-cleared", lines.get(0));
        byte[] small = HexFormat.of().parseHex(lines.get(1));
        byte[] large = HexFormat.of().parseHex(lines.get(2));
        assertFalse(Arrays.equals(small, large));
        byte[] plain = new byte[16384];
        for (int index = 0; index < plain.length; index++) plain[index] = (byte) (index % 251);
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (byte[] frame : List.of(small, large, small, large)) {
                assertArrayEquals(plain, Zstd.decompress(frame, plain.length));
                ByteBuffer source = source(frame, kind);
                ByteBuffer target = target(plain.length, kind);
                CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
                for (int call = 0; outcome != CodecOutcome.FINISHED; call++) {
                    assertTrue(call < 4, "Decoder failed to complete the frame");
                    outcome = decoder.decode(source, target);
                }
                assertEquals(source.limit(), source.position());
                assertEquals(target.limit(), target.position());
                checkSource(source, frame);
                checkSource(target, plain);
                decoder.reset();
            }
        }
    }

    /// Maps shared requested parameters, resolving the native LDM bucket clamp instead of rejecting valid frames.
    private static ZstdCodec codec(NativeFrame frame) {
        var builder = ZstdCodec.builder().compressionLevel(frame.value("level"));
        if (frame.value("random") == 0) return builder.build();
        int strategy = frame.value("strategy");
        int bucket = frame.value("ldmBucket");
        // Native automatic buckets depend on strategy and are clamped to the selected hash-table size.
        if (bucket == 0) bucket = Math.max(3, Math.min(8, strategy));
        bucket = Math.min(bucket, frame.value("ldmHash"));
        return builder.windowLog(frame.value("window")).hashLog(frame.value("hash"))
                .chainLog(frame.value("chain")).searchLog(frame.value("search"))
                .minimumMatch(frame.value("match")).targetLength(frame.value("target"))
                .strategy(ZstdStrategy.values()[strategy - 1])
                .contentSize(frame.value("contentSize") != 0).frameChecksum(frame.value("checksum") != 0)
                .dictionaryId(frame.value("dictionaryId") != 0).workerCount(frame.value("workers"))
                // The target restricts windowLog to 15; native automatic LDM does not enable at these windows.
                .longDistanceMatching(frame.value("ldm") == 1)
                .longDistanceHashLog(frame.value("ldmHash")).longDistanceMinimumMatch(frame.value("ldmMatch"))
                .longDistanceBucketSizeLog(bucket).longDistanceHashRateLog(frame.value("ldmRate")).build();
    }

    /// Encodes a complete source without modifying its contents or either guard region.
    private static byte[] encode(ZstdCodec codec, byte[] plain, int kind) throws IOException {
        ByteBuffer source = source(plain, kind);
        ByteBuffer compressed = codec.compress(source);
        ZstdStandardFrameInfo info = (ZstdStandardFrameInfo) codec.frameInfo(compressed);
        assertEquals(codec.emitsFrameChecksum(), info.checksum());
        assertEquals(codec.emitsContentSize() ? plain.length : CompressionCodec.UNKNOWN_SIZE, info.contentSize());
        assertEquals(3 + plain.length, source.position());
        checkSource(source, plain);
        byte[] bytes = new byte[compressed.remaining()];
        compressed.get(bytes);
        return bytes;
    }

    /// Requires one explicitly reset encoder to reproduce the allocating API's bytes in an exact-size target.
    private static void checkEncodingReset(ZstdCodec codec, byte[] plain, byte[] expected, int kind) throws IOException {
        try (var encoder = codec.newEncoder(EncodingOptions.ofSourceSize(plain.length))) {
            for (int repeat = 0; repeat < 2; repeat++) {
                ByteBuffer source = source(plain, kind);
                ByteBuffer target = target(expected.length, kind);
                assertEquals(CodecOutcome.NEEDS_INPUT, encoder.encode(source, target));
                assertFalse(source.hasRemaining());
                assertEquals(CodecOutcome.FINISHED, encoder.finish(target));
                assertEquals(target.limit(), target.position());
                checkSource(source, plain);
                checkSource(target, expected);
                encoder.reset();
            }
        }
    }

    /// Verifies exact output, zero-output backpressure, fragmented input, reset and frame-tail preservation.
    private static void decode(byte[] frame, byte[] plain, int kind) throws IOException {
        byte[] withTail = Arrays.copyOf(frame, frame.length + 7);
        Arrays.fill(withTail, frame.length, withTail.length, (byte) 0x37);
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (int repeat = 0; repeat < 2; repeat++) {
                ByteBuffer source = source(withTail, kind);
                ByteArrayOutputStream result = new ByteArrayOutputStream(plain.length);
                int fragment = repeat == 0 ? 7 : withTail.length;
                int outputSize = kind == 0 ? 31 : 4093;
                ByteBuffer target = target(outputSize, kind);
                CodecOutcome outcome = CodecOutcome.NEEDS_INPUT;
                for (int call = 0; outcome != CodecOutcome.FINISHED; call++) {
                    assertTrue(call <= frame.length + plain.length + 32, "Decoder failed to finish");
                    source.limit(Math.min(source.capacity() - 3, source.position() + fragment));
                    int inputPosition = source.position();
                    int inputLimit = source.limit();
                    target.position(3).limit(call == 0 ? 3 : 3 + outputSize);
                    outcome = decoder.decode(source, target);
                    assertEquals(inputLimit, source.limit());
                    assertEquals(call == 0 ? 3 : 3 + outputSize, target.limit());
                    target.limit(3 + outputSize);
                    checkGuards(target);
                    int produced = target.position() - 3;
                    byte[] part = new byte[produced];
                    target.duplicate().position(3).get(part);
                    result.writeBytes(part);
                    assertTrue(result.size() <= plain.length);
                    assertTrue(source.position() <= 3 + frame.length, "Consumed bytes after the frame");
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT
                            || outcome == CodecOutcome.FINISHED, outcome::toString);
                    if (call > 0 && outcome != CodecOutcome.FINISHED) {
                        assertTrue(source.position() > inputPosition || produced > 0, "No progress with available space");
                    }
                }
                assertArrayEquals(plain, result.toByteArray());
                assertEquals(3 + frame.length, source.position());
                source.limit(3 + withTail.length);
                checkSource(source, withTail);
                decoder.reset();
            }
        }
    }

    /// Creates nonzero-position heap, direct or read-only source buffers with immutable guard bytes.
    private static ByteBuffer source(byte[] bytes, int kind) {
        ByteBuffer buffer = target(bytes.length, kind);
        buffer.put(bytes).position(3);
        return kind == 2 ? buffer.asReadOnlyBuffer() : buffer;
    }

    /// Creates a writable guarded region, including an empty region when capacity is zero.
    private static ByteBuffer target(int capacity, int kind) {
        ByteBuffer buffer = kind == 1 ? ByteBuffer.allocateDirect(capacity + 6) : ByteBuffer.allocate(capacity + 6);
        while (buffer.hasRemaining()) buffer.put((byte) 0x5a);
        return buffer.position(3).limit(3 + capacity);
    }

    /// Checks limits, position bounds and guard regions without modifying the original buffer.
    private static void checkGuards(ByteBuffer buffer) {
        assertEquals(buffer.capacity() - 3, buffer.limit());
        assertTrue(buffer.position() >= 3 && buffer.position() <= buffer.limit());
        ByteBuffer all = buffer.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, all.get(index));
            assertEquals((byte) 0x5a, all.get(all.capacity() - 1 - index));
        }
    }

    /// Checks that all source bytes remain unchanged, including bytes after the completed frame.
    private static void checkSource(ByteBuffer buffer, byte[] expected) {
        checkGuards(buffer);
        byte[] actual = new byte[expected.length];
        buffer.duplicate().position(3).get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Returns all upstream levels, every strategy, parameter endpoints and arbitrary/exhausted seed tails.
    private static @Unmodifiable List<byte[]> seeds() {
        List<byte[]> result = new ArrayList<>();
        for (int level = -3; level <= 19; level++) {
            for (int size : new int[]{0, 1, 255, 4096, 131072, 131073}) {
                for (int variant = 0; variant < 2; variant++) {
                    ByteArrayOutputStream tail = new ByteArrayOutputStream();
                    parameter(tail, 0, 1, variant);
                    parameter(tail, 0, 1, 0);
                    parameter(tail, -3, 19, level);
                    parameter(tail, 0, 1, variant);
                    result.add(seed(payload(size, variant), tail.toByteArray()));
                }
            }
        }
        for (int strategy = 1; strategy <= 9; strategy++) {
            for (int variant = 0; variant < 6; variant++) {
                for (int size : new int[]{0, 1, 4096, 131073}) {
                    result.add(seed(payload(size, variant % 3), parameterTail(size, strategy, variant)));
                }
            }
        }
        // Cross the native minimum worker-job size without scaling every small-buffer case to megabytes.
        for (int variant = 0; variant < 6; variant++) {
            int size = 1024 * 1024 + 1;
            result.add(seed(payload(size, variant % 3), parameterTail(size, 1 + variant, variant)));
        }
        Random random = new Random(0x51ab_cdefL);
        for (int size = 0; size <= 128; size++) {
            byte[] seed = new byte[size];
            random.nextBytes(seed);
            result.add(seed);
        }
        for (int index = 0; index < 128; index++) {
            byte[] tail = new byte[96];
            random.nextBytes(tail);
            tail[1] = 1;
            result.add(seed(payload(index * 257, index % 3), tail));
        }
        return List.copyOf(result);
    }

    /// Encodes endpoint combinations in the exact order consumed by FUZZ_setRandomParameters.
    private static byte[] parameterTail(int size, int strategy, int variant) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        boolean high = (variant & 1) != 0;
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 1, 1);
        parameter(tail, 10, 15, high ? 15 : 10);
        parameter(tail, 6, 15, high ? 15 : 6);
        parameter(tail, 6, 16, high ? 16 : 6);
        parameter(tail, 1, 9, high ? 9 : 1);
        parameter(tail, 3, 7, high ? 7 : 3);
        parameter(tail, 0, 512, high ? 512 : 0);
        parameter(tail, 1, 9, strategy);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 6, 16, high ? 16 : 6);
        parameter(tail, 4, 4096, high ? 4096 : 4);
        parameter(tail, 0, 8, high ? 8 : 0);
        parameter(tail, 0, 25, high ? 25 : 0);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 6, high ? 6 : 0);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 1024, 131072, high ? 131072 : 1024);
        parameter(tail, 0, 1, variant & 1);
        parameter(tail, 0, 2, variant % 3);
        parameter(tail, 0, 1, 0);
        parameter(tail, 0, 2 * size, size);
        parameter(tail, 0, 1, 0);
        parameter(tail, 1340, 131072, high ? 131072 : 1340);
        parameter(tail, 0, 10, variant == 5 ? 1 : 0);
        if (variant == 5) parameter(tail, 0, 1, 1);
        parameter(tail, 0, 1, variant & 1);
        return tail.toByteArray();
    }

    /// Serializes one chosen inclusive-range value in producer consumption order.
    private static void parameter(ByteArrayOutputStream tail, int minimum, int maximum, int value) {
        assertTrue(value >= minimum && value <= maximum);
        int width = width(maximum - minimum);
        for (int index = width - 1; index >= 0; index--) tail.write((value - minimum) >>> (8 * index));
    }

    /// Reserves exactly the supplied plaintext and reverses parameter bytes for the tail-first producer.
    private static byte[] seed(byte[] plain, byte[] parameters) {
        int prefixWidth = 1;
        while (prefixWidth != width(plain.length + parameters.length + prefixWidth)) {
            prefixWidth = width(plain.length + parameters.length + prefixWidth);
        }
        byte[] seed = Arrays.copyOf(plain, plain.length + parameters.length + prefixWidth);
        for (int index = 0; index < parameters.length; index++) {
            seed[plain.length + parameters.length - 1 - index] = parameters[index];
        }
        for (int index = 0; index < prefixWidth; index++) {
            seed[plain.length + parameters.length + index] = (byte) (parameters.length >>> (8 * index));
        }
        assertEquals(plain.length, parameters(seed).size());
        return seed;
    }

    /// Returns the bytes consumed by the producer for a range beginning at zero.
    private static int width(int value) {
        int width = 0;
        for (; value != 0; value >>>= 8) width++;
        return width;
    }

    /// Creates incompressible, repeated-pattern and constant plaintext with stable seeds.
    private static byte[] payload(int size, int kind) {
        byte[] bytes = new byte[size];
        if (kind == 2) return bytes;
        new Random(0x517aL + size).nextBytes(bytes);
        if (kind == 1) for (int index = 64; index < size; index++) bytes[index] = bytes[index % 64];
        return bytes;
    }

    /// Preserves the whole-seed compression bound, prefix reservation and first two parameter selections.
    private static Input parameters(byte[] seed) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(seed);
        int size = producer.reservePrefix();
        int capacity = Math.toIntExact(Zstd.compressBound(seed.length) - producer.range(0, 1));
        return new Input(size, capacity, producer.range(0, 1) != 0);
    }

    /// Parses named native parameters followed by the complete encoded frame.
    private static NativeFrame response(String line) {
        String[] fields = line.split(" ");
        Map<String, Integer> parameters = new HashMap<>();
        for (int index = 0; index < fields.length - 1; index++) {
            String[] pair = fields[index].split("=");
            assertEquals(2, pair.length);
            assertFalse(parameters.containsKey(pair[0]));
            parameters.put(pair[0], Integer.parseInt(pair[1]));
        }
        assertEquals(26, parameters.size());
        return new NativeFrame(Map.copyOf(parameters), HexFormat.of().parseHex(fields[fields.length - 1]));
    }

    /// Resolves the optional executable, enforcing availability in the required-reference CI job.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_ROUND_TRIP_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_ROUND_TRIP"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_ROUND_TRIP_EXECUTABLE to the built official round-trip reference");
        }
        assumeTrue(available, "Official round-trip reference is not configured");
        return Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
    }

    /// Runs a finite native batch and retains request indices in diagnostics for reproducing assertion failures.
    private List<String> run(String executable, @Nullable String option, List<byte[]> seeds, String name)
            throws IOException, InterruptedException {
        Path input = directory.resolve(name + ".input");
        Path output = directory.resolve(name + ".output");
        Path errors = directory.resolve(name + ".errors");
        try (var writer = Files.newBufferedWriter(input, StandardCharsets.US_ASCII)) {
            for (byte[] seed : seeds) {
                writer.write(seed.length + " " + HexFormat.of().formatHex(seed));
                writer.newLine();
            }
        }
        ProcessBuilder builder = option == null ? new ProcessBuilder(executable) : new ProcessBuilder(executable, option);
        Process process = builder.redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official round-trip reference timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official round-trip reference did not terminate");
            }
        }
        return Files.readAllLines(output, StandardCharsets.US_ASCII);
    }

    /// Stores the original outer target's selections independently of native parameter adjustment.
    ///
    /// @param size the reserved plaintext size
    /// @param capacity the original whole-input compression bound, optionally reduced by one
    /// @param random whether the target uses random parameters instead of a compression level
    @NotNullByDefault
    private record Input(int size, int capacity, boolean random) {
    }

    /// Stores the exact resulting native frame and its requested context parameters.
    ///
    /// @param parameters immutable native selections after both deterministic compressions
    /// @param bytes the native frame, never a Java-generated expectation
    @NotNullByDefault
    private record NativeFrame(@Unmodifiable Map<String, Integer> parameters, byte @Unmodifiable [] bytes) {
        /// Returns a required named parameter, rejecting an incomplete reference response.
        private int value(String name) {
            return Objects.requireNonNull(parameters.get(name), name);
        }
    }
}
