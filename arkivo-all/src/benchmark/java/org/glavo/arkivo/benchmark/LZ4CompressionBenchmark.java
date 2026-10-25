// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
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

/// Compares allocating raw LZ4 operations across block sizes and match distributions.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class LZ4CompressionBenchmark {
    /// Implementation under test; the upstream factory excludes JNI implementations.
    @Param({"arkivo", "lz4-java"})
    public String implementation = "arkivo";

    /// Number of source bytes in each independently encoded block.
    @Param({"65536", "1048576"})
    public int size = 65536;

    /// Distribution of literal bytes and repeated ranges.
    @Param({"random", "periodic", "mixed", "shortMatches"})
    public String distribution = "random";

    /// Deterministic source, unchanged between invocations.
    private byte @Unmodifiable [] content = new byte[0];

    /// Common compressed input produced by lz4-java for both decoding measurements.
    private byte @Unmodifiable [] compressed = new byte[0];

    /// Fastest available Java compressor, excluding the native backend.
    private final LZ4Compressor compressor = LZ4Factory.fastestJavaInstance().fastCompressor();

    /// Length-bounded upstream decoder, rather than the trusted-input fast decoder.
    private final LZ4SafeDecompressor decompressor = LZ4Factory.fastestJavaInstance().safeDecompressor();

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
                for (int offset = 0; offset + 8192 <= bytes.length; offset += 8192) {
                    System.arraycopy(bytes, offset, bytes, offset + 4096, 4096);
                }
            }
            case "shortMatches" -> {
                for (int offset = 16; offset + 7 <= bytes.length; offset += 16) {
                    System.arraycopy(bytes, offset - 16, bytes, offset, 7);
                }
            }
            default -> throw new IllegalArgumentException(distribution);
        }
        content = bytes;
        compressed = compressor.compress(content);
        ByteBuffer encoded = LZ4BlockCodec.DEFAULT.compress(ByteBuffer.wrap(content));
        byte[] arkivoCompressed = new byte[encoded.remaining()];
        encoded.get(arkivoCompressed);
        try (var input = new BlockLZ4CompressorInputStream(new ByteArrayInputStream(arkivoCompressed))) {
            if (!Arrays.equals(content, input.readAllBytes())) throw new IllegalStateException("Compression mismatch");
        }
        if (!Arrays.equals(content, decompressor.decompress(arkivoCompressed, size))) {
            throw new IllegalStateException("Upstream decoding mismatch");
        }
        if (!ByteBuffer.wrap(content).equals(decode())) throw new IllegalStateException("Decoding mismatch");
        System.out.printf("LZ4 sizes: %s source=%d arkivo=%d lz4Java=%d backend=%s%n",
                distribution, size, arkivoCompressed.length, compressed.length, compressor);
    }

    /// Compresses a complete block into a newly allocated output buffer.
    @Benchmark
    public ByteBuffer encode() throws IOException {
        return switch (implementation) {
            case "arkivo" -> LZ4BlockCodec.DEFAULT.compress(ByteBuffer.wrap(content));
            case "lz4-java" -> ByteBuffer.wrap(compressor.compress(content));
            default -> throw new IllegalArgumentException(implementation);
        };
    }

    /// Decodes identical upstream blocks into newly allocated output bounded by the known size.
    @Benchmark
    public ByteBuffer decode() throws IOException {
        return switch (implementation) {
            case "arkivo" -> LZ4BlockCodec.DEFAULT.withMaximumBlockSize(size).withMaximumOutputSize(size)
                    .decompress(ByteBuffer.wrap(compressed));
            case "lz4-java" -> ByteBuffer.wrap(decompressor.decompress(compressed, size));
            default -> throw new IllegalArgumentException(implementation);
        };
    }
}
