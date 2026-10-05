// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/// Compares fragmented raw Deflate input with frequent dynamic headers or short-distance matches.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class DeflateReadBenchmark {
    /// The decoder implementation.
    @Param({"arkivo", "jdk"})
    public String api = "arkivo";

    /// The match or block-header workload.
    @Param({"blocks", "short-distance"})
    public String profile = "blocks";

    /// Compressed bytes exposed to the decoder at each input boundary.
    @Param({"7", "8192", "1048576"})
    public int chunk = 8192;

    /// The uncompressed bytes processed by one invocation.
    private static final int SIZE = 1024 * 1024;

    /// Common compressed input produced by the JDK.
    private byte @Unmodifiable [] compressed = new byte[0];

    /// Reused decoded output, with space to verify termination.
    private final ByteBuffer output = ByteBuffer.allocate(SIZE + 1);

    /// The reused pure Java decoder.
    private @Nullable CompressionDecoder decoder;

    /// The reused JDK decoder.
    private @Nullable Inflater inflater;

    /// Generates input and verifies exact decoded bytes before timing.
    @Setup
    public void setup() throws IOException, DataFormatException {
        if (!api.equals("arkivo") && !api.equals("jdk")) throw new IllegalArgumentException(api);
        if (!profile.equals("blocks") && !profile.equals("short-distance")) {
            throw new IllegalArgumentException(profile);
        }
        if (chunk <= 0) throw new IllegalArgumentException("chunk must be positive");
        byte[] body = new byte[SIZE];
        if (profile.equals("blocks")) {
            new Random(42).nextBytes(body);
            for (int i = 0; i < body.length; i++) body[i] &= 15;
        } else {
            Arrays.fill(body, 0, SIZE / 2, (byte) 'A');
            for (int i = SIZE / 2; i < SIZE; i++) body[i] = (byte) ('A' + i % 3);
        }
        Deflater encoder = new Deflater(6, true);
        try {
            if (profile.equals("blocks")) encoder.setStrategy(Deflater.HUFFMAN_ONLY);
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int position = 0; position < SIZE; position += 4096) {
                encoder.setInput(body, position, Math.min(4096, SIZE - position));
                while (true) {
                    int written = encoder.deflate(buffer, 0, buffer.length,
                            profile.equals("blocks") ? Deflater.FULL_FLUSH : Deflater.NO_FLUSH);
                    bytes.write(buffer, 0, written);
                    if (encoder.needsInput() && written < buffer.length) break;
                }
            }
            encoder.finish();
            while (!encoder.finished()) {
                int written = encoder.deflate(buffer);
                bytes.write(buffer, 0, written);
            }
            compressed = bytes.toByteArray();
        } finally {
            encoder.end();
        }
        decoder = DeflateCodec.DEFAULT.newDecoder();
        inflater = new Inflater(true);
        if (decode() != SIZE || !Arrays.equals(body, 0, SIZE, output.array(), 0, SIZE)) {
            throw new AssertionError("Fragmented Deflate output differs");
        }
        System.out.println("DEFLATE_READ_INPUT_BYTES " + profile + " " + compressed.length);
    }

    /// Decodes the common stream with the selected input-fragment size.
    @Benchmark
    public int decode() throws IOException, DataFormatException {
        output.clear();
        if (api.equals("arkivo")) {
            CompressionDecoder engine = Objects.requireNonNull(decoder);
            engine.reset();
            ByteBuffer source = ByteBuffer.wrap(compressed);
            source.limit(Math.min(chunk, compressed.length));
            while (true) {
                CodecOutcome outcome = source.limit() == compressed.length
                        ? engine.finish(source, output) : engine.decode(source, output);
                if (outcome == CodecOutcome.FINISHED) return output.position();
                if (outcome != CodecOutcome.NEEDS_INPUT || source.limit() == compressed.length) {
                    throw new IOException("Unexpected fragmented decode outcome: " + outcome);
                }
                source.limit(Math.min(compressed.length, source.limit() + chunk));
            }
        }
        Inflater engine = Objects.requireNonNull(inflater);
        engine.reset();
        int offset = 0;
        int produced = 0;
        while (!engine.finished()) {
            if (engine.needsInput()) {
                if (offset == compressed.length) throw new DataFormatException("Truncated benchmark input");
                int count = Math.min(chunk, compressed.length - offset);
                engine.setInput(compressed, offset, count);
                offset += count;
            }
            produced += engine.inflate(output.array(), produced, output.capacity() - produced);
        }
        output.position(produced);
        return produced;
    }

    /// Releases both decoder implementations after a trial.
    @TearDown
    public void close() {
        if (decoder != null) decoder.close();
        if (inflater != null) inflater.end();
    }
}
