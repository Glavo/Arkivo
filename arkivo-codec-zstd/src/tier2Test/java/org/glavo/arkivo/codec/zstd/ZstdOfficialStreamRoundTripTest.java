// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionEncoder;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Replays the completed actions of Zstandard 1.5.7 stream_round_trip.c through Java's separate encoding operations.
///
/// Native calls, entropy consumption, output-only retries and overlapping decompression remain in the original target.
/// Java receives the accepted plaintext and completed flush/frame boundaries, not native compressed-byte expectations.
/// Reconfiguration creates an encoder from a new immutable codec after the previous frame has completed. Native
/// contexts may be retained, but the previous seed's optional decoder block limit is cleared before each new seed.
/// Set `ARKIVO_ZSTD_STREAM_ROUND_TRIP_EXECUTABLE` to the executable built by `buildZstdStreamRoundTripReference`;
/// `ARKIVO_REQUIRE_ZSTD_STREAM_ROUND_TRIP=true` makes a missing executable fail instead of skip.
@NotNullByDefault
@Timeout(240)
final class ZstdOfficialStreamRoundTripTest {
    /// Holds finite requests, operation traces and native diagnostics, retaining failed cases for reproduction.
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    Path directory;

    /// Checks original native streams and Java action replay with fresh and retained native contexts.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialStreams(boolean reuse) throws IOException, InterruptedException {
        List<byte[]> seeds = seeds();
        List<String> lines = run(executable(), reuse, seeds);
        int cursor = 0;
        int flushed = 0;
        int emptyCalls = 0;
        int multipleFrames = 0;
        int reconfiguredInput = 0;
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            int size = new ZstdFuzzDataProducer(seed).reservePrefix();
            byte[] plain = Arrays.copyOf(seed, size);
            String[] begin = lines.get(cursor++).split(" ");
            assertEquals(List.of("B", Integer.toString(size), Long.toString(Zstd.compressBound(size) * 15)), List.of(begin));
            int capacity = Integer.parseInt(begin[2]);
            List<String> operations = new ArrayList<>();
            List<Integer> frameEnds = new ArrayList<>();
            List<Boundary> boundaries = new ArrayList<>();
            int configurations = 0;
            while (!lines.get(cursor).startsWith("R ")) {
                String operation = lines.get(cursor++);
                operations.add(operation);
                if (operation.startsWith("F ") || operation.startsWith("E ")) {
                    String[] fields = operation.split(" ");
                    assertEquals(4, fields.length);
                    boundaries.add(new Boundary(Integer.parseInt(fields[2]), Integer.parseInt(fields[1]),
                            fields[0].equals("E")));
                }
                switch (operation.charAt(0)) {
                    case 'P' -> configurations++;
                    case 'I' -> { if (configurations > 1) reconfiguredInput++; }
                    case 'F' -> flushed++;
                    case 'N' -> emptyCalls++;
                    case 'E' -> frameEnds.add(Integer.parseInt(operation.split(" ")[1]));
                    default -> fail("Unknown native operation: " + operation);
                }
            }
            String[] result = lines.get(cursor++).split(" ");
            assertEquals(4, result.length);
            byte[] encoded = HexFormat.of().parseHex(result[3]);
            assertEquals(encoded.length, Integer.parseInt(result[1]));
            assertTrue(encoded.length <= capacity);
            assertEquals(frameEnds.size(), Integer.parseInt(result[2]));
            assertFalse(frameEnds.isEmpty());
            assertEquals(size, frameEnds.get(frameEnds.size() - 1));
            if (frameEnds.size() > 1) multipleFrames++;
            try {
                checkFrames(encoded, plain, frameEnds);
                checkBoundaries(encoded, plain, boundaries);
                for (int kind = 0; kind < 3; kind++) decode(encoded, plain, frameEnds, kind);
                byte[] javaEncoded = replay(operations, plain, capacity, index % 3);
                checkFrames(javaEncoded, plain, frameEnds);
                decode(javaEncoded, plain, frameEnds, index % 3);
            } catch (IOException | RuntimeException | AssertionError failure) {
                throw new AssertionError("Seed " + index + ", reuse=" + reuse + ", size=" + size, failure);
            }
        }
        assertEquals(lines.size(), cursor);
        assertTrue(flushed > 100 && emptyCalls > 0 && multipleFrames > 10 && reconfiguredInput > 0,
                "flush=" + flushed + ", empty=" + emptyCalls + ", multi=" + multipleFrames + ", reconfigured=" + reconfiguredInput);
    }

    /// Replays accepted chunks and completed native actions without imposing native output sizes on Java encoders.
    private static byte[] replay(List<String> operations, byte[] plain, int capacity, int kind) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        ByteBuffer source = source(plain, kind);
        ByteBuffer storage = storage(capacity, kind);
        List<Boundary> boundaries = new ArrayList<>();
        @Nullable CompressionEncoder.FlushableFramed encoder = null;
        boolean activeFrame = false;
        try {
            for (String line : operations) {
                String[] fields = line.split(" ");
                if (fields[0].equals("P")) {
                    if (encoder != null) {
                        assertFalse(activeFrame, "Parameters may change only between completed frames");
                        encoder.close();
                    }
                    encoder = codec(fields).newEncoder();
                    activeFrame = true;
                    continue;
                }
                var current = Objects.requireNonNull(encoder);
                if (fields[0].equals("N")) {
                    ByteBuffer empty = source(new byte[0], kind);
                    ByteBuffer target = target(storage, 0);
                    CodecOutcome outcome = current.encode(empty, target);
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
                    checkSource(empty, new byte[0]);
                    checkGuards(target);
                    continue;
                }
                int offset = Integer.parseInt(fields[1]);
                int targetSize = Integer.parseInt(fields[fields.length - 1]);
                assertTrue(targetSize > 0 && targetSize <= capacity);
                assertEquals(3 + offset, source.position());
                if (fields[0].equals("I")) {
                    int length = Integer.parseInt(fields[2]);
                    assertTrue(length > 0 && offset + length <= plain.length);
                    source.limit(source.position() + length);
                    CodecOutcome outcome;
                    do {
                        ByteBuffer target = target(storage, targetSize);
                        int position = source.position();
                        int limit = source.limit();
                        outcome = current.encode(source, target);
                        assertEquals(limit, source.limit());
                        append(target, result);
                        assertTrue(source.position() > position || target.position() > 3 || outcome == CodecOutcome.NEEDS_INPUT);
                        assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
                    } while (outcome != CodecOutcome.NEEDS_INPUT);
                    assertFalse(source.hasRemaining());
                    activeFrame = true;
                } else {
                    boolean end = fields[0].equals("E");
                    assertTrue(end || fields[0].equals("F"));
                    // Unlike terminal Java finish(), the upstream trailing e_end emits an empty frame after an end.
                    if (end && !activeFrame) {
                        current.startFrame();
                        activeFrame = true;
                    }
                    CodecOutcome expected = end ? CodecOutcome.BOUNDARY_REACHED : CodecOutcome.FLUSHED;
                    CodecOutcome outcome;
                    do {
                        ByteBuffer target = target(storage, targetSize);
                        outcome = end ? current.finishFrame(target) : current.flush(target);
                        append(target, result);
                        assertTrue(outcome == expected || outcome == CodecOutcome.NEEDS_OUTPUT);
                        assertTrue(outcome == expected || target.position() > 3, "No boundary progress");
                    } while (outcome != expected);
                    boundaries.add(new Boundary(result.size(), offset, end));
                    if (end) activeFrame = false;
                }
                assertTrue(result.size() <= capacity, "Unbounded output during finite action replay");
            }
            assertFalse(activeFrame);
            assertEquals(plain.length + 3, source.position());
            source.limit(plain.length + 3);
            checkSource(source, plain);
            ByteBuffer target = target(storage, 0);
            assertEquals(CodecOutcome.FINISHED, Objects.requireNonNull(encoder).finish(target));
            assertEquals(3, target.position());
            checkGuards(target);
        } finally {
            if (encoder != null) encoder.close();
        }
        byte[] encoded = result.toByteArray();
        checkBoundaries(encoded, plain, boundaries);
        return encoded;
    }

    /// Describes the byte extents visible when a flush or frame end has completed.
    ///
    /// @param encodedEnd exclusive compressed offset
    /// @param decodedEnd exclusive plaintext offset
    /// @param frameEnd whether the action completes a frame rather than flushing an unfinished frame
    @NotNullByDefault
    private record Boundary(int encodedEnd, int decodedEnd, boolean frameEnd) {
    }

    /// Requires both decoders to expose all accepted plaintext without reading past each completed action.
    private static void checkBoundaries(byte[] encoded, byte[] plain, List<Boundary> boundaries) throws IOException {
        ByteBuffer nativeSource = ByteBuffer.allocateDirect(encoded.length).put(encoded).flip();
        ByteBuffer nativeTarget = ByteBuffer.allocateDirect(plain.length + 1);
        @UnmodifiableView ByteBuffer javaSource = ByteBuffer.wrap(encoded).asReadOnlyBuffer();
        ByteBuffer javaTarget = ByteBuffer.allocate(plain.length + 1);
        try (var reference = new ZstdDecompressCtx(); var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            boolean finished = false;
            int verified = 0;
            for (Boundary boundary : boundaries) {
                nativeSource.limit(boundary.encodedEnd());
                javaSource.limit(boundary.encodedEnd());
                while (nativeSource.hasRemaining()) {
                    int input = nativeSource.position();
                    int output = nativeTarget.position();
                    finished = reference.decompressDirectByteBufferStream(nativeTarget, nativeSource);
                    assertTrue(nativeSource.position() > input || nativeTarget.position() > output,
                            "No native progress at " + boundary);
                }
                assertEquals(boundary.frameEnd(), finished, "Native frame status at " + boundary);
                assertEquals(boundary.decodedEnd(), nativeTarget.position(), "Native flush visibility at " + boundary);
                CodecOutcome outcome;
                do {
                    int input = javaSource.position();
                    int output = javaTarget.position();
                    outcome = decoder.decode(javaSource, javaTarget);
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.FINISHED);
                    if (javaSource.hasRemaining()) {
                        assertTrue(javaSource.position() > input || javaTarget.position() > output,
                                "No Java progress at " + boundary);
                    }
                } while (javaSource.hasRemaining());
                assertEquals(boundary.frameEnd() ? CodecOutcome.FINISHED : CodecOutcome.NEEDS_INPUT, outcome);
                assertEquals(boundary.decodedEnd(), javaTarget.position(), "Java flush visibility at " + boundary);
                @UnmodifiableView ByteBuffer expected = ByteBuffer.wrap(plain, verified,
                        boundary.decodedEnd() - verified).asReadOnlyBuffer();
                assertEquals(-1, expected.mismatch(nativeTarget.duplicate().limit(boundary.decodedEnd()).position(verified)),
                        "Native plaintext at " + boundary);
                assertEquals(-1, expected.mismatch(javaTarget.duplicate().limit(boundary.decodedEnd()).position(verified)),
                        "Java plaintext at " + boundary);
                verified = boundary.decodedEnd();
                if (boundary.frameEnd()) decoder.reset();
            }
        }
        assertEquals(encoded.length, nativeSource.position());
        assertEquals(encoded.length, javaSource.position());
        byte[] nativePlain = new byte[nativeTarget.position()];
        nativeTarget.flip().get(nativePlain);
        assertArrayEquals(plain, nativePlain);
        assertArrayEquals(plain, Arrays.copyOf(javaTarget.array(), javaTarget.position()));
    }

    /// Maps the shared native parameters after adjustment, preserving Java's immutable configuration model.
    private static ZstdCodec codec(String[] fields) {
        Map<String, Integer> values = new HashMap<>();
        for (int index = 1; index < fields.length; index++) {
            String[] pair = fields[index].split("=");
            assertEquals(2, pair.length);
            assertFalse(values.containsKey(pair[0]));
            values.put(pair[0], Integer.parseInt(pair[1]));
        }
        assertEquals(17, values.size());
        int strategy = value(values, "strategy");
        int bucket = value(values, "ldmBucket");
        if (bucket == 0) bucket = Math.max(3, Math.min(8, strategy));
        bucket = Math.min(bucket, value(values, "ldmHash"));
        return ZstdCodec.builder().compressionLevel(value(values, "level"))
                .windowLog(value(values, "window")).hashLog(value(values, "hash"))
                .chainLog(value(values, "chain")).searchLog(value(values, "search"))
                .minimumMatch(value(values, "match")).targetLength(value(values, "target"))
                .strategy(ZstdStrategy.values()[strategy - 1]).contentSize(value(values, "contentSize") != 0)
                .frameChecksum(value(values, "checksum") != 0).dictionaryId(value(values, "dictionaryId") != 0)
                .workerCount(value(values, "workers")).longDistanceMatching(value(values, "ldm") == 1)
                .longDistanceHashLog(value(values, "ldmHash")).longDistanceMinimumMatch(value(values, "ldmMatch"))
                .longDistanceBucketSizeLog(bucket).longDistanceHashRateLog(value(values, "ldmRate")).build();
    }

    /// Returns a required native parameter rather than treating an incomplete trace as a default value.
    private static int value(Map<String, Integer> values, String name) {
        return Objects.requireNonNull(values.get(name), name);
    }

    /// Checks every physical frame against the accepted plaintext interval at its native end operation.
    private static void checkFrames(byte[] encoded, byte[] plain, List<Integer> frameEnds) {
        int offset = 0;
        int start = 0;
        for (int end : frameEnds) {
            int length = Math.toIntExact(Zstd.findFrameCompressedSize(encoded, offset, encoded.length - offset));
            byte[] frame = Arrays.copyOfRange(encoded, offset, offset + length);
            assertArrayEquals(Arrays.copyOfRange(plain, start, end), Zstd.decompress(frame, end - start));
            offset += length;
            start = end;
        }
        assertEquals(encoded.length, offset);
        assertArrayEquals(plain, Zstd.decompress(encoded, plain.length));
    }

    /// Decodes concatenated frames with explicit Java resets, including empty frames and bytes after the final frame.
    private static void decode(byte[] encoded, byte[] plain, List<Integer> frameEnds, int kind) throws IOException {
        byte[] withTail = Arrays.copyOf(encoded, encoded.length + 7);
        Arrays.fill(withTail, encoded.length, withTail.length, (byte) 0x37);
        ByteBuffer source = source(withTail, kind);
        ByteBuffer storage = storage(kind == 0 ? 31 : 4093, kind);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int completed = 0;
        int frameOffset = 0;
        int frameEnd = Math.toIntExact(Zstd.findFrameCompressedSize(encoded));
        try (var decoder = ZstdCodec.DEFAULT.newDecoder()) {
            for (int call = 0; completed < frameEnds.size(); call++) {
                assertTrue(call <= encoded.length + plain.length + frameEnds.size() * 4 + 32);
                source.limit(Math.min(3 + withTail.length, source.position() + (kind == 0 ? 7 : 8191)));
                int before = source.position();
                int limit = source.limit();
                ByteBuffer target = target(storage, call == 0 ? 0 : storage.capacity() - 6);
                CodecOutcome outcome = decoder.decode(source, target);
                assertEquals(limit, source.limit());
                append(target, result);
                assertTrue(result.size() <= plain.length);
                assertTrue(source.position() <= 3 + frameEnd);
                if (outcome == CodecOutcome.FINISHED) {
                    assertEquals(3 + frameEnd, source.position());
                    assertEquals(frameEnds.get(completed), result.size());
                    completed++;
                    frameOffset = frameEnd;
                    if (completed < frameEnds.size()) {
                        frameEnd += Math.toIntExact(Zstd.findFrameCompressedSize(encoded, frameOffset, encoded.length - frameOffset));
                        decoder.reset();
                    }
                } else {
                    assertTrue(outcome == CodecOutcome.NEEDS_INPUT || outcome == CodecOutcome.NEEDS_OUTPUT);
                    if (call != 0) assertTrue(source.position() > before || target.position() > 3, "No decoding progress");
                }
            }
        }
        assertArrayEquals(plain, result.toByteArray());
        assertEquals(encoded.length, frameOffset);
        source.limit(3 + withTail.length);
        checkSource(source, withTail);
    }

    /// Allocates reusable heap or direct storage, including guard space.
    private static ByteBuffer storage(int capacity, int kind) {
        return kind == 1 ? ByteBuffer.allocateDirect(capacity + 6) : ByteBuffer.allocate(capacity + 6);
    }

    /// Creates a fresh bounded target view with guard bytes at both ends of its advertised region.
    private static ByteBuffer target(ByteBuffer storage, int capacity) {
        ByteBuffer view = storage.duplicate().clear().limit(capacity + 6).slice();
        for (int index = 0; index < 3; index++) {
            view.put(index, (byte) 0x5a);
            view.put(capacity + 3 + index, (byte) 0x5a);
        }
        return view.position(3).limit(capacity + 3);
    }

    /// Creates guarded source storage without retaining a writable alias for read-only test inputs.
    private static ByteBuffer source(byte[] bytes, int kind) {
        ByteBuffer source = target(storage(bytes.length, kind), bytes.length);
        source.put(bytes).position(3);
        return kind == 2 ? source.asReadOnlyBuffer() : source;
    }

    /// Appends exactly the produced region and verifies that its advertised bounds were respected.
    private static void append(ByteBuffer target, ByteArrayOutputStream result) {
        checkGuards(target);
        byte[] bytes = new byte[target.position() - 3];
        target.duplicate().position(3).get(bytes);
        result.writeBytes(bytes);
    }

    /// Checks unchanged limits, valid progress and untouched sentinel bytes.
    private static void checkGuards(ByteBuffer buffer) {
        assertEquals(buffer.capacity() - 3, buffer.limit());
        assertTrue(buffer.position() >= 3 && buffer.position() <= buffer.limit());
        ByteBuffer view = buffer.duplicate().clear();
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0x5a, view.get(index));
            assertEquals((byte) 0x5a, view.get(view.capacity() - 1 - index));
        }
    }

    /// Checks all original input bytes, including trailing bytes not consumed by the decoder.
    private static void checkSource(ByteBuffer source, byte[] expected) {
        checkGuards(source);
        byte[] actual = new byte[expected.length];
        source.duplicate().position(3).get(actual);
        assertArrayEquals(expected, actual);
    }

    /// Creates finite original-layout seeds covering all action modes, empty tails and changing configurations.
    private static @Unmodifiable List<byte[]> seeds() {
        List<byte[]> seeds = new ArrayList<>();
        for (int mode = 0; mode < 10; mode++) {
            for (int size : new int[]{0, 1, 8, 256, 4096}) {
                for (int shape = 0; shape < 2; shape++) {
                    ByteArrayOutputStream tail = new ByteArrayOutputStream();
                    tail.writeBytes(profile(1 + mode % 9, shape, 0));
                    if (size > 0) {
                        parameter(tail, 1, size, size);
                        int capacity = Math.toIntExact(Zstd.compressBound(size) * 15);
                        parameter(tail, 1, capacity, capacity);
                        parameter(tail, 0, 9, mode);
                        if (mode == 3) {
                            parameter(tail, 0, 7, 0);
                            tail.writeBytes(profile(9, 1 - shape, 0));
                        }
                    }
                    seeds.add(seed(payload(size, shape), tail.toByteArray()));
                }
            }
        }
        Random random = new Random(0x57ea_ffffL);
        for (int strategy = 1; strategy <= 9; strategy++) {
            for (int workers = 0; workers < 3; workers++) {
                ByteArrayOutputStream tail = new ByteArrayOutputStream();
                tail.writeBytes(profile(strategy, workers % 2, workers));
                byte[] actions = new byte[512];
                random.nextBytes(actions);
                tail.writeBytes(actions);
                seeds.add(seed(payload(8192, workers % 3), tail.toByteArray()));
            }
        }
        for (int size = 0; size <= 64; size++) {
            byte[] seed = new byte[size];
            random.nextBytes(seed);
            seeds.add(seed);
        }
        for (int index = 0; index < 96; index++) {
            byte[] tail = new byte[512];
            random.nextBytes(tail);
            seeds.add(seed(payload(index * 97, index % 3), tail));
        }
        for (int workers = 0; workers < 3; workers++) {
            int size = 1024 * 1024 + 1;
            ByteArrayOutputStream tail = new ByteArrayOutputStream();
            tail.writeBytes(profile(3, 1, workers));
            parameter(tail, 1, size, size);
            int capacity = Math.toIntExact(Zstd.compressBound(size) * 15);
            parameter(tail, 1, capacity, capacity);
            parameter(tail, 0, 9, 5);
            seeds.add(seed(payload(size, 1), tail.toByteArray()));
        }
        return List.copyOf(seeds);
    }

    /// Encodes a fixed-width original parameter profile with optional hints and the external producer disabled.
    private static byte[] profile(int strategy, int variant, int workers) {
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        boolean high = variant != 0;
        parameter(tail, 10, 15, high ? 15 : 10);
        parameter(tail, 6, 15, high ? 15 : 6);
        parameter(tail, 6, 16, high ? 16 : 6);
        parameter(tail, 1, 9, high ? 9 : 1);
        parameter(tail, 3, 7, high ? 7 : 3);
        parameter(tail, 0, 512, high ? 512 : 0);
        parameter(tail, 1, 9, strategy);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 6, 16, high ? 16 : 6);
        parameter(tail, 4, 4096, high ? 4096 : 4);
        parameter(tail, 0, 8, high ? 8 : 0);
        parameter(tail, 0, 25, high ? 25 : 0);
        parameter(tail, 0, 2, workers);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 0, 6, high ? 6 : 0);
        parameter(tail, 0, 2, variant);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 1024, 131072, high ? 131072 : 1024);
        parameter(tail, 0, 1, variant);
        parameter(tail, 0, 2, variant);
        parameter(tail, 0, 1, 1);
        parameter(tail, 0, 1, 1);
        parameter(tail, 0, 10, 0);
        assertEquals(36, tail.size());
        return tail.toByteArray();
    }

    /// Appends one chosen inclusive-range parameter in its original consumption order.
    private static void parameter(ByteArrayOutputStream tail, int minimum, int maximum, int value) {
        assertTrue(value >= minimum && value <= maximum);
        for (int index = width(maximum - minimum) - 1; index >= 0; index--) tail.write((value - minimum) >>> (8 * index));
    }

    /// Reserves the complete plaintext and reverses parameter bytes for the original tail-first producer.
    private static byte[] seed(byte[] plain, byte[] parameters) {
        int width = 1;
        while (width != width(plain.length + parameters.length + width)) width = width(plain.length + parameters.length + width);
        byte[] seed = Arrays.copyOf(plain, plain.length + parameters.length + width);
        for (int index = 0; index < parameters.length; index++) seed[plain.length + parameters.length - 1 - index] = parameters[index];
        for (int index = 0; index < width; index++) seed[plain.length + parameters.length + index] = (byte) (parameters.length >>> (8 * index));
        assertEquals(plain.length, new ZstdFuzzDataProducer(seed).reservePrefix());
        return seed;
    }

    /// Returns bytes consumed by a range beginning at zero.
    private static int width(int value) {
        int width = 0;
        for (; value != 0; value >>>= 8) width++;
        return width;
    }

    /// Creates incompressible, repeating and constant payloads without platform-dependent randomness.
    private static byte[] payload(int size, int kind) {
        byte[] plain = new byte[size];
        if (kind == 2) return plain;
        new Random(0x57eaL + size).nextBytes(plain);
        if (kind == 1) for (int index = 64; index < size; index++) plain[index] = plain[index % 64];
        return plain;
    }

    /// Resolves the reference executable and enforces required-reference CI configuration.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_STREAM_ROUND_TRIP_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_STREAM_ROUND_TRIP"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_STREAM_ROUND_TRIP_EXECUTABLE to the built official streaming reference");
        }
        assumeTrue(available, "Official streaming reference is not configured");
        return Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
    }

    /// Runs one bounded batch with original stateful or fresh native contexts.
    private List<String> run(String executable, boolean reuse, List<byte[]> seeds) throws IOException, InterruptedException {
        Path input = directory.resolve("streams.input");
        Path output = directory.resolve("streams.output");
        Path errors = directory.resolve("streams.errors");
        try (var writer = Files.newBufferedWriter(input, StandardCharsets.US_ASCII)) {
            for (byte[] seed : seeds) {
                writer.write(seed.length + " " + HexFormat.of().formatHex(seed));
                writer.newLine();
            }
        }
        ProcessBuilder builder = reuse ? new ProcessBuilder(executable, "--reuse") : new ProcessBuilder(executable);
        Process process = builder.redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official stream reference timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official stream reference did not terminate");
            }
        }
        return Files.readAllLines(output, StandardCharsets.US_ASCII);
    }
}
