// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures FSE description parsing and table construction, including sparse zero-frequency runs.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZstdFseTableBenchmark {
    /// Table widths used for compressed Huffman weights and sequence codes.
    @Param({"6", "9"})
    public int tableLog = 6;

    /// Alphabet capacity for Huffman weights, offsets, literal lengths, match lengths, or byte symbols.
    @Param({"13", "32", "36", "53", "256"})
    public int alphabetSize = 256;

    /// Whether the description contains long zero runs or frequencies for every symbol.
    @Param({"sparse", "dense"})
    public String distribution = "sparse";

    /// The complete normalized-count description, without trailing input padding.
    private byte @Unmodifiable [] description = new byte[0];

    /// Validated normalized frequencies used to isolate table construction from parsing.
    private int @Unmodifiable [] frequencies = new int[0];

    /// Highest symbol represented by the description.
    private int maximumSymbol;

    /// Generates deterministic frequencies and verifies the parsed table against their exact counts.
    @Setup
    public void setup() throws IOException {
        int tableSize = 1 << tableLog;
        int[] normalized;
        if (distribution.equals("sparse")) {
            normalized = new int[alphabetSize];
            normalized[0] = tableSize - 2;
            normalized[(alphabetSize - 1) / 2] = 1;
            normalized[alphabetSize - 1] = -1;
        } else if (distribution.equals("dense")) {
            normalized = new int[Math.min(alphabetSize, tableSize)];
            Arrays.fill(normalized, 1);
            Random random = new Random(42);
            for (int index = normalized.length; index < tableSize; index++) {
                normalized[random.nextInt(normalized.length)]++;
            }
        } else {
            throw new IllegalArgumentException(distribution);
        }
        maximumSymbol = normalized.length - 1;
        frequencies = normalized;
        description = ZstdEntropy.encodeFseTableDescription(normalized, normalized.length, tableLog);
        var parsed = ZstdEntropy.readFseTable(description, 0, description.length, maximumSymbol, tableLog);
        if (parsed.bytesRead() != description.length || parsed.table().tableLog() != tableLog) {
            throw new AssertionError("Unexpected FSE table bounds");
        }
        int[] observed = new int[normalized.length];
        for (int state = 0; state < tableSize; state++) observed[parsed.table().symbol(state)]++;
        for (int symbol = 0; symbol < observed.length; symbol++) {
            if (observed[symbol] != Math.abs(normalized[symbol])) {
                throw new AssertionError("FSE symbol frequency differs");
            }
        }
    }

    /// Parses the complete description and builds its decoding table.
    @Benchmark
    public Object parse() throws IOException {
        return ZstdEntropy.readFseTable(description, 0, description.length, maximumSymbol, tableLog);
    }

    /// Builds the decoding table without parsing its description.
    @Benchmark
    public Object build() throws IOException {
        return ZstdEntropy.FseTable.fromNormalized(frequencies, frequencies.length, tableLog);
    }
}
