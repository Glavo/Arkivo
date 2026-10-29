// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/// Measures parsing and expansion of three sequence tables in a minimal valid compressed block.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZstdSequenceTableBenchmark {
    /// Whether every code is present or only the required code and the alphabet's last code.
    @Param({"dense", "sparse"})
    public String distribution = "dense";

    /// The complete compressed block, containing three dynamic tables and one sequence.
    private byte @Unmodifiable [] block = new byte[0];

    /// A frame-local decoder whose table workspaces are reused between blocks.
    private @Nullable ZstdBlockDecoder decoder;

    /// Encodes the tables and verifies the decoded block before measurement.
    @Setup
    public void setup() throws IOException {
        if (!distribution.equals("dense") && !distribution.equals("sparse")) {
            throw new IllegalArgumentException(distribution);
        }
        int[] maximumSymbols = {35, 31, 52};
        int[] logs = {9, 8, 9};
        int[] requiredSymbols = {1, 2, 0};
        int[] initialStates = new int[3];
        var encoded = new ByteArrayOutputStream();
        // One raw literal, one sequence, and three compressed sequence tables.
        encoded.writeBytes(new byte[]{8, 97, 1, (byte) 0xa8});
        for (int alphabet = 0; alphabet < maximumSymbols.length; alphabet++) {
            int[] counts = new int[maximumSymbols[alphabet] + 1];
            int tableSize = 1 << logs[alphabet];
            if (distribution.equals("dense")) {
                Arrays.fill(counts, tableSize / counts.length);
                for (int symbol = 0; symbol < tableSize % counts.length; symbol++) counts[symbol]++;
            } else {
                counts[requiredSymbols[alphabet]] = tableSize - 1;
                counts[counts.length - 1] = -1;
            }
            var table = ZstdEntropy.FseTable.fromNormalized(counts, counts.length, logs[alphabet]);
            int state = 0;
            while (table.symbol(state) != requiredSymbols[alphabet]) state++;
            initialStates[alphabet] = state;
            encoded.writeBytes(ZstdEntropy.encodeFseTableDescription(counts, counts.length, logs[alphabet]));
        }
        var bits = new ZstdEntropy.ReverseBitWriter();
        bits.writeBits(0, 2); // Offset code 2 and extra value 0 select distance 1.
        for (int alphabet = 2; alphabet >= 0; alphabet--) {
            bits.writeBits(initialStates[alphabet], logs[alphabet]);
        }
        encoded.writeBytes(bits.finish());
        block = encoded.toByteArray();
        decoder = new ZstdBlockDecoder(new ZstdHistory(ZstdBlockDecoder.MAX_BLOCK_SIZE), ZstdDictionaryContext.NONE);
        if (!decode().equals(ByteBuffer.wrap(new byte[]{97, 97, 97, 97}))) {
            throw new AssertionError("Unexpected sequence-table block output");
        }
    }

    /// Parses and expands all three tables, executes one sequence, and commits four output bytes.
    @Benchmark
    public @UnmodifiableView ByteBuffer decode() throws IOException {
        return Objects.requireNonNull(decoder).decodeCompressed(block, block.length, 4);
    }
}
