// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CompressionFormats;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/// Measures compression and decompression throughput through the public channel adapters.
@NotNullByDefault
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class CodecThroughputBenchmark {
    /// The uncompressed byte count processed by each benchmark invocation.
    private static final int SOURCE_SIZE = 4 * 1024 * 1024;

    /// The stable compression format name selected for the current benchmark trial.
    @Param({"deflate", "deflate64", "gzip", "zlib", "zstd"})
    public String formatName = "gzip";

    /// The deterministic compressible source bytes.
    private byte @Unmodifiable [] source = new byte[0];

    /// The compressed frame prepared for decompression measurements.
    private byte @Unmodifiable [] compressed = new byte[0];

    /// Prepares deterministic source data and validates the selected codec round trip.
    @Setup
    public void setUp() throws IOException {
        byte[] content = new byte[SOURCE_SIZE];
        for (int index = 0; index < content.length; index++) {
            int offset = index & 0xffff;
            content[index] = (byte) (offset < 48 * 1024 ? offset * 31 : index * 17);
        }
        source = content;
        compressed = compress(formatName, source);
        byte[] restored = decompress(formatName, compressed, source.length);
        if (!Arrays.equals(source, restored)) {
            throw new AssertionError("Codec benchmark setup did not round trip " + formatName);
        }
    }

    /// Compresses one fixed-size source through the public channel adapter API.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public byte[] compress() throws IOException {
        return compress(formatName, source);
    }

    /// Decompresses one encoding through the public channel adapter API.
    @Benchmark
    @OperationsPerInvocation(SOURCE_SIZE)
    public byte[] decompress() throws IOException {
        return decompress(formatName, compressed, source.length);
    }

    /// Compresses the requested source and returns the resulting frame.
    private static byte[] compress(String formatName, byte[] source) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream(source.length);
        CompressionFormats.compress(
                formatName,
                Channels.newChannel(new ByteArrayInputStream(source)),
                Channels.newChannel(target)
        );
        return target.toByteArray();
    }

    /// Decompresses one frame into an output buffer sized for the expected content.
    private static byte[] decompress(String formatName, byte[] compressed, int expectedSize) throws IOException {
        ByteArrayOutputStream target = new ByteArrayOutputStream(expectedSize);
        CompressionFormats.decompress(
                formatName,
                Channels.newChannel(new ByteArrayInputStream(compressed)),
                Channels.newChannel(target)
        );
        return target.toByteArray();
    }
}
