// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream;
import org.glavo.arkivo.codec.lz4.LZ4BlockCodec;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures raw LZ4 compression across block sizes and match distributions.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class LZ4CompressionBenchmark {
    /// Number of source bytes in each independently encoded block.
    @Param({"65536", "1048576"})
    public int size = 65536;

    /// Distribution of literal bytes and repeated ranges.
    @Param({"random", "periodic", "mixed", "shortMatches"})
    public String distribution = "random";

    /// Deterministic source, unchanged between invocations.
    private byte @Unmodifiable [] content = new byte[0];

    /// Generates input and checks complete encoded output with an independent decoder.
    @Setup
    public void setUp() throws IOException {
        byte[] bytes = new byte[size];
        new Random(42).nextBytes(bytes);
        switch (distribution) {
            case "random" -> { }
            case "periodic" -> {
                for (int index = 251; index < bytes.length; index++) bytes[index] = bytes[index % 251];
            }
            case "mixed" -> {
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    System.arraycopy(bytes, offset, bytes, offset + 4096, 4096);
                }
            }
            case "shortMatches" -> {
                for (int offset = 16; offset < bytes.length; offset += 16) {
                    System.arraycopy(bytes, offset - 16, bytes, offset, 7);
                }
            }
            default -> throw new IllegalArgumentException(distribution);
        }
        content = bytes;
        ByteBuffer encoded = encode();
        byte[] compressed = new byte[encoded.remaining()];
        encoded.get(compressed);
        try (var input = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(compressed))) {
            if (!Arrays.equals(content, input.readAllBytes())) throw new IllegalStateException("Compression mismatch");
        }
    }

    /// Compresses a complete block, including engine construction and output allocation.
    @Benchmark
    public ByteBuffer encode() throws IOException {
        return LZ4BlockCodec.DEFAULT.compress(ByteBuffer.wrap(content));
    }
}
