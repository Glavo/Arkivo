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
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Adapts fse_read_ncount.c's signed frequencies, parameter layout and randomized trailing bytes.
///
/// Java checks always run. Set `ARKIVO_ZSTD_FSE_EXECUTABLE` to the test-only native reference built by
/// `buildZstdFseReference` to compare encoded bytes, signed counts and every decoding state independently.
/// Set `ARKIVO_REQUIRE_ZSTD_FSE=true` to reject a missing reference instead of skipping that comparison.
@NotNullByDefault
@Timeout(120)
final class ZstdOfficialFseTest {
    /// Stores native requests, responses and diagnostics outside the source tree.
    @TempDir
    Path directory;

    /// Covers every maximum symbol and official table logarithm after parameter exhaustion.
    @ParameterizedTest
    @ValueSource(ints = {5, 6, 7, 8, 9, 10, 11, 12})
    void tableLogAndAlphabetBoundaries(int tableLog) throws IOException {
        for (int maximumSymbol = 0; maximumSymbol < 256; maximumSymbol++) {
            Case sample = fromInput(new byte[]{(byte) maximumSymbol, (byte) (tableLog - 5)});
            assertEquals(tableLog, sample.tableLog());
            assertEquals(maximumSymbol + 1, sample.counts().length);
            assertDistribution(sample);
        }
    }

    /// Exercises partially consumed parameters, negative frequencies, and random table suffixes.
    @Test
    void inputDerivedDistributions() throws IOException {
        int withSuffix = 0;
        int negativeLast = 0;
        for (Case sample : randomCases()) {
            assertDistribution(sample);
            if (sample.source().length > sample.encoded().length) withSuffix++;
            if (sample.counts()[sample.counts().length - 1] == -1) negativeLast++;
        }
        assertTrue(withSuffix > 100, "Random parameter tails must exercise table suffixes");
        assertTrue(negativeLast > 0, "The final frequency's optional -1 representation must be exercised");
    }

    /// Checks exact array limits even when the omitted bytes remain accessible beyond the supplied limit.
    @Test
    void truncationAndConfiguredLimits() throws IOException {
        for (int tableLog = 5; tableLog <= 12; tableLog++) {
            for (int maximumSymbol : new int[]{0, 31, 255}) {
                Case sample = fromInput(new byte[]{(byte) maximumSymbol, (byte) (tableLog - 5)});
                byte[] source = new byte[sample.encoded().length + 6];
                System.arraycopy(sample.encoded(), 0, source, 3, sample.encoded().length);
                for (int size = 0; size < sample.encoded().length; size++) {
                    int limit = 3 + size;
                    assertThrows(IOException.class, () -> ZstdEntropy.readFseTable(source, 3, limit, 255, 12));
                }
                assertThrows(IOException.class, () -> ZstdEntropy.readFseTable(source, 3,
                        3 + sample.encoded().length, 255, sample.tableLog() - 1));
                if (maximumSymbol > 0) {
                    assertThrows(IOException.class, () -> ZstdEntropy.readFseTable(source, 3,
                            3 + sample.encoded().length, maximumSymbol - 1, 12));
                }
            }
        }
    }

    /// Validates signed counts with native FSE_readNCount and compares native encoding and decoding tables.
    @Test
    void agreesWithOfficialFse() throws IOException, InterruptedException {
        @Nullable String configured = System.getenv("ARKIVO_ZSTD_FSE_EXECUTABLE");
        boolean available = configured != null && Files.isRegularFile(Path.of(configured));
        if (Boolean.parseBoolean(System.getenv("ARKIVO_REQUIRE_ZSTD_FSE"))) {
            assertTrue(available, "Set ARKIVO_ZSTD_FSE_EXECUTABLE to the built official FSE reference");
        }
        assumeTrue(available, "Official FSE reference is not configured");
        List<Case> cases = new ArrayList<>(randomCases());
        for (int tableLog = 5; tableLog <= 12; tableLog++) {
            for (int maximumSymbol = 0; maximumSymbol < 256; maximumSymbol++) {
                cases.add(fromInput(new byte[]{(byte) maximumSymbol, (byte) (tableLog - 5)}));
            }
        }
        Path requests = directory.resolve("requests.txt");
        Path responses = directory.resolve("responses.txt");
        Path errors = directory.resolve("errors.txt");
        try (var writer = Files.newBufferedWriter(requests, StandardCharsets.US_ASCII)) {
            for (Case sample : cases) {
                writer.write(sample.tableLog() + " " + (sample.counts().length - 1) + " " + sample.source().length);
                for (int count : sample.counts()) writer.write(" " + count);
                writer.write(" " + HexFormat.of().formatHex(sample.source()));
                writer.newLine();
            }
        }
        Process process = new ProcessBuilder(Path.of(Objects.requireNonNull(configured)).toAbsolutePath().toString())
                .redirectInput(requests.toFile()).redirectOutput(responses.toFile()).redirectError(errors.toFile()).start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) fail("Official FSE reference did not finish within 60 seconds");
            assertEquals(0, process.exitValue(), () -> diagnostics(errors));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Official FSE reference did not terminate");
            }
        }
        try (var reader = Files.newBufferedReader(responses, StandardCharsets.US_ASCII)) {
            for (int index = 0; index < cases.size(); index++) {
                Case sample = cases.get(index);
                @Nullable String line = reader.readLine();
                assertNotNull(line, "Missing native response for case " + index);
                String[] fields = line.split(" ");
                assertEquals(2, fields.length);
                byte[] encoded = HexFormat.of().parseHex(fields[0]);
                assertArrayEquals(encoded, sample.encoded(), "Native encoding differs for case " + index);
                var parsed = ZstdEntropy.readFseTable(encoded, 0, encoded.length, 255, 12);
                assertEquals(encoded.length, parsed.bytesRead());
                byte[] states = HexFormat.of().parseHex(fields[1]);
                assertEquals(4 << sample.tableLog(), states.length);
                for (int state = 0; state < (1 << sample.tableLog()); state++) {
                    assertEquals(Byte.toUnsignedInt(states[state * 4]), parsed.table().symbol(state));
                    assertEquals(Byte.toUnsignedInt(states[state * 4 + 1]), parsed.table().numberOfBits(state));
                    assertEquals(Short.toUnsignedInt(ByteArrayAccess.readShortLittleEndian(states, state * 4 + 2)),
                            parsed.table().baseline(state));
                }
            }
            assertNull(reader.readLine(), "Unexpected extra native response");
        }
    }

    /// Returns a bounded diagnostic for a native failure.
    private static String diagnostics(Path path) {
        try {
            String message = Files.readString(path);
            return message.substring(0, Math.min(4096, message.length()));
        } catch (IOException exception) {
            return exception.toString();
        }
    }

    /// Generates deterministic inputs of varying lengths, including complete parameter exhaustion.
    private static @Unmodifiable List<Case> randomCases() {
        Random random = new Random(0xf5e15_07L);
        List<Case> result = new ArrayList<>();
        for (int length : new int[]{0, 1, 2, 3, 4, 8, 16, 32, 256, 1024}) {
            for (int trial = 0; trial < 32; trial++) {
                byte[] input = new byte[length];
                random.nextBytes(input);
                result.add(fromInput(input));
            }
        }
        return List.copyOf(result);
    }

    /// Preserves the upstream parameter order, signed frequency range and 512-byte suffix capacity.
    private static Case fromInput(byte @Unmodifiable [] input) {
        ZstdFuzzDataProducer producer = new ZstdFuzzDataProducer(input);
        int tableLog = Math.toIntExact(producer.range(5, 12));
        int maximumSymbol = Math.toIntExact(producer.range(0, 255));
        int remaining = (1 << tableLog) - 1;
        int[] counts = new int[maximumSymbol + 1];
        for (int symbol = 0; symbol < maximumSymbol && remaining > 0; symbol++) {
            int count = Math.toIntExact(producer.range(0, remaining + 1)) - 1;
            counts[symbol] = count;
            remaining -= Math.abs(count);
        }
        counts[maximumSymbol] = remaining + 1;
        if (counts[maximumSymbol] == 1 && producer.range(0, 1) == 1) counts[maximumSymbol] = -1;
        byte[] encoded = ZstdEntropy.encodeFseTableDescription(counts, counts.length, tableLog);
        assertTrue(encoded.length <= 512);
        int size = Math.toIntExact(producer.range(encoded.length, 512));
        byte[] source = Arrays.copyOf(encoded, size);
        for (int index = encoded.length; index < size; index++) source[index] = (byte) producer.range(0, 255);
        return new Case(tableLog, counts, encoded, source);
    }

    /// Checks the complete state table, frequency magnitudes, final symbol, input consumption and immutability.
    private static void assertDistribution(Case sample) throws IOException {
        var expected = ZstdEntropy.FseTable.fromNormalized(sample.counts(), sample.counts().length, sample.tableLog());
        for (byte[] payload : new byte[][]{sample.encoded(), sample.source()}) {
            byte[] source = new byte[payload.length + 6];
            Arrays.fill(source, (byte) 0xa5);
            System.arraycopy(payload, 0, source, 3, payload.length);
            byte[] unchanged = source.clone();
            var parsed = ZstdEntropy.readFseTable(source, 3, 3 + payload.length, 255, 12);
            assertEquals(sample.encoded().length, parsed.bytesRead());
            assertEquals(sample.tableLog(), parsed.table().tableLog());
            int[] frequencies = new int[sample.counts().length];
            for (int state = 0; state < (1 << sample.tableLog()); state++) {
                assertEquals(expected.symbol(state), parsed.table().symbol(state));
                assertEquals(expected.numberOfBits(state), parsed.table().numberOfBits(state));
                assertEquals(expected.baseline(state), parsed.table().baseline(state));
                frequencies[parsed.table().symbol(state)]++;
            }
            for (int symbol = 0; symbol < frequencies.length; symbol++) {
                assertEquals(Math.abs(sample.counts()[symbol]), frequencies[symbol]);
            }
            assertTrue(frequencies[frequencies.length - 1] > 0);
            assertArrayEquals(unchanged, source);
        }
    }

    /// Stores one upstream distribution and its description with and without trailing bytes.
    ///
    /// @param tableLog the base-two logarithm of the normalized total
    /// @param counts signed normalized frequencies through the last nonzero symbol
    /// @param encoded the table description without trailing bytes
    /// @param source the description followed by input-derived arbitrary bytes
    @NotNullByDefault
    private record Case(int tableLog, int @Unmodifiable [] counts,
                        byte @Unmodifiable [] encoded, byte @Unmodifiable [] source) {
    }
}
