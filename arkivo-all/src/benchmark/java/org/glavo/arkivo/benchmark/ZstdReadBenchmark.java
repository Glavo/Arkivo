// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdDecompressCtx;
import org.glavo.arkivo.codec.CodecOutcome;
import org.glavo.arkivo.codec.CompressionDecoder;
import org.glavo.arkivo.codec.zstd.ZstdCodec;
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Compares decoding of identical checksum-protected native Zstandard frames with reused heap buffers.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class ZstdReadBenchmark {
    /// The uncompressed byte count per invocation.
    @Param({"1048576"})
    public int size = 1024 * 1024;

    /// The decoder implementation; native uses zstd-jni, not a Java implementation.
    @Param({"arkivo", "native"})
    public String implementation = "arkivo";

    /// Whether one engine is reset between frames or a fresh engine is created and closed for each invocation.
    @Param({"true"})
    public boolean reuseContext = true;

    /// The deterministic block and match workload.
    @Param({"random", "periodic", "short-distance", "long-distance", "short-matches", "huffman", "skewed-huffman"})
    public String profile = "random";

    /// The shared compressed representation.
    private byte @Unmodifiable [] compressed = new byte[0];

    /// The reused input view for Arkivo.
    private @UnmodifiableView ByteBuffer input = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// The reused output storage for both implementations.
    private byte[] output = new byte[0];

    /// The output view used by Arkivo.
    private ByteBuffer target = ByteBuffer.allocate(0);

    /// The pure Java decoder.
    private @Nullable CompressionDecoder decoder;

    /// The native decoder context.
    private @Nullable ZstdDecompressCtx nativeDecoder;

    /// Creates common compressed input and verifies both decoders before measurement.
    @Setup
    public void setup() throws IOException {
        if (!implementation.equals("arkivo") && !implementation.equals("native")) {
            throw new IllegalArgumentException(implementation);
        }
        byte[] body = new byte[size];
        new Random(42).nextBytes(body);
        switch (profile) {
            case "random" -> { }
            case "huffman" -> {
                for (int i = 0; i < body.length; i++) body[i] &= 63;
            }
            case "skewed-huffman" -> {
                for (int i = 0; i < body.length; i++) {
                    int value = Byte.toUnsignedInt(body[i]);
                    body[i] = (byte) (value < 128 ? value & 7 : value);
                }
            }
            case "periodic" -> {
                for (int i = 251; i < body.length; i++) body[i] = body[i % 251];
            }
            case "short-distance" -> {
                Arrays.fill(body, 0, size / 2, (byte) 'A');
                for (int i = size / 2; i < size; i++) body[i] = (byte) ('A' + i % 3);
            }
            case "long-distance" -> {
                for (int i = 65_537; i < body.length; i++) body[i] = body[i % 65_537];
            }
            case "short-matches" -> {
                byte[] dictionary = new byte[4096];
                new Random(43).nextBytes(dictionary);
                Random matches = new Random(44);
                // Alternate reusable 16-byte tokens with independent random literals.
                for (int i = 0; i < body.length; i += 32) {
                    System.arraycopy(dictionary, matches.nextInt(256) * 16, body, i,
                            Math.min(16, body.length - i));
                }
            }
            default -> throw new IllegalArgumentException(profile);
        }
        try (var encoder = new ZstdCompressCtx()) {
            compressed = encoder.setLevel(3).setChecksum(true).compress(body);
        }
        decoder = ZstdCodec.DEFAULT.newDecoder();
        nativeDecoder = new ZstdDecompressCtx();
        input = ByteBuffer.wrap(compressed).asReadOnlyBuffer();
        output = new byte[size + 1];
        target = ByteBuffer.wrap(output);
        if (decodeArkivo(decoder) != size || !Arrays.equals(body, 0, size, output, 0, size)) {
            throw new AssertionError("Arkivo Zstandard output differs");
        }
        Arrays.fill(output, (byte) 0);
        if (nativeDecoder.decompress(output, compressed) != size
                || !Arrays.equals(body, 0, size, output, 0, size)) {
            throw new AssertionError("Native Zstandard output differs");
        }
        System.out.println("ZSTD_READ_INPUT_BYTES " + profile + " " + compressed.length);
        if (!reuseContext) {
            close();
            decoder = null;
            nativeDecoder = null;
        }
    }

    /// Decodes one complete frame into the reused output buffer.
    @Benchmark
    public int decode() throws IOException {
        if (reuseContext) {
            return implementation.equals("arkivo") ? decodeArkivo(Objects.requireNonNull(decoder))
                    : Objects.requireNonNull(nativeDecoder).decompress(output, compressed);
        }
        if (implementation.equals("arkivo")) {
            try (var engine = ZstdCodec.DEFAULT.newDecoder()) {
                return decodeArkivo(engine);
            }
        }
        try (var engine = new ZstdDecompressCtx()) {
            return engine.decompress(output, compressed);
        }
    }

    /// Resets the Java decoder and consumes exactly one frame.
    private int decodeArkivo(CompressionDecoder engine) throws IOException {
        engine.reset();
        input.clear();
        target.clear();
        if (engine.finish(input, target) != CodecOutcome.FINISHED || input.hasRemaining()) {
            throw new IOException("Unexpected Zstandard frame boundary");
        }
        return target.position();
    }

    /// Releases both decoder contexts.
    @TearDown
    public void close() {
        if (decoder != null) decoder.close();
        if (nativeDecoder != null) nativeDecoder.close();
    }
}
