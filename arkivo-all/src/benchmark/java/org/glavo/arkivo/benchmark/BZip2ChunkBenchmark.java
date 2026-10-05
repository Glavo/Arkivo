// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.bzip2.BZip2Codec;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.jetbrains.annotations.NotNullByDefault;
import org.openjdk.jmh.annotations.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Isolates the effect of compressed input chunk size on the same BZip2 decoder and bitstream.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class BZip2ChunkBenchmark {
    /// The maximum compressed bytes exposed per decoder call.
    @Param({"8192", "65536", "1048576"}) public int chunk = 8192;
    /// The common compressed stream.
    private byte[] compressed = new byte[0];
    /// The reusable decoded output, with one spare byte for finalization.
    private final ByteBuffer output = ByteBuffer.allocate(1048577);
    /// Produces the same one-MiB partially repeated profile as the stream comparison.
    @Setup
    public void setup() throws IOException {
        byte[] body = new byte[1048576];
        new Random(42).nextBytes(body);
        for (int i = 0; i < body.length; i += 8192) System.arraycopy(body, i, body, i + 4096, 4096);
        var target = new ByteArrayOutputStream();
        try (var out = new BZip2CompressorOutputStream(target)) { out.write(body); }
        compressed = target.toByteArray();
        if (decode() != body.length || !Arrays.equals(body, 0, body.length, output.array(), 0, body.length))
            throw new AssertionError("Chunked output mismatch");
    }
    /// Decodes identical bytes with only the exposed input chunk size varied.
    @Benchmark
    public int decode() throws IOException {
        ByteBuffer source = ByteBuffer.wrap(compressed);
        source.limit(Math.min(chunk, compressed.length));
        output.clear();
        try (var decoder = BZip2Codec.DEFAULT.newDecoder()) {
            while (true) {
                CodecOutcome outcome = source.limit() == compressed.length
                        ? decoder.finish(source, output) : decoder.decode(source, output);
                if (outcome == CodecOutcome.FINISHED) return output.position();
                if (outcome != CodecOutcome.NEEDS_INPUT || source.limit() == compressed.length)
                    throw new IOException("Unexpected decoder outcome: " + outcome);
                source.limit(Math.min(source.limit() + chunk, compressed.length));
            }
        }
    }
}
