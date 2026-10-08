// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import com.github.luben.zstd.Zstd;
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
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Adapts Zstandard's huf_round_trip.c and huf_decompress.c through internal literal and entropy APIs.
///
/// The native generator retains the 256-KiB primitive limit, which exceeds a Zstandard block's limit.
/// Valid block-sized encodings also pass through the production literal-section decoder. Native CPU and
/// X1/X2 table-layout options select reference encodings; Java has no corresponding implementation switches.
/// Set `ARKIVO_ZSTD_HUFFMAN_EXECUTABLE` to the tool built by `buildZstdHuffmanReference` and optionally
/// `ARKIVO_REQUIRE_ZSTD_HUFFMAN=true` to require independent native table and stream generation.
@NotNullByDefault
@Timeout(180)
final class ZstdOfficialHuffmanTest {
    /// Holds generated requests, responses and native diagnostics outside the repository sources.
    @TempDir
    Path directory;

    /// Verifies Java literal encoding against both the Java block decoder and the independent native decoder.
    @ParameterizedTest
    @ValueSource(ints = {4, 256})
    void javaLiteralRoundTrips(int alphabet) throws IOException {
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9, 31, 255, 1023, 1024, 16383, 16384, 65536, 131000}) {
            assertJavaLiteralEncoding(payload(size, alphabet));
        }
    }

    /// Preserves the fuzz target's table-first parsing and reuse of the complete input as the bitstream.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void arbitraryTablesAndStreams(boolean bmi2) throws IOException {
        List<byte[]> seeds = new ArrayList<>();
        Random random = new Random(0x4f_15_07L);
        for (int length = 0; length <= 256; length++) {
            byte[] seed = new byte[length];
            random.nextBytes(seed);
            seeds.add(seed);
        }
        for (int alphabet : new int[]{4, 16, 128, 256}) {
            byte @Nullable [] table = ZstdLiteralEncoder.buildTableDescription(payload(4096, alphabet));
            assertNotNull(table);
            for (int size = 0; size <= table.length; size++) {
                for (int streams : new int[]{0, 1}) {
                    seeds.add(withParameters(Arrays.copyOf(table, size), streams, 0, 0, 0, 12, bmi2, false));
                    seeds.add(withParameters(Arrays.copyOf(table, size), streams, 1, 63, 64, 12, bmi2, false));
                }
            }
            for (int index = 0; index < table.length; index++) {
                byte[] changed = table.clone();
                changed[index] ^= (byte) 0xff;
                seeds.add(withParameters(changed, index & 1, 0, 0, 257, 12, bmi2, false));
            }
        }
        int parsedTables = 0;
        for (byte[] seed : seeds) {
            Parameters parameters = parameters(seed, bmi2, false);
            byte[] source = Arrays.copyOf(seed, parameters.size());
            byte[] unchanged = source.clone();
            byte[] target = new byte[parameters.capacity() + 6];
            Arrays.fill(target, (byte) 0xa5);
            try {
                var table = ZstdEntropy.readHuffmanTable(source, 0, source.length);
                parsedTables++;
                if (parameters.streams() == 0) {
                    table.table().decode(source, 0, source.length, target, 3, parameters.capacity());
                } else {
                    byte[] block = literalBlock(parameters.capacity(), 1,
                            Arrays.copyOf(source, table.bytesRead()), source);
                    byte[] decoded = new ZstdBlockDecoder(1 << 17, ZstdDictionaryContext.NONE).decodeCompressed(block);
                    assertEquals(parameters.capacity(), decoded.length);
                    System.arraycopy(decoded, 0, target, 3, decoded.length);
                }
            } catch (IOException expected) {
                // The upstream target permits malformed tables and streams, but not unchecked failures.
            }
            assertArrayEquals(unchanged, source);
            assertGuards(target);
        }
        assertTrue(parsedTables > 10, "The malformed-stream loop must also reach valid Huffman tables");
    }

    /// Compares native-generated weights, descriptions and streams under the original input-derived parameters.
    @Test
    void officialRoundTrips() throws IOException, InterruptedException {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_HUFFMAN_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_HUFFMAN"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_HUFFMAN_EXECUTABLE to the built official Huffman reference");
        }
        assumeTrue(available, "Official Huffman reference is not configured");
        String executable = Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString();
        Path cpu = directory.resolve("cpu.txt");
        run(new ProcessBuilder(executable, "--cpu"), cpu);
        String capability = Files.readString(cpu).trim();
        assertTrue(capability.equals("0") || capability.equals("1"));
        boolean bmi2 = capability.equals("1");
        List<byte[]> seeds = roundTripSeeds(bmi2);
        Path requests = directory.resolve("requests.txt");
        Path responses = directory.resolve("responses.txt");
        try (var writer = Files.newBufferedWriter(requests, StandardCharsets.US_ASCII)) {
            for (byte[] seed : seeds) {
                writer.write(seed.length + " " + HexFormat.of().formatHex(seed));
                writer.newLine();
            }
        }
        run(new ProcessBuilder(executable).redirectInput(requests.toFile()), responses);
        int[] streamsSeen = new int[2];
        int[] descriptionsSeen = new int[2];
        int small = 0;
        int rle = 0;
        int skippedEncoding = 0;
        int large = 0;
        try (var reader = Files.newBufferedReader(responses, StandardCharsets.US_ASCII)) {
            for (int index = 0; index < seeds.size(); index++) {
                byte[] seed = seeds.get(index);
                Parameters parameters = parameters(seed, bmi2, true);
                @Nullable String line = reader.readLine();
                assertNotNull(line, "Missing native response for seed " + index);
                String[] fields = line.split(" ");
                assertEquals(parameters.streams(), Integer.parseInt(fields[0]));
                assertEquals(parameters.size(), Integer.parseInt(fields[1]));
                assertEquals(parameters.capacity(), Integer.parseInt(fields[2]));
                assertEquals(parameters.tableLog(), Integer.parseInt(fields[3]));
                byte[] plain = Arrays.copyOf(seed, parameters.size());
                if (plain.length <= ZstdBlockDecoder.MAX_BLOCK_SIZE) assertJavaLiteralEncoding(plain);
                if (!fields[4].equals("ok")) {
                    assertEquals(5, fields.length);
                    switch (fields[4]) {
                        case "small" -> { assertTrue(parameters.size() <= 1); small++; }
                        case "rle" -> {
                            for (int i = 1; i < parameters.size(); i++) assertEquals(seed[0], seed[i]);
                            rle++;
                        }
                        case "table", "no-stream" -> skippedEncoding++;
                        default -> fail("Unknown native outcome: " + line);
                    }
                    continue;
                }
                assertEquals(9, fields.length);
                byte[] weights = HexFormat.of().parseHex(fields[6]);
                byte[] description = HexFormat.of().parseHex(fields[7]);
                byte[] stream = HexFormat.of().parseHex(fields[8]);
                var parsed = ZstdEntropy.readHuffmanTable(description, 0, description.length);
                assertEquals(description.length, parsed.bytesRead());
                assertEquals(Integer.parseInt(fields[5]), parsed.tableLog());
                assertEquals(weights.length, parsed.symbolCount());
                for (int symbol = 0; symbol < weights.length; symbol++) {
                    assertEquals(Byte.toUnsignedInt(weights[symbol]), parsed.weights()[symbol]);
                }
                byte[] tableAndStream = new byte[3 + description.length + stream.length];
                System.arraycopy(description, 0, tableAndStream, 3, description.length);
                System.arraycopy(stream, 0, tableAndStream, 3 + description.length, stream.length);
                byte[] unchanged = tableAndStream.clone();
                var withSuffix = ZstdEntropy.readHuffmanTable(tableAndStream, 3, tableAndStream.length);
                assertEquals(description.length, withSuffix.bytesRead());
                assertArrayEquals(parsed.weights(), withSuffix.weights());
                assertArrayEquals(unchanged, tableAndStream);
                assertNativeStream(parsed.table(), stream, plain, parameters.streams());
                streamsSeen[parameters.streams()]++;
                descriptionsSeen[Byte.toUnsignedInt(description[0]) < 128 ? 0 : 1]++;
                if (plain.length > ZstdBlockDecoder.MAX_BLOCK_SIZE) large++;
                if (plain.length <= ZstdBlockDecoder.MAX_BLOCK_SIZE
                        && (parameters.streams() == 1 || plain.length <= 1023 && description.length + stream.length <= 1023)) {
                    byte[] block = literalBlock(plain.length, parameters.streams(), description, stream);
                    assertArrayEquals(plain, new ZstdBlockDecoder(1 << 17, ZstdDictionaryContext.NONE).decodeCompressed(block));
                }
            }
            assertNull(reader.readLine(), "Unexpected extra native response");
        }
        assertTrue(streamsSeen[0] > 20 && streamsSeen[1] > 20, "Both one-stream and four-stream paths must decode");
        assertTrue(descriptionsSeen[0] > 0 && descriptionsSeen[1] > 0, "Both FSE and direct weights must decode");
        assertTrue(small > 0 && rle > 0 && skippedEncoding > 0 && large > 0);
    }

    /// Independently validates Java encodings for every applicable upstream payload, including native capacity failures.
    private static void assertJavaLiteralEncoding(byte[] plain) throws IOException {
        byte[] section = ZstdLiteralEncoder.encode(plain);
        byte[] block = Arrays.copyOf(section, section.length + 1);
        assertArrayEquals(plain, new ZstdBlockDecoder(1 << 17, ZstdDictionaryContext.NONE).decodeCompressed(block));
        assertArrayEquals(plain, Zstd.decompress(frame(block, plain.length), plain.length));
    }

    /// Decodes native streams at nonzero input/output offsets, including primitive inputs larger than one block.
    private static void assertNativeStream(ZstdEntropy.HuffmanTable table, byte[] stream, byte[] plain, int streams)
            throws IOException {
        byte[] source = new byte[stream.length + 6];
        System.arraycopy(stream, 0, source, 3, stream.length);
        byte[] unchanged = source.clone();
        byte[] target = new byte[plain.length + 6];
        Arrays.fill(target, (byte) 0xa5);
        if (streams == 0) {
            table.decode(source, 3, 3 + stream.length, target, 3, plain.length);
        } else {
            assertTrue(stream.length >= 10);
            int segmentSize = (plain.length + 3) >>> 2;
            int input = 9;
            for (int segment = 0; segment < 4; segment++) {
                int length = segment == 3 ? 3 + stream.length - input
                        : Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(source, 3 + 2 * segment));
                assertTrue(length > 0 && input + length <= 3 + stream.length);
                int output = segment == 3 ? plain.length - 3 * segmentSize : segmentSize;
                table.decode(source, input, input + length, target, 3 + segment * segmentSize, output);
                input += length;
            }
            assertEquals(3 + stream.length, input);
        }
        assertArrayEquals(plain, Arrays.copyOfRange(target, 3, target.length - 3));
        assertGuards(target);
        assertArrayEquals(unchanged, source);
    }

    /// Adds a literals header and an empty sequence section without changing the native table or bitstreams.
    private static byte[] literalBlock(int size, int streams, byte[] description, byte[] stream) {
        int compressed = description.length + stream.length;
        int headerSize = streams == 0 ? 3 : 5;
        long header = streams == 0 ? (long) compressed << 14 | (long) size << 4 | 2
                : (long) compressed << 22 | (long) size << 4 | 14;
        byte[] encodedHeader = new byte[8];
        ByteArrayAccess.writeLongLittleEndian(encodedHeader, 0, header);
        byte[] block = new byte[headerSize + compressed + 1];
        System.arraycopy(encodedHeader, 0, block, 0, headerSize);
        System.arraycopy(description, 0, block, headerSize, description.length);
        System.arraycopy(stream, 0, block, headerSize + description.length, stream.length);
        return block;
    }

    /// Wraps one literal-only block with a 128-KiB window, including blocks whose headers exceed their output size.
    private static byte[] frame(byte[] block, int size) {
        assertTrue(block.length <= ZstdBlockDecoder.MAX_BLOCK_SIZE);
        byte[] frame = new byte[13 + block.length];
        ByteArrayAccess.writeIntLittleEndian(frame, 0, 0xfd2fb528);
        frame[4] = (byte) 0x80;
        frame[5] = 7 << 3;
        ByteArrayAccess.writeIntLittleEndian(frame, 6, size);
        ByteArrayAccess.writeIntLittleEndian(frame, 10, block.length << 3 | 5);
        System.arraycopy(block, 0, frame, 13, block.length);
        return frame;
    }

    /// Checks output sentinels without constraining partial output on decoding failure.
    private static void assertGuards(byte[] target) {
        for (int index = 0; index < 3; index++) {
            assertEquals((byte) 0xa5, target[index]);
            assertEquals((byte) 0xa5, target[target.length - 1 - index]);
        }
    }

    /// Runs one native batch with bounded execution and process cleanup.
    private void run(ProcessBuilder builder, Path output) throws IOException, InterruptedException {
        Path errors = directory.resolve(output.getFileName() + ".errors");
        Process process = builder.redirectOutput(output.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official Huffman reference timed out");
            assertEquals(0, process.exitValue(), Files.readString(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official Huffman reference did not terminate");
            }
        }
    }

    /// Returns skewed alphabets containing both low and high byte values.
    private static byte[] payload(int size, int alphabet) {
        byte[] data = new byte[size];
        for (int index = 0; index < size; index++) {
            data[index] = (byte) (index % 5 == 0 ? index % alphabet : index % 3);
        }
        if (size > 1) data[size - 1] = (byte) (alphabet - 1);
        return data;
    }

    /// Covers size boundaries, reference table implementations, flags, small capacities, and arbitrary inputs.
    private static @Unmodifiable List<byte[]> roundTripSeeds(boolean bmi2) {
        List<byte[]> seeds = new ArrayList<>();
        for (int size : new int[]{0, 1, 2, 3, 4, 5, 7, 8, 9, 31, 255, 1023, 1024, 16383, 16384, 131072, 262144, 262145}) {
            for (int streams : new int[]{0, 1}) {
                for (int symbols : new int[]{0, 1}) {
                    for (int alphabet : new int[]{4, 256}) {
                        seeds.add(withParameters(payload(size, alphabet), streams, symbols, symbols == 0 ? 0 : 63,
                                2 * size + 32, 1 + size % 12, bmi2, true));
                    }
                }
            }
        }
        for (int capacity : new int[]{0, 1, 2, 4, 16, 32, 64}) {
            for (int streams : new int[]{0, 1}) {
                seeds.add(withParameters(payload(4096, 256), streams, 1, 63, capacity, 12, bmi2, true));
            }
        }
        seeds.add(withParameters(new byte[4096], 0, 0, 0, 4096, 12, bmi2, true));
        Random random = new Random(0x4f_c0decL);
        for (int size = 0; size < 192; size++) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            seeds.add(data);
        }
        return List.copyOf(seeds);
    }

    /// Reads the native target's fields, including its CPU-dependent extra flag byte.
    private static Parameters parameters(byte[] input, boolean bmi2, boolean roundTrip) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(input);
        int streams = (int) producer.range(0, 1);
        producer.range(0, 1);
        if (bmi2) producer.range(0, 1);
        for (int flag = 0; flag < 5; flag++) producer.range(0, 1);
        int capacity = (int) producer.range(0, roundTrip ? 4L * input.length : 8L * input.length + 500);
        int tableLog = (int) producer.range(1, 12);
        int size = roundTrip ? Math.min(256 * 1024, producer.remainingBytes()) : producer.remainingBytes();
        return new Parameters(streams, size, capacity, tableLog);
    }

    /// Appends a parameter tail selecting the supplied payload, including the native target's capacity bound.
    private static byte[] withParameters(byte[] data, int streams, int symbols, int flags, int capacity,
                                         int tableLog, boolean bmi2, boolean roundTrip) {
        int fixed = (bmi2 ? 8 : 7) + 1;
        int width = 1;
        while (byteWidth(roundTrip ? 4L * (data.length + fixed + width) : 8L * (data.length + fixed + width) + 500) != width) {
            width = byteWidth(roundTrip ? 4L * (data.length + fixed + width) : 8L * (data.length + fixed + width) + 500);
        }
        byte[] input = Arrays.copyOf(data, data.length + fixed + width);
        int cursor = input.length;
        input[--cursor] = (byte) streams;
        input[--cursor] = (byte) symbols;
        if (bmi2) input[--cursor] = (byte) (flags & 1);
        for (int flag = 1; flag < 6; flag++) input[--cursor] = (byte) (flags >>> flag & 1);
        for (int index = width - 1; index >= 0; index--) input[--cursor] = (byte) (capacity >>> (8 * index));
        input[--cursor] = (byte) (tableLog - 1);
        assertEquals(data.length, cursor);
        assertEquals(new Parameters(streams, Math.min(roundTrip ? 256 * 1024 : Integer.MAX_VALUE, data.length), capacity, tableLog),
                parameters(input, bmi2, roundTrip));
        return input;
    }

    /// Returns the number of tail bytes consumed by an inclusive unsigned range starting at zero.
    private static int byteWidth(long range) {
        int width = 0;
        for (; range != 0; range >>>= 8) width++;
        return width;
    }

    /// Stores the target parameters relevant to Java decoding.
    ///
    /// @param streams zero for one stream, one for four streams
    /// @param size payload bytes remaining after parameter consumption and the applicable size cap
    /// @param capacity encoded capacity for round trips, decoded capacity for arbitrary-input tests
    /// @param tableLog the requested native table logarithm before alphabet-dependent adjustment
    @NotNullByDefault
    private record Parameters(int streams, int size, int capacity, int tableLog) {
    }
}
