// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.deflate.DeflateCodec;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/// Compares raw Deflate decoding through fragmented input and bounded heap or direct output ranges.
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
    @Param({"blocks", "skewed-literals", "short-distance", "text", "random", "long-distance"})
    public String profile = "blocks";

    /// Compressed bytes exposed to the decoder at each input boundary.
    @Param({"7", "8192", "1048576"})
    public int chunk = 8192;

    /// Decoded bytes exposed to each decoder operation.
    @Param({"8192", "65536"})
    public int outputChunk = 8192;

    /// Storage used for both compressed input and decoded output.
    @Param({"heap", "direct"})
    public String bufferKind = "heap";

    /// The uncompressed bytes processed by one invocation.
    private static final int SIZE = 1024 * 1024;

    /// Common compressed input produced by the JDK.
    private byte @Unmodifiable [] compressed = new byte[0];

    /// Reused decoded output, with space to verify termination.
    private ByteBuffer output = ByteBuffer.allocate(0);

    /// Reused read-only compressed input, with its limit advanced at input boundaries.
    private @UnmodifiableView ByteBuffer input = ByteBuffer.allocate(0);

    /// The reused pure Java decoder.
    private @Nullable CompressionDecoder decoder;

    /// The reused JDK decoder.
    private @Nullable Inflater inflater;

    /// Generates input and verifies exact decoded bytes before timing.
    @Setup
    public void setup() throws IOException, DataFormatException {
        if (!api.equals("arkivo") && !api.equals("jdk")) throw new IllegalArgumentException(api);
        if (!Arrays.asList("blocks", "skewed-literals", "short-distance", "text", "random", "long-distance")
                .contains(profile)) {
            throw new IllegalArgumentException(profile);
        }
        if (chunk <= 0 || outputChunk <= 0) throw new IllegalArgumentException("Chunks must be positive");
        if (!bufferKind.equals("heap") && !bufferKind.equals("direct")) throw new IllegalArgumentException(bufferKind);
        boolean literalOnly = profile.equals("blocks") || profile.equals("skewed-literals");
        byte[] body = new byte[SIZE];
        if (profile.equals("blocks")) {
            new Random(42).nextBytes(body);
            for (int i = 0; i < body.length; i++) body[i] &= 15;
        } else if (profile.equals("skewed-literals")) {
            new Random(42).nextBytes(body);
            for (int i = 0; i < body.length; i++) {
                if ((i & 31) != 0) body[i] = 'a';
            }
        } else if (profile.equals("short-distance")) {
            Arrays.fill(body, 0, SIZE / 2, (byte) 'A');
            for (int i = SIZE / 2; i < SIZE; i++) body[i] = (byte) ('A' + i % 3);
        } else if (profile.equals("text")) {
            byte[] phrase = "Incremental Deflate decoding with bounded input and output buffers.\n"
                    .getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < body.length; i++) body[i] = phrase[i % phrase.length];
        } else {
            new Random(42).nextBytes(body);
            if (profile.equals("long-distance")) {
                for (int i = 16_384; i < body.length; i++) body[i] = body[i % 16_384];
            }
        }
        Deflater encoder = new Deflater(6, true);
        try {
            if (literalOnly) encoder.setStrategy(Deflater.HUFFMAN_ONLY);
            var bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int position = 0; position < SIZE; position += 4096) {
                encoder.setInput(body, position, Math.min(4096, SIZE - position));
                while (true) {
                    int written = encoder.deflate(buffer, 0, buffer.length,
                            literalOnly ? Deflater.FULL_FLUSH : Deflater.NO_FLUSH);
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
        input = bufferKind.equals("direct") ? ByteBuffer.allocateDirect(compressed.length)
                : ByteBuffer.allocate(compressed.length);
        input.put(compressed).flip();
        input = input.asReadOnlyBuffer();
        output = bufferKind.equals("direct") ? ByteBuffer.allocateDirect(SIZE + 1) : ByteBuffer.allocate(SIZE + 1);
        if (decode() != SIZE) throw new AssertionError("Fragmented Deflate output size differs");
        byte[] actual = new byte[SIZE];
        output.get(0, actual);
        if (!Arrays.equals(body, actual)) {
            throw new AssertionError("Fragmented Deflate output differs");
        }
        System.out.println("DEFLATE_READ_INPUT_BYTES " + profile + " " + compressed.length);
    }

    /// Decodes the common stream with the selected input-fragment size.
    @Benchmark
    public int decode() throws IOException, DataFormatException {
        output.clear().limit(Math.min(outputChunk, output.capacity()));
        input.clear().limit(Math.min(chunk, compressed.length));
        if (api.equals("arkivo")) {
            CompressionDecoder engine = Objects.requireNonNull(decoder);
            engine.reset();
            while (true) {
                CodecOutcome outcome = input.limit() == compressed.length
                        ? engine.finish(input, output) : engine.decode(input, output);
                if (outcome == CodecOutcome.FINISHED) return output.position();
                if (outcome == CodecOutcome.NEEDS_OUTPUT) {
                    output.limit(Math.min(output.capacity(), output.limit() + outputChunk));
                } else if (outcome == CodecOutcome.NEEDS_INPUT && input.limit() < compressed.length) {
                    input.limit(Math.min(compressed.length, input.limit() + chunk));
                } else {
                    throw new IOException("Unexpected fragmented decode outcome: " + outcome);
                }
            }
        }
        Inflater engine = Objects.requireNonNull(inflater);
        engine.reset();
        while (!engine.finished()) {
            if (engine.needsInput()) {
                if (!input.hasRemaining()) {
                    if (input.limit() == compressed.length) throw new DataFormatException("Truncated benchmark input");
                    input.limit(Math.min(compressed.length, input.limit() + chunk));
                }
                engine.setInput(input);
            }
            engine.inflate(output);
            if (!output.hasRemaining()) output.limit(Math.min(output.capacity(), output.limit() + outputChunk));
        }
        return output.position();
    }

    /// Releases both decoder implementations after a trial.
    @TearDown
    public void close() {
        if (decoder != null) decoder.close();
        if (inflater != null) inflater.end();
    }
}
