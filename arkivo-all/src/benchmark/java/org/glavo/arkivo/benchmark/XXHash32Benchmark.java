// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.arkivo.benchmark;

import org.glavo.arkivo.checksum.ChecksumAccumulator;
import org.glavo.arkivo.checksum.xxhash.XXHash32;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.jetbrains.annotations.UnmodifiableView;
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

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/// Measures reusable XXH32 state across array, read-only heap, and direct-buffer updates.
@NotNullByDefault
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
public class XXHash32Benchmark {
    /// Input bytes processed by one invocation.
    private static final int SIZE = 1 << 20;

    /// The public input representation under measurement.
    @Param({"array", "readonly", "direct"})
    public String kind = "array";

    /// Bytes processed by each accumulator update.
    @Param({"8192", "1048576"})
    public int chunk = 8192;

    /// Deterministic source content.
    private byte @Unmodifiable [] bytes = new byte[0];

    /// Caller-owned input storage reused between invocations.
    private @UnmodifiableView ByteBuffer input = ByteBuffer.allocate(0).asReadOnlyBuffer();

    /// Reusable zero-seeded checksum state.
    private final ChecksumAccumulator.Width32 accumulator = XXHash32.DEFAULT.newAccumulator();

    /// Creates source data and checks the selected path against individual byte updates.
    @Setup
    public void setup() {
        if (chunk <= 0) throw new IllegalArgumentException("Chunk size must be positive");
        bytes = new byte[SIZE];
        new Random(42).nextBytes(bytes);
        input = switch (kind) {
            case "array", "readonly" -> ByteBuffer.wrap(bytes).asReadOnlyBuffer();
            case "direct" -> ByteBuffer.allocateDirect(SIZE).put(bytes).flip().asReadOnlyBuffer();
            default -> throw new IllegalArgumentException(kind);
        };
        ChecksumAccumulator.Width32 reference = XXHash32.DEFAULT.newAccumulator();
        for (byte value : bytes) reference.update(value);
        if (reference.finishInt() != hash()) throw new AssertionError("XXH32 update paths differ");
    }

    /// Hashes one MiB without including accumulator or input allocation.
    @Benchmark
    @OperationsPerInvocation(SIZE)
    public int hash() {
        accumulator.reset();
        if (kind.equals("array")) {
            for (int offset = 0; offset < SIZE; offset += chunk) {
                accumulator.update(bytes, offset, Math.min(chunk, SIZE - offset));
            }
        } else {
            input.clear();
            while (input.position() < SIZE) {
                input.limit(input.position() + Math.min(chunk, SIZE - input.position()));
                accumulator.update(input);
            }
        }
        return accumulator.finishInt();
    }
}
