// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream;
import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/// Measures complete compression stream sessions with bulk or single-byte writes.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class CodecWriteBenchmark {
    /// Format encoded through the common output-stream adapter.
    @Param({"gzip", "lz4"})
    public String format = "gzip";

    /// How each session supplies its uncompressed bytes.
    @Param({"bulk", "scalar"})
    public String access = "bulk";

    /// Deterministic source containing repeated 4 KiB regions.
    private byte @Unmodifiable [] content = new byte[0];

    /// Generates the source and validates the selected write path with an independent decoder.
    @Setup
    public void setUp() throws IOException {
        byte[] bytes = new byte[64 * 1024];
        new Random(42).nextBytes(bytes);
        for (int offset = 0; offset < bytes.length; offset += 8192) {
            System.arraycopy(bytes, offset, bytes, offset + 4096, 4096);
        }
        content = bytes;
        byte[] encoded = encode();
        try (InputStream input = format.equals("gzip")
                ? new GZIPInputStream(new ByteArrayInputStream(encoded))
                : new FramedLZ4CompressorInputStream(new ByteArrayInputStream(encoded))) {
            if (!Arrays.equals(content, input.readAllBytes())) throw new IllegalStateException("Compression mismatch");
        }
    }

    /// Encodes all input, finishes and closes the stream, and returns its complete output.
    @Benchmark
    public byte[] encode() throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream(content.length);
        try (var output = CompressionFormats.require(format).defaultCodec().newOutputStream(target)) {
            switch (access) {
                case "bulk" -> output.write(content);
                case "scalar" -> {
                    for (byte value : content) output.write(value);
                }
                default -> throw new IllegalArgumentException(access);
            }
        }
        return target.toByteArray();
    }
}
