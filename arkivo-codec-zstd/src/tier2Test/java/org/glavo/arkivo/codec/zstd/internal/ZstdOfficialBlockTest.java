// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.glavo.arkivo.codec.zstd.ZstdFuzzDataProducer;
import org.glavo.arkivo.internal.ByteArrayAccess;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Adapts Zstandard 1.5.7's block_round_trip.c and block_decompress.c without a frame wrapper.
///
/// Native block generation and decoding require `ARKIVO_ZSTD_BLOCK_EXECUTABLE`, built by
/// `buildZstdBlockReference`. Set `ARKIVO_REQUIRE_ZSTD_BLOCK=true` to reject a missing reference.
/// Java block round trips and bounded malformed-input replay run without that optional executable.
@NotNullByDefault
@Timeout(180)
final class ZstdOfficialBlockTest {
    /// Original block limit shared by the official targets and the Zstandard format.
    private static final int BLOCK_SIZE = 128 * 1024;

    /// Payload boundaries below, at and above the upstream block-size cap.
    private static final int @Unmodifiable [] SIZES = {
            0, 1, 3, 4, 7, 8, 9, 255, 256, 1023, 1024, 4096, 65536, 131071, 131072, 131073
    };

    /// Holds batch input, output and native diagnostics outside the source tree.
    @TempDir
    Path directory;

    /// Retains the entire upstream level range and resets one Java encoder before every independent block.
    @ParameterizedTest
    @ValueSource(ints = {-3, -2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    void javaRoundTrips(int level) throws IOException {
        ZstdBlockEncoder encoder = new ZstdBlockEncoder();
        for (byte[] seed : levelSeeds(level)) {
            Input input = parameters(seed);
            assertEquals(level, input.level());
            byte[] plain = Arrays.copyOf(seed, Math.min(input.size(), BLOCK_SIZE));
            Encoded encoded = encode(encoder, plain, input.level());
            assertArrayEquals(plain, decode(encoded));
        }
    }

    /// Replays complete arbitrary input, not the producer's remaining prefix, through a fresh block decoder.
    @Test
    void arbitraryBlocks() throws IOException {
        List<byte[]> valid = javaBlocks();
        for (byte[] input : malformedInputs(valid)) {
            byte[] unchanged = input.clone();
            try {
                byte[] output = new ZstdBlockDecoder(BLOCK_SIZE, ZstdDictionaryContext.NONE).decodeCompressed(input);
                assertTrue(output.length <= BLOCK_SIZE);
            } catch (IOException expected) {
                // Upstream permits malformed input to fail, but not unchecked exceptions or unbounded output.
            }
            assertArrayEquals(unchanged, input);
        }
    }

    /// Checks native block generation, Java block encoding, malformed input and both native context lifetimes.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void officialBlocks(boolean reuse) throws IOException, InterruptedException {
        String executable = executable();
        List<byte[]> seeds = new ArrayList<>();
        for (int level = -3; level <= 19; level++) seeds.addAll(levelSeeds(level));
        Random random = new Random(0xb10c_f022L);
        for (int size = 0; size <= 256; size++) {
            byte[] input = new byte[size];
            random.nextBytes(input);
            seeds.add(input);
        }
        List<Request> requests = seeds.stream().map(seed -> new Request('R', seed, null)).toList();
        List<String> responses = run(executable, reuse, requests, "round-trips");
        ZstdBlockEncoder encoder = new ZstdBlockEncoder();
        List<Request> probes = new ArrayList<>();
        Map<String, byte[]> mutationSeeds = new LinkedHashMap<>();
        int nativeCompressed = 0;
        int nativeRaw = 0;
        int[] javaTypes = new int[3];
        for (int index = 0; index < seeds.size(); index++) {
            byte[] seed = seeds.get(index);
            Input input = parameters(seed);
            byte[] plain = Arrays.copyOf(seed, Math.min(input.size(), BLOCK_SIZE));
            String[] fields = responses.get(index).split(" ");
            assertEquals(5, fields.length);
            assertEquals(input.level(), Integer.parseInt(fields[0]));
            assertEquals(input.size(), Integer.parseInt(fields[1]));
            assertEquals(plain.length, Integer.parseInt(fields[2]));
            byte[] compressed = bytes(fields[4]);
            assertEquals(compressed.length, Integer.parseInt(fields[3]));
            if (compressed.length == 0) {
                nativeRaw++;
            } else {
                nativeCompressed++;
                assertArrayEquals(plain, new ZstdBlockDecoder(BLOCK_SIZE, ZstdDictionaryContext.NONE).decodeCompressed(compressed));
                probes.add(new Request('D', compressed, plain));
                if (mutationSeeds.size() < 32) mutationSeeds.putIfAbsent(HexFormat.of().formatHex(compressed), compressed);
            }
            Encoded javaEncoded = encode(encoder, plain, input.level());
            assertArrayEquals(plain, decode(javaEncoded));
            javaTypes[javaEncoded.type()]++;
            if (javaEncoded.type() == 2) probes.add(new Request('D', javaEncoded.payload(), plain));
        }
        assertTrue(nativeCompressed > 100 && nativeRaw > 100);
        for (int count : javaTypes) assertTrue(count > 0, "Java must exercise raw, RLE and compressed blocks");
        for (byte[] input : malformedInputs(new ArrayList<>(mutationSeeds.values()))) {
            probes.add(new Request('D', input, null));
        }
        List<String> decoded = run(executable, reuse, probes, "decoding");
        int rejected = 0;
        int accepted = 0;
        for (int index = 0; index < probes.size(); index++) {
            Request request = probes.get(index);
            String line = decoded.get(index);
            byte @Nullable [] nativeOutput;
            if (line.equals("error")) {
                nativeOutput = null;
                rejected++;
            } else {
                String[] fields = line.split(" ");
                assertEquals(3, fields.length);
                assertEquals("ok", fields[0]);
                nativeOutput = bytes(fields[2]);
                assertEquals(nativeOutput.length, Integer.parseInt(fields[1]));
                accepted++;
            }
            byte[] unchanged = request.input().clone();
            byte @Nullable [] javaOutput;
            try {
                javaOutput = new ZstdBlockDecoder(BLOCK_SIZE, ZstdDictionaryContext.NONE).decodeCompressed(request.input());
            } catch (IOException expected) {
                javaOutput = null;
            }
            assertArrayEquals(unchanged, request.input());
            if (request.expected() != null) {
                assertArrayEquals(request.expected(), nativeOutput, "Native valid-block decoding: " + index);
                assertArrayEquals(request.expected(), javaOutput, "Java valid-block decoding: " + index);
            } else if (nativeOutput != null) {
                assertArrayEquals(nativeOutput, javaOutput, "Native-accepted arbitrary block: " + index);
            } else {
                assertNull(javaOutput, "Native-rejected arbitrary block: " + index);
            }
        }
        assertTrue(accepted > 100 && rejected > 100);
    }

    /// Resolves the explicitly enabled test-only native reference.
    private static String executable() {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_BLOCK_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_BLOCK"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_BLOCK_EXECUTABLE to the built official block reference");
        }
        assumeTrue(available, "Official block reference is not configured");
        return Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
    }

    /// Runs one bounded batch and requires exactly one response for every request.
    private List<String> run(String executable, boolean reuse, List<Request> requests, String name)
            throws IOException, InterruptedException {
        Path input = directory.resolve(name + ".input");
        Path output = directory.resolve(name + ".output");
        Path errors = directory.resolve(name + ".errors");
        try (var writer = Files.newBufferedWriter(input, StandardCharsets.US_ASCII)) {
            for (Request request : requests) {
                writer.write(request.mode() + " " + request.input().length + " " + HexFormat.of().formatHex(request.input()));
                writer.newLine();
            }
        }
        ProcessBuilder builder = reuse ? new ProcessBuilder(executable, "--reuse") : new ProcessBuilder(executable);
        Process process = builder.redirectInput(input.toFile()).redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official block reference timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official block reference did not terminate");
            }
        }
        List<String> lines = Files.readAllLines(output, StandardCharsets.US_ASCII);
        assertEquals(requests.size(), lines.size());
        return lines;
    }

    /// Creates independent Java block state with the upstream level and exact source size.
    private static Encoded encode(ZstdBlockEncoder encoder, byte[] plain, int level) throws IOException {
        var parameters = new ZstdEncoderParameters(level, 0, 0, 0, 0, 0, 0, 0,
                false, true, true, false, 0, 0, 0, 0, 0, 0, 0, plain.length, null);
        encoder.reset(parameters);
        byte[] unchanged = plain.clone();
        byte[] encoded = encoder.encode(plain, plain.length, true, parameters);
        assertArrayEquals(unchanged, plain);
        assertTrue(encoded.length >= 3);
        int header = ByteArrayAccess.readIntLittleEndian(Arrays.copyOf(encoded, 4), 0) & 0xffffff;
        assertEquals(1, header & 1);
        int type = header >>> 1 & 3;
        assertTrue(type < 3);
        int size = header >>> 3;
        assertEquals(type == 1 ? 1 : size, encoded.length - 3);
        if (type != 2) assertEquals(plain.length, size);
        return new Encoded(type, size, Arrays.copyOfRange(encoded, 3, encoded.length));
    }

    /// Decodes each block representation without inventing a compressed stream for a native raw fallback.
    private static byte[] decode(Encoded encoded) throws IOException {
        if (encoded.type() == 0) return encoded.payload().clone();
        var decoder = new ZstdBlockDecoder(BLOCK_SIZE, ZstdDictionaryContext.NONE);
        return encoded.type() == 1 ? decoder.decodeRle(Byte.toUnsignedInt(encoded.payload()[0]), encoded.size())
                : decoder.decodeCompressed(encoded.payload());
    }

    /// Returns representative independently reset Java compressed blocks for always-enabled malformed replay.
    private static List<byte[]> javaBlocks() throws IOException {
        List<byte[]> blocks = new ArrayList<>();
        ZstdBlockEncoder encoder = new ZstdBlockEncoder();
        for (int size : new int[]{255, 1024, 4096, 65536}) {
            Encoded encoded = encode(encoder, payload(size, 2), 3);
            assertEquals(2, encoded.type());
            blocks.add(encoded.payload());
        }
        return blocks;
    }

    /// Adds proper prefixes, header/sequence mutations, appended bytes, oversized input and arbitrary bytes.
    private static List<byte[]> malformedInputs(List<byte[]> blocks) {
        List<byte[]> result = new ArrayList<>();
        Random random = new Random(0xb10c_dec0L);
        for (int size = 0; size <= 256; size++) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            result.add(bytes);
        }
        result.add(new byte[BLOCK_SIZE + 1]);
        result.add(new byte[2 * BLOCK_SIZE]);
        for (byte[] block : blocks) {
            result.add(block);
            for (int size = 0; size < Math.min(32, block.length); size++) result.add(Arrays.copyOf(block, size));
            result.add(Arrays.copyOf(block, block.length - 1));
            result.add(Arrays.copyOf(block, block.length + 1));
            for (int index = 0; index < Math.min(32, block.length); index++) {
                for (int bit : new int[]{0, 7}) {
                    byte[] changed = block.clone();
                    changed[index] ^= (byte) (1 << bit);
                    result.add(changed);
                }
            }
        }
        return result;
    }

    /// Creates constant, random and match-rich data without platform-dependent randomness.
    private static byte[] payload(int size, int kind) {
        byte[] bytes = new byte[size];
        if (kind == 0) return bytes;
        new Random(0xb10cL + size).nextBytes(bytes);
        if (kind == 2) {
            for (int index = 64; index < size; index++) bytes[index] = bytes[index % 64];
        }
        return bytes;
    }

    /// Returns all block-size and data-shape cases for one original compression level.
    private static List<byte[]> levelSeeds(int level) {
        List<byte[]> seeds = new ArrayList<>();
        for (int size : SIZES) {
            for (int kind = 0; kind < 3; kind++) seeds.add(inputFor(payload(size, kind), level));
        }
        return seeds;
    }

    /// Appends a tail selecting exactly one parameter byte after reserving the complete original payload.
    private static byte[] inputFor(byte[] payload, int level) {
        int width = 1;
        while (byteWidth(payload.length + 1L + width) != width) width = byteWidth(payload.length + 1L + width);
        byte[] input = Arrays.copyOf(payload, payload.length + 1 + width);
        int cursor = input.length;
        for (int index = width - 1; index >= 0; index--) input[--cursor] = (byte) (1 >>> (8 * index));
        input[--cursor] = (byte) (level + 3);
        assertEquals(payload.length, cursor);
        assertEquals(new Input(payload.length, level), parameters(input));
        return input;
    }

    /// Preserves reserveDataPrefix followed by the signed -3..19 level range.
    private static Input parameters(byte[] input) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(input);
        int size = producer.reservePrefix();
        int level = (int) producer.range(0, 22) - 3;
        return new Input(size, level);
    }

    /// Returns the number of bytes used for the producer's inclusive unsigned range.
    private static int byteWidth(long value) {
        int width = 0;
        for (; value != 0; value >>>= 8) width++;
        return width;
    }

    /// Decodes the native batch protocol's empty or hexadecimal data field.
    private static byte[] bytes(String value) {
        return value.equals("-") ? new byte[0] : HexFormat.of().parseHex(value);
    }

    /// Stores the producer's untruncated payload length and requested level.
    ///
    /// @param size bytes reserved before applying the 128-KiB block limit
    /// @param level the original target's selected compression level
    @NotNullByDefault
    private record Input(int size, int level) {
    }

    /// Stores a native request and, for generated valid data, its independently known plaintext.
    ///
    /// @param mode R for round-trip generation or D for compressed-block decoding
    /// @param input the complete fuzz seed or compressed block
    /// @param expected plaintext required from a valid block, or null for arbitrary input
    @NotNullByDefault
    private record Request(char mode, byte @Unmodifiable [] input, byte @Nullable @Unmodifiable [] expected) {
    }

    /// Stores a Java block after checking and removing its three-byte header.
    ///
    /// @param type raw, RLE or compressed block type
    /// @param size the size carried by the block header
    /// @param payload the unchanged physical block body
    @NotNullByDefault
    private record Encoded(int type, int size, byte @Unmodifiable [] payload) {
    }
}
