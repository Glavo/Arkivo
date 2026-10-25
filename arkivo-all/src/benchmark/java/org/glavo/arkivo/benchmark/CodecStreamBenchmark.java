// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CompressionFormats;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.jetbrains.annotations.NotNullByDefault;
import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Compares complete stream sessions using default codec configurations and a common compressed input.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class CodecStreamBenchmark {
    /// The stream implementation.
    @Param({"arkivo", "commons"}) public String api = "arkivo";
    /// The compressed stream format.
    @Param({"gzip", "bzip2", "xz", "lz4-framed"}) public String format = "gzip";
    /// Reads decoded data in bulk, one byte at a time, or through the stream's skip operation.
    @Param({"bulk"}) public String access = "bulk";
    /// One MiB of moderately compressible deterministic input.
    private final byte[] body = new byte[1024 * 1024];
    /// The common Commons-produced compressed input.
    private byte[] compressed = new byte[0];
    /// Reusable decoding output scratch space.
    private final byte[] buffer = new byte[32768];
    /// The stateless Commons stream factory.
    private final CompressorStreamFactory factory = new CompressorStreamFactory();
    /// Prepares input with repeated 4 KiB regions and validates each selected implementation.
    @Setup
    public void setup() throws Exception {
        if (!Set.of("bulk", "scalar", "skip").contains(access)) throw new IllegalArgumentException(access);
        new Random(42).nextBytes(body);
        for (int i = 0; i < body.length; i += 8192) System.arraycopy(body, i, body, i + 4096, 4096);
        String selected = api;
        api = "commons"; compressed = encode(); api = selected;
        byte[] output = encode();
        try (InputStream in = factory.createCompressorInputStream(commonsFormat(), new ByteArrayInputStream(output))) {
            if (!Arrays.equals(body, in.readAllBytes())) throw new AssertionError("Encoded data mismatch");
        }
        try (InputStream in = input()) {
            if (!Arrays.equals(body, in.readAllBytes())) throw new AssertionError("Decoded data mismatch");
        }
        decode();
        System.out.println("SURVEY_CODEC_BYTES " + api + " " + format + " " + output.length);
    }
    /// Maps the public format names used by each library.
    private String arkivoFormat() { return format.equals("lz4-framed") ? "lz4" : format; }
    /// Maps gzip to the Commons factory identifier.
    private String commonsFormat() { return format.equals("gzip") ? "gz" : format; }
    /// Opens the selected decoder over the common input.
    private InputStream input() throws Exception {
        var source = new ByteArrayInputStream(compressed);
        return api.equals("arkivo") ? CompressionFormats.require(arkivoFormat()).defaultCodec().newInputStream(source)
                : factory.createCompressorInputStream(commonsFormat(), source);
    }
    /// Measures complete decoding sessions including engine allocation.
    @Benchmark
    public long decode() throws Exception {
        try (InputStream in = input()) {
            long size = 0;
            if (access.equals("scalar")) {
                while (in.read() >= 0) size++;
            } else if (access.equals("skip")) {
                while (true) {
                    long skipped = in.skip(body.length);
                    if (skipped > 0) {
                        size += skipped;
                    } else if (in.read() < 0) {
                        break;
                    } else {
                        size++;
                    }
                }
            } else {
                int n;
                while ((n = in.read(buffer)) >= 0) size += n;
            }
            if (size != body.length) throw new AssertionError(size);
            return size;
        }
    }
    /// Measures complete encoding sessions including engine allocation and output collection.
    @Benchmark
    public byte[] encode() throws Exception {
        var target = new ByteArrayOutputStream(body.length);
        try (OutputStream out = api.equals("arkivo")
                ? CompressionFormats.require(arkivoFormat()).defaultCodec().newOutputStream(target)
                : factory.createCompressorOutputStream(commonsFormat(), target)) { out.write(body); }
        return target.toByteArray();
    }
}
