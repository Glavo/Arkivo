// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.codec.zstd.internal;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
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
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures construction of fresh single-symbol and derived paired Huffman tables.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZstdHuffmanTableBenchmark {
    /// Maximum code length in the generated complete tree.
    @Param({"6", "11"})
    public int tableLog = 6;

    /// A comb-shaped tree or a larger alphabet obtained by splitting its shorter leaves.
    @Param({"comb", "spread"})
    public String shape = "comb";

    /// Deterministic weights, fixed after setup.
    private int @Unmodifiable [] weights = new int[0];

    /// Access to the private derived-table builder without changing production visibility.
    private @Nullable MethodHandle pairedBuilder;

    /// Generates complete trees and checks paired entries against independently assigned canonical codes.
    @Setup
    public void setup() throws Throwable {
        int[] generated = new int[256];
        for (int symbol = 0; symbol <= tableLog; symbol++) generated[symbol] = Math.max(1, symbol);
        Random random = new Random(40271 + tableLog);
        if (shape.equals("spread")) {
            int symbols = tableLog + 1;
            while (symbols < Math.min(256, 1 << tableLog)) {
                int split = random.nextInt(symbols);
                while (generated[split] == 1) split = (split + 1) % symbols;
                generated[split]--;
                generated[symbols++] = generated[split];
            }
        } else if (!shape.equals("comb")) {
            throw new IllegalArgumentException(shape);
        }
        for (int index = generated.length - 1; index > 0; index--) {
            int selected = random.nextInt(index + 1);
            int weight = generated[index];
            generated[index] = generated[selected];
            generated[selected] = weight;
        }
        weights = generated;
        var lookup = MethodHandles.privateLookupIn(ZstdEntropy.HuffmanTable.class, MethodHandles.lookup());
        pairedBuilder = lookup.findVirtual(ZstdEntropy.HuffmanTable.class, "pairedEntries",
                MethodType.methodType(int[].class));
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        short[] single = new short[1 << tableLog];
        int nextPrefix = 0;
        for (int weight = 1; weight <= tableLog; weight++) {
            int width = 1 << (weight - 1);
            for (int symbol = 0; symbol < weights.length; symbol++) {
                if (weights[symbol] != weight) continue;
                short entry = (short) ((symbol << 4) | (tableLog + 1 - weight));
                Arrays.fill(single, nextPrefix, nextPrefix + width, entry);
                nextPrefix += width;
            }
        }
        if (nextPrefix != single.length) throw new AssertionError("Incomplete generated tree");
        int @Unmodifiable [] actual = (int[]) pairedBuilder.invokeExact(table);
        if (Integer.bitCount(actual.length) != 1) throw new AssertionError("Invalid paired table size");
        int lookupBits = Integer.numberOfTrailingZeros(actual.length);
        if (lookupBits < tableLog || lookupBits > tableLog * 2) {
            throw new AssertionError("Invalid paired lookahead");
        }
        int[] expected = new int[1 << lookupBits];
        for (int prefix = 0; prefix < expected.length; prefix++) {
            int first = single[prefix >>> (lookupBits - tableLog)];
            int firstLength = first & 15;
            if (firstLength == 0) throw new AssertionError("Incomplete generated tree");
            int second = single[((prefix << firstLength) & (expected.length - 1)) >>> (lookupBits - tableLog)];
            int secondLength = second & 15;
            if (firstLength + secondLength <= lookupBits) {
                expected[prefix] = ((first >>> 4) << 8) | ((second >>> 4) << 16)
                        | (firstLength + secondLength) | (2 << 30);
            } else {
                expected[prefix] = ((first >>> 4) << 8) | firstLength | (1 << 30);
            }
        }
        if (!Arrays.equals(expected, actual)) throw new AssertionError("Paired table differs");
    }

    /// Builds a fresh table and forces its derived paired lookup table.
    @Benchmark
    public int @Unmodifiable [] build() throws Throwable {
        var table = ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
        return (int[]) Objects.requireNonNull(pairedBuilder).invokeExact(table);
    }

    /// Builds only the canonical single-symbol table, leaving paired entries unallocated.
    @Benchmark
    public Object buildSingle() throws IOException {
        return ZstdEntropy.HuffmanTable.fromWeights(weights, weights.length, tableLog);
    }
}
